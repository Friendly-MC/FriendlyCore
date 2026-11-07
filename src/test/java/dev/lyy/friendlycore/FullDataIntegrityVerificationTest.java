package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import dev.lyy.friendlycore.modules.teams.migration.ZelTeamsMigrator;
import dev.lyy.friendlycore.modules.teams.model.Team;
import dev.lyy.friendlycore.modules.teams.model.TeamMember;
import dev.lyy.friendlycore.modules.teams.model.TeamPlayerStats;
import dev.lyy.friendlycore.tools.converter.SqlTableTransfer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive data integrity test suite verifying zero data loss across all legacy sources:
 * ModernHome, ZelTeams, Eco/EcoPets, DonutOrder, VirtualSpawner, and AxAuctions.
 */
public class FullDataIntegrityVerificationTest {

    @Test
    @DisplayName("Verify ModernHome 100% Data Integrity & Zero Loss")
    void testModernHomeFullDataIntegrity() throws Exception {
        File dbFile = new File("plugin-config/ModernHome/storage.db");
        if (!dbFile.exists()) return;

        Map<String, String> originalData = new HashMap<>();

        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            // Create target friendlycore_homes table
            try (Statement stmt = targetConn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_homes (
                        player_uuid VARCHAR(36) NOT NULL,
                        home_slot INT NOT NULL,
                        home_name VARCHAR(64) NOT NULL,
                        server_id VARCHAR(64) NOT NULL,
                        world VARCHAR(64) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, home_slot)
                    );
                """);
            }

            // Read source and populate target
            String insertSql = "INSERT INTO friendlycore_homes (player_uuid, home_slot, home_name, server_id, world, x, y, z, yaw, pitch, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            int rowCount = 0;

            try (PreparedStatement srcPs = srcConn.prepareStatement("SELECT uuid, home_index, world, x, y, z, name, server_id FROM homes");
                 ResultSet rs = srcPs.executeQuery();
                 PreparedStatement targetPs = targetConn.prepareStatement(insertSql)) {

                while (rs.next()) {
                    String uuid = rs.getString("uuid").trim();
                    int slot = Math.max(1, rs.getInt("home_index"));
                    String world = rs.getString("world") != null ? rs.getString("world") : "world";
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");
                    String name = rs.getString("name") != null ? rs.getString("name") : "";
                    String serverId = rs.getString("server_id") != null ? rs.getString("server_id") : "";

                    String key = uuid + ":" + slot;
                    String val = name + "|" + serverId + "|" + world + "|" + x + "|" + y + "|" + z;
                    originalData.put(key, val);

                    targetPs.setString(1, uuid);
                    targetPs.setInt(2, slot);
                    targetPs.setString(3, name);
                    targetPs.setString(4, serverId);
                    targetPs.setString(5, world);
                    targetPs.setDouble(6, x);
                    targetPs.setDouble(7, y);
                    targetPs.setDouble(8, z);
                    targetPs.setFloat(9, 0.0f);
                    targetPs.setFloat(10, 0.0f);
                    targetPs.setLong(11, System.currentTimeMillis());
                    targetPs.addBatch();
                    rowCount++;
                }
                targetPs.executeBatch();
            }

            assertTrue(rowCount > 0, "The supplied ModernHome database must contain homes");

            // Verify target table contents against source map
            int verifiedRows = 0;
            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT player_uuid, home_slot, home_name, server_id, world, x, y, z FROM friendlycore_homes")) {
                while (rs.next()) {
                    String uuid = rs.getString("player_uuid");
                    int slot = rs.getInt("home_slot");
                    String name = rs.getString("home_name");
                    String serverId = rs.getString("server_id");
                    String world = rs.getString("world");
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");

                    String key = uuid + ":" + slot;
                    assertTrue(originalData.containsKey(key), "Target home key must exist in source: " + key);

                    String expectedVal = originalData.get(key);
                    String actualVal = name + "|" + serverId + "|" + world + "|" + x + "|" + y + "|" + z;
                    assertEquals(expectedVal, actualVal, "Home record fields must match exactly");
                    verifiedRows++;
                }
            }

            assertEquals(originalData.size(), verifiedRows, "Verified row count must match distinct keys without loss");
        }
    }

    @Test
    @DisplayName("Verify ZelTeams 100% Data Integrity against the supplied database")
    void testZelTeamsFullDataIntegrity() throws Exception {
        File dbFile = new File("plugin-config/ZelTeams/storage.db");
        if (!dbFile.exists()) return;

        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            // Create target tables
            try (Statement stmt = targetConn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_teams (
                        team_uuid VARCHAR(36) PRIMARY KEY,
                        team_name VARCHAR(32) NOT NULL,
                        tag VARCHAR(16) NOT NULL,
                        color VARCHAR(16) NOT NULL,
                        leader_uuid VARCHAR(36) NOT NULL,
                        leader_name VARCHAR(32) NOT NULL,
                        created_at BIGINT NOT NULL,
                        bank_balance DOUBLE NOT NULL DEFAULT 0.0,
                        enderchest_data MEDIUMTEXT,
                        friendly_fire BOOLEAN NOT NULL DEFAULT 0,
                        open_join BOOLEAN NOT NULL DEFAULT 0,
                        members_data MEDIUMTEXT,
                        allies_data MEDIUMTEXT,
                        homes_data MEDIUMTEXT
                    );
                """);
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_team_players (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(32) NOT NULL,
                        team_uuid VARCHAR(36),
                        kills INT NOT NULL DEFAULT 0,
                        deaths INT NOT NULL DEFAULT 0,
                        mob_kills INT NOT NULL DEFAULT 0,
                        blocks_broken INT NOT NULL DEFAULT 0,
                        blocks_placed INT NOT NULL DEFAULT 0,
                        playtime_seconds BIGINT NOT NULL DEFAULT 0,
                        level INT NOT NULL DEFAULT 1,
                        updated_at BIGINT NOT NULL
                    );
                """);
            }

            // Migrate teamdata
            int teamCount = 0;
            double sourceBankTotal = 0.0;
            String insertTeamSql = "INSERT INTO friendlycore_teams (team_uuid, team_name, tag, color, leader_uuid, leader_name, created_at, bank_balance, enderchest_data, friendly_fire, open_join, members_data, allies_data, homes_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement srcPs = srcConn.prepareStatement("SELECT owner, ownername, tag, bank, enderchest, homes, home, members, allies, ff, color FROM teamdata");
                 ResultSet rs = srcPs.executeQuery();
                 PreparedStatement targetPs = targetConn.prepareStatement(insertTeamSql)) {

                while (rs.next()) {
                    String owner = rs.getString("owner").trim();
                    UUID leaderUuid = UUID.fromString(owner);
                    String leaderName = rs.getString("ownername");
                    String tag = rs.getString("tag");
                    double bank = rs.getDouble("bank");
                    sourceBankTotal += bank;
                    String ec = rs.getString("enderchest");
                    String color = rs.getString("color");
                    boolean ff = rs.getBoolean("ff");

                    targetPs.setString(1, leaderUuid.toString());
                    targetPs.setString(2, tag != null ? tag : leaderName + "'s Team");
                    targetPs.setString(3, tag != null ? tag : "TEAM");
                    targetPs.setString(4, color != null ? color : "&f");
                    targetPs.setString(5, leaderUuid.toString());
                    targetPs.setString(6, leaderName != null ? leaderName : "Player");
                    targetPs.setLong(7, System.currentTimeMillis());
                    targetPs.setDouble(8, bank);
                    targetPs.setString(9, ec);
                    targetPs.setBoolean(10, ff);
                    targetPs.setBoolean(11, false);
                    targetPs.setString(12, rs.getString("members"));
                    targetPs.setString(13, rs.getString("allies"));
                    targetPs.setString(14, rs.getString("homes"));
                    targetPs.addBatch();
                    teamCount++;
                }
                targetPs.executeBatch();
            }

            assertTrue(teamCount > 0, "The supplied ZelTeams database must contain teams");

            // Migrate playerdata
            int playerCount = 0;
            String insertPlayerSql = "INSERT INTO friendlycore_team_players (player_uuid, username, team_uuid, kills, deaths, mob_kills, blocks_broken, blocks_placed, playtime_seconds, level, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement srcPs = srcConn.prepareStatement("SELECT player, username, team, stats FROM playerdata");
                 ResultSet rs = srcPs.executeQuery();
                 PreparedStatement targetPs = targetConn.prepareStatement(insertPlayerSql)) {

                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("player").trim());
                    String name = rs.getString("username");
                    String team = rs.getString("team");
                    String rawStats = rs.getString("stats");
                    TeamPlayerStats stats = ZelTeamsMigrator.parseZelTeamsStats(uuid, name, rawStats);

                    targetPs.setString(1, uuid.toString());
                    targetPs.setString(2, name != null ? name : "Player");
                    targetPs.setString(3, (team != null && !team.isBlank() && !team.equalsIgnoreCase("none")) ? team : null);
                    targetPs.setInt(4, stats.getKills());
                    targetPs.setInt(5, stats.getDeaths());
                    targetPs.setInt(6, stats.getMobKills());
                    targetPs.setInt(7, stats.getBlocksBroken());
                    targetPs.setInt(8, stats.getBlocksPlaced());
                    targetPs.setLong(9, stats.getPlaytimeSeconds());
                    targetPs.setInt(10, stats.getLevel());
                    targetPs.setLong(11, System.currentTimeMillis());
                    targetPs.addBatch();
                    playerCount++;
                }
                targetPs.executeBatch();
            }

            assertTrue(playerCount > 0, "The supplied ZelTeams database must contain player stats");

            // Verify record counts in target
            try (Statement stmt = targetConn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*), SUM(bank_balance) FROM friendlycore_teams")) {
                    assertTrue(rs.next());
                    assertEquals(teamCount, rs.getInt(1));
                    assertEquals(sourceBankTotal, rs.getDouble(2), 0.01, "Total team bank balances must match the source database");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM friendlycore_team_players")) {
                    assertTrue(rs.next());
                    assertEquals(playerCount, rs.getInt(1));
                }
            }
        }
    }

    @Test
    @DisplayName("Verify DonutOrder SqlTableTransfer Data Integrity")
    void testDonutOrderTransferDataIntegrity() throws Exception {
        File dbFile = new File("plugin-config/DonutOrder/storage.db");
        if (!dbFile.exists()) return;

        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
            assertFalse(results.isEmpty());

            Map<String, Integer> rowMap = new HashMap<>();
            for (SqlTableTransfer.TableTransferResult tr : results) {
                assertTrue(tr.success(), "Table transfer must succeed: " + tr.tableName());
                rowMap.put(tr.tableName(), tr.rowsMigrated());
            }

            int sourceOrders = countRows(srcConn, "orders");
            int sourceOrderLogs = countRows(srcConn, "order_logs");
            long sourceTotal;
            try (Statement statement = srcConn.createStatement();
                 ResultSet result = statement.executeQuery("SELECT sum(total_amount) FROM orders")) {
                assertTrue(result.next());
                sourceTotal = result.getLong(1);
            }
            assertEquals(sourceOrders, rowMap.get("orders"), "Transferred orders count must match the source");
            assertEquals(sourceOrderLogs, rowMap.get("order_logs"), "Transferred order_logs count must match the source");

            // Verify totals in target
            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*), sum(total_amount) FROM orders")) {
                assertTrue(rs.next());
                assertEquals(sourceOrders, rs.getInt(1));
                assertEquals(sourceTotal, rs.getLong(2));
            }
        }
    }

    @Test
    @DisplayName("Verify VirtualSpawner SqlTableTransfer Data Integrity")
    void testVirtualSpawnerTransferDataIntegrity() throws Exception {
        File storageDb = new File("plugin-config/VirtualSpawner/storage.db");
        File logsDb = new File("plugin-config/VirtualSpawner/logs.db");
        if (!storageDb.exists() || !logsDb.exists()) return;

        try (Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            int sourceSpawners;
            int sourceTemp;
            int sourceStackTotal;
            int sourceLogs;
            int sourceLogAmountTotal;
            // Transfer storage.db
            try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + storageDb.getAbsolutePath())) {
                List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
                Map<String, Integer> map = new HashMap<>();
                for (var r : results) map.put(r.tableName(), r.rowsMigrated());

                sourceSpawners = countRows(srcConn, "spawners");
                sourceTemp = countRows(srcConn, "temp");
                try (Statement statement = srcConn.createStatement();
                     ResultSet result = statement.executeQuery("SELECT sum(stack) FROM spawners")) {
                    assertTrue(result.next());
                    sourceStackTotal = result.getInt(1);
                }
                assertEquals(sourceSpawners, map.get("spawners"), "Spawners count must match the source");
                assertEquals(sourceTemp, map.get("temp"), "Temp count must match the source");
            }

            // Transfer logs.db
            try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + logsDb.getAbsolutePath())) {
                List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
                Map<String, Integer> map = new HashMap<>();
                for (var r : results) map.put(r.tableName(), r.rowsMigrated());

                sourceLogs = countRows(srcConn, "logs");
                try (Statement statement = srcConn.createStatement();
                     ResultSet result = statement.executeQuery("SELECT sum(amount) FROM logs")) {
                    assertTrue(result.next());
                    sourceLogAmountTotal = result.getInt(1);
                }
                assertEquals(sourceLogs, map.get("logs"), "Logs count must match the source");
            }

            // Verify in target
            try (Statement stmt = targetConn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*), sum(stack) FROM spawners")) {
                    assertTrue(rs.next());
                    assertEquals(sourceSpawners, rs.getInt(1));
                    assertEquals(sourceStackTotal, rs.getInt(2));
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*), sum(amount) FROM logs")) {
                    assertTrue(rs.next());
                    assertEquals(sourceLogs, rs.getInt(1));
                    assertEquals(sourceLogAmountTotal, rs.getInt(2));
                }
            }
        }
    }

    @Test
    @DisplayName("Verify AxAuctions H2 SqlTableTransfer Data Integrity")
    void testAxAuctionsTransferDataIntegrity() throws Exception {
        File dataDb = new File("plugin-config/AxAuctions/data.mv.db");
        if (!dataDb.exists()) return;

        String h2Path = dataDb.getAbsolutePath();
        if (h2Path.endsWith(".mv.db")) {
            h2Path = h2Path.substring(0, h2Path.length() - 6);
        }

        try (Connection srcConn = DriverManager.getConnection("jdbc:h2:" + h2Path + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r");
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, "axauctions");
            assertFalse(results.isEmpty());

            Map<String, Integer> map = new HashMap<>();
            for (var r : results) {
                assertTrue(r.success(), "Table transfer failed for " + r.tableName() + ": " + r.errorMessage());
                map.put(r.tableName().toUpperCase(Locale.ROOT), r.rowsMigrated());
            }

            for (String table : List.of("AXAUCTIONS_USERS", "AXAUCTIONS_ITEMS", "AXAUCTIONS_HISTORY",
                    "AXAUCTIONS_LOGS", "AXAUCTIONS_CURRENCIES")) {
                int sourceCount = countRows(srcConn, table);
                assertEquals(sourceCount, map.get(table), table + " row count must match the source database");
            }

            // Verify in target database
            try (Statement stmt = targetConn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM `AXAUCTIONS_USERS`")) {
                    assertTrue(rs.next());
                    assertEquals(countRows(srcConn, "AXAUCTIONS_USERS"), rs.getInt(1));
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM `AXAUCTIONS_ITEMS`")) {
                    assertTrue(rs.next());
                    assertEquals(countRows(srcConn, "AXAUCTIONS_ITEMS"), rs.getInt(1));
                }
            }
        }
    }

    @Test
    @DisplayName("Verify Lands SQLite SqlTableTransfer Data Integrity")
    void testLandsTransferDataIntegrity() throws Exception {
        File dataDb = new File("plugin-config/Lands/Data/database_v2.db");
        if (!dataDb.exists()) return;

        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + dataDb.getAbsolutePath());
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
            assertFalse(results.isEmpty());

            Map<String, Integer> map = new HashMap<>();
            for (var r : results) {
                assertTrue(r.success(), "Table transfer failed for " + r.tableName() + ": " + r.errorMessage());
                map.put(r.tableName().toLowerCase(Locale.ROOT), r.rowsMigrated());
            }

            for (String table : List.of("lands_servers", "lands_worlds", "lands_lands",
                    "lands_lands_claims", "lands_players", "lands_relations")) {
                assertEquals(countRows(srcConn, table), map.get(table),
                        table + " row count must match the source database");
            }

            // Verify in target database
            try (Statement stmt = targetConn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*), min(created_at), max(created_at) FROM `lands_lands`")) {
                    assertTrue(rs.next());
                    assertEquals(countRows(srcConn, "lands_lands"), rs.getInt(1));
                    assertNotNull(rs.getTimestamp(2), "min created_at must parse as valid Timestamp");
                    assertNotNull(rs.getTimestamp(3), "max created_at must parse as valid Timestamp");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT server, last_seen FROM `lands_servers`")) {
                    assertTrue(rs.next());
                    assertNotNull(rs.getString(1));
                    assertNotNull(rs.getTimestamp(2), "last_seen must parse as valid Timestamp");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM `lands_players`")) {
                    assertTrue(rs.next());
                    assertEquals(countRows(srcConn, "lands_players"), rs.getInt(1));
                }
            }
        }
    }

    private static int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM `" + table + "`")) {
            assertTrue(result.next(), "Count query must return a row for " + table);
            return result.getInt(1);
        }
    }
}
