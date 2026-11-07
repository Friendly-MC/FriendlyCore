package dev.lyy.friendlycore.modules.auctions;

import dev.lyy.friendlycore.modules.auctions.converters.AxAuctionsSchema;
import dev.lyy.friendlycore.modules.auctions.storage.AuctionTransactions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class AuctionTransactionsTest {
    private String database() {
        String mysql = System.getProperty("auction.test.mysql");
        if (mysql != null) return mysql + "auction_review_" + UUID.randomUUID().toString().replace("-", "")
                + "?user=root&createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false";
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private void schema(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE friendlycore_auction_items (id INT PRIMARY KEY, item_bytes BLOB, seller_uuid VARCHAR(36), seller_name VARCHAR(64), price DOUBLE, start_time BIGINT)");
            s.execute("CREATE TABLE friendlycore_auction_deleted (item_bytes BLOB, seller_uuid VARCHAR(36), seller_name VARCHAR(64), price DOUBLE, start_time BIGINT, deleted_time BIGINT)");
            s.execute("CREATE TABLE friendlycore_auction_history (item_bytes BLOB, seller_uuid VARCHAR(36), seller_name VARCHAR(64), buyer_uuid VARCHAR(36), buyer_name VARCHAR(64), price DOUBLE, sold_time BIGINT)");
            s.execute("CREATE TABLE friendlycore_auction_payouts (user_uuid VARCHAR(36), amount DOUBLE, reason VARCHAR(255), created_at BIGINT)");
            s.execute("INSERT INTO friendlycore_auction_items VALUES (1, X'010203', 'seller', 'Seller', 100.0, 1000)");
        }
    }

    private int count(Connection c, String table) throws SQLException {
        try (var s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM friendlycore_auction_" + table)) {
            rs.next(); return rs.getInt(1);
        }
    }

    @Test void salePaysFullPriceAndOnlyOneBuyerWins() throws Exception {
        for (String url : new String[]{database(), "jdbc:sqlite::memory:"}) {
            try (var c = DriverManager.getConnection(url)) {
                schema(c);
                assertTrue(AuctionTransactions.purchase(c, 1, 100, 0, "buyer", "Buyer"));
                assertFalse(AuctionTransactions.purchase(c, 1, 100, 0, "other", "Other"));
                assertEquals(0, count(c, "items"));
                assertEquals(1, count(c, "history"));
                assertEquals(1, count(c, "payouts"));
                try (var s = c.createStatement(); var rs = s.executeQuery("SELECT user_uuid, amount FROM friendlycore_auction_payouts")) {
                    assertTrue(rs.next()); assertEquals("seller", rs.getString(1)); assertEquals(100.0, rs.getDouble(2));
                }
                assertTrue(c.getAutoCommit());
            }
        }
    }

    @Test void failedPayoutWriteRollsBackSaleAndHistory() throws Exception {
        for (String url : new String[]{database(), "jdbc:sqlite::memory:"}) {
            try (var c = DriverManager.getConnection(url)) {
                schema(c);
                c.createStatement().execute("DROP TABLE friendlycore_auction_payouts");
                assertThrows(SQLException.class, () -> AuctionTransactions.purchase(c, 1, 100, 0, "buyer", "Buyer"));
                assertEquals(1, count(c, "items"));
                assertEquals(0, count(c, "history"));
                assertTrue(c.getAutoCommit());
            }
        }
    }

    @Test void staleExpiredSelfAndInvalidPurchasesAreRejected() throws Exception {
        try (var c = DriverManager.getConnection(database())) {
            schema(c);
            assertFalse(AuctionTransactions.purchase(c, 1, 50, 0, "buyer", "Buyer"));
            assertFalse(AuctionTransactions.purchase(c, 1, 100, 1000, "buyer", "Buyer"));
            assertFalse(AuctionTransactions.purchase(c, 1, 100, 0, "seller", "Seller"));
            for (double price : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0}) {
                assertFalse(AuctionTransactions.purchase(c, 1, price, 0, "buyer", "Buyer"));
            }
            assertEquals(1, count(c, "items"));
            assertEquals(0, count(c, "history"));
            assertEquals(0, count(c, "payouts"));
        }
    }

    @Test void archivalIsAtomicAndIdempotent() throws Exception {
        for (String url : new String[]{database(), "jdbc:sqlite::memory:"}) {
            try (var c = DriverManager.getConnection(url)) {
                schema(c);
                assertTrue(AuctionTransactions.archive(c, 1));
                assertFalse(AuctionTransactions.archive(c, 1));
                assertEquals(0, count(c, "items"));
                assertEquals(1, count(c, "deleted"));
                c.createStatement().execute("INSERT INTO friendlycore_auction_items VALUES (2, X'01', 'seller', 'Seller', 1, 1)");
                c.createStatement().execute("DROP TABLE friendlycore_auction_deleted");
                assertThrows(SQLException.class, () -> AuctionTransactions.archive(c, 2));
                assertEquals(1, count(c, "items"));
            }
        }
    }

    @Test void concurrentServersCannotSellAndArchiveTheSameItem() throws Exception {
        String url = database();
        try (var c = DriverManager.getConnection(url)) {
            schema(c);
            CountDownLatch start = new CountDownLatch(1);
            var sale = CompletableFuture.supplyAsync(() -> race(url, start, true));
            var archive = CompletableFuture.supplyAsync(() -> race(url, start, false));
            start.countDown();
            assertEquals(1, (sale.get() ? 1 : 0) + (archive.get() ? 1 : 0));
            assertEquals(0, count(c, "items"));
            assertEquals(1, count(c, "history") + count(c, "deleted"));
            assertEquals(count(c, "history"), count(c, "payouts"));
        }
    }

    private boolean race(String url, CountDownLatch start, boolean sale) {
        try (var c = DriverManager.getConnection(url)) {
            start.await();
            return sale ? AuctionTransactions.purchase(c, 1, 100, 0, "buyer", "Buyer") : AuctionTransactions.archive(c, 1);
        } catch (SQLException e) {
            // The deadlock victim is rolled back; only the committed winner may deliver.
            if ("40001".equals(e.getSQLState())) return false;
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new RuntimeException(e);
        }
    }

    /** Two servers delivering the same seller's payouts must never both receive a row (double deposit). */
    @Test void concurrentPayoutClaimsNeverHandOutARowTwice() throws Exception {
        String url = database();
        UUID seller = UUID.randomUUID();
        int rows = 25;
        try (var c = DriverManager.getConnection(url)) {
            try (var s = c.createStatement()) {
                s.execute("CREATE TABLE friendlycore_auction_payouts (id INT PRIMARY KEY, user_uuid VARCHAR(36), amount DOUBLE, reason VARCHAR(255), created_at BIGINT)");
                for (int i = 1; i <= rows; i++) {
                    s.execute("INSERT INTO friendlycore_auction_payouts VALUES (" + i + ", '" + seller + "', 10.0, 'Auction #" + i + " sold', 0)");
                }
            }
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<CompletableFuture<java.util.List<Integer>>> servers = new java.util.ArrayList<>();
            for (int server = 0; server < 4; server++) {
                servers.add(CompletableFuture.supplyAsync(() -> claim(url, seller, start)));
            }
            start.countDown();
            java.util.List<Integer> claimed = new java.util.ArrayList<>();
            for (var server : servers) claimed.addAll(server.get());
            assertEquals(claimed.size(), new java.util.HashSet<>(claimed).size(), "a payout was claimed twice");
            assertEquals(rows, claimed.size() + count(c, "payouts"), "a payout vanished without being claimed");
            assertTrue(c.getAutoCommit());
        }
    }

    private java.util.List<Integer> claim(String url, UUID seller, CountDownLatch start) {
        try (var c = DriverManager.getConnection(url)) {
            start.await();
            return AuctionTransactions.claimPayouts(c, seller).stream().map(p -> p.getId()).toList();
        } catch (SQLException e) {
            // A lock-timeout victim rolls back, leaving its rows queued for the next delivery.
            return java.util.List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new RuntimeException(e);
        }
    }

    @Test void nonVaultAndUnknownCurrenciesCannotBecomeMoney() {
        assertEquals(Set.of(1), AxAuctionsSchema.allowedCurrencyIds(Map.of(1, "Vault", 2, "PlayerPoints"), false));
        assertEquals(Set.of(), AxAuctionsSchema.allowedCurrencyIds(Map.of(2, "PlayerPoints"), false));
        assertEquals(Set.of(), AxAuctionsSchema.allowedCurrencyIds(Map.of(2, "Coins"), true));
        assertFalse(AxAuctionsSchema.isCurrencyAllowed(null, Set.of(1)));
        assertFalse(AxAuctionsSchema.isCurrencyAllowed(2, Set.of()));
    }

    @Test void statisticsReflectCompletedSalesOnly() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        for (String url : new String[]{database(), "jdbc:sqlite::memory:"}) {
            try (var c = DriverManager.getConnection(url)) {
                schema(c);
                try (var statement = c.prepareStatement("UPDATE friendlycore_auction_items SET seller_uuid = ?")) {
                    statement.setString(1, seller.toString()); statement.executeUpdate();
                }
                assertEquals(0, AuctionTransactions.statistics(c, seller).sold());
                assertTrue(AuctionTransactions.purchase(c, 1, 100, 0, buyer.toString(), "Buyer"));
                var sellerStats = AuctionTransactions.statistics(c, seller);
                var buyerStats = AuctionTransactions.statistics(c, buyer);
                assertEquals(1, sellerStats.sold()); assertEquals(100, sellerStats.earned());
                assertEquals(0, sellerStats.purchased()); assertEquals(0, sellerStats.spent());
                assertEquals(1, buyerStats.purchased()); assertEquals(100, buyerStats.spent());
                assertEquals(0, buyerStats.sold()); assertEquals(0, buyerStats.earned());
                var globalStats = AuctionTransactions.statistics(c, null);
                assertEquals(1, globalStats.sold()); assertEquals(1, globalStats.purchased());
                assertEquals(100, globalStats.earned()); assertEquals(100, globalStats.spent());
            }
        }
    }
}
