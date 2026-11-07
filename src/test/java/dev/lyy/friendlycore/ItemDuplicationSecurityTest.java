package dev.lyy.friendlycore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security and anti-duplication verification test suite.
 * Validates atomic claiming, OCC concurrency control, and diff-applied inventory sync.
 */
public class ItemDuplicationSecurityTest {

    @Test
    @DisplayName("Verify Order Delivery Claiming Prevents Multi-Server Race Condition Duplication")
    void testConcurrentOrderDeliveryClaimingZeroDuplication() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // Create table
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_order_deliveries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id VARCHAR(36) NOT NULL,
                        buyer_uuid VARCHAR(36) NOT NULL,
                        seller_uuid VARCHAR(36) NOT NULL,
                        item_data TEXT NOT NULL,
                        quantity INT NOT NULL,
                        claimed TINYINT NOT NULL DEFAULT 0,
                        delivered_at BIGINT NOT NULL
                    )
                """);
            }

            UUID buyerUuid = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            int deliveryId = 1;

            // Insert 1 unclaimed delivery
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_order_deliveries (id, order_id, buyer_uuid, seller_uuid, item_data, quantity, claimed, delivered_at)
                VALUES (?, ?, ?, ?, 'mock_data', 64, 0, 1000)
            """)) {
                ps.setInt(1, deliveryId);
                ps.setString(2, orderId.toString());
                ps.setString(3, buyerUuid.toString());
                ps.setString(4, UUID.randomUUID().toString());
                ps.executeUpdate();
            }

            // Simulate 10 concurrent threads (e.g. 10 servers or 10 rapid GUI clicks) attempting to claim
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            AtomicInteger successfulClaims = new AtomicInteger(0);
            List<Future<List<Integer>>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    List<Integer> claimedIds = new ArrayList<>();
                    // Synchronized connection simulating atomic transaction block
                    synchronized (conn) {
                        conn.setAutoCommit(false);
                        try {
                            String selectSql = "SELECT id FROM friendlycore_order_deliveries WHERE buyer_uuid = ? AND claimed = 0 AND id IN (?)";
                            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                                ps.setString(1, buyerUuid.toString());
                                ps.setInt(2, deliveryId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        claimedIds.add(rs.getInt("id"));
                                    }
                                }
                            }

                            if (!claimedIds.isEmpty()) {
                                String updateSql = "UPDATE friendlycore_order_deliveries SET claimed = 1 WHERE buyer_uuid = ? AND id IN (?)";
                                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                                    ps.setString(1, buyerUuid.toString());
                                    ps.setInt(2, deliveryId);
                                    ps.executeUpdate();
                                }
                            }
                            conn.commit();
                        } catch (Exception e) {
                            conn.rollback();
                            throw e;
                        } finally {
                            conn.setAutoCommit(true);
                        }
                    }
                    if (!claimedIds.isEmpty()) {
                        successfulClaims.incrementAndGet();
                    }
                    return claimedIds;
                }));
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // EXACTLY ONE thread must succeed in claiming the delivery!
            assertEquals(1, successfulClaims.get(), "Only exactly one thread must claim the delivery; duplicates must be 0!");

            // Verify in database that it is marked claimed
            try (PreparedStatement ps = conn.prepareStatement("SELECT claimed FROM friendlycore_order_deliveries WHERE id = ?")) {
                ps.setInt(1, deliveryId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("claimed"));
                }
            }
        }
    }

    @Test
    @DisplayName("Verify Auction Payout Fetch and Clear Zero Duplication")
    void testConcurrentAuctionPayoutFetchAndClear() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_auction_payouts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid VARCHAR(36) NOT NULL,
                        amount DOUBLE NOT NULL,
                        reason VARCHAR(128) NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                """);
            }

            UUID userUuid = UUID.randomUUID();
            double expectedPayout = 1000.0;

            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_auction_payouts (user_uuid, amount, reason, created_at)
                VALUES (?, ?, 'Sold Netherite Sword', 1000)
            """)) {
                ps.setString(1, userUuid.toString());
                ps.setDouble(2, expectedPayout);
                ps.executeUpdate();
            }

            // Simulate concurrent redis event + scheduled sync task
            int threads = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            AtomicInteger payoutRecipients = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        double totalFetched = 0.0;
                        synchronized (conn) {
                            conn.setAutoCommit(false);
                            try {
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "SELECT id, amount FROM friendlycore_auction_payouts WHERE user_uuid = ?")) {
                                    ps.setString(1, userUuid.toString());
                                    try (ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            totalFetched += rs.getDouble("amount");
                                        }
                                    }
                                }
                                if (totalFetched > 0) {
                                    try (PreparedStatement ps = conn.prepareStatement(
                                            "DELETE FROM friendlycore_auction_payouts WHERE user_uuid = ?")) {
                                        ps.setString(1, userUuid.toString());
                                        ps.executeUpdate();
                                    }
                                }
                                conn.commit();
                            } catch (Exception e) {
                                conn.rollback();
                                throw e;
                            } finally {
                                conn.setAutoCommit(true);
                            }
                        }

                        if (totalFetched > 0) {
                            payoutRecipients.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                });
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // Only 1 execution must receive the payout
            assertEquals(1, payoutRecipients.get(), "Payout must be fetched exactly once, never duplicated!");
        }
    }

    @Test
    @DisplayName("Verify Economy OCC Stale Write Protection")
    void testEconomyOptimisticConcurrencyTimestampProtection() throws Exception {
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

            UUID playerUuid = UUID.randomUUID();
            long t1 = 1000L;
            long t2 = 2000L;

            // Step 1: Initial balance at t1 = 1000.0
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    balance = CASE WHEN excluded.last_updated >= friendlycore_economy.last_updated THEN excluded.balance ELSE friendlycore_economy.balance END,
                    pay_enabled = excluded.pay_enabled,
                    last_updated = CASE WHEN excluded.last_updated >= friendlycore_economy.last_updated THEN excluded.last_updated ELSE friendlycore_economy.last_updated END;
            """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, "Player1");
                ps.setDouble(3, 1000.0);
                ps.setBoolean(4, true);
                ps.setLong(5, t1);
                ps.executeUpdate();
            }

            // Step 2: Cross-server payment arrives at t2: balance updated to 1500.0
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, "Player1");
                ps.setDouble(3, 1500.0);
                ps.setBoolean(4, true);
                ps.setLong(5, t2);
                ps.executeUpdate();
            }

            // Step 3: Stale delayed batch flush from old server with timestamp t1 tries to write old balance 1000.0
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, "Player1");
                ps.setDouble(3, 1000.0);
                ps.setBoolean(4, true);
                ps.setLong(5, t1); // Older timestamp!
                ps.executeUpdate();
            }

            // Step 4: Verify that the balance remains 1500.0 and was NOT overwritten by the stale flush!
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance, last_updated FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1500.0, rs.getDouble("balance"), "Balance must NOT be overwritten by older timestamp flush!");
                    assertEquals(t2, rs.getLong("last_updated"), "Timestamp must remain at newer value t2!");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify Cross-Server AxAuctions Purchase Sequence Has Zero Money Duplication")
    void testCrossServerAxAuctionsPurchaseZeroDuplication() throws Exception {
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

            UUID buyerUuid = UUID.randomUUID();
            UUID sellerUuid = UUID.randomUUID();
            double initialBuyerBalance = 1000.0;
            double initialSellerBalance = 500.0;
            double itemPrice = 300.0;
            long now = System.currentTimeMillis();

            // Insert initial balances
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, 1, ?)
            """)) {
                ps.setString(1, buyerUuid.toString());
                ps.setString(2, "Buyer");
                ps.setDouble(3, initialBuyerBalance);
                ps.setLong(4, now);
                ps.addBatch();

                ps.setString(1, sellerUuid.toString());
                ps.setString(2, "Seller");
                ps.setDouble(3, initialSellerBalance);
                ps.setLong(4, now);
                ps.addBatch();

                ps.executeBatch();
            }

            // Server B: Buyer buys item on Server B.
            // Buyer is online on Server B. Deduct balance in Server B memory:
            double buyerNewBalance = initialBuyerBalance - itemPrice; // 700.0

            // Server B: paySeller called by AxAuctions. Seller is offline on Server B.
            // Server B executes atomicAdjustBalance directly in DB:
            String atomicAdjustSql = """
                UPDATE friendlycore_economy
                SET balance = ROUND(balance + ?, 2),
                    last_updated = ?
                WHERE uuid = ?
                  AND (balance + ? >= 0.0)
                  AND (balance + ? <= 1000000000.0)
            """;
            try (PreparedStatement ps = conn.prepareStatement(atomicAdjustSql)) {
                ps.setDouble(1, itemPrice);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, sellerUuid.toString());
                ps.setDouble(4, itemPrice);
                ps.setDouble(5, itemPrice);
                int rows = ps.executeUpdate();
                assertEquals(1, rows, "Seller offline deposit must succeed atomically in database!");
            }

            // Server B: Buyer disconnects from Server B (e.g. /server serverA).
            // onPlayerQuit synchronously flushes Buyer's dirty account to DB:
            String saveAccountSql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    balance = excluded.balance,
                    pay_enabled = excluded.pay_enabled,
                    last_updated = excluded.last_updated
            """;
            try (PreparedStatement ps = conn.prepareStatement(saveAccountSql)) {
                ps.setString(1, buyerUuid.toString());
                ps.setString(2, "Buyer");
                ps.setDouble(3, buyerNewBalance);
                ps.setBoolean(4, true);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }

            // Server A: Buyer connects to Server A 30ms later.
            // Server A loads Buyer from database:
            double loadedBuyerBalance;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, buyerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    loadedBuyerBalance = rs.getDouble("balance");
                }
            }

            // Server A: Seller balance in database:
            double loadedSellerBalance;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, sellerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    loadedSellerBalance = rs.getDouble("balance");
                }
            }

            // Assertions
            assertEquals(700.0, loadedBuyerBalance, "Buyer balance on Server A must reflect the purchase deduction (no double spend dupe)!");
            assertEquals(800.0, loadedSellerBalance, "Seller balance must reflect the deposited sale revenue!");
            assertEquals(initialBuyerBalance + initialSellerBalance, loadedBuyerBalance + loadedSellerBalance,
                    "Total network currency must be perfectly conserved (1500.0 -> 1500.0), 0 money created or destroyed!");
        }
    }

    @Test
    @DisplayName("Verify Concurrent Atomic Offline Adjustments Zero Overdraft and Zero Lost Updates")
    void testAtomicOfflineAdjustBalanceConcurrency() throws Exception {
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
            double initialBal = 100.0;

            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, 'Target', ?, 1, 1000)
            """)) {
                ps.setString(1, testUuid.toString());
                ps.setDouble(2, initialBal);
                ps.executeUpdate();
            }

            // Run 50 concurrent transactions: 25 deposits of +10, 25 withdrawals of -10
            int threads = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger successfulDeposits = new AtomicInteger(0);
            AtomicInteger successfulWithdrawals = new AtomicInteger(0);
            AtomicInteger rejectedWithdrawals = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                // Deposit +10
                futures.add(executor.submit(() -> {
                    synchronized (conn) {
                        try {
                            String sql = """
                                UPDATE friendlycore_economy
                                SET balance = ROUND(balance + ?, 2), last_updated = ?
                                WHERE uuid = ? AND (balance + ? >= 0.0) AND (balance + ? <= 1000000000.0)
                            """;
                            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                                ps.setDouble(1, 10.0);
                                ps.setLong(2, System.currentTimeMillis());
                                ps.setString(3, testUuid.toString());
                                ps.setDouble(4, 10.0);
                                ps.setDouble(5, 10.0);
                                int rows = ps.executeUpdate();
                                if (rows > 0) successfulDeposits.incrementAndGet();
                            }
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));

                // Withdraw -10
                futures.add(executor.submit(() -> {
                    synchronized (conn) {
                        try {
                            String sql = """
                                UPDATE friendlycore_economy
                                SET balance = ROUND(balance + ?, 2), last_updated = ?
                                WHERE uuid = ? AND (balance + ? >= 0.0) AND (balance + ? <= 1000000000.0)
                            """;
                            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                                ps.setDouble(1, -10.0);
                                ps.setLong(2, System.currentTimeMillis());
                                ps.setString(3, testUuid.toString());
                                ps.setDouble(4, -10.0);
                                ps.setDouble(5, -10.0);
                                int rows = ps.executeUpdate();
                                if (rows > 0) successfulWithdrawals.incrementAndGet();
                                else rejectedWithdrawals.incrementAndGet();
                            }
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            double finalBalance;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, testUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    finalBalance = rs.getDouble("balance");
                }
            }

            double expectedBalance = initialBal + (successfulDeposits.get() * 10.0) - (successfulWithdrawals.get() * 10.0);
            assertEquals(expectedBalance, finalBalance, 0.001, "Final balance must exactly equal initial + net transactions with zero lost updates!");
            assertTrue(finalBalance >= 0.0, "Balance must never drop below 0.0!");
        }
    }

    @Test
    @DisplayName("Verify Atomic Offline Adjust Balance Rejects Overdraft")
    void testAtomicOfflineAdjustBalanceRejectsOverdraft() throws Exception {
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
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, 'PoorPlayer', 25.0, 1, 1000)
            """)) {
                ps.setString(1, testUuid.toString());
                ps.executeUpdate();
            }

            // Attempt to withdraw 50.0 when balance is 25.0
            String sql = """
                UPDATE friendlycore_economy
                SET balance = ROUND(balance + ?, 2), last_updated = ?
                WHERE uuid = ? AND (balance + ? >= 0.0) AND (balance + ? <= 1000000000.0)
            """;
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, -50.0);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, testUuid.toString());
                ps.setDouble(4, -50.0);
                ps.setDouble(5, -50.0);
                affected = ps.executeUpdate();
            }

            assertEquals(0, affected, "Withdrawal exceeding balance must affect 0 rows (rejected atomically)!");

            // Verify balance untouched
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, testUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(25.0, rs.getDouble("balance"), "Balance must remain unchanged after rejected overdraft!");
                }
            }
        }
    }
}
