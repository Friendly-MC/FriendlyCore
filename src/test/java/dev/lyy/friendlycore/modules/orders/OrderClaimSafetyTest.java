package dev.lyy.friendlycore.modules.orders;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.database.MySQLDatabase;
import dev.lyy.friendlycore.common.database.SQLiteDatabase;
import dev.lyy.friendlycore.modules.orders.model.OrderDelivery;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class OrderClaimSafetyTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void allActionsWaitForTheDatabaseAndShareThePlayerGuard(int action) throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.nextClaim = new CompletableFuture<>();
            runtime.nextCounter = new CompletableFuture<>();
            CompletableFuture<Integer> result = runtime.claim(action);
            assertFalse(result.isDone());
            assertEquals(0, runtime.itemsGiven);
            assertEquals(0, runtime.moneyGiven);
            for (int other = 0; other < 3; other++) assertEquals(0, runtime.claim(other).join());
            runtime.commit(List.of(1));
            assertFalse(result.isDone(), "The guard must also cover the claim accounting write");
            assertEquals(action == 2 ? 0 : 65, runtime.itemsGiven);
            assertEquals(action == 2 ? 650 : 0, runtime.moneyGiven);
            assertEquals(65, runtime.counted);
            runtime.nextCounter.complete(null);
            assertEquals(action == 2 ? 1 : 2, result.join());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void emptyAndFailedClaimsNeverGrantAnythingAndAllowRetry(int action) throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.nextClaim = new CompletableFuture<>();
            CompletableFuture<Integer> result = runtime.claim(action);
            runtime.commit(List.of());
            assertEquals(0, result.join());
            runtime.nextClaim = CompletableFuture.failedFuture(new IllegalStateException("database unavailable"));
            assertEquals(0, runtime.claim(action).join());
            assertEquals(0, runtime.itemsGiven + runtime.moneyGiven);
            assertEquals(0, runtime.counted);
            runtime.nextClaim = null;
            assertTrue(runtime.claim(action).join() > 0);
        }
    }

    @Test
    void partialSellClaimsPayOnlyTheWinningRowsAndLeaveUnsellableItems() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.nextClaim = new CompletableFuture<>();
            CompletableFuture<Integer> result = runtime.manager.sellDeliveries(runtime.player);
            runtime.commit(List.of(2));
            assertEquals(1, result.join());
            assertEquals(30, runtime.moneyGiven);
            assertEquals(3, runtime.counted);
            runtime.manager.unitPrice = 0;
            assertEquals(0, runtime.manager.sellDeliveries(runtime.player).join());
            assertEquals(Set.of(2), runtime.claimed);
            assertEquals(30, runtime.moneyGiven);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disconnectedAndRetiredPlayersRestoreOnlyAcquiredClaims(boolean retired) throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.scheduler.pause = true;
            runtime.nextClaim = new CompletableFuture<>();
            runtime.nextRestore = new CompletableFuture<>();
            CompletableFuture<Integer> result = runtime.manager.claimDeliveries(runtime.player);
            runtime.claimed.add(2); // Another backend owns this row.
            runtime.commit(List.of(1));
            if (retired) runtime.scheduler.retire.run();
            else { runtime.online = false; runtime.scheduler.queued.run(); }
            assertFalse(result.isDone(), "Keep the guard until rollback commits");
            assertEquals(List.of(1), runtime.restored);
            assertEquals(Set.of(2), runtime.claimed);
            assertEquals(0, runtime.itemsGiven + runtime.moneyGiven);
            runtime.online = true;
            assertEquals(0, runtime.manager.sellDeliveries(runtime.player).join());
            runtime.nextRestore.complete(null);
            assertEquals(0, result.join());
        }
    }

    @Test
    void rejectedPayoutRestoresClaimsForRetry() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.manager.depositSucceeds = false;
            assertEquals(0, runtime.manager.sellDeliveries(runtime.player).join());
            assertEquals(Set.of(), runtime.claimed);
            assertEquals(List.of(1, 2), runtime.restored);
            assertEquals(0, runtime.counted);
            runtime.manager.depositSucceeds = true;
            assertEquals(2, runtime.manager.sellDeliveries(runtime.player).join());
            assertEquals(680, runtime.moneyGiven);
            assertEquals(68, runtime.counted);
        }
    }

    @Test
    void priceChangesDuringClaimDoNotConsumeItemsForAnInvalidSale() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.nextClaim = new CompletableFuture<>();
            CompletableFuture<Integer> result = runtime.manager.sellDeliveries(runtime.player);
            runtime.manager.unitPrice = Double.POSITIVE_INFINITY;
            runtime.commit(List.of(1));
            assertEquals(0, result.join());
            assertEquals(List.of(1), runtime.restored);
            assertTrue(runtime.claimed.isEmpty());
            assertEquals(0, runtime.moneyGiven);
        }
    }

    @Test
    void uncertainInventoryFailureDoesNotReplayPossiblyAwardedItems() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.failInventory = true;
            assertEquals(0, runtime.manager.claimDelivery(runtime.player, 1).join());
            assertEquals(Set.of(1), runtime.claimed);
            assertTrue(runtime.restored.isEmpty());
            assertEquals(64, runtime.itemsGiven);
            assertEquals(0, runtime.manager.claimDelivery(runtime.player, 1).join());
            assertEquals(64, runtime.itemsGiven);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realDatabaseClaimsAndRestoresAreOwnerScopedAndTransactional(boolean mysql) throws Exception {
        try (Runtime runtime = new Runtime()) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(mysql ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL" : "jdbc:sqlite::memory:");
            config.setMaximumPoolSize(1);
            try (HikariDataSource pool = new HikariDataSource(config)) {
                DatabaseManager database = mysql ? new MySQLDatabase(runtime.plugin) : new SQLiteDatabase(runtime.plugin);
                set(database, database.getClass(), "dataSource", pool);
                try (var conn = pool.getConnection(); var statement = conn.createStatement()) {
                    statement.execute("CREATE TABLE friendlycore_order_deliveries (id INT PRIMARY KEY, buyer_uuid VARCHAR(36), claimed INT NOT NULL, CHECK (id <> 2 OR claimed <> 0))");
                    statement.execute("INSERT INTO friendlycore_order_deliveries VALUES (1, '" + runtime.uuid + "', 0), (2, '" + runtime.uuid + "', 1)");
                }
                assertEquals(List.of(), database.claimDeliveries(UUID.randomUUID(), List.of(1)).get(5, TimeUnit.SECONDS));
                assertEquals(List.of(1), database.claimDeliveries(runtime.uuid, List.of(1, 2)).get(5, TimeUnit.SECONDS));
                assertEquals(List.of(), database.claimDeliveries(runtime.uuid, List.of(1)).get(5, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> database.restoreClaimedDeliveries(runtime.uuid, List.of(1, 2)).get(5, TimeUnit.SECONDS));
                assertEquals(List.of(), database.claimDeliveries(runtime.uuid, List.of(1)).get(5, TimeUnit.SECONDS), "A failed restore must roll back the entire batch");
                database.restoreClaimedDeliveries(UUID.randomUUID(), List.of(1)).get(5, TimeUnit.SECONDS);
                assertEquals(List.of(), database.claimDeliveries(runtime.uuid, List.of(1)).get(5, TimeUnit.SECONDS));
                database.restoreClaimedDeliveries(runtime.uuid, List.of(1)).get(5, TimeUnit.SECONDS);
                assertEquals(List.of(1), database.claimDeliveries(runtime.uuid, List.of(1)).get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static class Runtime implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final Scheduler scheduler = new Scheduler();
        final Set<Integer> claimed = new HashSet<>();
        final List<Integer> restored = new ArrayList<>();
        final List<OrderDelivery> deliveries = List.of(delivery(1, 65), delivery(2, 3));
        final Player player;
        final Manager manager;
        CompletableFuture<List<Integer>> nextClaim;
        CompletableFuture<Void> nextCounter;
        CompletableFuture<Void> nextRestore;
        boolean online = true;
        boolean failInventory;
        int itemsGiven;
        int counted;
        double moneyGiven;

        Runtime() throws Exception {
            ConfigService configs = allocate(ConfigService.class);
            set(configs, ConfigService.class, "aliases", new HashMap<>());
            set(configs, ConfigService.class, "configs", new ConcurrentHashMap<>(Map.of(
                    "config.yml", new YamlConfiguration(), "modules/orders/config.yml", new YamlConfiguration())));
            set(plugin, FriendlyCore.class, "configService", configs);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "textService", new TextService() {
                @Override public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {}
            });
            Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
            set(plugin, JavaPlugin.class, "logger", logger);
            DatabaseManager database = proxy(DatabaseManager.class, (object, method, args) -> switch (method.getName()) {
                case "loadUnclaimedDeliveries" -> CompletableFuture.completedFuture(deliveries.stream()
                        .filter(delivery -> !claimed.contains(delivery.getId())).toList());
                case "claimDeliveries" -> {
                    if (nextClaim != null) yield nextClaim;
                    List<Integer> acquired = ((List<Integer>) args[1]).stream().filter(claimed::add).toList();
                    yield CompletableFuture.completedFuture(acquired);
                }
                case "restoreClaimedDeliveries" -> {
                    restored.addAll((List<Integer>) args[1]); claimed.removeAll((List<Integer>) args[1]);
                    yield nextRestore == null ? CompletableFuture.completedFuture(null) : nextRestore;
                }
                case "incrementClaimedAmount" -> {
                    counted += (int) args[1];
                    yield nextCounter == null ? CompletableFuture.completedFuture(null) : nextCounter;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            set(plugin, FriendlyCore.class, "databaseManager", database);
            PlayerInventory inventory = proxy(PlayerInventory.class, (object, method, args) -> {
                if (!method.getName().equals("addItem")) throw new UnsupportedOperationException(method.getName());
                itemsGiven += ((ItemStack[]) args[0])[0].getAmount();
                if (failInventory) throw new IllegalStateException("inventory award outcome unknown");
                return new HashMap<Integer, ItemStack>();
            });
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Buyer";
                case "isOnline" -> online;
                case "getInventory" -> inventory;
                case "updateInventory" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            manager = new Manager(this);
        }

        private OrderDelivery delivery(int id, int quantity) {
            return new OrderDelivery(id, UUID.randomUUID(), uuid, UUID.randomUUID(), "Seller", stack(1), quantity, 1, false);
        }
        CompletableFuture<Integer> claim(int action) {
            return switch (action) {
                case 0 -> manager.claimDeliveries(player);
                case 1 -> manager.claimDelivery(player, 1);
                default -> manager.sellDeliveries(player);
            };
        }
        void commit(List<Integer> ids) { claimed.addAll(ids); nextClaim.complete(ids); }
        @Override public void close() { scheduler.getAsyncExecutor().shutdownNow(); }
    }

    private static class Manager extends OrderManager {
        final Runtime runtime;
        boolean depositSucceeds = true;
        double unitPrice = 10;
        Manager(Runtime runtime) { super(runtime.plugin); this.runtime = runtime; }
        @Override public double getStandardPrice(Material material) { return unitPrice; }
        @Override public boolean depositMoney(UUID uuid, double amount, String reason) {
            if (depositSucceeds) runtime.moneyGiven += amount;
            return depositSucceeds;
        }
        @Override public String formatItemName(ItemStack item) { return "Stone"; }
    }

    private static class Scheduler extends FriendlyScheduler {
        boolean pause;
        Runnable queued;
        Runnable retire;
        Scheduler() { super(null); }
        @Override public Object runEntity(Entity entity, Runnable task, Runnable retired) {
            if (pause) { queued = task; retire = retired; }
            else task.run();
            return null;
        }
        @Override public Object runGlobal(Runnable task) { return null; }
    }

    private static class Stack extends ItemStack {
        int amount;
        @Override public Material getType() { return Material.STONE; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public Stack clone() { return stack(amount); }
    }
    private static Stack stack(int amount) { Stack stack = allocate(Stack.class); stack.amount = amount; return stack; }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
