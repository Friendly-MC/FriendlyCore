package dev.lyy.friendlycore;

import com.zaxxer.hikari.HikariDataSource;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.database.MySQLDatabase;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.common.redis.CrossServerListener;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.common.redis.RedisService;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionAdminRemovalGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionShulkerPreviewGui;
import dev.lyy.friendlycore.modules.economy.AccountData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.SetParams;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ServerLogRegressionTest {
    @Test
    void previewAndAdminCloseDeferReturningAndRespectTransitionsAndDisconnects() throws Exception {
        for (Class<? extends AuctionMenuBase> type : List.of(AuctionShulkerPreviewGui.class, AuctionAdminRemovalGui.class)) {
            try (Scheduler scheduler = new Scheduler()) {
                FriendlyCore plugin = plugin();
                set(plugin, FriendlyCore.class, "scheduler", scheduler);
                boolean[] online = {true};
                InventoryView view = proxy(InventoryView.class, (object, method, args) -> {
                    throw new AssertionError("A disconnected callback must not access the view");
                });
                Player viewer = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online[0];
                    case "isDead" -> false;
                    case "getOpenInventory" -> view;
                    default -> null;
                });
                PreviousMenu previous = allocate(PreviousMenu.class);
                AuctionMenuBase menu = allocate(type);
                set(menu, PaginatedGui.class, "plugin", plugin);
                set(menu, PaginatedGui.class, "viewer", viewer);
                set(menu, AuctionMenuBase.class, "previousMenu", previous);
                set(menu, AuctionMenuBase.class, "auctionManager", allocate(Manager.class));

                for (InventoryCloseEvent.Reason reason : List.of(InventoryCloseEvent.Reason.PLAYER,
                        InventoryCloseEvent.Reason.PLUGIN, InventoryCloseEvent.Reason.UNKNOWN)) {
                    online[0] = true;
                    menu.handleClose(new InventoryCloseEvent(view, reason));
                    assertEquals(0, previous.opens, "Opening inside close would recurse on Paper");
                    assertEquals(1, scheduler.pending.size());
                    online[0] = false;
                    scheduler.pending.remove().run();
                    assertEquals(0, previous.opens, "Disconnect before the next tick cancels the return");
                }
                online[0] = true;
                for (InventoryCloseEvent.Reason reason : List.of(InventoryCloseEvent.Reason.OPEN_NEW,
                        InventoryCloseEvent.Reason.DISCONNECT, InventoryCloseEvent.Reason.DEATH,
                        InventoryCloseEvent.Reason.CANT_USE, InventoryCloseEvent.Reason.UNLOADED)) {
                    menu.handleClose(new InventoryCloseEvent(view, reason));
                    assertTrue(scheduler.pending.isEmpty());
                }
            }
        }
    }

    @Test
    void batchesUseUuidOrderAndRetryTheSameSnapshotsAfterDeadlock() throws Exception {
        try (DatabaseProbe probe = new DatabaseProbe()) {
            AccountData first = account("00000000-0000-0000-0000-000000000001");
            AccountData second = account("ffffffff-ffff-ffff-ffff-ffffffffffff");
            probe.failures = 1;
            probe.failure = deadlock();
            probe.onRetry = () -> first.deposit(5);
            probe.database.saveAccountsBatchSync(List.of(second, first));
            assertEquals(2, probe.attempts);
            assertEquals(1, probe.rollbacks);
            assertEquals(1, probe.commits);
            assertEquals(List.of(first.getUuid().toString(), second.getUuid().toString()), probe.batches.getFirst());
            assertEquals(probe.batches.getFirst(), probe.batches.getLast());
            assertEquals(List.of(List.of(100.0, 100.0), List.of(100.0, 100.0)), probe.balances);
            assertTrue(first.isDirty(), "A newer mutation must remain dirty after the earlier snapshot commits");
            assertFalse(second.isDirty());
        }
    }

    @Test
    void persistentDeadlockStopsAfterThreeAttemptsAndKeepsAccountsDirty() throws Exception {
        try (DatabaseProbe probe = new DatabaseProbe()) {
            AccountData account = account(UUID.randomUUID().toString());
            probe.failures = 10;
            probe.failure = deadlock();
            assertThrows(CompletionException.class, () -> probe.database.saveAccountsBatchSync(List.of(account)));
            assertEquals(3, probe.attempts);
            assertEquals(3, probe.rollbacks);
            assertEquals(0, probe.commits);
            assertTrue(account.isDirty());
        }
    }

    @Test
    void connectionFailuresAndFailedRollbacksAreNeverRetried() throws Exception {
        for (boolean rollbackFails : new boolean[]{false, true}) {
            try (DatabaseProbe probe = new DatabaseProbe()) {
                AccountData account = account(UUID.randomUUID().toString());
                probe.failures = 1;
                probe.failure = rollbackFails ? deadlock() : new SQLException("Connection lost", "08S01");
                probe.rollbackFails = rollbackFails;
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> probe.database.saveAccountsBatchSync(List.of(account)));
                assertEquals(1, probe.attempts);
                assertEquals(0, probe.commits);
                assertTrue(account.isDirty());
                assertEquals(rollbackFails ? 1 : 0, failure.getCause().getSuppressed().length);
            }
        }
    }

    @Test
    void nonceTimeoutAndDuplicateRejectPacketsWhileRedisRecoveryAllowsFreshPackets() throws Exception {
        String secret = "0123456789abcdef0123456789abcdef";
        FriendlyCore plugin = plugin();
        set(plugin, JavaPlugin.class, "isEnabled", true);
        List<Runnable> accepted = new ArrayList<>();
        try (Scheduler scheduler = new Scheduler() {
            @Override public Object runGlobal(Runnable action) { accepted.add(action); return action; }
        }; UnifiedJedis jedis = new UnifiedJedis(new redis.clients.jedis.Connection()) {
            int calls;
            @Override public String set(String key, String value, SetParams params) {
                assertTrue(key.startsWith("friendlycore:nonce:hub:"));
                if (++calls == 1) throw new JedisConnectionException("Read timed out");
                return calls == 2 ? null : "OK";
            }
        }) {
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            RedisService redis = new RedisService(plugin);
            set(redis, RedisService.class, "enabled", true);
            set(redis, RedisService.class, "serverId", "hub");
            set(redis, RedisService.class, "redisClient", jedis);
            set(plugin, FriendlyCore.class, "redisService", redis);
            CrossServerListener listener = new CrossServerListener(plugin, "hub", "sync", secret, 30, 4096);
            set(redis, RedisService.class, "listener", listener);
            for (int attempt = 0; attempt < 3; attempt++) {
                CrossServerPacket packet = new CrossServerPacket("economy", CrossServerPacket.Action.BALTOP_INVALIDATE,
                        null, null, 0, null);
                packet.sign(secret);
                listener.onMessage("sync", new com.google.gson.Gson().toJson(packet));
                assertEquals(attempt == 2 ? 1 : 0, accepted.size());
            }
        }
    }

    private static BatchUpdateException deadlock() {
        return new BatchUpdateException("Deadlock", "40001", 1213, new int[0]);
    }

    private static AccountData account(String uuid) {
        AccountData account = new AccountData(UUID.fromString(uuid), "Player", 100);
        account.setDirty(true);
        return account;
    }

    private static class DatabaseProbe extends HikariDataSource {
        final MySQLDatabase database;
        final List<List<String>> batches = new ArrayList<>();
        final List<List<Double>> balances = new ArrayList<>();
        int attempts, rollbacks, commits, failures;
        boolean rollbackFails;
        SQLException failure;
        Runnable onRetry;

        DatabaseProbe() throws Exception {
            database = new MySQLDatabase(plugin());
            set(database, MySQLDatabase.class, "dataSource", this);
        }

        @Override public Connection getConnection() {
            attempts++;
            if (attempts > 1 && onRetry != null) onRetry.run();
            List<String> rows = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            String[] uuid = {null};
            double[] balance = {0};
            PreparedStatement statement = proxy(PreparedStatement.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setString" -> { if ((int) args[0] == 1) uuid[0] = (String) args[1]; }
                    case "setDouble" -> balance[0] = (double) args[1];
                    case "addBatch" -> { rows.add(uuid[0]); values.add(balance[0]); }
                    case "executeBatch" -> {
                        batches.add(List.copyOf(rows));
                        balances.add(List.copyOf(values));
                        if (failures-- > 0) throw failure;
                        return new int[rows.size()];
                    }
                }
                return null;
            });
            return proxy(Connection.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement" -> { return statement; }
                    case "commit" -> commits++;
                    case "rollback" -> {
                        rollbacks++;
                        if (rollbackFails) throw new SQLException("Rollback unavailable", "08S01");
                    }
                    case "setAutoCommit" -> assertEquals(false, args[0], "Never enable autocommit after a failed rollback");
                }
                return null;
            });
        }
    }

    private static class PreviousMenu extends AuctionMenuBase {
        int opens;
        PreviousMenu() { super(null, null, null, null, 1, null); }
        @Override public void refresh() {}
        @Override public void open() { opens++; }
    }

    private static class Manager extends AuctionManager {
        Manager() { super(null); }
        @Override public void unregisterActiveMenu(AuctionMenuBase menu) {}
    }

    private static class Scheduler extends FriendlyScheduler implements AutoCloseable {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        Scheduler() { super(null); }
        @Override public Object runEntity(Entity entity, Runnable action) { action.run(); return null; }
        @Override public Object runEntityLater(Entity entity, Runnable action, long ticks) {
            assertEquals(1L, ticks);
            pending.add(action);
            return action;
        }
        @Override public void close() { shutdown(); }
    }

    private static FriendlyCore plugin() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        set(plugin, JavaPlugin.class, "logger", logger);
        return plugin;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
