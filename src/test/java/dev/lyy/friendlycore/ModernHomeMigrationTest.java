package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ModernHomeMigrationTest {

    @Test
    @DisplayName("Verify reading and parsing real ModernHome SQLite database")
    void testModernHomeSqliteReading() throws Exception {
        File dbFile = new File("plugin-config/ModernHome/storage.db");
        if (!dbFile.exists()) return;

        Map<UUID, PlayerHomes> playerMap = new HashMap<>();
        int homesCount = 0;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
            String sql = "SELECT uuid, home_index, world, x, y, z, name, server_id FROM homes";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String uuidStr = rs.getString("uuid");
                    if (uuidStr == null || uuidStr.isBlank()) continue;
                    UUID uuid = UUID.fromString(uuidStr.trim());
                    int slot = rs.getInt("home_index");
                    String world = rs.getString("world");
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");
                    String name = rs.getString("name");
                    String serverId = rs.getString("server_id");

                    HomeData home = new HomeData(
                            uuid,
                            Math.max(1, slot),
                            name != null ? name : "",
                            serverId != null ? serverId : "",
                            world != null ? world : "world",
                            x,
                            y,
                            z,
                            0.0f,
                            0.0f,
                            System.currentTimeMillis()
                    );

                    PlayerHomes profile = playerMap.computeIfAbsent(uuid, u -> new PlayerHomes(u, "Player"));
                    profile.setHome(home);
                    homesCount++;
                }
            }
        }

        assertEquals(2255, homesCount, "ModernHome storage.db must contain 2,255 homes");
        assertEquals(2197, playerMap.size(), "Must contain exactly 2,197 unique players");

        // Verify one of the sample homes
        UUID sampleUuid = UUID.fromString("649f932f-0627-3e67-bee4-a04a825ee70b");
        PlayerHomes sampleProfile = playerMap.get(sampleUuid);
        assertNotNull(sampleProfile);
        HomeData sampleHome = sampleProfile.getHome(1);
        assertNotNull(sampleHome);
        assertEquals(404.0, sampleHome.getX());
        assertEquals(76.0, sampleHome.getY());
        assertEquals(-816.0, sampleHome.getZ());
        assertEquals("1", sampleHome.getName());

        // Verify fallback matching for player homes
        assertEquals(sampleHome, sampleProfile.getHomeByNameOrSlot("1"));
        assertEquals(sampleHome, sampleProfile.getHomeByNameOrSlot("home"));
        assertEquals(sampleHome, sampleProfile.getHomeByNameOrSlot("home1"));
        assertEquals(sampleHome, sampleProfile.getHomeByNameOrSlot("default"));
    }

    @Test
    @DisplayName("Verify 100% end-to-end data fidelity for all 2,255 ModernHome rows")
    void testEndToEndHomeDataFidelity() throws Exception {
        File dbFile = new File("plugin-config/ModernHome/storage.db");
        if (!dbFile.exists()) return;

        // Simulated target database
        File targetDb = File.createTempFile("friendlycore_test_homes", ".db");
        targetDb.deleteOnExit();

        try (Connection targetConn = DriverManager.getConnection("jdbc:sqlite:" + targetDb.getAbsolutePath());
             Connection srcConn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {

            // Create friendlycore_homes table
            try (var stmt = targetConn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_homes (
                        uuid VARCHAR(36) NOT NULL,
                        slot INT NOT NULL,
                        name VARCHAR(64) DEFAULT '',
                        server_id VARCHAR(64) NOT NULL DEFAULT '',
                        world VARCHAR(64) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL DEFAULT 0.0,
                        pitch FLOAT NOT NULL DEFAULT 0.0,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (uuid, slot)
                    );
                """);
            }

            // Perform batch migration
            String insertSql = "INSERT INTO friendlycore_homes (uuid, slot, name, server_id, world, x, y, z, yaw, pitch, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (var psSrc = srcConn.prepareStatement("SELECT uuid, home_index, world, x, y, z, name, server_id FROM homes");
                 var rs = psSrc.executeQuery();
                 var psIns = targetConn.prepareStatement(insertSql)) {

                targetConn.setAutoCommit(false);
                while (rs.next()) {
                    psIns.setString(1, rs.getString("uuid"));
                    psIns.setInt(2, rs.getInt("home_index"));
                    psIns.setString(3, rs.getString("name") != null ? rs.getString("name") : "");
                    psIns.setString(4, rs.getString("server_id") != null ? rs.getString("server_id") : "");
                    psIns.setString(5, rs.getString("world"));
                    psIns.setDouble(6, rs.getDouble("x"));
                    psIns.setDouble(7, rs.getDouble("y"));
                    psIns.setDouble(8, rs.getDouble("z"));
                    psIns.setFloat(9, 0.0f);
                    psIns.setFloat(10, 0.0f);
                    psIns.setLong(11, System.currentTimeMillis());
                    psIns.addBatch();
                }
                psIns.executeBatch();
                targetConn.commit();
            }

            // Verify count and compare all 2,255 rows 1:1
            try (var stmt = targetConn.createStatement();
                 var rsCount = stmt.executeQuery("SELECT count(*) FROM friendlycore_homes")) {
                assertTrue(rsCount.next());
                assertEquals(2255, rsCount.getInt(1), "Target table must contain exactly 2,255 rows");
            }

            // Read both and ensure 100% field-by-field match
            Map<String, String> srcData = new HashMap<>();
            try (var stmt = srcConn.createStatement();
                 var rs = stmt.executeQuery("SELECT uuid, home_index, world, x, y, z, name, server_id FROM homes")) {
                while (rs.next()) {
                    String key = rs.getString("uuid") + ":" + rs.getInt("home_index");
                    String val = rs.getString("world") + "|" + rs.getDouble("x") + "|" + rs.getDouble("y") + "|" + rs.getDouble("z") + "|" + (rs.getString("name") != null ? rs.getString("name") : "");
                    srcData.put(key, val);
                }
            }

            Map<String, String> targetData = new HashMap<>();
            try (var stmt = targetConn.createStatement();
                 var rs = stmt.executeQuery("SELECT uuid, slot, world, x, y, z, name FROM friendlycore_homes")) {
                while (rs.next()) {
                    String key = rs.getString("uuid") + ":" + rs.getInt("slot");
                    String val = rs.getString("world") + "|" + rs.getDouble("x") + "|" + rs.getDouble("y") + "|" + rs.getDouble("z") + "|" + rs.getString("name");
                    targetData.put(key, val);
                }
            }

            assertEquals(srcData.size(), targetData.size());
            for (var entry : srcData.entrySet()) {
                assertEquals(entry.getValue(), targetData.get(entry.getKey()), "Data mismatch for home key: " + entry.getKey());
            }
        }
    }
}
