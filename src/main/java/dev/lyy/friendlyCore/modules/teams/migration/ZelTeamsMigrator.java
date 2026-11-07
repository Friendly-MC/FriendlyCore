package dev.lyy.friendlycore.modules.teams.migration;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.model.*;
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
 * Asynchronous migration utility for importing legacy ZelTeams SQLite and MySQL databases into FriendlyCore.
 */
public class ZelTeamsMigrator {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public ZelTeamsMigrator(FriendlyCore plugin, TeamsManager teamsManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
    }

    public boolean isRunning() {
        return RUNNING.get();
    }

    public CompletableFuture<ZelTeamsReport> migrate(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            TeamMessages.send(plugin, sender, "a-team-migration-is-already-in-progress");
            return CompletableFuture.completedFuture(new ZelTeamsReport(0, 0, false, "Already running"));
        }

        return plugin.getScheduler().submitMigration(() -> {
            long startTime = System.currentTimeMillis();
            TeamMessages.send(plugin, sender, "scanning-for-zelteams-data");

            File baseFolder = customFolder;
            if (baseFolder == null || !baseFolder.exists()) {
                baseFolder = findZelTeamsFolder();
            }

            if (baseFolder == null || !baseFolder.exists()) {
                String err = "ZelTeams folder not found. Expected at 'plugins/ZelTeams/' or 'plugin-config/ZelTeams/'";
                TeamMessages.send(plugin, sender, "migration-error", "%err%", String.valueOf(err));
                return new ZelTeamsReport(0, 0, false, err);
            }

            TeamMessages.send(plugin, sender, "found-source", "%path%", String.valueOf(baseFolder.getPath()));

            ZelTeamsDbResult dbResult = migrateDatabase(baseFolder, sender);

            // Populate in-memory caches and refresh leaderboards with migrated teams
            CompletableFuture<Void> reloadFuture = teamsManager.getDao().loadAllPlayerStats().thenCompose(stats -> {
                teamsManager.getPlayerStatsMap().putAll(stats);
                return teamsManager.getDao().loadAllTeams(
                        teamsManager.getRoleRegistry(),
                        teamsManager.getDefaultMemberRole(),
                        teamsManager.getOwnerRole()
                ).thenAccept(teams -> {
                    for (Team t : teams) {
                        teamsManager.registerTeamInMemory(t);
                    }
                    if (sender != null) {
                        TeamMessages.send(plugin, sender, "loaded-teams-into-memory-cache", "%size%", String.valueOf(teams.size()));
                    }
                });
            });
            reloadFuture.join();

            long elapsed = System.currentTimeMillis() - startTime;
            TeamMessages.send(plugin, sender, "teams-migration-completed");
            TeamMessages.send(plugin, sender, "total-teams-imported", "%teams_migrated%", String.valueOf(dbResult.teamsMigrated()));
            TeamMessages.send(plugin, sender, "total-player-records", "%players_migrated%", String.valueOf(dbResult.playersMigrated()));
            TeamMessages.send(plugin, sender, "time-taken-ms", "%elapsed%", String.valueOf(elapsed));

            return new ZelTeamsReport(dbResult.teamsMigrated(), dbResult.playersMigrated(), true, null);
        }).whenComplete((result, error) -> RUNNING.set(false));
    }

    private ZelTeamsDbResult migrateDatabase(File baseFolder, CommandSender sender) {
        // Check for MySQL credentials in database.yml first
        File dbConfigFile = baseFolder.isDirectory() ? new File(baseFolder, "database.yml") : new File(baseFolder.getParentFile(), "database.yml");
        if (dbConfigFile.exists()) {
            try {
                YamlConfiguration dbConfig = YamlConfiguration.loadConfiguration(dbConfigFile);
                String dbType = dbConfig.getString("Database-Type", "SQLite");
                if (dbType.equalsIgnoreCase("MySQL") || dbType.equalsIgnoreCase("MariaDB")) {
                    return migrateFromMySQL(dbConfig, sender);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error reading ZelTeams database.yml", e);
            }
        }

        // Fall back to local SQLite database file
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

        if (sqliteFile != null && sqliteFile.exists()) {
            return migrateFromSQLite(sqliteFile, sender);
        }

        return new ZelTeamsDbResult(0, 0);
    }

    public ZelTeamsDbResult migrateFromSQLite(File sqliteFile, CommandSender sender) {
        String jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            return executeMigrationFromConnection(conn, sender);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed connecting to ZelTeams SQLite: " + sqliteFile.getAbsolutePath(), e);
            if (sender != null) {
                TeamMessages.send(plugin, sender, "sqlite-error", "%error%", String.valueOf(e.getMessage()));
            }
            return new ZelTeamsDbResult(0, 0);
        }
    }

    public ZelTeamsDbResult migrateFromMySQL(YamlConfiguration config, CommandSender sender) {
        String host = config.getString("MySQL.host", "localhost");
        int port = config.getInt("MySQL.port", 3306);
        String user = config.getString("MySQL.user", "root");
        String pass = config.getString("MySQL.password", "");
        String database = config.getString("MySQL.database", "teams");
        boolean useSsl = config.getBoolean("MySQL.use-ssl", false);

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, pass)) {
            if (sender != null) {
                TeamMessages.send(plugin, sender, "connected-to-zelteams-mysql-database");
            }
            return executeMigrationFromConnection(conn, sender);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed connecting to ZelTeams MySQL", e);
            if (sender != null) {
                TeamMessages.send(plugin, sender, "mysql-error", "%error%", String.valueOf(e.getMessage()));
            }
            return new ZelTeamsDbResult(0, 0);
        }
    }

    public ZelTeamsDbResult executeMigrationFromConnection(Connection srcConn, CommandSender sender) {
        int teamsMigrated = 0;
        int playersMigrated = 0;
        int batchSize = 500;

        TeamRole ownerRole = teamsManager.getOwnerRole();
        TeamRole defaultMemberRole = teamsManager.getDefaultMemberRole();
        Map<String, TeamRole> roleRegistry = teamsManager.getRoleRegistry();

        List<Team> teamBatch = new ArrayList<>(batchSize);
        String selectTeamsSql = "SELECT * FROM teamdata";
        try (PreparedStatement ps = srcConn.prepareStatement(selectTeamsSql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                try {
                    String ownerUuidStr = rs.getString("owner");
                    if (ownerUuidStr == null || ownerUuidStr.isBlank()) continue;
                    UUID ownerUuid = UUID.fromString(ownerUuidStr.trim());
                    String ownerName = rs.getString("ownername");
                    if (ownerName == null || ownerName.isBlank()) ownerName = "Owner";

                    String tag = rs.getString("tag");
                    if (tag == null || tag.isBlank()) tag = "Team";
                    String name = tag;

                    TeamOwner owner = new TeamOwner(ownerUuid, ownerName, ownerRole, 0L);
                    Team team = new Team(ownerUuid, name, tag, owner);

                    team.setBankBalance(rs.getDouble("bank"));
                    team.setMaxHomes(Math.max(1, rs.getInt("maxhomes")));
                    team.setLastTagChange(rs.getLong("lasttagchange"));
                    team.setFriendlyFireEnabled(rs.getBoolean("ff"));

                    String color = rs.getString("color");
                    if (color != null && !color.isBlank()) {
                        team.setColorKey(color.trim());
                    }

                    String ec = rs.getString("enderchest");
                    if (ec != null && !ec.isBlank()) {
                        team.getEnderChest().setStoredSnapshot(ec);
                    }

                    String alliesStr = rs.getString("allies");
                    if (alliesStr != null && !alliesStr.isBlank()) {
                        for (String aUuid : alliesStr.split(",")) {
                            if (!aUuid.isBlank()) {
                                try {
                                    team.getAllies().add(UUID.fromString(aUuid.trim()));
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Homes format in ZelTeams: 'home==world:x:y:z:yaw:pitch||home2==...' or legacy single 'home' column
                    String homesStr = rs.getString("homes");
                    if (homesStr == null || homesStr.isBlank()) {
                        String singleHome = rs.getString("home");
                        if (singleHome != null && !singleHome.isBlank()) {
                            homesStr = "home==" + singleHome;
                        }
                    }

                    String defaultServerId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");
                    if (homesStr != null && !homesStr.isBlank()) {
                        String[] homeEntries = homesStr.contains("||") ? homesStr.split("\\|\\|") : new String[]{homesStr};
                        for (String hEntry : homeEntries) {
                            if (hEntry.isBlank()) continue;
                            TeamHome parsedHome = parseZelTeamsHome(hEntry, defaultServerId);
                            if (parsedHome != null) {
                                team.getHomes().put(parsedHome.getName().toLowerCase(Locale.ROOT), parsedHome);
                            }
                        }
                    }

                    // Member records formatted as 'uuid;username;roleId'
                    String membersStr = rs.getString("members");
                    if (membersStr != null && !membersStr.isBlank()) {
                        String[] memberTokens = membersStr.contains(",") ? membersStr.split(",") : new String[]{membersStr};
                        for (String mToken : memberTokens) {
                            if (mToken.isBlank()) continue;
                            String[] parts = mToken.split(";");
                            if (parts.length >= 2) {
                                try {
                                    UUID mUuid = UUID.fromString(parts[0].trim());
                                    String mName = parts[1].trim();
                                    String roleKey = parts.length > 2 ? parts[2].trim() : "6";

                                    TeamRole role = roleRegistry.getOrDefault(roleKey, defaultMemberRole);
                                    if (mUuid.equals(ownerUuid)) {
                                        team.setOwner(new TeamOwner(mUuid, mName, ownerRole, System.currentTimeMillis()));
                                    } else {
                                        team.addMember(new TeamMember(mUuid, mName, role, System.currentTimeMillis()));
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    team.setDirty(true);
                    teamBatch.add(team);
                    teamsMigrated++;

                    if (teamBatch.size() >= batchSize) {
                        teamsManager.getDao().saveTeamsBatch(teamBatch).join();
                        teamBatch.clear();
                        if (sender != null && teamsMigrated % 500 == 0) {
                            TeamMessages.send(plugin, sender, "migrated-teams", "%teams_migrated%", String.valueOf(teamsMigrated));
                        }
                    }
                } catch (Exception rowEx) {
                    plugin.getLogger().log(Level.WARNING, "Error reading legacy ZelTeams team row", rowEx);
                }
            }

            if (!teamBatch.isEmpty()) {
                teamsManager.getDao().saveTeamsBatch(teamBatch).join();
                teamBatch.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed reading legacy teamdata table", e);
        }

        List<TeamPlayerStats> statsBatch = new ArrayList<>(batchSize);
        Map<UUID, UUID> playerToTeamMap = new HashMap<>(batchSize);

        String selectPlayersSql = "SELECT player, username, team, stats FROM playerdata";
        try (PreparedStatement ps = srcConn.prepareStatement(selectPlayersSql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                try {
                    String playerUuidStr = rs.getString("player");
                    if (playerUuidStr == null || playerUuidStr.isBlank()) continue;
                    UUID playerUuid = UUID.fromString(playerUuidStr.trim());
                    String username = rs.getString("username");
                    if (username == null || username.isBlank()) username = "Player";

                    String teamUuidStr = rs.getString("team");
                    UUID teamUuid = null;
                    if (teamUuidStr != null && !teamUuidStr.isBlank()) {
                        try {
                            teamUuid = UUID.fromString(teamUuidStr.trim());
                        } catch (Exception ignored) {}
                    }

                    String rawStats = rs.getString("stats");
                    TeamPlayerStats stats = parseZelTeamsStats(playerUuid, username, rawStats);
                    stats.setDirty(true);

                    statsBatch.add(stats);
                    if (teamUuid != null) {
                        playerToTeamMap.put(playerUuid, teamUuid);
                    }
                    playersMigrated++;

                    if (statsBatch.size() >= batchSize) {
                        teamsManager.getDao().savePlayerStatsBatch(statsBatch, playerToTeamMap).join();
                        statsBatch.clear();
                        playerToTeamMap.clear();
                        if (sender != null && playersMigrated % 2500 == 0) {
                            TeamMessages.send(plugin, sender, "migrated-player-stats", "%players_migrated%", String.valueOf(playersMigrated));
                        }
                    }
                } catch (Exception rowEx) {
                    plugin.getLogger().log(Level.WARNING, "Error reading legacy ZelTeams player row", rowEx);
                }
            }

            if (!statsBatch.isEmpty()) {
                teamsManager.getDao().savePlayerStatsBatch(statsBatch, playerToTeamMap).join();
                statsBatch.clear();
                playerToTeamMap.clear();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed reading legacy playerdata table", e);
        }

        return new ZelTeamsDbResult(teamsMigrated, playersMigrated);
    }

    public static TeamHome parseZelTeamsHome(String raw) {
        return parseZelTeamsHome(raw, "");
    }

    public static TeamHome parseZelTeamsHome(String raw, String defaultServerId) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String name = "home";
            String locData = raw;
            if (raw.contains("==")) {
                String[] parts = raw.split("==", 2);
                name = parts[0].trim();
                locData = parts[1].trim();
            }

            // In ZelTeams, location is formatted as "world:x:y:z:yaw:pitch" (or semicolon)
            String delimiter = locData.contains(";") ? ";" : ":";
            String[] tokens = locData.split(delimiter);
            if (tokens.length >= 4) {
                String world = tokens[0];
                double x = Double.parseDouble(tokens[1]);
                double y = Double.parseDouble(tokens[2]);
                double z = Double.parseDouble(tokens[3]);
                float yaw = tokens.length > 4 ? Float.parseFloat(tokens[4]) : 0.0f;
                float pitch = tokens.length > 5 ? Float.parseFloat(tokens[5]) : 0.0f;
                return new TeamHome(name, world, x, y, z, yaw, pitch, defaultServerId != null ? defaultServerId : "");
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static TeamPlayerStats parseZelTeamsStats(UUID playerUuid, String username, String rawStats) {
        if (rawStats == null || rawStats.isBlank()) {
            return new TeamPlayerStats(playerUuid, username);
        }

        int kills = 0;
        int deaths = 0;
        int mobKills = 0;
        int blocksBroken = 0;
        int blocksPlaced = 0;
        int level = 1;

        try {
            String[] entries = rawStats.split(",");
            for (String entry : entries) {
                String[] kv = entry.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().toUpperCase(Locale.ROOT);
                    String val = kv[1].trim();
                    try {
                        switch (key) {
                            case "KILLS" -> kills = Integer.parseInt(val);
                            case "DEATHS" -> deaths = Integer.parseInt(val);
                            case "MOBS_KILLED" -> mobKills = Integer.parseInt(val);
                            case "BLOCKS_BROKEN" -> blocksBroken = Integer.parseInt(val);
                            case "BLOCKS_PLACED" -> blocksPlaced = Integer.parseInt(val);
                            case "LEVEL" -> level = Math.max(1, Integer.parseInt(val));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return new TeamPlayerStats(playerUuid, username, kills, deaths, mobKills, blocksBroken, blocksPlaced, 0L, level);
    }

    private File findZelTeamsFolder() {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), "ZelTeams"),
                new File(plugin.getDataFolder().getParentFile(), "zelteams"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/ZelTeams"),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/zelteams"),
                new File("plugins/ZelTeams"),
                new File("plugins/zelteams"),
                new File("plugin-config/ZelTeams"),
                new File("plugin-config/zelteams"),
                new File(new File("."), "plugin-config/ZelTeams"),
                new File(new File("."), "ZelTeams")
        };

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    public record ZelTeamsDbResult(int teamsMigrated, int playersMigrated) {}

    public record ZelTeamsReport(int teamsMigrated, int playersMigrated, boolean success, String errorMessage) {}
}
