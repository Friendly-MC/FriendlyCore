package dev.lyy.friendlycore;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

public class PluginConverterInspectionTest {

    @Test
    @DisplayName("Verify DonutOrder config and SQLite database")
    void testDonutOrderDatabase() throws Exception {
        File folder = new File("plugin-config/DonutOrder");
        if (!folder.exists()) return;

        File conf = new File(folder, "databases.yml");
        assertTrue(conf.exists());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(conf);
        assertEquals("sqlite", yaml.getString("type"));
        assertNotNull(yaml.getString("mysql.host"));

        File db = new File(folder, "storage.db");
        assertTrue(db.exists());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM orders");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "DonutOrder database must contain orders");
            }
        }
    }

    @Test
    @DisplayName("Verify VirtualSpawner config and SQLite database")
    void testVirtualSpawnerDatabase() throws Exception {
        File folder = new File("plugin-config/VirtualSpawner");
        if (!folder.exists()) return;

        File conf = new File(folder, "databases.yml");
        assertTrue(conf.exists());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(conf);
        assertEquals("sqlite", yaml.getString("type"));

        File db = new File(folder, "storage.db");
        assertTrue(db.exists());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM spawners");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "VirtualSpawner database must contain spawners");
            }
        }
    }

    @Test
    @DisplayName("Verify AxAuctions config and H2 database file")
    void testAxAuctionsDatabase() {
        File folder = new File("plugin-config/AxAuctions");
        if (!folder.exists()) return;

        File conf = new File(folder, "config.yml");
        assertTrue(conf.exists());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(conf);
        assertNotNull(yaml.getString("database.type"));
        assertNotNull(yaml.getString("database.address"));

        File db = new File(folder, "data.mv.db");
        assertTrue(db.exists());
    }

    @Test
    @DisplayName("Verify Lands config and SQLite database")
    void testLandsDatabase() throws Exception {
        File folder = new File("plugin-config/Lands");
        if (!folder.exists()) return;

        File conf = new File(folder, "config.yml");
        assertTrue(conf.exists());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(conf);
        assertNotNull(yaml.getString("database.mysql-v2.address_2"));

        File db = new File(folder, "Data/database_v2.db");
        assertTrue(db.exists());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM lands_lands");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Lands database must contain land records");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM lands_lands_claims");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Lands database must contain claim records");
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM lands_players");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Lands database must contain player records");
            }
        }
    }
}
