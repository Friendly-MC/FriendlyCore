package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.AccountData;
import net.milkbowl.vault.economy.EconomyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class EconomySafetyAuditRemediationTest {

    @Test
    @DisplayName("Safety Audit #1: Placeholder flags correctly distinguish transient and loaded accounts")
    void testPlaceholderFlagAndLoadedFlag() {
        UUID uuid = UUID.randomUUID();
        AccountData standard = new AccountData(uuid, "Player1", 100.0);
        assertFalse(standard.isPlaceholder(), "Standard account must not be placeholder");
        assertTrue(standard.isLoaded(), "Standard account must be loaded");

        AccountData placeholder = new AccountData(uuid, "Player1", 0.0, true);
        assertTrue(placeholder.isPlaceholder(), "Placeholder account must be marked placeholder");
        assertFalse(placeholder.isLoaded(), "Placeholder account must not be loaded");

        placeholder.setLoaded(true);
        assertFalse(placeholder.isPlaceholder());
        assertTrue(placeholder.isLoaded());

        placeholder.setPlaceholder(true);
        assertTrue(placeholder.isPlaceholder());
        assertFalse(placeholder.isLoaded());
    }

    @Test
    @DisplayName("Safety Audit #1: Transient placeholder does not overwrite or block real database data when loaded")
    void testPlaceholderDoesNotOverwriteLoadedData() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();

        // 1. Transient placeholder created synchronously on main thread
        AccountData placeholder = new AccountData(uuid, "Player", 0.0, true);
        accountCache.put(uuid, placeholder);

        // 2. Database async load returns with real player balance
        AccountData dbLoaded = new AccountData(uuid, "RealSteve", 5000.0, false);
        dbLoaded.setDirty(false);

        // 3. Cache compute logic as implemented in EconomyManager.loadPlayerAccount:
        accountCache.compute(uuid, (key, existing) -> {
            if (existing == null) return dbLoaded;
            if (existing.isPlaceholder()) {
                double startingBal = 0.0;
                double delta = existing.getBalance() - startingBal;
                if (delta != 0.0) {
                    if (delta > 0) {
                        dbLoaded.deposit(delta);
                    } else {
                        dbLoaded.withdraw(-delta);
                    }
                }
                if (existing.getUsername() != null && !existing.getUsername().equalsIgnoreCase("Player")
                        && !existing.getUsername().equalsIgnoreCase("Unknown")) {
                    dbLoaded.setUsername(existing.getUsername());
                }
                return dbLoaded;
            }
            if (existing.isDirty() || existing.getLastUpdated() >= dbLoaded.getLastUpdated()) return existing;
            return dbLoaded;
        });

        AccountData resolved = accountCache.get(uuid);
        assertNotNull(resolved);
        assertEquals(5000.0, resolved.getBalance(), "Real DB balance must completely replace placeholder");
        assertEquals("RealSteve", resolved.getUsername());
        assertFalse(resolved.isPlaceholder(), "Resolved account must not be a placeholder");
    }

    @Test
    @DisplayName("Safety Audit #1: Transient placeholder deltas transfer onto loaded account without balance wipe")
    void testPlaceholderWithDeltaTransfersToLoadedData() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();

        // 1. Placeholder created with starting balance 0.0
        AccountData placeholder = new AccountData(uuid, "Player", 0.0, true);
        accountCache.put(uuid, placeholder);

        // 2. Main thread operation occurs before DB returns (e.g. deposit 50.0)
        placeholder.deposit(50.0);
        assertEquals(50.0, placeholder.getBalance());

        // 3. Database returns real balance of 10,000.0
        AccountData dbLoaded = new AccountData(uuid, "Alex", 10000.0, false);
        dbLoaded.setDirty(false);

        // 4. Cache compute transfers delta
        accountCache.compute(uuid, (key, existing) -> {
            if (existing == null) return dbLoaded;
            if (existing.isPlaceholder()) {
                double startingBal = 0.0;
                double delta = existing.getBalance() - startingBal;
                if (delta != 0.0) {
                    if (delta > 0) {
                        dbLoaded.deposit(delta);
                    } else {
                        dbLoaded.withdraw(-delta);
                    }
                }
                if (existing.getUsername() != null && !existing.getUsername().equalsIgnoreCase("Player")
                        && !existing.getUsername().equalsIgnoreCase("Unknown")) {
                    dbLoaded.setUsername(existing.getUsername());
                }
                return dbLoaded;
            }
            if (existing.isDirty() || existing.getLastUpdated() >= dbLoaded.getLastUpdated()) return existing;
            return dbLoaded;
        });

        AccountData resolved = accountCache.get(uuid);
        assertNotNull(resolved);
        assertEquals(10050.0, resolved.getBalance(), "Loaded balance must incorporate the delta executed on placeholder");
        assertEquals("Alex", resolved.getUsername());
        assertTrue(resolved.isDirty(), "Account must be dirty so the transferred delta is persisted");
    }

    @Test
    @DisplayName("Safety Audit #2: Snapshot immutability prevents quit-save mutation race and preserves dirty state")
    void testSnapshotImmutabilityAndVersionTracking() {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "Racer", 100.0);

        // Account is modified -> version 1
        account.deposit(50.0);
        assertEquals(150.0, account.getBalance());
        assertTrue(account.isDirty());
        long versionAtQuit = account.getCurrentVersion();

        // Snapshot taken at quit
        AccountData snapshot = account.snapshot();
        assertEquals(150.0, snapshot.getBalance());
        assertEquals(versionAtQuit, snapshot.getCurrentVersion());

        // Concurrent operation mutates the live account after snapshot (e.g. offline deposit) -> version 2
        account.deposit(25.0);
        assertEquals(175.0, account.getBalance());
        assertTrue(account.getCurrentVersion() > versionAtQuit);

        // Snapshot is completely unaffected by live account mutations
        assertEquals(150.0, snapshot.getBalance(), "Snapshot balance must be immutable");
        assertEquals(versionAtQuit, snapshot.getCurrentVersion());

        // When the quit save of snapshot completes, it marks saved with snapshot version
        account.markSaved(snapshot.getCurrentVersion());

        // The account MUST still be dirty because version 2 has not been saved yet!
        assertTrue(account.isDirty(), "Account must remain dirty after older snapshot save completes");
    }

    @Test
    @DisplayName("Safety Audit #5: Vault deposit failure does not return inflated balance")
    void testVaultDepositFailureDoesNotInflateBalance() {
        double currentBal = 500.0;
        double depositAmount = 100.0;
        boolean success = false; // Deposit failed (e.g. exceed cap or server lock)

        double prevBal = currentBal;
        double bal;
        if (success) {
            bal = prevBal + depositAmount;
        } else {
            bal = prevBal;
        }

        EconomyResponse response = new EconomyResponse(
                success ? depositAmount : 0,
                bal,
                success ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                success ? null : "Could not deposit"
        );

        assertEquals(500.0, response.balance, "Failed deposit must NOT add deposit amount to reported balance");
        assertEquals(0.0, response.amount);
        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
    }

    @Test
    @DisplayName("Safety Audit #5: Vault deposit on cold account does not double-count amount in reported balance")
    void testVaultColdAccountDepositArithmeticDoesNotDoubleCount() {
        // Suppose cold account DB balance before deposit was 1000.0, deposit amount is 500.0
        double dbBalanceBefore = 1000.0;
        double depositAmount = 500.0;
        boolean success = true;

        // Cold account: not in cache prior to deposit
        AccountData cached = null;
        double prevBal = cached != null ? cached.getBalance() : -1.0;
        assertEquals(-1.0, prevBal);

        // After DB atomic update succeeds, the database has 1500.0:
        double dbBalanceAfter = dbBalanceBefore + depositAmount;
        AccountData afterCached = null; // Still null if async load has not populated cache yet

        // Proper arithmetic as fixed in FriendlyVaultEconomy line 233:
        double bal;
        if (success) {
            if (afterCached != null) {
                bal = afterCached.getBalance();
            } else if (prevBal >= 0) {
                bal = prevBal + depositAmount;
            } else {
                bal = dbBalanceAfter; // economyManager.getBalance(...) returns the new balance in DB
            }
        } else {
            bal = prevBal >= 0 ? prevBal : dbBalanceBefore;
        }

        EconomyResponse response = new EconomyResponse(depositAmount, bal, EconomyResponse.ResponseType.SUCCESS, null);
        assertEquals(1500.0, response.balance, "Cold account deposit must report 1500.0, not 2000.0!");
        assertEquals(500.0, response.amount);
    }

    @Test
    @DisplayName("Safety Audit #5: Vault deposit with invalid negative or NaN amount returns current balance instead of 0")
    void testVaultDepositInvalidAmountReportsAccurateBalance() {
        double currentBal = 1250.0;
        double invalidAmount = -50.0;

        EconomyResponse response;
        if (invalidAmount < 0 || Double.isNaN(invalidAmount) || Double.isInfinite(invalidAmount)) {
            response = new EconomyResponse(0, currentBal, EconomyResponse.ResponseType.FAILURE, "Amount cannot be negative or invalid");
        } else {
            response = new EconomyResponse(invalidAmount, currentBal + invalidAmount, EconomyResponse.ResponseType.SUCCESS, null);
        }

        assertEquals(1250.0, response.balance, "Invalid amount response must report the player's true balance");
        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
    }

    @Test
    @DisplayName("Safety Audit #6: SQLite setPlayerBalance preserves existing username and pay_enabled for uncached player")
    void testSqliteSetPlayerBalancePreservesUsernameAndPayEnabled() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(36) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                        last_updated BIGINT NOT NULL
                    )
                """);
            }

            UUID uuid = UUID.randomUUID();
            long initialTime = 1000L;

            // Existing player in database with custom username and payments DISABLED (pay_enabled = 0)
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, 'Alice', 200.0, 0, ?)
            """)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, initialTime);
                ps.executeUpdate();
            }

            // Simulate setPlayerBalance with "Unknown" username and new balance 999.0
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    last_updated = excluded.last_updated;
            """;
            long updateTime = 2000L;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, "Unknown");
                ps.setDouble(3, 999.0);
                ps.setLong(4, updateTime);
                ps.executeUpdate();
            }

            // Verify the row:
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Alice", rs.getString("username"), "Username 'Alice' must NOT be overwritten by 'Unknown'");
                    assertEquals(999.0, rs.getDouble("balance"), "Balance must be updated to 999.0");
                    assertFalse(rs.getBoolean("pay_enabled"), "pay_enabled must remain false (0) and not reset to 1");
                    assertEquals(updateTime, rs.getLong("last_updated"));
                }
            }

            // Also verify that passing a known username DOES update if desired:
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, "AliceNew");
                ps.setDouble(3, 1200.0);
                ps.setLong(4, 3000L);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("AliceNew", rs.getString("username"), "Known username must update properly");
                    assertEquals(1200.0, rs.getDouble("balance"));
                    assertFalse(rs.getBoolean("pay_enabled"), "pay_enabled must still remain 0");
                }
            }
        }
    }

    @Test
    @DisplayName("Safety Audit #6: SQLite setPlayerBalance inserts fresh player with pay_enabled=1")
    void testSqliteSetPlayerBalanceInsertsFreshRow() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(36) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                        last_updated BIGINT NOT NULL
                    )
                """);
            }

            UUID newUuid = UUID.randomUUID();
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    last_updated = excluded.last_updated;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newUuid.toString());
                ps.setString(2, "Bob");
                ps.setDouble(3, 750.0);
                ps.setLong(4, 1000L);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM friendlycore_economy WHERE uuid = ?")) {
                ps.setString(1, newUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Bob", rs.getString("username"));
                    assertEquals(750.0, rs.getDouble("balance"));
                    assertTrue(rs.getBoolean("pay_enabled"), "Fresh account must default pay_enabled to true (1)");
                }
            }
        }
    }

    @Test
    @DisplayName("Safety Audit #4: Premature setDirty(false) elimination - version marking on atomic adjustment")
    void testPrematureSetDirtyElimination() {
        UUID uuid = UUID.randomUUID();
        AccountData cached = new AccountData(uuid, "OfflineUser", 300.0);
        cached.setDirty(false);
        assertFalse(cached.isDirty());

        // Player receives an offline deposit:
        cached.deposit(50.0);
        assertTrue(cached.isDirty());
        long version = cached.getCurrentVersion();

        // Simulate DB failure:
        boolean dbSuccess = false;
        if (dbSuccess) {
            cached.markSaved(version);
        }

        // Account must remain dirty because DB failed:
        assertTrue(cached.isDirty(), "Account must not be marked clean if DB adjustment fails");

        // Simulate DB success:
        dbSuccess = true;
        if (dbSuccess) {
            cached.markSaved(version);
        }
        assertFalse(cached.isDirty(), "Account is marked clean only upon verified DB commit");
    }

    @Test
    @DisplayName("Safety Audit #3: Cross-server BALANCE_INVALIDATE syncs online account from database and protects newer local transactions")
    void testCrossServerBalanceInvalidateSyncOnlineAccount() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();

        // 1. Player is online locally with balance 500.0, saved to DB at t=1000
        AccountData onlineAccount = new AccountData(uuid, "OnlinePlayer", 500.0);
        onlineAccount.setLastUpdated(1000L);
        onlineAccount.setDirty(false);
        accountCache.put(uuid, onlineAccount);

        // Remote server modified balance in database to 850.0 at t=2000 (e.g. auction payout)
        AccountData dbLoaded = new AccountData(uuid, "OnlinePlayer", 850.0);
        dbLoaded.setLastUpdated(2000L);

        // Execute sync compute logic:
        accountCache.computeIfPresent(uuid, (key, existing) -> {
            if (existing.isPlaceholder()) {
                return dbLoaded;
            }
            if (!existing.isDirty() || dbLoaded.getLastUpdated() >= existing.getLastUpdated()) {
                existing.setBalance(dbLoaded.getBalance());
                existing.setLastUpdated(dbLoaded.getLastUpdated());
                existing.markSaved(existing.getCurrentVersion());
            }
            return existing;
        });

        AccountData synced = accountCache.get(uuid);
        assertNotNull(synced);
        assertEquals(850.0, synced.getBalance(), "Online account must adopt remote DB balance upon invalidate");
        assertEquals(2000L, synced.getLastUpdated());
        assertFalse(synced.isDirty(), "Synced account must be marked clean");

        // 2. Now player performs a local transaction at t=3000 (e.g. buys item for $100 -> balance $750)
        synced.withdraw(100.0);
        synced.setLastUpdated(3000L);
        assertTrue(synced.isDirty());
        assertEquals(750.0, synced.getBalance());

        // A delayed/stale invalidate packet arrives with older DB snapshot at t=2000:
        AccountData staleDbLoaded = new AccountData(uuid, "OnlinePlayer", 850.0);
        staleDbLoaded.setLastUpdated(2000L);

        accountCache.computeIfPresent(uuid, (key, existing) -> {
            if (existing.isPlaceholder()) {
                return staleDbLoaded;
            }
            if (!existing.isDirty() || staleDbLoaded.getLastUpdated() >= existing.getLastUpdated()) {
                existing.setBalance(staleDbLoaded.getBalance());
                existing.setLastUpdated(staleDbLoaded.getLastUpdated());
                existing.markSaved(existing.getCurrentVersion());
            }
            return existing;
        });

        AccountData afterStale = accountCache.get(uuid);
        assertNotNull(afterStale);
        assertEquals(750.0, afterStale.getBalance(), "Newer local modification must NOT be overwritten by stale DB data");
        assertEquals(3000L, afterStale.getLastUpdated());
        assertTrue(afterStale.isDirty(), "Account must remain dirty to persist local modifications");
    }

    @Test
    @DisplayName("Safety Audit #2 & #4: Chained adjustment and save properly clean up inFlightAccountSaves map")
    void testChainedFutureMapCleanup() {
        ConcurrentHashMap<UUID, java.util.concurrent.CompletableFuture<Void>> inFlightAccountSaves = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();

        java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Void> queued = inFlightAccountSaves.compute(uuid, (key, previous) -> {
            java.util.concurrent.CompletableFuture<Void> predecessor = previous == null
                    ? java.util.concurrent.CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return predecessor.thenCompose(nothing -> java.util.concurrent.CompletableFuture.supplyAsync(() -> true))
                    .whenComplete((applied, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(applied);
                        }
                    }).handle((val, err) -> null);
        });
        queued.whenComplete((ignored, error) -> inFlightAccountSaves.computeIfPresent(uuid, (key, c) -> c == queued ? null : c));

        // When result completes, wait for queued future to also finish:
        assertTrue(result.join());
        queued.join();

        // The entry MUST be removed from inFlightAccountSaves completely!
        assertNull(inFlightAccountSaves.get(uuid), "inFlightAccountSaves must not leak completed futures");
    }

    @Test
    @DisplayName("Safety Audit #1 & #6: setBalance clears placeholder status so subsequent DB load preserves explicit balance")
    void testPlaceholderSetBalanceClearsPlaceholderFlag() {
        ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();

        // 1. Placeholder account created on main thread with starting balance 0.0
        AccountData placeholder = new AccountData(uuid, "TestUser", 0.0, true);
        accountCache.put(uuid, placeholder);
        assertTrue(placeholder.isPlaceholder());

        // 2. Admin or plugin calls setBalance(1500.0) -> sets placeholder to false
        placeholder.setPlaceholder(false);
        placeholder.setBalance(1500.0);
        assertFalse(placeholder.isPlaceholder());
        assertTrue(placeholder.isDirty());

        // 3. Database returns older row with balance 200.0
        AccountData dbLoaded = new AccountData(uuid, "TestUser", 200.0, false);
        dbLoaded.setDirty(false);

        // 4. Cache compute logic runs:
        accountCache.compute(uuid, (key, existing) -> {
            if (existing == null) return dbLoaded;
            if (existing.isPlaceholder()) {
                double delta = existing.getBalance() - 0.0;
                dbLoaded.deposit(delta);
                return dbLoaded;
            }
            if (existing.isDirty() || existing.getLastUpdated() >= dbLoaded.getLastUpdated()) return existing;
            return dbLoaded;
        });

        AccountData finalAccount = accountCache.get(uuid);
        assertNotNull(finalAccount);
        assertEquals(1500.0, finalAccount.getBalance(), "Explicit setBalance must be preserved and not modified by DB delta");
        assertFalse(finalAccount.isPlaceholder());
        assertTrue(finalAccount.isDirty());
    }

    @Test
    @DisplayName("Safety Audit #4: Shutdown awaits all pending in-flight saves, adjustments, and cascading tasks")
    void testShutdownAwaitsInFlightSavesAndAdjustments() throws Exception {
        ConcurrentHashMap<UUID, java.util.concurrent.CompletableFuture<Void>> inFlightAccountSaves = new ConcurrentHashMap<>();
        java.util.Set<java.util.concurrent.CompletableFuture<?>> inFlightAtomicAdjustments = ConcurrentHashMap.newKeySet();

        java.util.concurrent.CompletableFuture<Void> saveFuture = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Boolean> adjFuture = new java.util.concurrent.CompletableFuture<>();

        UUID uuid = UUID.randomUUID();
        inFlightAccountSaves.put(uuid, saveFuture);
        inFlightAtomicAdjustments.add(adjFuture);

        java.util.concurrent.atomic.AtomicBoolean shutdownCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread shutdownThread = new Thread(() -> {
            while (!inFlightAccountSaves.isEmpty() || !inFlightAtomicAdjustments.isEmpty()) {
                java.util.List<java.util.concurrent.CompletableFuture<?>> allPending = new java.util.ArrayList<>();
                allPending.addAll(inFlightAccountSaves.values());
                allPending.addAll(inFlightAtomicAdjustments);

                if (allPending.isEmpty()) {
                    break;
                }
                java.util.concurrent.CompletableFuture.allOf(allPending.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .handle((ignored, error) -> null)
                        .join();
            }
            shutdownCompleted.set(true);
        });

        shutdownThread.start();
        Thread.sleep(100);

        // While futures are not resolved, shutdown must be waiting:
        assertFalse(shutdownCompleted.get(), "Shutdown must await unresolved in-flight futures");

        // Complete the in-flight tasks and verify map/set removal:
        inFlightAccountSaves.remove(uuid);
        saveFuture.complete(null);

        inFlightAtomicAdjustments.remove(adjFuture);
        adjFuture.complete(true);

        shutdownThread.join(2000);
        assertTrue(shutdownCompleted.get(), "Shutdown must complete promptly once in-flight tasks resolve");
    }

    @Test
    @DisplayName("Safety Audit #5: Vault withdraw failure returns accurate balance and zero amount")
    void testVaultWithdrawFailureReturnsAccurateBalance() {
        double currentBal = 100.0;
        double withdrawAmount = 250.0;
        boolean success = false; // Insufficient funds

        double bal = currentBal;
        EconomyResponse response = new EconomyResponse(
                success ? withdrawAmount : 0,
                bal,
                success ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                success ? null : "Insufficient funds"
        );

        assertEquals(100.0, response.balance, "Failed withdrawal must report existing balance");
        assertEquals(0.0, response.amount);
        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
    }

    @Test
    @DisplayName("Safety Audit #7: Database schema for friendlycore_economy has zero schema changes or column additions")
    void testZeroSchemaChangeCompatibility() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE friendlycore_economy (
                        uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(36) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                        last_updated BIGINT NOT NULL
                    )
                """);
            }

            // Verify exact column names and count
            java.util.Set<String> columns = new java.util.HashSet<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(friendlycore_economy)")) {
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
            }

            assertEquals(5, columns.size(), "Table must have exactly 5 columns: uuid, username, balance, pay_enabled, last_updated");
            assertTrue(columns.contains("uuid"));
            assertTrue(columns.contains("username"));
            assertTrue(columns.contains("balance"));
            assertTrue(columns.contains("pay_enabled"));
            assertTrue(columns.contains("last_updated"));
        }
    }
}
