package dev.lyy.friendlycore.modules.kits.migration;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import dev.lyy.friendlycore.modules.kits.model.PlayerKitData;
import dev.lyy.friendlycore.modules.kits.model.PlayerKitRecord;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Enterprise-grade, asynchronous PlayerKits (v1 & v2) Migrator for Kit configurations and Player Data.
 */
public class PlayerKitsMigrator {

    private final FriendlyCore plugin;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public PlayerKitsMigrator(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return RUNNING.get();
    }

    public CompletableFuture<MigrationSummary> migrate(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            Messages.send(plugin, sender, "kits", "migrate-a-kit-migration-is-already-in");
            return CompletableFuture.completedFuture(new MigrationSummary(0, 0, 0, false, "Already running"));
        }

        return plugin.getScheduler().submitMigration(() -> {
            long startTime = System.currentTimeMillis();
            Messages.send(plugin, sender, "kits", "migrate-scanning-for-playerkits-files");

            File baseFolder = customFolder;
            if (baseFolder == null || !baseFolder.exists()) {
                baseFolder = findPkDataFolder();
            }

            if (baseFolder == null || !baseFolder.exists()) {
                String error = "PlayerKits folder not found. Expected at 'plugins/PlayerKits2/' or 'plugins/PlayerKits/'";
                Messages.send(plugin, sender, "kits", "migrate-error", "%error%", String.valueOf(error));
                return new MigrationSummary(0, 0, 0, false, error);
            }

            Messages.send(plugin, sender, "kits", "migrate-found-directory", "%path%", String.valueOf(baseFolder.getPath()));

            int kitsMigrated = migrateKits(baseFolder, sender);

            migrateInventories(baseFolder, sender);

            // Import player data from flat files or database
            int playersMigrated = migratePlayerData(baseFolder, sender);

            // Reload in-memory kits and broadcast updates to proxy network
            plugin.getKitManager().loadKits();
            if (plugin.getKitManager().getCrossServerService() != null) {
                plugin.getKitManager().getCrossServerService().broadcastReload();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Messages.send(plugin, sender, "kits", "migrate-kit-migration-completed");
            Messages.send(plugin, sender, "kits", "migrate-kits-imported", "%kits_migrated%", String.valueOf(kitsMigrated));
            Messages.send(plugin, sender, "kits", "migrate-player-records", "%players_migrated%", String.valueOf(playersMigrated));
            Messages.send(plugin, sender, "kits", "migrate-ms", "%elapsed%", String.valueOf(elapsed));

            return new MigrationSummary(kitsMigrated, playersMigrated, 0, true, null);
        }).whenComplete((result, error) -> RUNNING.set(false));
    }

