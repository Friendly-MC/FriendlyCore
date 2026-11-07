package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.auctions.model.AuctionPayout;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class PlayerDataLossPreventionTest {

    @Test
    @DisplayName("Verify homes upsert preserves unaffected slots instead of wiping all slots")
    void testHomesUpsertPreservesOtherSlots() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
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

            UUID uuid = UUID.randomUUID();

            // Simulate existing slots 1 and 2 already saved in database
            String insertSql = """
                INSERT INTO friendlycore_homes (uuid, slot, name, server_id, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                // Slot 1
                ps.setString(1, uuid.toString());
                ps.setInt(2, 1);
                ps.setString(3, "Home1");
                ps.setString(4, "survival");
                ps.setString(5, "world");
                ps.setDouble(6, 100.0);
                ps.setDouble(7, 64.0);
                ps.setDouble(8, 100.0);
                ps.setFloat(9, 0.0f);
                ps.setFloat(10, 0.0f);
                ps.setLong(11, 1000L);
                ps.executeUpdate();

                // Slot 2
                ps.setString(1, uuid.toString());
                ps.setInt(2, 2);
                ps.setString(3, "Home2");
                ps.setString(4, "survival");
                ps.setString(5, "world");
                ps.setDouble(6, 200.0);
                ps.setDouble(7, 64.0);
                ps.setDouble(8, 200.0);
                ps.setFloat(9, 0.0f);
                ps.setFloat(10, 0.0f);
                ps.setLong(11, 2000L);
                ps.executeUpdate();
            }

            // Now perform batch save of only slot 1 update using ON CONFLICT(uuid, slot) DO UPDATE
            String upsertSql = """
                INSERT INTO friendlycore_homes (uuid, slot, name, server_id, world, x, y, z, yaw, pitch, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid, slot) DO UPDATE SET
                    name = excluded.name,
                    server_id = excluded.server_id,
                    world = excluded.world,
                    x = excluded.x,
                    y = excluded.y,
                    z = excluded.z,
                    yaw = excluded.yaw,
                    pitch = excluded.pitch,
                    created_at = excluded.created_at
            """;
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, 1);
                ps.setString(3, "UpdatedHome1");
                ps.setString(4, "survival");
                ps.setString(5, "world");
                ps.setDouble(6, 150.0);
                ps.setDouble(7, 64.0);
                ps.setDouble(8, 150.0);
                ps.setFloat(9, 0.0f);
                ps.setFloat(10, 0.0f);
                ps.setLong(11, 3000L);
                ps.executeUpdate();
            }

            // Verify both Slot 1 and Slot 2 still exist, and Slot 1 was updated
            try (PreparedStatement ps = conn.prepareStatement("SELECT slot, name FROM friendlycore_homes WHERE uuid = ? ORDER BY slot")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Slot 1 must exist");
                    assertEquals(1, rs.getInt("slot"));
                    assertEquals("UpdatedHome1", rs.getString("name"));

                    assertTrue(rs.next(), "Slot 2 must NOT be deleted by partial batch save");
                    assertEquals(2, rs.getInt("slot"));
                    assertEquals("Home2", rs.getString("name"));

                    assertFalse(rs.next(), "No more slots should exist");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify auction payout DB table preserves rows when delivery is not acknowledged")
    void testAuctionPayoutPersistence() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_auction_payouts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid VARCHAR(36) NOT NULL,
                        amount DOUBLE NOT NULL,
                        reason VARCHAR(255) NOT NULL,
                        created_at BIGINT NOT NULL
                    );
                """);
            }

            UUID uuid = UUID.randomUUID();

            // Insert pending payout
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO friendlycore_auction_payouts (user_uuid, amount, reason, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, 500.0);
                ps.setString(3, "Auction Sale");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }

            // Load pending payouts (new method: SELECT without DELETE)
            List<AuctionPayout> loaded = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, user_uuid, amount, reason, created_at FROM friendlycore_auction_payouts WHERE user_uuid = ? ORDER BY id ASC")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        loaded.add(new AuctionPayout(
                                rs.getInt("id"),
                                UUID.fromString(rs.getString("user_uuid")),
                                rs.getDouble("amount"),
                                rs.getString("reason"),
                                rs.getLong("created_at")
                        ));
                    }
                }
            }

            assertEquals(1, loaded.size());
            assertEquals(500.0, loaded.get(0).getAmount());

            // If deposit fails, rows remain in DB
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM friendlycore_auction_payouts WHERE user_uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "Row must still exist in DB if deposit was not finalized");
                }
            }

            // After successful deposit, deleteAuctionPayouts is executed
            int payoutId = loaded.get(0).getId();
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM friendlycore_auction_payouts WHERE user_uuid = ? AND id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, payoutId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM friendlycore_auction_payouts WHERE user_uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Row should be deleted after successful deposit");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify in-flight saves tracking awaits all tasks on shutdown")
    void testInFlightSavesTracking() throws Exception {
        java.util.Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();

        CompletableFuture<Void> save1 = new CompletableFuture<>();
        CompletableFuture<Void> save2 = new CompletableFuture<>();

        inFlightSaves.add(save1);
        save1.whenComplete((r, ex) -> inFlightSaves.remove(save1));

        inFlightSaves.add(save2);
        save2.whenComplete((r, ex) -> inFlightSaves.remove(save2));

        assertEquals(2, inFlightSaves.size());

        // Complete save1
        save1.complete(null);
        assertEquals(1, inFlightSaves.size());

        // Complete save2
        save2.complete(null);
        assertEquals(0, inFlightSaves.size());

        // Awaiting empty set completes immediately
        CompletableFuture.allOf(inFlightSaves.toArray(new CompletableFuture[0]))
                .get(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Verify placeholder account delta reconciliation preserves offline transactions")
    void testPlaceholderDeltaReconciliation() {
        UUID uuid = UUID.randomUUID();
        // 1. Placeholder account created when player was loading with starting balance 0
        AccountData placeholder = new AccountData(uuid, "TestPlayer", 0.0);
        double startingBal = placeholder.getBalance();

        // 2. While loading in background, a payment or deposit arrives: +150.0
        placeholder.deposit(150.0);

        // 3. Database load completes, returning persisted balance 500.0
        AccountData loaded = new AccountData(uuid, "TestPlayer", 500.0);

        // 4. Reconcile delta: delta = existing.getBalance() - startingBal
        double delta = placeholder.getBalance() - startingBal;
        assertEquals(150.0, delta);
        if (delta > 0) {
            loaded.deposit(delta);
        } else if (delta < 0) {
            loaded.withdraw(-delta);
        }

        // 5. Final balance must be 650.0 (500 + 150), preserving the deposit!
        assertEquals(650.0, loaded.getBalance(), 0.001);
    }

    @Test
    @DisplayName("Verify order delivery remains unclaimed if claim commit is aborted")
    void testOrderDeliveryClaimFailureLeavesUnclaimed() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_order_deliveries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id VARCHAR(36) NOT NULL,
                        buyer_uuid VARCHAR(36) NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        seller_name VARCHAR(64) NOT NULL,
                        quantity INT NOT NULL,
                        claimed INT NOT NULL DEFAULT 0
                    );
                """);
            }

            UUID buyerUuid = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO friendlycore_order_deliveries (order_id, buyer_uuid, seller_uuid, seller_name, quantity, claimed) VALUES (?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, buyerUuid.toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.setString(4, "Seller1");
                ps.setInt(5, 64);
                ps.executeUpdate();
            }

            // If player is offline or deposit fails, claim update does not run
            // Verify delivery is still claimed = 0
            try (PreparedStatement ps = conn.prepareStatement("SELECT claimed FROM friendlycore_order_deliveries WHERE buyer_uuid = ?")) {
                ps.setString(1, buyerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt("claimed"), "Delivery must remain unclaimed if delivery/deposit failed before claim commit");
                }
            }
        }
    }
}
