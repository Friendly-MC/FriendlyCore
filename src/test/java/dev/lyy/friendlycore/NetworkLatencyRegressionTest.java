package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.redis.RedisService;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.EconomyManager;
import dev.lyy.friendlycore.modules.rtp.RtpLocationCache;
import dev.lyy.friendlycore.modules.rtp.RtpLocationFinder;
import dev.lyy.friendlycore.modules.rtp.RtpManager;
import dev.lyy.friendlycore.modules.rtp.RtpWorldConfig;
import dev.lyy.friendlycore.modules.spawn.LastLocationService;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import dev.lyy.friendlycore.common.CooldownManager;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.rtp.RtpActionbarAnimator;
import dev.lyy.friendlycore.modules.rtp.RtpSpreadingManager;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NetworkLatencyRegressionTest {
    @Test
    void coldOfflineWithdrawalReturnsWithoutDebitingAndCanBeRetriedAfterLoading() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        serverField.set(null, proxy(Server.class, (object, method, args) -> {
            assertEquals("isPrimaryThread", method.getName());
            return true;
        }));
        try {
            FriendlyCore plugin = allocate(FriendlyCore.class);
            UUID uuid = UUID.randomUUID();
            var load = new CompletableFuture<AccountData>();
            int[] loads = {0}, debits = {0};
            DatabaseManager database = proxy(DatabaseManager.class, (object, method, args) -> switch (method.getName()) {
                case "loadAccount" -> { loads[0]++; yield load; }
                case "atomicAdjustBalance" -> {
                    assertEquals(uuid, args[0]);
                    assertEquals(-25.0, args[2]);
                    debits[0]++;
                    yield CompletableFuture.completedFuture(true);
                }
                default -> throw new AssertionError(method.getName());
            });
            set(plugin, FriendlyCore.class, "databaseManager", database);
            set(plugin, FriendlyCore.class, "redisService", new RedisService(null));
            EconomyManager manager = new EconomyManager(plugin);
            assertFalse(manager.withdraw(uuid, "Steve", 25.0, false));
            assertFalse(manager.withdraw(uuid, "Steve", 25.0, false));
            assertEquals(1, loads[0], "Repeated attempts share the account load");
            assertEquals(0, debits[0], "A failed synchronous withdrawal must never launch a later debit");
            load.complete(new AccountData(uuid, "Steve", 100.0));
            assertEquals(0, debits[0], "Loading completion must not retry the rejected payment automatically");
            assertEquals(100.0, manager.getCachedAccount(uuid).getBalance());
            assertTrue(manager.withdraw(uuid, "Steve", 25.0, false));
            assertEquals(1, debits[0]);
            assertEquals(75.0, manager.getCachedAccount(uuid).getBalance());
        } finally {
            serverField.set(null, previousServer);
        }
    }

    @Test
    void joinReadsRedisOnlyOffThreadAndResumesOnThePlayerScheduler() throws Exception {
        for (String marker : new String[]{null, "1"}) {
            for (boolean online : new boolean[]{true, false}) {
                FriendlyCore plugin = allocate(FriendlyCore.class);
                Scheduler scheduler = new Scheduler();
                Config config = config();
                config.yaml.set("join-spawn.last-location.enabled", false);
                set(plugin, FriendlyCore.class, "scheduler", scheduler);
                set(plugin, FriendlyCore.class, "configService", config);
                int[] reads = {0};
                RedisService redis = new RedisService(null) {
                    @Override public boolean isEnabled() { return true; }
                    @Override public String getPersistent(String key) {
                        assertTrue(scheduler.inAsync, "Redis must never block the join/player thread");
                        assertTrue(key.startsWith("friendlycore:spawn_respawn:"));
                        reads[0]++;
                        return marker;
                    }
                };
                set(plugin, FriendlyCore.class, "redisService", redis);
                JoinProbe manager = allocate(JoinProbe.class);
                set(manager, SpawnManager.class, "plugin", plugin);
                set(manager, SpawnManager.class, "arrivalTickets", new ConcurrentHashMap<>());
                set(manager, SpawnManager.class, "crossServerRespawns", ConcurrentHashMap.newKeySet());
                int[] routing = {0};
                LastLocationService service = new LastLocationService(plugin, manager) {
                    @Override public boolean tryHandleJoin(Player player) {
                        assertFalse(scheduler.inAsync);
                        routing[0]++;
                        return false;
                    }
                };
                set(manager, SpawnManager.class, "lastLocationService", service);
                UUID uuid = UUID.randomUUID();
                Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnline" -> online;
                    case "hasPlayedBefore", "hasPermission" -> true;
                    case "isDead" -> false;
                    default -> throw new AssertionError(method.getName());
                });

                manager.onPlayerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
                assertEquals(0, reads[0], "The join event returns before any Redis I/O");
                assertTrue(scheduler.entity.isEmpty());
                scheduler.runAsyncTask();
                assertEquals(1, reads[0]);
                assertEquals(0, routing[0], "Redis completion must not route a player off thread");
                scheduler.entity.remove().run();
                assertEquals(online ? 1 : 0, routing[0]);
                assertEquals(online && marker != null ? 1 : 0, manager.clears);
            }
        }
    }

    @Test
    void failedChunkAttemptsRespectCacheThrottleAndKeepInteractiveRetrySpeed() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        set(plugin, FriendlyCore.class, "configService", config());
        Scheduler scheduler = new Scheduler();
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
            @Override public int[] generateRandomCoordinates(World world, RtpWorldConfig config) { return new int[]{0, 0}; }
        };
        for (boolean loadFails : new boolean[]{true, false}) {
            int[] chunks = {0};
            Chunk brokenChunk = proxy(Chunk.class, (object, method, args) -> { throw new IllegalStateException("Column unavailable"); });
            World world = proxy(World.class, (object, method, args) -> switch (method.getName()) {
                case "getChunkAtAsync" -> {
                    chunks[0]++;
                    yield loadFails ? CompletableFuture.failedFuture(new IllegalStateException("Load unavailable"))
                            : CompletableFuture.completedFuture(brokenChunk);
                }
                case "getHighestBlockYAt" -> 64;
                case "getMaxHeight" -> 320;
                case "getMinHeight" -> -64;
                default -> throw new AssertionError(method.getName());
            });
            for (long delay : new long[]{4L, 1L}) {
                chunks[0] = 0;
                CompletableFuture<Optional<Location>> result = delay == 4L
                        ? finder.findSafeLocation(world, worldConfig("world"), 2, null, null, delay)
                        : finder.findSafeLocation(world, worldConfig("world"), 2, null, null);
                assertEquals(1, chunks[0]);
                assertEquals(delay, scheduler.delays.getLast());
                assertFalse(result.isDone(), () -> result.handle((value, error) -> String.valueOf(error)).getNow("pending"));
                scheduler.delayed.remove().run();
                assertEquals(2, chunks[0]);
                assertEquals(delay, scheduler.delays.getLast(), "The configured delay must survive recursion");
                scheduler.delayed.remove().run();
                assertEquals(Optional.empty(), result.join());
                assertEquals(2, chunks[0], "Do not exceed the configured attempt limit");
            }
        }
    }

    @Test
    void refillLimitIncludesExistingWorkersAndReloadCannotReleaseNewWorkers() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        List<World> worlds = new ArrayList<>();
        for (String name : List.of("one", "two", "three", "four")) {
            worlds.add(proxy(World.class, (object, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                default -> throw new AssertionError(method.getName());
            }));
        }
        serverField.set(null, proxy(Server.class, (object, method, args) -> switch (method.getName()) {
            case "getWorlds" -> worlds;
            case "getOnlinePlayers" -> List.of(proxy(Player.class, (p, m, a) -> null));
            case "getWorld" -> worlds.stream().filter(world -> world.getName().equals(args[0])).findFirst().orElse(null);
            default -> throw new AssertionError(method.getName());
        }));
        try {
            FriendlyCore plugin = allocate(FriendlyCore.class);
            Config config = config();
            config.yaml.set("location-cache.cache-size", 1);
            Scheduler scheduler = new Scheduler();
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "configService", config);
            set(plugin, FriendlyCore.class, "rtpManager", new RtpManager(plugin) {
                @Override public RtpWorldConfig getWorldConfig(String worldName) { return worldConfig(worldName); }
            });
            List<CompletableFuture<Optional<Location>>> searches = new ArrayList<>();
            RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
                @Override public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config,
                        int attempts, Consumer<Integer> progress, UUID uuid, long delay) {
                    assertEquals(4L, delay);
                    var future = new CompletableFuture<Optional<Location>>();
                    searches.add(future);
                    return future;
                }
            };
            RtpLocationCache cache = new RtpLocationCache(plugin, finder);
            cache.initialize();
            scheduler.repeating.run();
            scheduler.repeating.run();
            assertEquals(2, searches.size(), "Repeated checks must include workers already searching");
            searches.getFirst().complete(Optional.of(new Location(worlds.getFirst(), 0, 64, 0)));
            assertTrue(scheduler.delayed.isEmpty(), "A full cache must not leave a redundant refill task");
            scheduler.repeating.run();
            assertEquals(3, searches.size(), "Only the freed slot can start another world");
            cache.initialize();
            assertTrue(searches.get(1).isCancelled(), "Reload must cancel the old search, not only discard its result");
            assertTrue(searches.get(2).isCancelled());
            scheduler.repeating.run();
            assertEquals(5, searches.size());
            searches.get(1).complete(Optional.empty());
            searches.get(2).complete(Optional.empty());
            scheduler.repeating.run();
            assertEquals(5, searches.size(), "Old completions must not release workers from the new session");

            config.yaml.set("location-cache.cache-size", 2);
            cache.initialize();
            scheduler.repeating.run();
            searches.get(5).complete(Optional.of(new Location(worlds.getFirst(), 0, 64, 0)));
            assertEquals(1, scheduler.delayed.size());
            cache.initialize();
            scheduler.repeating.run();
            int activeSearches = searches.size();
            scheduler.delayed.remove().run();
            scheduler.repeating.run();
            assertEquals(activeSearches, searches.size(), "A stale delayed refill must not release a new worker");
            cache.shutdown();
        } finally {
            serverField.set(null, previousServer);
        }
    }

    @Test
    void cancelledSearchStopsRetriesAndSubmittedChunksKeepTheirAdmissionSlot() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = config();
        config.yaml.set("settings.max-concurrent-searches", 1);
        set(plugin, FriendlyCore.class, "configService", config);
        Scheduler scheduler = new Scheduler();
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        var chunk = new CompletableFuture<Chunk>();
        int[] loads = {0};
        World world = proxy(World.class, (p, method, args) -> {
            assertEquals("getChunkAtAsync", method.getName());
            loads[0]++;
            return chunk;
        });
        RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
            @Override public int[] generateRandomCoordinates(World w, RtpWorldConfig c) { return new int[]{0, 0}; }
        };
        var first = finder.findSafeLocation(world, worldConfig("world"), 25, null, null);
        first.cancel(false);
        assertEquals(Optional.empty(), finder.findSafeLocation(world, worldConfig("world"), 25, null, null).join());
        assertEquals(1, loads[0], "Cancellation cannot release an unfinished native chunk load");
        chunk.completeExceptionally(new IllegalStateException("Load failed"));
        assertTrue(scheduler.delayed.isEmpty(), "Cancelled chunk callback must not queue retries");
        var next = finder.findSafeLocation(world, worldConfig("world"), 25, null, null);
        assertEquals(2, loads[0]);
        next.cancel(false);
        while (!scheduler.delayed.isEmpty()) scheduler.delayed.remove().run();
        assertEquals(2, loads[0], "Cancellation must stop already queued retries");
    }

    @Test
    void timeoutStopsRetriesButDoesNotReleaseAnUnfinishedChunkLoad() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = config();
        config.yaml.set("settings.max-concurrent-searches", 1);
        config.yaml.set("settings.search-timeout-seconds", 1);
        set(plugin, FriendlyCore.class, "configService", config);
        set(plugin, FriendlyCore.class, "scheduler", new Scheduler());
        var chunk = new CompletableFuture<Chunk>();
        World world = proxy(World.class, (p, method, args) -> chunk);
        RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
            @Override public int[] generateRandomCoordinates(World w, RtpWorldConfig c) { return new int[]{0, 0}; }
        };
        var search = finder.findSafeLocation(world, worldConfig("world"), 25, null, null);
        assertEquals(Optional.empty(), search.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(Optional.empty(), finder.findSafeLocation(world, worldConfig("world"), 25, null, null).join());
        chunk.complete(null);
    }

    @Test
    void realSamplerPreservesNarrowRadiusAndRejectsImpossibleBorders() {
        double[] borderSize = {6000};
        WorldBorder border = proxy(WorldBorder.class, (p, method, args) -> switch (method.getName()) {
            case "getSize" -> borderSize[0];
            case "getCenter" -> new Location(null, 0, 0, 0);
            default -> throw new AssertionError(method.getName());
        });
        World world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
            case "getWorldBorder" -> border;
            case "getName" -> "world";
            default -> throw new AssertionError(method.getName());
        });
        RtpLocationFinder finder = new RtpLocationFinder(null, null, null);
        for (RtpWorldConfig.Shape shape : RtpWorldConfig.Shape.values()) {
            for (RtpSpreadingManager.DistributionAlgorithm algorithm : RtpSpreadingManager.DistributionAlgorithm.values()) {
                RtpWorldConfig config = new RtpWorldConfig("world", true, RtpWorldConfig.WorldType.NORMAL,
                        shape, 1000, 1050, 0, 0, List.of(), List.of(), algorithm);
                for (int i = 0; i < 300; i++) {
                    int[] sample = finder.generateRandomCoordinates(world, config);
                    assertNotNull(sample);
                    double x = Math.abs(sample[0] + 0.5), z = Math.abs(sample[1] + 0.5);
                    double distance = shape == RtpWorldConfig.Shape.CIRCLE ? Math.hypot(x, z) : Math.max(x, z);
                    assertTrue(distance >= 1000 && distance <= 1050, "Configured radii must apply to actual block centres");
                }
                borderSize[0] = 100;
                assertNull(finder.generateRandomCoordinates(world, config));
                borderSize[0] = 6000;
            }
        }
    }

    @Test
    void unlistedWorldsStayDisabledAndLateCallbacksCannotFinishANewerLocalRequest() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = config();
        config.yaml.set("worlds.world.enabled", true);
        Scheduler scheduler = new Scheduler();
        set(plugin, FriendlyCore.class, "configService", config);
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        set(plugin, FriendlyCore.class, "cooldownManager", new CooldownManager());
        RtpManager manager = new RtpManager(plugin);
        manager.reload();
        assertNull(manager.getWorldConfig("lobby"));
        assertNotNull(manager.getWorldConfig("WORLD"));
        config.yaml.set("settings.local-enabled", false);
        manager.reload();
        assertNull(manager.getWorldConfig("world"));
        config.yaml.set("settings.local-enabled", true);
        manager.reload();
        World world = proxy(World.class, (p, method, args) -> "world");
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getWorld" -> world;
            case "isOnline" -> true;
            case "hasPermission" -> true;
            default -> throw new AssertionError(method.getName());
        });
        List<CompletableFuture<Optional<Location>>> searches = new ArrayList<>();
        RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
            @Override public CompletableFuture<Optional<Location>> findSafeLocation(World w, RtpWorldConfig c,
                    int attempts, Consumer<Integer> progress, UUID playerId) {
                var future = new CompletableFuture<Optional<Location>>();
                searches.add(future);
                return future;
            }
        };
        set(manager, RtpManager.class, "locationFinder", finder);
        set(manager, RtpManager.class, "locationCache", new RtpLocationCache(plugin, finder));
        set(manager, RtpManager.class, "actionbarAnimator", new RtpActionbarAnimator(plugin));
        manager.startRtp(player, null, false);
        searches.getFirst().complete(Optional.empty()); // Completion is queued on the entity scheduler.
        Runnable retired = scheduler.retired.remove();
        manager.cancelWarmup(player, null, null);
        manager.startRtp(player, null, false);
        var field = RtpManager.class.getDeclaredField("activeSearchTasks");
        field.setAccessible(true);
        Map<?, ?> tasks = (Map<?, ?>) field.get(manager);
        Object newSpinner = tasks.get(uuid);
        scheduler.entity.remove().run();
        retired.run();
        assertSame(newSpinner, tasks.get(uuid));
        var guard = RtpManager.class.getDeclaredField("requestPlayers");
        guard.setAccessible(true);
        assertTrue(((java.util.Set<?>) guard.get(manager)).contains(uuid), "Old callbacks cannot release a newer request's guard");
        manager.cancelWarmup(player, null, null);
        assertTrue(searches.getLast().isCancelled());
    }

    @Test
    void spreadWaitsForLandingCountsFailuresAndContinuesAfterRetirement() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = config();
        Scheduler scheduler = new Scheduler();
        set(plugin, FriendlyCore.class, "configService", config);
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        List<String> counts = new ArrayList<>();
        set(plugin, FriendlyCore.class, "textService", new TextService() {
            @Override public void sendMessage(CommandSender sender, String message, Map<String, String> values) {
                counts.add(values.get("%count%"));
            }
        });
        World world = proxy(World.class, (p, method, args) -> "world");
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID uuid = UUID.randomUUID();
            players.add(proxy(Player.class, (p, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "isOnline" -> true;
                default -> throw new AssertionError(method.getName());
            }));
        }
        List<CompletableFuture<Boolean>> landings = new ArrayList<>();
        RtpManager manager = new RtpManager(plugin) {
            @Override public RtpWorldConfig getWorldConfig(String name) { return worldConfig(name); }
            @Override public CompletableFuture<Boolean> executeTeleportAsync(Player target, Location destination) {
                var landing = new CompletableFuture<Boolean>();
                landings.add(landing);
                return landing;
            }
        };
        RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
            @Override public CompletableFuture<Optional<Location>> findSafeLocation(World w, RtpWorldConfig c,
                    int attempts, Consumer<Integer> progress, UUID uuid) {
                return CompletableFuture.completedFuture(Optional.of(new Location(w, 1000, 64, 1000)));
            }
        };
        set(manager, RtpManager.class, "locationFinder", finder);
        var spread = RtpManager.class.getDeclaredMethod("spreadNextPlayer", CommandSender.class, List.class,
                int.class, World.class, RtpWorldConfig.class, int.class, int.class);
        spread.setAccessible(true);
        CommandSender sender = proxy(CommandSender.class, (p, method, args) -> null);
        spread.invoke(manager, sender, players, 0, world, worldConfig("world"), 25, 0);
        scheduler.entity.remove().run();
        scheduler.retired.remove();
        assertEquals(1, landings.size());
        assertTrue(scheduler.global.isEmpty(), "Do not advance until teleport completion");
        landings.getFirst().complete(false);
        scheduler.global.remove().run();
        scheduler.entity.remove();
        scheduler.retired.remove().run(); // Second player disconnected before its scheduled callback.
        scheduler.global.remove().run();
        scheduler.entity.remove().run();
        landings.getLast().complete(true);
        scheduler.global.remove().run();
        assertEquals(List.of("1"), counts);
    }

    @Test
    void configuredHubDisablesLocalWorldsWithoutDisablingTheRtpModule() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = config();
        config.yaml.set("worlds.world.enabled", true);
        config.yaml.set("join-spawn.last-location.hub-servers", List.of("lobby"));
        set(plugin, FriendlyCore.class, "configService", config);
        SpawnManager spawn = allocate(JoinProbe.class);
        set(plugin, FriendlyCore.class, "spawnManager", spawn);
        set(plugin, FriendlyCore.class, "redisService", new RedisService(null) {
            @Override public String getServerId() { return "LOBBY"; }
        });
        RtpManager manager = new RtpManager(plugin);
        manager.reload();
        assertNull(manager.getWorldConfig("world"));
        config.yaml.set("settings.local-enabled", true);
        manager.reload();
        assertTrue(manager.getWorldConfig("world").isEnabled());
    }

    @Test
    void landingReservationsCheckCurrentPlayersRecentLandingsAndConcurrentDestinations() throws Exception {
        RtpSpreadingManager spreading = allocate(RtpSpreadingManager.class);
        set(spreading, RtpSpreadingManager.class, "enabled", true);
        set(spreading, RtpSpreadingManager.class, "minPlayerDistance", 200.0);
        set(spreading, RtpSpreadingManager.class, "minPlayerDistanceSq", 40_000.0);
        set(spreading, RtpSpreadingManager.class, "minRecentDistance", 250.0);
        set(spreading, RtpSpreadingManager.class, "minRecentDistanceSq", 62_500.0);
        set(spreading, RtpSpreadingManager.class, "recentCacheDurationMs", 600_000L);
        set(spreading, RtpSpreadingManager.class, "recentTeleports", new ConcurrentHashMap<>());
        set(spreading, RtpSpreadingManager.class, "playerPositions", new ConcurrentHashMap<>());
        set(spreading, RtpSpreadingManager.class, "landingReservations", new ConcurrentHashMap<>());
        UUID worldId = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        World world = proxy(World.class, (p, method, args) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "world";
            default -> throw new AssertionError(method.getName());
        });
        Location target = new Location(world, 1000, 64, 1000);
        Location nearby = new Location(world, 1100, 64, 1000);
        assertTrue(spreading.reserveLanding(first, target));
        assertFalse(spreading.reserveLanding(second, nearby));
        spreading.releaseLanding(first, target.clone());
        assertFalse(spreading.reserveLanding(second, nearby), "An older/different reservation cannot release the current one");
        spreading.releaseLanding(first, target);
        assertTrue(spreading.reserveLanding(second, nearby));
        spreading.releaseLanding(second, nearby);
        Player player = proxy(Player.class, (p, method, args) -> second);
        spreading.onMove(new org.bukkit.event.player.PlayerMoveEvent(player, new Location(world, 5000, 64, 5000), nearby));
        assertFalse(spreading.reserveLanding(first, target), "Players moving in during warmup must be rechecked");
        spreading.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
        spreading.recordTeleport(target);
        assertFalse(spreading.reserveLanding(second, nearby));
        assertTrue(spreading.reserveLanding(second, new Location(world, 5000, 64, 5000)));
    }

    private static RtpWorldConfig worldConfig(String name) {
        return new RtpWorldConfig(name, true, RtpWorldConfig.WorldType.NORMAL, RtpWorldConfig.Shape.CIRCLE,
                150, 3000, 0, 0, List.of(), List.of());
    }

    private static class Config extends ConfigService {
        YamlConfiguration yaml;
        Config() { super(null); }
        @Override public FileConfiguration getSpawnConfig() { return yaml; }
        @Override public FileConfiguration getRtpConfig() { return yaml; }
        @Override public FileConfiguration getMessages() { return yaml; }
    }

    private static Config config() throws Exception {
        Config config = allocate(Config.class);
        config.yaml = new YamlConfiguration();
        return config;
    }

    private static class JoinProbe extends SpawnManager {
        @Override public boolean hasPendingAirborneSpawn(Player player) { return false; }
        int clears;
        JoinProbe() { super(null); }
        @Override public SpawnData getSpawnData() { return null; }
        @Override public void clearPendingRespawn(Player player) { clears++; }
        @Override public boolean hasPendingRespawn(Player player) { return false; }
    }

    private static class Scheduler extends FriendlyScheduler {
        final ArrayDeque<Runnable> async = new ArrayDeque<>(), entity = new ArrayDeque<>(), delayed = new ArrayDeque<>();
        final ArrayDeque<Runnable> retired = new ArrayDeque<>(), global = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();
        Runnable repeating;
        boolean inAsync;
        Scheduler() { super(null); }
        @Override public Object runAsync(Runnable action) { async.add(action); return action; }
        @Override public Object runEntity(Entity target, Runnable action) { entity.add(action); return action; }
        @Override public Object runEntity(Entity target, Runnable action, Runnable onRetired) {
            entity.add(action); retired.add(onRetired); return action;
        }
        @Override public Object runEntityTimer(Entity target, Runnable action, long delay, long period) { return new Object(); }
        @Override public Object runGlobal(Runnable action) { global.add(action); return action; }
        @Override public Object runLocation(Location location, Runnable action) { action.run(); return action; }
        @Override public Object runGlobalLater(Runnable action, long ticks) { delayed.add(action); delays.add(ticks); return action; }
        @Override public Object runGlobalTimer(Runnable action, long delay, long period) { repeating = action; return action; }
        @Override public void cancel(Object task) {}
        void runAsyncTask() {
            inAsync = true;
            try { async.remove().run(); } finally { inAsync = false; }
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