    private int migrateKits(File baseFolder, CommandSender sender) {
        int count = 0;
        Set<File> filesToMigrate = new LinkedHashSet<>();

        if (baseFolder.isFile() && baseFolder.getName().endsWith(".yml")) {
            filesToMigrate.add(baseFolder);
        } else if (baseFolder.isDirectory()) {
            // Check direct kit YAML files in the given directory
            File[] directFiles = baseFolder.listFiles((dir, name) -> name.endsWith(".yml")
                    && !name.equalsIgnoreCase("inventory.yml")
                    && !name.equalsIgnoreCase("inventories.yml")
                    && !name.equalsIgnoreCase("config.yml")
                    && !name.equalsIgnoreCase("messages.yml")
                    && !name.equalsIgnoreCase("players.yml")
                    && !name.equalsIgnoreCase("kits.yml"));
            if (directFiles != null) {
                for (File f : directFiles) {
                    filesToMigrate.add(f);
                }
            }

            // Check 'kits' subdirectory if pointing to plugin root folder
            File subKitsFolder = new File(baseFolder, "kits");
            if (subKitsFolder.exists() && subKitsFolder.isDirectory()) {
                File[] subFiles = subKitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
                if (subFiles != null) {
                    for (File f : subFiles) {
                        filesToMigrate.add(f);
                    }
                }
            }
        }

        synchronized (plugin.getConfigService()) {
            for (File file : filesToMigrate) {
                try {
                    String kitName = file.getName().substring(0, file.getName().length() - 4);
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    Kit kit = plugin.getKitManager().parseKitFromSection(yaml, kitName);
                    if (kit != null) {
                        plugin.getKitManager().saveKitToSection(plugin.getConfigService().getKitsConfig(), "kits." + kitName.toLowerCase(Locale.ROOT), kit);
                        count++;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed migrating kit file: " + file.getName(), e);
                }
            }

            File singleKitsFile = new File(baseFolder, "kits.yml");
            if (!singleKitsFile.exists() && baseFolder.getParentFile() != null) {
                singleKitsFile = new File(baseFolder.getParentFile(), "kits.yml");
            }
            if (singleKitsFile.exists() && singleKitsFile.isFile()) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(singleKitsFile);
                    ConfigurationSection kitsSec = yaml.getConfigurationSection("Kits");
                    if (kitsSec != null) {
                        for (String key : kitsSec.getKeys(false)) {
                            Kit kit = parseV1Kit(kitsSec.getConfigurationSection(key), key);
                            if (kit != null) {
                                plugin.getKitManager().saveKitToSection(plugin.getConfigService().getKitsConfig(), "kits." + key.toLowerCase(Locale.ROOT), kit);
                                count++;
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed migrating v1 kits.yml", e);
                }
            }

            if (count > 0) {
                plugin.getConfigService().save("kits.yml");
            }
        }

        if (count > 0) {
            Messages.send(plugin, sender, "kits", "migrate-migrated-kit-definitions", "%count%", String.valueOf(count));
        }
        return count;
    }

    private void migrateInventories(File baseFolder, CommandSender sender) {
        File invFile = new File(baseFolder, "inventory.yml");
        if (!invFile.exists() && baseFolder.getParentFile() != null) {
            invFile = new File(baseFolder.getParentFile(), "inventory.yml");
        }
        if (!invFile.exists()) {
            invFile = new File(baseFolder, "inventories.yml");
            if (!invFile.exists() && baseFolder.getParentFile() != null) {
                invFile = new File(baseFolder.getParentFile(), "inventories.yml");
            }
        }
        if (!invFile.exists()) return;

        try {
            YamlConfiguration src = YamlConfiguration.loadConfiguration(invFile);
            File destFile = new File(plugin.getDataFolder(), "modules/kits/inventories/kits.yml");
            if (!destFile.exists()) {
                destFile.getParentFile().mkdirs();
            }
            YamlConfiguration dest = YamlConfiguration.loadConfiguration(destFile);

            if (src.contains("inventories")) {
                dest.set("inventories", src.get("inventories"));
                dest.save(destFile);
                Messages.send(plugin, sender, "kits", "migrate-migrated-gui-inventories-from", "%inv_file_name%", invFile.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed migrating inventory configuration", e);
        }
    }

    private int migratePlayerData(File baseFolder, CommandSender sender) {
        int count = 0;
        int batchSize = 500;
        List<PlayerKitData> batch = new ArrayList<>(batchSize);

        File playerDataFolder = new File(baseFolder, "playerdata");
        if (!playerDataFolder.exists() && baseFolder.getParentFile() != null) {
            playerDataFolder = new File(baseFolder.getParentFile(), "playerdata");
        }
        if (playerDataFolder.exists() && playerDataFolder.isDirectory()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(playerDataFolder.toPath(), "*.yml")) {
                for (Path p : stream) {
                    try {
                        File f = p.toFile();
                        String fileName = f.getName();
                        String uuidStr = fileName.substring(0, fileName.length() - 4);
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }

                        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
                        String name = yaml.getString("name", "Player");
                        PlayerKitData data = new PlayerKitData(uuid, name);

                        ConfigurationSection kitsSec = yaml.getConfigurationSection("kits");
                        if (kitsSec != null) {
                            for (String kitKey : kitsSec.getKeys(false)) {
                                long cd = kitsSec.getLong(kitKey + ".cooldown", 0L);
                                boolean oneTime = kitsSec.getBoolean(kitKey + ".one_time", false);
                                boolean bought = kitsSec.getBoolean(kitKey + ".bought", false);
                                data.addRecord(new PlayerKitRecord(kitKey, cd, oneTime, bought, System.currentTimeMillis()));
                            }
                        }

                        data.markDirty();
                        batch.add(data);
                        count++;

                        if (batch.size() >= batchSize) {
                            plugin.getDatabaseManager().savePlayerKitDataBatch(batch).join();
                            batch.clear();
                            if (count % 2500 == 0) {
                                Messages.send(plugin, sender, "kits", "migrate-migrated-player-records", "%count%", String.valueOf(count));
                            }
                        }
                    } catch (Exception fileEx) {
                        plugin.getLogger().log(Level.WARNING, "Skipped corrupted player file: " + p.getFileName(), fileEx);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scanning PlayerKits2 playerdata folder", e);
            }
        }

        // Scan PlayerKits v1 legacy single file (players.yml)
        File v1PlayersFile = new File(baseFolder, "players.yml");
        if (v1PlayersFile.exists() && v1PlayersFile.isFile()) {
            try {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(v1PlayersFile);
                ConfigurationSection playersSec = yaml.getConfigurationSection("Players");
                if (playersSec != null) {
                    for (String uuidStr : playersSec.getKeys(false)) {
                        try {
                            UUID uuid;
                            try {
                                uuid = UUID.fromString(uuidStr);
                            } catch (IllegalArgumentException e) {
                                continue;
                            }
                            String name = playersSec.getString(uuidStr + ".name", "Player");
                            PlayerKitData data = new PlayerKitData(uuid, name);
                            ConfigurationSection userSec = playersSec.getConfigurationSection(uuidStr);
                            if (userSec != null) {
                                for (String kitKey : userSec.getKeys(false)) {
                                    if (kitKey.equalsIgnoreCase("name")) continue;
                                    long cd = userSec.getLong(kitKey + ".cooldown", 0L);
                                    boolean oneTime = userSec.getBoolean(kitKey + ".one_time", false);
                                    boolean bought = userSec.getBoolean(kitKey + ".buyed", false);
                                    data.addRecord(new PlayerKitRecord(kitKey, cd, oneTime, bought, System.currentTimeMillis()));
                                }
                            }
                            data.markDirty();
                            batch.add(data);
                            count++;

                            if (batch.size() >= batchSize) {
                                plugin.getDatabaseManager().savePlayerKitDataBatch(batch).join();
                                batch.clear();
                            }
                        } catch (Exception playerEx) {
                            plugin.getLogger().log(Level.WARNING, "Skipped corrupt player record: " + uuidStr, playerEx);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error reading v1 players.yml", e);
            }
        }

        // Migrate PlayerKits2 MySQL database tables if enabled
        File mainConfigFile = new File(baseFolder, "config.yml");
        if (mainConfigFile.exists()) {
            try {
                YamlConfiguration pkConfig = YamlConfiguration.loadConfiguration(mainConfigFile);
                if (pkConfig.getBoolean("mysql_database.enabled", false)) {
                    count += migrateFromMySQL(pkConfig, batch, sender);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error reading PlayerKits2 config.yml for MySQL migration", e);
            }
        }

        if (!batch.isEmpty()) {
            plugin.getDatabaseManager().savePlayerKitDataBatch(batch).join();
            batch.clear();
        }

        return count;
    }

    private int migrateFromMySQL(YamlConfiguration config, List<PlayerKitData> batch, CommandSender sender) {
        int sqlCount = 0;
        String host = config.getString("mysql_database.host", "localhost");
        int port = config.getInt("mysql_database.port", 3306);
        String user = config.getString("mysql_database.username", "root");
        String pass = config.getString("mysql_database.password", "");
        String database = config.getString("mysql_database.database", "database");

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            Messages.send(plugin, sender, "kits", "migrate-connected-to-playerkits-mysql-database-migrating");

            String sql = """
                SELECT p.UUID, p.PLAYER_NAME, k.NAME, k.COOLDOWN, k.ONE_TIME, k.BOUGHT
                FROM playerkits_players p
                LEFT JOIN playerkits_players_kits k ON p.UUID = k.UUID;
            """;

            Map<UUID, PlayerKitData> map = new HashMap<>();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuidStr = rs.getString("UUID");
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidStr);
                    } catch (Exception e) {
                        continue;
                    }

                    String name = rs.getString("PLAYER_NAME");
                    PlayerKitData data = map.computeIfAbsent(uuid, u -> new PlayerKitData(u, name));

                    String kitName = rs.getString("NAME");
                    if (kitName != null) {
                        long cd = rs.getLong("COOLDOWN");
                        boolean oneTime = rs.getBoolean("ONE_TIME");
                        boolean bought = rs.getBoolean("BOUGHT");
                        data.addRecord(new PlayerKitRecord(kitName, cd, oneTime, bought, System.currentTimeMillis()));
                    }
                }
            }

            for (PlayerKitData data : map.values()) {
                data.markDirty();
                batch.add(data);
                sqlCount++;
                if (batch.size() >= 500) {
                    plugin.getDatabaseManager().savePlayerKitDataBatch(batch).join();
                    batch.clear();
                }
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed migrating from PlayerKits MySQL", e);
        }

        return sqlCount;
    }

    private File findPkDataFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "PlayerKits2"),
                new File(plugin.getDataFolder().getParentFile(), "playerkits2"),
                new File(plugin.getDataFolder().getParentFile(), "PlayerKits"),
                new File(plugin.getDataFolder().getParentFile(), "playerkits")
        };

        for (File f : candidates) {
            if (f.exists() && f.isDirectory()) return f;
        }
        return null;
    }

    static Kit parseV1Kit(ConfigurationSection section, String key) {
        if (section == null) return null;
        Kit kit = new Kit(key);
        kit.setCooldownSeconds(readLegacyLong(section, "cooldown", 0L));
        kit.setOneTime(readLegacyBoolean(section, "one_time", false));
        kit.setAutoArmor(readLegacyBoolean(section, "auto_armor", false));
        kit.setPermissionRequired(section.contains("permission"));

        List<KitItem> items = new ArrayList<>();
        ConfigurationSection itemSec = section.getConfigurationSection("Items");
        if (itemSec != null) {
            for (String itemKey : itemSec.getKeys(false)) {
                String path = itemKey + ".";
                String id = itemSec.getString(path + "id", "STONE");
                KitItem ki = new KitItem(id);
                ki.setAmount(Math.max(1, readLegacyInt(itemSec, path + "amount", 1)));
                ki.setName(itemSec.getString(path + "name"));
                ki.setLore(itemSec.getStringList(path + "lore"));
                ki.setEnchants(itemSec.getStringList(path + "enchants"));
                ki.setPreviewSlot(readLegacyInt(itemSec, path + "preview_slot", -1));

                KitItem.EquipmentSlot equipmentSlot = parseLegacyEquipmentSlot(
                        itemSec.getString(path + "equipment_slot",
                                itemSec.getString(path + "equipment-slot")));
                if (equipmentSlot == null && readLegacyBoolean(itemSec, path + "offhand", false)) {
                    equipmentSlot = KitItem.EquipmentSlot.OFF_HAND;
                }
                if (equipmentSlot == null && kit.isAutoArmor()) {
                    equipmentSlot = inferLegacyEquipmentSlot(id);
                }
                if (equipmentSlot != null) {
                    ki.setEquipmentSlot(equipmentSlot);
                }
                items.add(ki);
            }
        }
        kit.setKitItems(items);
        return kit;
    }

    private static boolean readLegacyBoolean(ConfigurationSection section, String path, boolean defaultValue) {
        Object raw = section.get(path);
        if (raw instanceof Boolean value) return value;
        if (raw instanceof Number value) return value.intValue() != 0;
        if (raw instanceof String value) {
            String normalized = value.trim();
            if (normalized.equalsIgnoreCase("true") || normalized.equals("1")) return true;
            if (normalized.equalsIgnoreCase("false") || normalized.equals("0")) return false;
        }
        return defaultValue;
    }

    private static int readLegacyInt(ConfigurationSection section, String path, int defaultValue) {
        long parsed = readLegacyLong(section, path, defaultValue);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) return defaultValue;
        return (int) parsed;
    }

