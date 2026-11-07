package dev.lyy.friendlycore.modules.homes.migration;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Asynchronous migrator for importing player homes and GUI configurations from ModernHome (SQLite or MySQL).
 */
public class ModernHomeMigrator {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public ModernHomeMigrator(FriendlyCore plugin, HomesManager homesManager) {
        this.plugin = plugin;
        this.homesManager = homesManager;
    }

    public boolean isRunning() {
        return RUNNING.get();
    }

    public CompletableFuture<MigrationResult> migrate(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            Messages.send(plugin, sender, "homes", "migrate-a-home-migration-is-already-in");
            return CompletableFuture.completedFuture(new MigrationResult(0, 0, 0, false, "Already running"));
        }

        return plugin.getScheduler().submitMigration(() -> {
            long startTime = System.currentTimeMillis();
            Messages.send(plugin, sender, "homes", "migrate-scanning-for-modernhome-data");

            File baseFolder = customFolder;
            if (baseFolder == null || !baseFolder.exists()) {
                baseFolder = findModernHomeFolder();
            }

            if (baseFolder == null || !baseFolder.exists()) {
                String err = "ModernHome folder not found. Expected at 'plugins/ModernHome/' or 'plugin-config/ModernHome/'";
                Messages.send(plugin, sender, "homes", "migrate-error", "%err%", String.valueOf(err));
                return new MigrationResult(0, 0, 0, false, err);
            }

            Messages.send(plugin, sender, "homes", "migrate-found-source", "%path%", String.valueOf(baseFolder.getPath()));

            migrateGuiInventories(baseFolder, sender);

            // Migrate stored homes from SQLite or MySQL database
            MigrationDbResult dbResult = migrateDatabaseHomes(baseFolder, sender);

            long elapsed = System.currentTimeMillis() - startTime;
            Messages.send(plugin, sender, "homes", "migrate-homes-migration-completed");
            Messages.send(plugin, sender, "homes", "migrate-total-homes-imported", "%homes_migrated%", String.valueOf(dbResult.homesMigrated()));
            Messages.send(plugin, sender, "homes", "migrate-total-players-affected", "%players_migrated%", String.valueOf(dbResult.playersMigrated()));
            Messages.send(plugin, sender, "homes", "migrate-time-taken-ms", "%elapsed%", String.valueOf(elapsed));

            return new MigrationResult(dbResult.homesMigrated(), dbResult.playersMigrated(), 0, true, null);
        }).whenComplete((result, error) -> RUNNING.set(false));
    }

    private void migrateGuiInventories(File baseFolder, CommandSender sender) {
        File guiFolder = baseFolder.isDirectory() ? new File(baseFolder, "gui") : new File(baseFolder.getParentFile(), "gui");
        if (!guiFolder.exists() || !guiFolder.isDirectory()) return;

        File targetFolder = new File(plugin.getDataFolder(), "modules/homes/inventories");
        if (!targetFolder.exists()) {
            targetFolder.mkdirs();
        }

        File homeGui = new File(guiFolder, "home.yml");
        if (homeGui.exists()) {
            try {
                YamlConfiguration src = YamlConfiguration.loadConfiguration(homeGui);
                File dest = new File(targetFolder, "home.yml");
                if (!dest.exists()) {
                    src.save(dest);
                    Messages.send(plugin, sender, "homes", "migrate-migrated-gui-menu-home-yml");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed migrating home.yml GUI", e);
            }
        }

        File confirmGui = new File(guiFolder, "confirm.yml");
        if (confirmGui.exists()) {
            try {
                YamlConfiguration src = YamlConfiguration.loadConfiguration(confirmGui);
                File dest = new File(targetFolder, "confirm.yml");
                if (!dest.exists()) {
                    src.save(dest);
                    Messages.send(plugin, sender, "homes", "migrate-migrated-gui-menu-confirm-yml");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed migrating confirm.yml GUI", e);
            }
        }
    }

    private MigrationDbResult migrateDatabaseHomes(File baseFolder, CommandSender sender) {
        File sqliteFile = null;
        if (baseFolder.isFile() && baseFolder.getName().endsWith(".db")) {
            sqliteFile = baseFolder;
        } else if (baseFolder.isDirectory()) {
            File directDb = new File(baseFolder, "storage.db");
            if (directDb.exists()) {
                sqliteFile = directDb;
            } else {
                File altDb = new File(baseFolder, "database.db");
                if (altDb.exists()) {
                    sqliteFile = altDb;
                }
            }
        }

        File dbConfigFile = baseFolder.isDirectory() ? new File(baseFolder, "database.yml") : new File(baseFolder.getParentFile(), "database.yml");
        if (dbConfigFile.exists()) {
            try {
                YamlConfiguration dbConfig = YamlConfiguration.loadConfiguration(dbConfigFile);
                String dbType = dbConfig.getString("database.type", "sqlite");
                if (dbType.equalsIgnoreCase("mysql") || dbType.equalsIgnoreCase("mariadb")) {
                    return migrateFromMySQL(dbConfig, sender);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error reading ModernHome database.yml", e);
            }
        }

        if (sqliteFile != null && sqliteFile.exists()) {
            return migrateFromSQLite(sqliteFile, sender);
        }

        return new MigrationDbResult(0, 0);
    }

    public MigrationDbResult migrateFromSQLite(File sqliteFile, CommandSender sender) {
        int homesCount = 0;
        int batchSize = 500;
        Map<UUID, PlayerHomes> playerMap = new LinkedHashMap<>();
        List<PlayerHomes> batch = new ArrayList<>(batchSize);

        String jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();

        String defaultServerId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");

        try (Connection conn = dev.lyy.friendlycore.tools.converter.SqlTableTransfer.createConnection(jdbcUrl, null, null)) {
            String sql = "SELECT * FROM homes";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                java.sql.ResultSetMetaData meta = rs.getMetaData();
                Set<String> cols = new HashSet<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    cols.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
                }

                while (rs.next()) {
                    try {
                        String uuidStr = rs.getString("uuid");
                        if (uuidStr == null || uuidStr.isBlank()) continue;
                        UUID uuid = UUID.fromString(uuidStr.trim());
                        int slot = cols.contains("home_index") ? rs.getInt("home_index") : (cols.contains("slot") ? rs.getInt("slot") : 1);
                        String world = cols.contains("world") ? rs.getString("world") : "world";
                        if (world == null || world.isBlank()) world = "world";
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = cols.contains("yaw") ? rs.getFloat("yaw") : (cols.contains("rotation_yaw") ? rs.getFloat("rotation_yaw") : 0.0f);
                        float pitch = cols.contains("pitch") ? rs.getFloat("pitch") : (cols.contains("rotation_pitch") ? rs.getFloat("rotation_pitch") : 0.0f);
                        String name = cols.contains("name") ? rs.getString("name") : (cols.contains("home_name") ? rs.getString("home_name") : "");
                        String serverId = cols.contains("server_id") ? rs.getString("server_id") : (cols.contains("server") ? rs.getString("server") : defaultServerId);
                        if (serverId == null || serverId.isBlank()) serverId = defaultServerId;

                        HomeData homeData = new HomeData(
                                uuid,
                                Math.max(1, slot),
                                name != null ? name : "",
                                serverId,
                                world,
                                x,
                                y,
                                z,
                                yaw,
                                pitch,
                                System.currentTimeMillis()
                        );

                        PlayerHomes profile = playerMap.computeIfAbsent(uuid, u -> new PlayerHomes(u, "Player"));
                        profile.setHome(homeData);
                        homesCount++;
                    } catch (Exception rowEx) {
                        plugin.getLogger().log(Level.WARNING, "Skipped corrupted ModernHome row", rowEx);
                    }
                }
            }

            for (PlayerHomes profile : playerMap.values()) {
                profile.setDirty(true);
                batch.add(profile);

                PlayerHomes cached = homesManager.getPlayerHomes(profile.getPlayerUuid());
                if (cached != null) {
                    for (HomeData h : profile.getHomes().values()) {
                        cached.setHome(h);
                    }
                }

                if (batch.size() >= batchSize) {
                    plugin.getDatabaseManager().savePlayerHomesBatch(batch).join();
                    batch.clear();
                    if (sender != null && playerMap.size() > 1000) {
                        Messages.send(plugin, sender, "homes", "migrate-processed-homes", "%homes_count%", String.valueOf(homesCount));
                    }
                }
            }

            if (!batch.isEmpty()) {
                plugin.getDatabaseManager().savePlayerHomesBatch(batch).join();
                batch.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed migrating from ModernHome SQLite: " + sqliteFile.getAbsolutePath(), e);
            if (sender != null) {
                Messages.send(plugin, sender, "homes", "migrate-sqlite-error", "%error%", String.valueOf(e.getMessage()));
            }
        }

        return new MigrationDbResult(homesCount, playerMap.size());
    }

    public MigrationDbResult migrateFromMySQL(YamlConfiguration config, CommandSender sender) {
        int homesCount = 0;
        int batchSize = 500;
        Map<UUID, PlayerHomes> playerMap = new LinkedHashMap<>();
        List<PlayerHomes> batch = new ArrayList<>(batchSize);

        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String user = config.getString("database.mysql.user", "root");
        String pass = config.getString("database.mysql.password", "");
        String database = config.getString("database.mysql.database", "modernhome");
        boolean useSsl = config.getBoolean("database.mysql.use-ssl", false);
        String defaultServerId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true";

        try (Connection conn = dev.lyy.friendlycore.tools.converter.SqlTableTransfer.createConnection(jdbcUrl, user, pass)) {
            if (sender != null) {
                Messages.send(plugin, sender, "homes", "migrate-connected-to-modernhome-mysql-database-reading");
            }

            String sql = "SELECT * FROM homes";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                java.sql.ResultSetMetaData meta = rs.getMetaData();
                Set<String> cols = new HashSet<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    cols.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
                }

                while (rs.next()) {
                    try {
                        String uuidStr = rs.getString("uuid");
                        if (uuidStr == null || uuidStr.isBlank()) continue;
                        UUID uuid = UUID.fromString(uuidStr.trim());
                        int slot = cols.contains("home_index") ? rs.getInt("home_index") : (cols.contains("slot") ? rs.getInt("slot") : 1);
                        String world = cols.contains("world") ? rs.getString("world") : "world";
                        if (world == null || world.isBlank()) world = "world";
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = cols.contains("yaw") ? rs.getFloat("yaw") : (cols.contains("rotation_yaw") ? rs.getFloat("rotation_yaw") : 0.0f);
                        float pitch = cols.contains("pitch") ? rs.getFloat("pitch") : (cols.contains("rotation_pitch") ? rs.getFloat("rotation_pitch") : 0.0f);
                        String name = cols.contains("name") ? rs.getString("name") : (cols.contains("home_name") ? rs.getString("home_name") : "");
                        String serverId = cols.contains("server_id") ? rs.getString("server_id") : (cols.contains("server") ? rs.getString("server") : defaultServerId);
                        if (serverId == null || serverId.isBlank()) serverId = defaultServerId;

                        HomeData homeData = new HomeData(
                                uuid,
                                Math.max(1, slot),
                                name != null ? name : "",
                                serverId,
                                world,
                                x,
                                y,
                                z,
                                yaw,
                                pitch,
                                System.currentTimeMillis()
                        );

                        PlayerHomes profile = playerMap.computeIfAbsent(uuid, u -> new PlayerHomes(u, "Player"));
                        profile.setHome(homeData);
                        homesCount++;
                    } catch (Exception rowEx) {
                        plugin.getLogger().log(Level.WARNING, "Skipped corrupted ModernHome MySQL row", rowEx);
                    }
                }
            }

            for (PlayerHomes profile : playerMap.values()) {
                profile.setDirty(true);
                batch.add(profile);

                PlayerHomes cached = homesManager.getPlayerHomes(profile.getPlayerUuid());
                if (cached != null) {
                    for (HomeData h : profile.getHomes().values()) {
                        cached.setHome(h);
                    }
                }

                if (batch.size() >= batchSize) {
                    plugin.getDatabaseManager().savePlayerHomesBatch(batch).join();
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                plugin.getDatabaseManager().savePlayerHomesBatch(batch).join();
                batch.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed migrating from ModernHome MySQL", e);
            if (sender != null) {
                Messages.send(plugin, sender, "homes", "migrate-mysql-error", "%error%", String.valueOf(e.getMessage()));
            }
        }

        return new MigrationDbResult(homesCount, playerMap.size());
    }

    private File findModernHomeFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "ModernHome"),
                new File(plugin.getDataFolder().getParentFile(), "modernhome"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/ModernHome"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/modernhome"),
                new File("plugins/ModernHome"),
                new File("plugins/modernhome"),
                new File("plugin-config/ModernHome"),
                new File("plugin-config/modernhome"),
                new File(new File("."), "plugin-config/ModernHome"),
                new File(new File("."), "ModernHome")
        };

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    public record MigrationDbResult(int homesMigrated, int playersMigrated) {}

    public record MigrationResult(
            int totalHomes,
            int totalPlayers,
            int errors,
            boolean success,
            String errorMessage
    ) {}
}
