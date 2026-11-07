package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.hooks.ProtectionHook;
import dev.lyy.friendlycore.common.redis.RedisService;
import dev.lyy.friendlycore.modules.economy.EconomyManager;
import dev.lyy.friendlycore.modules.economy.FriendlyVaultEconomy;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import dev.lyy.friendlycore.modules.pets.migration.EcoPetsMigrator;
import dev.lyy.friendlycore.modules.tools.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServerSpikeRegressionTest {
    @TempDir Path directory;

    @Test
    void concurrentMigrationCoordinatorsLeaveDatabaseWorkersAvailable() throws Exception {
        FriendlyScheduler scheduler = new FriendlyScheduler(null);
        try {
            List<CompletableFuture<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(scheduler.submitMigration(() -> CompletableFuture.supplyAsync(() -> 42,
                        scheduler.getAsyncExecutor()).join()));
            }
            for (var result : results) assertEquals(42, result.get(2, TimeUnit.SECONDS));
        } finally { scheduler.shutdown(); }
    }

    @Test
    void rejectedMigrationAdmissionReleasesItsRunningGate() throws Exception {
        FriendlyScheduler scheduler = new FriendlyScheduler(null);
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        try {
            scheduler.submitMigration(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 8; i++) scheduler.submitMigration(() -> null);
            FriendlyCore plugin = allocate(FriendlyCore.class);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            EcoPetsMigrator migrator = new EcoPetsMigrator(plugin, null);
            var rejected = migrator.migrateAll(proxy(CommandSender.class, (o, m, a) -> null), null);
            assertTrue(rejected.isCompletedExceptionally());
            assertFalse(migrator.isRunning());
        } finally { release.countDown(); scheduler.shutdown(); }
    }

    @Test
    void vanishReloadDoesNotWaitForPendingWritesOrReplayOldState() throws Exception {
        try (Fixture f = new Fixture()) {
            UUID live = UUID.randomUUID(), stale = UUID.randomUUID();
            VanishManager manager = new VanishManager(f.plugin, null);
            Set<UUID> vanished = field(manager, VanishManager.class, "vanishedPlayers");
            vanished.add(live);
            Path storage = directory.resolve("vanished.yml");
            java.nio.file.Files.writeString(storage, "vanished-uuids: [" + stale + "]");
            set(manager, VanishManager.class, "storageFile", storage.toFile());
            CompletableFuture<Void> pending = new CompletableFuture<>();
            set(manager, VanishManager.class, "pendingSave", pending);
            f.config.set("vanish.save-state-on-quit", false);
            assertTimeoutPreemptively(Duration.ofSeconds(1), manager::reload);
            assertTrue(manager.isVanished(live));
            assertFalse(manager.isVanished(stale));
            assertFalse(pending.isDone());
        }
    }

    @Test
    void unknownEconomyNameUsesBackgroundLookupWhileVaultNeverPerformsWebLookup() throws Exception {
        try (Fixture f = new Fixture()) {
            Thread caller = Thread.currentThread();
            UUID uuid = UUID.randomUUID();
            AtomicInteger networkLookups = new AtomicInteger();
            OfflinePlayer offline = proxy(OfflinePlayer.class, (o, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> uuid;
                case "hasPlayedBefore" -> true;
                default -> defaultValue(m.getReturnType());
            });
            f.server = proxy(Server.class, (o, m, a) -> switch (m.getName()) {
                case "getOfflinePlayer" -> {
                    if (a[0] instanceof String) {
                        assertNotSame(caller, Thread.currentThread());
                        networkLookups.incrementAndGet();
                    }
                    yield offline;
                }
                default -> defaultValue(m.getReturnType());
            });
            f.serverField.set(null, f.server);
            DatabaseManager database = proxy(DatabaseManager.class, (o, m, a) ->
                    m.getName().equals("getUuidByUsername") ? CompletableFuture.completedFuture(null) : null);
            set(f.plugin, FriendlyCore.class, "databaseManager", database);
            set(f.plugin, FriendlyCore.class, "redisService", new RedisService(null));
            EconomyManager economy = new EconomyManager(f.plugin);
            assertFalse(new FriendlyVaultEconomy(f.plugin, economy).hasAccount("Uncached"));
            assertEquals(0, networkLookups.get());
            assertEquals(uuid, economy.getUuidByNameAsync("Offline").get(2, TimeUnit.SECONDS));
            assertEquals(1, networkLookups.get());
        }
    }

    @Test
    void drillHonorsTickBudgetAndStopsAfterToolSwap() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.set("drill.blocks-per-tick", 3);
            DrillHandler drill = new DrillHandler(f.plugin);
            drill.onBlockBreak(new BlockBreakEvent(f.block(8, 60, 8), f.player));
            assertEquals(0, f.breaks);
            f.runTick();
            assertEquals(3, f.breaks);
            assertEquals(1, f.scheduled.size());
            f.held = new Stack(Material.STONE);
            f.runTick();
            assertEquals(3, f.breaks);
            assertTrue(f.scheduled.isEmpty());
        }
    }

    @Test
    void cancelledDrillBreakAndDeniedProtectionNeverBreakExtraBlocks() throws Exception {
        try (Fixture f = new Fixture()) {
            DrillHandler drill = new DrillHandler(f.plugin);
            BlockBreakEvent event = new BlockBreakEvent(f.block(8, 60, 8), f.player);
            drill.onBlockBreak(event);
            event.setCancelled(true);
            f.runTick();
            assertEquals(0, f.breaks);
            f.allowed = false;
            drill.onBlockBreak(new BlockBreakEvent(f.block(8, 60, 8), f.player));
            for (int i = 0; i < 4; i++) f.runTick();
            assertEquals(0, f.breaks);
            assertTrue(f.scheduled.isEmpty());
        }
    }

    @Test
    void leafScanningIsDeferredBoundedAndLimitedToRemovedLogNeighborhoods() throws Exception {
        try (Fixture f = new Fixture()) {
            f.leafMode = true;
            LeafDecayManager decay = new LeafDecayManager(f.plugin);
            decay.scheduleLeafDecay(List.of(f.block(8, 20, 8), f.block(8, 220, 8)));
            assertEquals(0, f.reads);
            assertEquals(1, f.scheduled.size(), "One scan task for this chunk, not one timer per leaf");
            f.runTick();
            assertTrue(f.reads <= 128);
            assertTrue(f.reads > 0);
            for (int i = 0; i < 25; i++) f.runTick();
            assertTrue(f.readYs.stream().allMatch(y -> (y >= 18 && y <= 26) || (y >= 218 && y <= 226)));
            assertEquals(0, f.breaks, "Leaves supported by surviving logs must remain intact");
        }
    }

    @Test
    void leafScansShareOneServerTickBudgetAcrossChunks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.leafMode = true;
            List<Block> logs = new ArrayList<>();
            for (int i = 0; i < 13; i++) logs.add(f.block(i * 16 + 8, 60, 8));
            new LeafDecayManager(f.plugin).scheduleLeafDecay(logs);
            f.runTick();
            assertEquals(1024, f.reads, "Concurrent chunk scans must share a global read budget");
        }
    }

    @Test
    void drillSkipsUnownedFoliaRegionsWithoutReadingOrBreakingTheirBlocks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.folia = true;
            new DrillHandler(f.plugin).onBlockBreak(new BlockBreakEvent(f.block(8, 60, 8), f.player));
            for (int i = 0; i < 4; i++) f.runTick();
            assertEquals(0, f.reads);
            assertEquals(0, f.breaks);
            assertTrue(f.scheduled.isEmpty());
        }
    }

    private static class Stack extends ItemStack {
        private final Material material;
        Stack(Material material) { this.material = material; }
        @Override public Material getType() { return material; }
        @Override public ItemMeta getItemMeta() { return null; }
        @Override public boolean isSimilar(ItemStack other) { return other != null && other.getType() == material; }
        @Override public ItemStack clone() { return new Stack(material); }
    }
    private static class Config extends ConfigService {
        YamlConfiguration settings;
        Config() { super(null); }
        @Override public FileConfiguration getToolsConfig() { return settings; }
        @Override public FileConfiguration get(String path) { return settings; }
    }
    private static class Tools extends ToolManager {
        Tools() { super(null); }
        @Override public CustomToolType getToolType(ItemStack item) {
            return item.getType() == Material.DIAMOND_PICKAXE ? CustomToolType.DRILL_PICKAXE : null;
        }
        @Override public void updateDrillMeta(ItemStack item, String name) {}
    }
    private static class Hooks extends HookManager {
        ProtectionHook protection;
        Hooks() { super(null); }
        @Override public ProtectionHook getProtectionHook() { return protection; }
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return (T) field.get(target);
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    private static class Fixture implements AutoCloseable {
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final YamlConfiguration config = new YamlConfiguration();
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        final Set<Integer> readYs = new HashSet<>();
        final Field serverField = Bukkit.class.getDeclaredField("server");
        final Object oldServer;
        Server server;
        int tick, breaks, reads;
        boolean allowed = true, leafMode, folia;
        ItemStack held = new Stack(Material.DIAMOND_PICKAXE);
        final UUID uuid = UUID.randomUUID();
        final World world = proxy(World.class, (o, m, a) -> switch (m.getName()) {
            case "getUID" -> uuid;
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "isChunkLoaded" -> true;
            case "getBlockAt" -> {
                int x, y, z;
                if (a.length == 1) {
                    Location location = (Location) a[0];
                    x = location.getBlockX(); y = location.getBlockY(); z = location.getBlockZ();
                } else { x = (int) a[0]; y = (int) a[1]; z = (int) a[2]; }
                reads++; readYs.add(y); yield block(x, y, z);
            }
            default -> defaultValue(m.getReturnType());
        });
        final PlayerInventory inventory = proxy(PlayerInventory.class, (o, m, a) ->
            m.getName().equals("getItemInMainHand") ? held : defaultValue(m.getReturnType()));
        final Player player = proxy(Player.class, (o, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> uuid;
            case "getInventory" -> inventory;
            case "isOnline", "hasPermission" -> true;
            case "getName" -> "Miner";
            default -> defaultValue(m.getReturnType());
        });
        final FriendlyScheduler scheduler = new FriendlyScheduler(null) {
            @Override public boolean isFolia() { return folia; }
            @Override public Object runEntityLater(Entity entity, Runnable task, Runnable retired, long delay) {
                scheduled.add(task); return task;
            }
            @Override public Object runLocationLater(Location location, Runnable task, long delay) {
                scheduled.add(task); return task;
            }
        };
        Fixture() throws Exception {
            serverField.setAccessible(true); oldServer = serverField.get(null);
            server = proxy(Server.class, (o, m, a) -> m.getName().equals("getCurrentTick") ? tick : defaultValue(m.getReturnType()));
            serverField.set(null, server);
            Config service = allocate(Config.class); service.settings = config;
            config.set("vanish.actionbar-reminder", false);
            config.set("drill.effects.sound.enabled", false);
            config.set("drill.effects.particle.enabled", false);
            set(plugin, FriendlyCore.class, "configService", service);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "toolManager", allocate(Tools.class));
            Hooks hooks = allocate(Hooks.class);
            hooks.protection = new ProtectionHook() {
                @Override public boolean canBreak(Player player, Block block) { return allowed; }
            };
            set(plugin, FriendlyCore.class, "hookManager", hooks);
        }
        Block block(int x, int y, int z) {
            return proxy(Block.class, (o, m, a) -> switch (m.getName()) {
                case "getWorld" -> world;
                case "getX" -> x;
                case "getY" -> y;
                case "getZ" -> z;
                case "getLocation" -> new Location(world, x, y, z);
                case "getType" -> Material.STONE;
                case "getBlockData" -> leafMode ? proxy(Leaves.class, (lo, lm, la) ->
                    lm.getName().equals("getDistance") ? 1 : defaultValue(lm.getReturnType())) : null;
                case "breakNaturally" -> { breaks++; yield true; }
                default -> defaultValue(m.getReturnType());
            });
        }
        void runTick() {
            tick++;
            int count = scheduled.size();
            for (int i = 0; i < count; i++) scheduled.remove().run();
        }
        @Override public void close() throws Exception { scheduler.shutdown(); serverField.set(null, oldServer); }
    }
}