    private static long readLegacyLong(ConfigurationSection section, String path, long defaultValue) {
        Object raw = section.get(path);
        if (raw instanceof Number value) return value.longValue();
        if (raw instanceof String value) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static KitItem.EquipmentSlot parseLegacyEquipmentSlot(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "HEAD", "HELMET" -> KitItem.EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> KitItem.EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> KitItem.EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> KitItem.EquipmentSlot.FEET;
            case "OFF_HAND", "OFFHAND" -> KitItem.EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    private static KitItem.EquipmentSlot inferLegacyEquipmentSlot(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String id = itemId.trim().toUpperCase(Locale.ROOT);
        if (id.endsWith("_HELMET") || id.endsWith("PLAYER_HEAD") || id.equals("CARVED_PUMPKIN")
                || id.endsWith("_SKULL") || id.endsWith("_HEAD") || id.contains("SKULL")) {
            return KitItem.EquipmentSlot.HEAD;
        }
        if (id.endsWith("_CHESTPLATE") || id.endsWith("ELYTRA")) {
            return KitItem.EquipmentSlot.CHEST;
        }
        if (id.endsWith("_LEGGINGS")) {
            return KitItem.EquipmentSlot.LEGS;
        }
        if (id.endsWith("_BOOTS")) {
            return KitItem.EquipmentSlot.FEET;
        }
        return null;
    }

    public record MigrationSummary(
            int kitsMigrated,
            int playersMigrated,
            int errors,
            boolean success,
            String errorMessage
    ) {}
}
