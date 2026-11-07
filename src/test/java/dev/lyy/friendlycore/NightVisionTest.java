package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.hooks.FloodgateHook;
import dev.lyy.friendlycore.modules.nightvision.NightVisionProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class NightVisionTest {

    private UUID playerUuid;
    private NightVisionProfile profile;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        profile = new NightVisionProfile(playerUuid, "TestPlayer", false);
    }

    @Test
    void testProfileDefaultValues() {
        assertEquals(playerUuid, profile.getUuid());
        assertEquals("TestPlayer", profile.getUsername());
        assertFalse(profile.isEnabled());
        assertFalse(profile.isDirty());
        assertTrue(profile.getLastUpdated() > 0);
    }

    @Test
    void testProfileToggleAndDirtyState() {
        assertFalse(profile.isEnabled());
        assertFalse(profile.isDirty());

        // Toggle to true
        profile.setEnabled(true);
        assertTrue(profile.isEnabled());
        assertTrue(profile.isDirty());

        // Clear dirty
        profile.setDirty(false);
        assertFalse(profile.isDirty());

        // Setting same value should not mark dirty
        profile.setEnabled(true);
        assertFalse(profile.isDirty());

        // Toggle to false
        profile.setEnabled(false);
        assertFalse(profile.isEnabled());
        assertTrue(profile.isDirty());
    }

    @Test
    void testProfileUsernameUpdate() {
        assertFalse(profile.isDirty());

        profile.setUsername("UpdatedName");
        assertEquals("UpdatedName", profile.getUsername());
        assertTrue(profile.isDirty());

        profile.setDirty(false);
        profile.setUsername("UpdatedName");
        assertFalse(profile.isDirty());
    }

    @Test
    void testProfileVersioningAndConcurrentSave() {
        assertFalse(profile.isDirty());
        long v1 = profile.getVersion();

        profile.setEnabled(true);
        assertTrue(profile.isDirty());
        long v2 = profile.getVersion();
        assertTrue(v2 > v1);

        profile.markSaved(v2);
        assertFalse(profile.isDirty());

        // Concurrently mutated while save in flight
        profile.setEnabled(false);
        long v3 = profile.getVersion();
        assertTrue(v3 > v2);
        assertTrue(profile.isDirty());

        // Outdated save completion does not clear newer mutation
        profile.markSaved(v2);
        assertTrue(profile.isDirty());

        // Newest save completion clears dirty
        profile.markSaved(v3);
        assertFalse(profile.isDirty());
    }

    @Test
    void testConcurrentProfileMutations() throws InterruptedException {
        int threads = 10;
        int operations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operations; j++) {
                        profile.setEnabled(j % 2 == 0);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertNotNull(profile.getUuid());
        assertTrue(profile.getLastUpdated() > 0);
    }

    @Test
    void testFloodgateHookBedrockDetection() {
        FloodgateHook hook = new FloodgateHook();

        // Null checks
        assertFalse(hook.isBedrockPlayer((UUID) null));

        // When Floodgate plugin is not running in test env, isFloodgateAvailable is false
        assertFalse(hook.isFloodgateAvailable());
        assertFalse(hook.isGeyserAvailable());

        UUID randomUuid = UUID.randomUUID();
        assertFalse(hook.isBedrockPlayer(randomUuid));
    }

    @Test
    void testSqliteNightVisionSchemaAndPersistence() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement stmt = conn.createStatement()) {

            // Create table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_nightvision (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    last_updated BIGINT NOT NULL
                );
            """);

            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();

            // Insert single
            String insertSql = """
                INSERT INTO friendlycore_nightvision (uuid, username, enabled, last_updated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    enabled = excluded.enabled,
                    last_updated = excluded.last_updated;
            """;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, u1.toString());
                ps.setString(2, "Player1");
                ps.setBoolean(3, true);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }

            // Query single
            try (PreparedStatement ps = conn.prepareStatement("SELECT enabled FROM friendlycore_nightvision WHERE uuid = ?")) {
                ps.setString(1, u1.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("enabled"));
                }
            }

            // Batch insert/update
            Map<UUID, Boolean> batch = new HashMap<>();
            batch.put(u1, false);
            batch.put(u2, true);

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                conn.setAutoCommit(false);
                for (Map.Entry<UUID, Boolean> entry : batch.entrySet()) {
                    ps.setString(1, entry.getKey().toString());
                    ps.setString(2, "Unknown");
                    ps.setBoolean(3, entry.getValue());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            }

            // Verify batch update
            try (PreparedStatement ps = conn.prepareStatement("SELECT enabled FROM friendlycore_nightvision WHERE uuid = ?")) {
                ps.setString(1, u1.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertFalse(rs.getBoolean("enabled"));
                }

                ps.setString(1, u2.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getBoolean("enabled"));
                }
            }
        }
    }
}
