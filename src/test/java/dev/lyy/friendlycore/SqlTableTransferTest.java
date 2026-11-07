package dev.lyy.friendlycore;

import dev.lyy.friendlycore.tools.converter.SqlTableTransfer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SqlTableTransferTest {

    @Test
    @DisplayName("Test SqlTableTransfer schema derivation and data transfer")
    void testSqlTableTransfer() throws Exception {
        // Create source SQLite in-memory DB
        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            // Setup source table with various data types
            try (Statement stmt = srcConn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE orders (
                        order_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_name VARCHAR(64) NOT NULL,
                        total_amount INTEGER NOT NULL,
                        price DOUBLE NOT NULL,
                        creation_time BIGINT NOT NULL
                    );
                """);

                stmt.executeUpdate("INSERT INTO orders (player_name, total_amount, price, creation_time) VALUES ('Player1', 10, 250.5, 1785800000);");
                stmt.executeUpdate("INSERT INTO orders (player_name, total_amount, price, creation_time) VALUES ('Player2', 5, 1200.0, 1785800100);");
            }

            // Transfer to target
            int count = SqlTableTransfer.transferSingleTable(srcConn, targetConn, "orders", "orders");
            assertEquals(2, count, "Should transfer 2 rows");

            // Verify in target
            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM orders ORDER BY order_id")) {
                assertTrue(rs.next());
                assertEquals("Player1", rs.getString("player_name"));
                assertEquals(10, rs.getInt("total_amount"));
                assertEquals(250.5, rs.getDouble("price"));
                assertEquals(1785800000L, rs.getLong("creation_time"));

                assertTrue(rs.next());
                assertEquals("Player2", rs.getString("player_name"));
                assertEquals(5, rs.getInt("total_amount"));
                assertEquals(1200.0, rs.getDouble("price"));
                assertEquals(1785800100L, rs.getLong("creation_time"));

                assertFalse(rs.next());
            }
        }
    }

    @Test
    @DisplayName("Test Lands multi-server chunk composite primary key preservation")
    void testLandsCompositePrimaryKeyPreservation() throws Exception {
        try (Connection srcConn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Connection targetConn = DriverManager.getConnection("jdbc:sqlite::memory:")) {

            // Create SQLite table with composite PK (server, world, x, z)
            try (Statement stmt = srcConn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE chunks (
                        server VARCHAR(64) NOT NULL,
                        world VARCHAR(64) NOT NULL,
                        x INT NOT NULL,
                        z INT NOT NULL,
                        land_id INT NOT NULL,
                        area_id INT NOT NULL,
                        PRIMARY KEY (server, world, x, z)
                    );
                """);

                // Insert multiple claims from the SAME server
                stmt.executeUpdate("INSERT INTO chunks VALUES ('default', 'world', 1, 1, 101, 1);");
                stmt.executeUpdate("INSERT INTO chunks VALUES ('default', 'world', 1, 2, 101, 1);");
                stmt.executeUpdate("INSERT INTO chunks VALUES ('default', 'world', 2, 1, 102, 1);");
                stmt.executeUpdate("INSERT INTO chunks VALUES ('default', 'world_nether', 1, 1, 103, 1);");
            }

            // Transfer with prefix "lands_"
            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, "lands_");
            assertEquals(1, results.size());
            assertTrue(results.get(0).success());
            assertEquals(4, results.get(0).rowsMigrated(), "Must migrate all 4 distinct chunk rows without collapsing");

            // Verify all 4 claims exist in target table lands_chunks
            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM lands_chunks")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1), "All 4 chunk claims must be preserved in lands_chunks");
            }

            // Test server-id remapping to "economy-a"
            try (PreparedStatement ps = targetConn.prepareStatement(
                    "UPDATE lands_chunks SET server = ? WHERE server = 'default' OR server = ''")) {
                ps.setString(1, "economy-a");
                int updated = ps.executeUpdate();
                assertEquals(4, updated, "All 4 claims should be remapped to economy-a");
            }

            // Verify claims now have server = 'economy-a'
            try (Statement stmt = targetConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT server, world, x, z, land_id FROM lands_chunks WHERE server = 'economy-a' ORDER BY world, x, z")) {
                assertTrue(rs.next());
                assertEquals("economy-a", rs.getString("server"));
                assertEquals("world", rs.getString("world"));
                assertEquals(1, rs.getInt("x"));
                assertEquals(1, rs.getInt("z"));

                assertTrue(rs.next());
                assertEquals(1, rs.getInt("x"));
                assertEquals(2, rs.getInt("z"));

                assertTrue(rs.next());
                assertEquals(2, rs.getInt("x"));
                assertEquals(1, rs.getInt("z"));

                assertTrue(rs.next());
                assertEquals("world_nether", rs.getString("world"));

                assertFalse(rs.next());
            }
        }
    }
}
