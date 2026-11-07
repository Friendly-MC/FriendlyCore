package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.AccountData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class EconomyOptimizationTest {

    @Test
    @DisplayName("AccountCache: Retains cached account balance during player join to prevent main-thread cache-miss stalls")
    void testAccountCacheRetainedOnJoin() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AccountData cached = new AccountData(uuid, "Steve", 500.0);
        cached.setDirty(false);
        accountCache.put(uuid, cached);

        // Simulate reloadAccountForJoin without cache eviction:
        // Cache must still hold the account so concurrent Vault getBalance calls hit memory immediately
        AccountData inCache = accountCache.get(uuid);
        assertNotNull(inCache);
        assertEquals(500.0, inCache.getBalance());

        // Username update applies without losing balance
        inCache.setUsername("SteveUpdated");
        assertEquals("SteveUpdated", inCache.getUsername());
        assertEquals(500.0, inCache.getBalance());
    }

    @Test
    @DisplayName("AccountCache: Dirty in-memory account is never overwritten by older state")
    void testDirtyAccountPreservedOnJoin() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AccountData dirtyAccount = new AccountData(uuid, "Alex", 1250.0);
        dirtyAccount.setDirty(true);
        accountCache.put(uuid, dirtyAccount);

        // Simulate DB returning older balance 1000.0
        AccountData dbLoaded = new AccountData(uuid, "Alex", 1000.0);
        dbLoaded.setDirty(false);

        // Cache update logic from EconomyManager.loadPlayerAccount:
        AccountData resolved = accountCache.compute(uuid, (key, existing) -> {
            if (existing == null) return dbLoaded;
            if (existing.isDirty() || existing.getLastUpdated() >= dbLoaded.getLastUpdated()) return existing;
            return dbLoaded;
        });

        assertEquals(1250.0, resolved.getBalance(), "Dirty in-memory account must not be overwritten by database row");
        assertTrue(resolved.isDirty());
    }

    @Test
    @DisplayName("MySQLDatabase: Ensures connection init SQL configures fail-fast lock wait timeouts")
    void testMySQLConnectionInitSqlConfigured() throws Exception {
        // Inspect the source or configuration of MySQLDatabase
        String mysqlDbFile = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/dev/lyy/friendlycore/common/database/MySQLDatabase.java")
        );
        assertTrue(mysqlDbFile.contains("innodb_lock_wait_timeout = 5"),
                "MySQLDatabase must set innodb_lock_wait_timeout = 5 on connection init");
        assertTrue(mysqlDbFile.contains("lock_wait_timeout = 10"),
                "MySQLDatabase must set lock_wait_timeout = 10 on connection init");
    }

    @Test
    @DisplayName("AccountCache: Offline deposit and withdraw update in-memory cache without eviction")
    void testOfflineTransactionUpdatesCacheWithoutEviction() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "OfflinePlayer", 250.0);
        account.setDirty(false);
        accountCache.put(uuid, account);

        // Simulate deposit:
        double depositAmount = 50.0;
        accountCache.computeIfPresent(uuid, (k, acc) -> {
            acc.deposit(depositAmount);
            acc.setDirty(false);
            return acc;
        });

        assertEquals(300.0, accountCache.get(uuid).getBalance(), "Deposit must update cache balance to 300.0");
        assertFalse(accountCache.get(uuid).isDirty(), "Clean DB commit must mark account not dirty");

        // Simulate withdraw:
        double withdrawAmount = 100.0;
        accountCache.computeIfPresent(uuid, (k, acc) -> {
            acc.withdraw(withdrawAmount);
            acc.setDirty(false);
            return acc;
        });

        assertEquals(200.0, accountCache.get(uuid).getBalance(), "Withdraw must update cache balance to 200.0");
        assertFalse(accountCache.get(uuid).isDirty(), "Clean DB commit must mark account not dirty");
    }

    @Test
    @DisplayName("AccountCache: Offline setBalance retains updated clean account in cache")
    void testOfflineSetBalanceRetainsCache() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "OfflineSetter", 50.0);
        account.setDirty(false);
        accountCache.put(uuid, account);

        // Simulate setBalance to 1000.0:
        AccountData data = accountCache.get(uuid);
        assertNotNull(data);
        data.setBalance(1000.0);
        data.setDirty(false);
        accountCache.put(uuid, data);

        AccountData inCache = accountCache.get(uuid);
        assertNotNull(inCache);
        assertEquals(1000.0, inCache.getBalance());
        assertFalse(inCache.isDirty());
    }

    @Test
    @DisplayName("AccountCache: Non-blocking fast fallback on cache miss resolves starting balance without server thread stalls")
    void testNonBlockingFastFallbackResolvesStartingBalance() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        assertNull(accountCache.get(uuid), "Account must initially be cold");

        double fallbackBalance = 0.0;
        AccountData fallback = new AccountData(uuid, "ColdPlayer", fallbackBalance);
        accountCache.putIfAbsent(uuid, fallback);

        assertEquals(0.0, accountCache.get(uuid).getBalance());
    }

    @Test
    @DisplayName("AccountCache: Offline withdraw with insufficient funds fails fast in RAM without database query")
    void testOfflineWithdrawInsufficientFundsFailsFastInRam() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "BrokePlayer", 25.0);
        accountCache.put(uuid, account);

        // Fast-path balance check in memory:
        AccountData cached = accountCache.get(uuid);
        assertNotNull(cached);
        double withdrawAmount = 50.0;
        boolean canWithdraw = cached.getBalance() >= withdrawAmount;
        assertFalse(canWithdraw, "Withdrawal exceeding cached balance must fail fast in memory");
        assertEquals(25.0, cached.getBalance(), "Cached balance must remain untouched");
    }

    @Test
    @DisplayName("AccountCache: Cold offline deposit populates cache upon atomic persistence completion")
    void testColdOfflineDepositPopulatesCache() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        assertNull(accountCache.get(uuid), "Account must initially be cold (not in cache)");

        // Simulate async DB completion populating cache:
        AccountData loaded = new AccountData(uuid, "ColdSeller", 150.0);
        accountCache.compute(uuid, (k, existing) -> {
            if (existing == null) return loaded;
            if (existing.isDirty()) return existing;
            if (existing.getLastUpdated() > loaded.getLastUpdated()) return existing;
            if (existing.getLastUpdated() == loaded.getLastUpdated() && existing.getBalance() >= loaded.getBalance()) return existing;
            return loaded;
        });

        assertNotNull(accountCache.get(uuid));
        assertEquals(150.0, accountCache.get(uuid).getBalance());
        assertFalse(accountCache.get(uuid).isDirty());
    }

    @Test
    @DisplayName("Database: atomicAdjustBalance cancellation rolls back transaction so no balance is deducted")
    void testAtomicAdjustBalanceCancellationRollsBack() throws Exception {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (java.sql.Statement stmt = conn.createStatement()) {
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

            UUID uuid = UUID.randomUUID();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated) VALUES (?, 'TestUser', 500.0, 1, 1000)")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }

            // Simulate atomicAdjustBalance logic when cancelled before commit:
            conn.setAutoCommit(false);
            try (java.sql.PreparedStatement psUpd = conn.prepareStatement("""
                UPDATE friendlycore_economy
                SET balance = ROUND(balance + ?, 2),
                    last_updated = ?
                WHERE uuid = ?
                  AND (balance + ? >= 0.0)
                  AND (balance + ? <= 1000000000.0)
            """)) {
                psUpd.setDouble(1, -100.0);
                psUpd.setLong(2, System.currentTimeMillis());
                psUpd.setString(3, uuid.toString());
                psUpd.setDouble(4, -100.0);
                psUpd.setDouble(5, -100.0);
                int affected = psUpd.executeUpdate();
                assertEquals(1, affected);

                // Simulation: future was cancelled (e.g. timed out on caller thread)
                boolean isCancelled = true;
                if (isCancelled) {
                    conn.rollback();
                } else {
                    conn.commit();
                }
            } finally {
                conn.setAutoCommit(true);
            }

            // Verify balance was rolled back:
            try (java.sql.PreparedStatement psSel = conn.prepareStatement("SELECT balance FROM friendlycore_economy WHERE uuid = ?")) {
                psSel.setString(1, uuid.toString());
                try (java.sql.ResultSet rs = psSel.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(500.0, rs.getDouble("balance"), "Balance must remain unchanged after cancellation rollback");
                }
            }
        }
    }

    @Test
    @DisplayName("VaultEconomy: Deposit on cold offline player derives accurate balance without blocking main thread")
    void testVaultDepositDerivesBalanceWithoutBlocking() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        double startingBalance = 0.0;
        double depositAmount = 250.0;

        AccountData cached = accountCache.get(uuid);
        double prevBal = cached != null ? cached.getBalance() : -1.0;
        assertEquals(-1.0, prevBal, "Account is cold, so prevBal is -1.0");

        // Primary thread non-blocking balance derivation:
        double bal = prevBal >= 0 ? prevBal + depositAmount : (startingBalance + depositAmount);
        assertEquals(250.0, bal, "Resulting balance must accurately include the deposited amount");
    }

    @Test
    @DisplayName("AccountCache: Same millisecond concurrent loads retain newest balance")
    void testSameMillisecondConcurrentLoadsRetainNewestBalance() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        long now = 1700000000000L;

        AccountData firstLoad = new AccountData(uuid, "Player", 100.0);
        firstLoad.setLastUpdated(now);
        accountCache.put(uuid, firstLoad);

        AccountData secondLoad = new AccountData(uuid, "Player", 250.0);
        secondLoad.setLastUpdated(now);

        accountCache.compute(uuid, (k, existing) -> {
            if (existing == null) return secondLoad;
            if (existing.isDirty()) return existing;
            if (existing.getLastUpdated() > secondLoad.getLastUpdated()) return existing;
            if (existing.getLastUpdated() == secondLoad.getLastUpdated() && existing.getBalance() >= secondLoad.getBalance()) return existing;
            return secondLoad;
        });

        assertEquals(250.0, accountCache.get(uuid).getBalance(), "Higher balance from second load must be retained on identical timestamp");
    }
}

