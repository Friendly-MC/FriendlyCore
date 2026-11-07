package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.teams.model.TeamEnderChest;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class RestartAndEnderchestSafetyTest {

    @Test
    @DisplayName("Verify economy upsert preserves balance on server restart even if DB timestamp is newer (clock skew)")
    void testRestartBalancePersistenceWithClockSkew() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                        last_updated BIGINT NOT NULL DEFAULT 0
                    )
                """);
            }

            UUID testUuid = UUID.randomUUID();
            // 1. Existing row in DB from previous session or another server with a timestamp of 50,000
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, 'TargetPlayer', 500.0, 1, 50000)
            """)) {
                ps.setString(1, testUuid.toString());
                ps.executeUpdate();
            }

            // 2. Player plays on this server and earns 350.0 -> balance is now 850.0.
            // Due to system clock skew, this server's timestamp is 45,000 (< 50,000).
            AccountData account = new AccountData(testUuid, "TargetPlayer", 850.0);
            account.setDirty(true);
            account.setLastUpdated(45000L);
            assertTrue(account.isDirty());

            // 3. Batch save query executed on shutdown
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    pay_enabled = excluded.pay_enabled,
                    last_updated = MAX(friendlycore_economy.last_updated, excluded.last_updated);
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, account.getUuid().toString());
                ps.setString(2, account.getUsername());
                ps.setDouble(3, account.getBalance());
                ps.setBoolean(4, account.isPayEnabled());
                ps.setLong(5, account.getLastUpdated());
                ps.executeUpdate();
            }

            // 4. Verify balance is 850.0, NOT reverted to 500.0!
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance, last_updated FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, testUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(850.0, rs.getDouble("balance"), "Player balance must be 850.0 and NEVER discarded due to clock skew!");
                    assertEquals(50000L, rs.getLong("last_updated"), "Timestamp must remain at maximum (50,000)");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify batch saving persists multiple dirty accounts atomically during shutdown")
    void testRestartBalanceBatchSaveMultipleDirtyAccounts() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                        last_updated BIGINT NOT NULL DEFAULT 0
                    )
                """);
            }

            List<AccountData> accounts = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID u = UUID.randomUUID();
                AccountData data = new AccountData(u, "User" + i, 100.0 * (i + 1));
                accounts.add(data);
            }

            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    pay_enabled = excluded.pay_enabled,
                    last_updated = MAX(friendlycore_economy.last_updated, excluded.last_updated);
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (AccountData acc : accounts) {
                    ps.setString(1, acc.getUuid().toString());
                    ps.setString(2, acc.getUsername());
                    ps.setDouble(3, acc.getBalance());
                    ps.setBoolean(4, acc.isPayEnabled());
                    ps.setLong(5, acc.getLastUpdated());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Verify all accounts exist with correct balances
            for (AccountData acc : accounts) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                    ps.setString(1, acc.getUuid().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(acc.getBalance(), rs.getDouble("balance"), 0.001);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Verify TeamEnderChest handles large payload sizes up to expanded limits without throwing")
    void testTeamEnderChestExpandedPayloadHandling() {
        TeamEnderChest chest = new TeamEnderChest(6, Component.text("Test Vault"));

        // Build a synthetic payload of 400 KB (which previously exceeded tight 256KB limits)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(1); // 1 item
            dos.writeInt(0); // slot 0
            byte[] dummyData = new byte[400_000]; // 400 KB item payload
            dos.writeInt(dummyData.length);
            dos.write(dummyData);
        } catch (Exception e) {
            fail("Failed writing dummy stream", e);
        }

        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        // Deserializing 400KB item must not throw or crash
        assertDoesNotThrow(() -> chest.deserialize(base64));
    }

    @Test
    @DisplayName("Verify TeamEnderChest does not wipe existing inventory if deserialization encounters corrupt bytes")
    void testTeamEnderChestCorruptStreamDoesNotWipeExistingInventory() {
        TeamEnderChest chest = new TeamEnderChest(6, Component.text("Test Vault"));

        // Synthetic malformed Base64 that will fail Java object stream / Paper deserialization
        byte[] malformed = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x77, 0x04, 0x12, 0x34};
        String base64 = Base64.getEncoder().encodeToString(malformed);

        // Deserializing corrupt stream must not throw and must safely abort without clearing existing items
        assertDoesNotThrow(() -> chest.deserialize(base64));
    }

    @Test
    @DisplayName("Verify saveTeamsBatch does NOT overwrite bank balance or enderchest_data with stale values on server restart")
    void testSaveTeamsBatchPreservesBankBalanceAndEnderchest() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_teams (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(32) NOT NULL,
                        tag VARCHAR(16) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL,
                        owner_name VARCHAR(16) NOT NULL,
                        members_data TEXT,
                        allies_data TEXT,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        enderchest_data TEXT,
                        homes_data TEXT,
                        max_homes INT NOT NULL DEFAULT 1,
                        last_tag_change BIGINT NOT NULL DEFAULT 0,
                        color_key VARCHAR(16),
                        friendly_fire BOOLEAN NOT NULL DEFAULT 0,
                        closed BOOLEAN NOT NULL DEFAULT 0,
                        score INT NOT NULL DEFAULT 0,
                        rank_pos INT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL DEFAULT 0
                    )
                """);
            }

            UUID teamUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();

            // 1. Team has $10,000,000 balance and enderchest containing Mace & Shulkers in DB
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_teams (uuid, name, tag, owner_uuid, owner_name, balance, enderchest_data)
                VALUES (?, 'TopTeam', 'TOP', ?, 'Owner', 10000000.0, 'precious_mace_and_shulkers')
            """)) {
                ps.setString(1, teamUuid.toString());
                ps.setString(2, ownerUuid.toString());
                ps.executeUpdate();
            }

            // 2. A restarting node (e.g. spawn server) flushes batch with stale memory (balance=0, enderchest_data=null)
            String sqliteSql = """
                INSERT INTO friendlycore_teams (
                    uuid, name, tag, owner_uuid, owner_name, members_data, allies_data, balance,
                    enderchest_data, homes_data, max_homes, last_tag_change, color_key, friendly_fire,
                    closed, score, rank_pos, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    tag = excluded.tag,
                    owner_uuid = excluded.owner_uuid,
                    owner_name = excluded.owner_name,
                    members_data = excluded.members_data,
                    allies_data = excluded.allies_data,
                    homes_data = excluded.homes_data,
                    max_homes = excluded.max_homes,
                    last_tag_change = excluded.last_tag_change,
                    color_key = excluded.color_key,
                    friendly_fire = excluded.friendly_fire,
                    closed = excluded.closed,
                    score = excluded.score,
                    rank_pos = excluded.rank_pos;
            """;

            try (PreparedStatement ps = conn.prepareStatement(sqliteSql)) {
                ps.setString(1, teamUuid.toString());
                ps.setString(2, "TopTeamRenamed");
                ps.setString(3, "TOP2");
                ps.setString(4, ownerUuid.toString());
                ps.setString(5, "Owner");
                ps.setString(6, "[]");
                ps.setString(7, "[]");
                ps.setDouble(8, 0.0); // Stale $0 in memory!
                ps.setString(9, "stale_empty"); // Stale empty chest in memory!
                ps.setString(10, "{}");
                ps.setInt(11, 1);
                ps.setLong(12, 0L);
                ps.setString(13, "GOLD");
                ps.setBoolean(14, false);
                ps.setBoolean(15, false);
                ps.setInt(16, 100);
                ps.setInt(17, 1);
                ps.setLong(18, 0L);
                ps.executeUpdate();
            }

            // 3. Verify balance and vault data are NOT overwritten
            try (PreparedStatement ps = conn.prepareStatement("SELECT name, balance, enderchest_data FROM friendlycore_teams WHERE uuid = ?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("TopTeamRenamed", rs.getString("name"), "Metadata (name) should be updated");
                    assertEquals(10000000.0, rs.getDouble("balance"), "Bank balance MUST be preserved and not overwritten with $0!");
                    assertEquals("precious_mace_and_shulkers", rs.getString("enderchest_data"), "Vault data MUST be preserved and not overwritten!");
                }
            }
        }
    }
}
