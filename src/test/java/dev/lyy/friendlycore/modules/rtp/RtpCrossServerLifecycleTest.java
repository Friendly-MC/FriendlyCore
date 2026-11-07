package dev.lyy.friendlycore.modules.rtp;

import com.google.gson.Gson;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.*;
import dev.lyy.friendlycore.common.redis.*;
import dev.lyy.friendlycore.modules.economy.*;
import org.bukkit.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

class RtpCrossServerLifecycleTest {
    @Test
    void returnedSourceJoinCancelsUnchargedRtpAndSuppressesLastLocationForwarding() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.origin.service.cancelPendingRequest(net.uuid);
            assertEquals("TRANSFERRING", net.read(request.id()).phase());
            net.origin.service.handleArrival(net.origin.player);
            assertEquals("FAILED", net.read(request.id()).phase());
            assertTrue(net.origin.service.isReturnArrival(net.uuid));
            var locations = new dev.lyy.friendlycore.modules.spawn.LastLocationService(net.origin.plugin, null);
            var inbound = locations.getClass().getDeclaredMethod("isInboundTeleport", UUID.class, boolean.class, boolean.class);
            inbound.setAccessible(true);
            assertEquals(true, inbound.invoke(locations, net.uuid, false, false));
            net.origin.service.clearReturnArrival(net.destination.player);
            assertTrue(net.origin.service.isReturnArrival(net.uuid), "An old connection's quit must not clear this return");
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertTrue(net.origin.manager.beginRequest(net.uuid));
            assertEquals(100, net.wallet.getBalance());
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals(0, net.destination.economy.withdrawals);
            net.origin.service.clearReturnArrival(net.origin.player);
            assertFalse(net.origin.service.isReturnArrival(net.uuid));
            assertEquals(false, inbound.invoke(locations, net.uuid, false, false));
        }
    }
    @Test
    void pacedDestinationHoldsRequestsUntilReleasedAndAFullDestinationSaysSo() throws Exception {
        try (Network net = new Network()) {
            net.destination.config.rtp.set("cross-server.arrivals-per-second", 4);
            net.destination.service.reload();
            var request = net.request();
            net.use(net.destination);
            net.destination.service.handleIncomingRequest(net.origin.redis.packets.getLast());
            assertEquals(0, net.destination.manager.searches, "queued, not searched yet");
            assertEquals("SEARCHING", net.read(request.id()).phase());
            net.destination.scheduler.globalTimer.run();
            assertEquals(1, net.destination.manager.searches);
        }
        try (Network net = new Network()) {
            net.destination.config.rtp.set("cross-server.max-players", 1);
            net.destination.service.reload();
            var request = net.request();
            net.use(net.destination);
            net.destination.service.handleIncomingRequest(net.origin.redis.packets.getLast());
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(0, net.destination.manager.searches);
            net.use(net.origin);
            net.origin.service.handleIncomingFailure(net.destination.redis.packets.getLast());
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertTrue(net.origin.actionbars.getLast().contains("is full"), net.origin.actionbars.toString());
            assertEquals(100, net.wallet.getBalance());
        }
    }

    @Test
    void remoteRequestWithoutAWorldCanSelectAnEndOnlyDestination() throws Exception {
        try (Network net = new Network(World.Environment.THE_END)) {
            var request = net.prepare(net.requestWithoutWorld());
            assertEquals(1, net.destination.manager.searches);
            assertEquals("READY", net.read(request.id()).phase());
        }
    }
    @Test
    void arrivalWaitsForSynchronizationWithoutClaimingChargingOrLosingItsTicket() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.use(net.destination);
            boolean[] locked = {true};
            var hooks = allocate(dev.lyy.friendlycore.common.hooks.HookManager.class);
            var bridge = new dev.lyy.friendlycore.common.hooks.McDataBridgeHook(null) {
                @Override public void checkHook() {}
                @Override public boolean isPlayerLocked(Player player) { return locked[0]; }
            };
            set(hooks, dev.lyy.friendlycore.common.hooks.HookManager.class, "mcDataBridgeHook", bridge);
            set(net.destination.plugin, FriendlyCore.class, "hookManager", hooks);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals("TRANSFERRING", net.read(request.id()).phase());
            assertNotNull(net.destination.service.getTicket(net.uuid));
            assertEquals(0, net.destination.economy.withdrawals);
            assertEquals(0, net.destination.manager.teleports);
            Runnable retry = net.destination.scheduler.entityLater;
            retry.run();
            assertEquals(0, net.destination.manager.teleports);
            locked[0] = false;
            retry.run();
            retry.run();
            assertEquals(1, net.destination.manager.teleports);
            assertEquals(1, net.destination.economy.withdrawals);
        }
    }

    @Test
    void forwardingOnlyLobbyRoutesPlainRtpWithoutSearchingAndRejectsSelfRouting() throws Exception {
        try (Network net = new Network()) {
            net.origin.config.rtp.set("settings.local-enabled", false);
            net.origin.config.rtp.set("cross-server.target-servers", List.of("origin", "destination"));
            net.origin.service.reload();
            RtpManager manager = new RtpManager(net.origin.plugin);
            manager.reload();
            set(manager, RtpManager.class, "crossServerService", net.origin.service);
            set(manager, RtpManager.class, "actionbarAnimator", net.origin.manager.getActionbarAnimator());
            set(net.origin.plugin, FriendlyCore.class, "rtpManager", manager);
            manager.startRtp(net.origin.player, null, false);
            assertTrue(net.origin.service.hasPendingRequest(net.uuid));
            assertEquals("destination", net.origin.redis.packets.getLast().getExtra().split("\\|")[0]);
            assertEquals(0, net.origin.manager.searches);
            assertEquals(0, net.origin.economy.withdrawals);
            net.origin.service.cancelPendingRequest(net.uuid);
            net.origin.service.requestCrossServerRtp(net.origin.player, "origin", null);
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertTrue(manager.beginRequest(net.uuid));
        }
    }

    @Test
    void remoteCancellationStopsDestinationSearchAndDuplicateRequestsDoNotStartAnother() throws Exception {
        try (Network net = new Network()) {
            var request = net.request();
            var pending = new CompletableFuture<Optional<Location>>();
            int[] searches = {0};
            set(net.destination.manager, RtpManager.class, "locationFinder", new RtpLocationFinder(net.destination.plugin, null, null) {
                @Override public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config,
                        int attempts, java.util.function.Consumer<Integer> progress, UUID playerUuid) {
                    searches[0]++;
                    return pending;
                }
            });
            net.use(net.destination);
            CrossServerPacket packet = net.origin.redis.packets.getLast();
            net.destination.service.handleIncomingRequest(packet);
            net.destination.service.handleIncomingRequest(packet);
            assertEquals(1, searches[0]);
            net.write(request.withPhase("FAILED", request.expiresAt()));
            net.destination.scheduler.cleanup.run();
            assertTrue(pending.isCancelled());
            assertNull(net.destination.service.getTicket(net.uuid));
            assertEquals(100, net.wallet.getBalance());
        }
    }

    @Test
    void cancellationInvalidatesArrivalAndNeverChargesOrRefunds() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            assertTrue(net.origin.service.hasPendingRequest(net.uuid), "Reply must retain origin ownership");
            assertFalse(net.origin.manager.beginRequest(net.uuid), "Local search must share the remote guard");
            net.origin.service.cancelPendingRequest(net.uuid);
            assertEquals("FAILED", net.read(request.id()).phase());
            net.use(net.destination);
            assertNull(net.destination.service.getTicket(net.uuid));
            net.destination.service.handleArrival(net.destination.player);
            assertEquals(100, net.wallet.getBalance());
            assertEquals(0, net.destination.economy.withdrawals + net.destination.economy.deposits);
        }
    }

    @Test
    void staleAndWrongServerRepliesCannotClaimANewerRequest() throws Exception {
        try (Network net = new Network()) {
            var old = net.request();
            net.origin.service.cancelPendingRequest(net.uuid);
            var current = net.request();
            net.origin.service.handleIncomingReply(net.reply(old.id(), "destination"));
            net.origin.service.handleIncomingReply(net.reply(current.id(), "unrelated"));
            assertEquals(0, net.origin.manager.warmups);
            assertEquals("SEARCHING", net.read(current.id()).phase());
            net.origin.service.handleIncomingReply(net.reply(current.id(), "destination"));
            net.origin.service.handleIncomingReply(net.reply(current.id(), "destination"));
            assertEquals(1, net.origin.manager.warmups);
        }
    }

    @Test
    void costBypassTimeoutAndDisabledDestinationCannotMintMoney() throws Exception {
        try (Network net = new Network()) {
            net.bypassCost = true;
            var request = net.request();
            net.write(request.withPhase("SEARCHING", 1));
            net.origin.scheduler.cleanup.run();
            assertEquals(0, net.origin.economy.withdrawals + net.origin.economy.deposits);
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            request = net.request();
            net.destination.config.rtp.set("cross-server.enabled", false);
            net.destination.service.reload();
            net.use(net.destination);
            net.destination.service.handleIncomingRequest(net.origin.redis.packets.getLast());
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(0, net.destination.manager.searches);
            assertEquals(100, net.wallet.getBalance());
        }
    }

    @Test
    void successfulArrivalChargesOriginPriceOnceAndOriginQuitDoesNotCancelHandoff() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            assertEquals("TRANSFERRING", net.read(request.id()).phase());
            net.origin.service.cancelPendingRequest(net.uuid); // Normal proxy quit.
            assertEquals("TRANSFERRING", net.read(request.id()).phase());
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals(50, net.wallet.getBalance(), "Destination config cost is 999; origin price is 50");
            assertEquals(1, net.destination.economy.withdrawals);
            assertEquals(1, net.destination.manager.teleports);
            net.destination.manager.landing.complete(true);
            assertEquals("SUCCEEDED", net.read(request.id()).phase());
            net.origin.redis.packets.clear(); // Completion does not depend on a Pub/Sub acknowledgement.
            net.use(net.origin);
            net.origin.scheduler.cleanup.run();
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertTrue(net.origin.manager.beginRequest(net.uuid));
            assertEquals(0, net.origin.economy.deposits);
        }
    }

    @Test
    void failedLandingRefundSurvivesDisconnectRestartAndDifferentBackendPolicy() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals(50, net.wallet.getBalance());
            net.online = false;
            net.destination.manager.landing.complete(false);
            assertEquals("REFUND_PENDING", net.read(request.id()).phase());
            net.destination.service.shutdown();
            Node recovery = net.node("recovery", 999, false);
            net.online = true;
            net.use(recovery);
            recovery.service.handleArrival(recovery.player);
            recovery.service.collectRefunds(recovery.player);
            recovery.service.collectRefunds(recovery.player);
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(100, net.wallet.getBalance());
            assertEquals(1, recovery.economy.deposits);
        }
    }

    @Test
    void redisOutageAfterDebitRetainsOutcomeWithoutRepeatingWithdrawal() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            net.redisUnavailable = true;
            net.destination.manager.landing.complete(false);
            net.redisUnavailable = false;
            net.destination.scheduler.cleanup.run();
            net.destination.scheduler.cleanup.run();
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(1, net.destination.economy.withdrawals);
            assertEquals(1, net.destination.economy.deposits);
            assertEquals(100, net.wallet.getBalance());
        }
    }

    @Test
    void insufficientArrivalBalanceDoesNotTeleportAndBypassNeverRefunds() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.wallet.setBalance(1);
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(0, net.destination.manager.teleports);
            assertEquals(0, net.destination.economy.deposits);
        }
        try (Network net = new Network()) {
            net.bypassCost = true;
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            net.destination.manager.landing.complete(false);
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(0, net.destination.economy.withdrawals + net.destination.economy.deposits);
        }
    }

    @Test
    void failedProxySendAndExpiredTicketsReleaseOwnershipWithoutCharges() throws Exception {
        try (Network net = new Network()) {
            var request = net.prepare();
            net.failProxy = true;
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            assertEquals("FAILED", net.read(request.id()).phase());
            net.origin.scheduler.cleanup.run();
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertEquals(100, net.wallet.getBalance());
        }
        try (Network net = new Network()) {
            var request = net.prepare();
            net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
            net.write(net.read(request.id()).withPhase("TRANSFERRING", 1));
            net.use(net.destination);
            net.destination.service.handleArrival(net.destination.player);
            assertEquals("FAILED", net.read(request.id()).phase());
            assertEquals(0, net.destination.economy.withdrawals);
        }
    }

    @Test
    void nativeChunkAndSafetyFailuresCompleteAndRefundTheActualCharge() throws Exception {
        for (boolean chunkFailure : List.of(true, false)) {
            try (Network net = new Network()) {
                var request = net.prepare();
                net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
                net.destination.manager.nativeTeleport = true;
                net.chunkFailure = chunkFailure;
                net.use(net.destination);
                net.destination.service.handleArrival(net.destination.player);
                net.destination.service.collectRefunds(net.destination.player);
                assertEquals("FAILED", net.read(request.id()).phase());
                assertEquals(100, net.wallet.getBalance());
                assertEquals(1, net.destination.economy.withdrawals);
                assertEquals(1, net.destination.economy.deposits);
            }
        }
    }

    @Test
    void lateWarmupTimerCannotConnectAfterMovementCancellation() throws Exception {
        try (Network net = new Network()) {
            net.origin.manager.nativeWarmup = true;
            var request = net.prepare();
            assertTrue(net.origin.manager.isWarmupActive(net.uuid));
            Runnable timer = net.origin.scheduler.entityTimer;
            var listener = new RtpListener(net.origin.plugin, net.origin.manager);
            listener.onPlayerMove(new org.bukkit.event.player.PlayerMoveEvent(net.origin.player,
                    new Location(net.world, 0, 64, 0), new Location(net.world, 5, 64, 0)));
            for (int i = 0; i < 35; i++) timer.run();
            assertEquals("FAILED", net.read(request.id()).phase());
            assertFalse(net.origin.manager.isWarmupActive(net.uuid));
            assertFalse(net.origin.service.hasPendingRequest(net.uuid));
            assertEquals(0, net.proxyMessages);
            assertEquals(100, net.wallet.getBalance());
        }
    }

    @Test
    void proxySendWaitsForOriginAccountSaveAndSaveFailurePreventsTransfer() throws Exception {
        for (boolean failSave : List.of(false, true)) {
            try (Network net = new Network()) {
                var request = net.prepare();
                net.origin.economy.save = new CompletableFuture<>();
                net.origin.service.sendPlayerToProxyServer(net.origin.player, "destination");
                assertEquals(0, net.proxyMessages);
                if (failSave) net.origin.economy.save.completeExceptionally(new IllegalStateException("Database unavailable"));
                else net.origin.economy.save.complete(null);
                assertEquals(failSave ? 0 : 1, net.proxyMessages);
                assertEquals(failSave ? "FAILED" : "TRANSFERRING", net.read(request.id()).phase());
                assertEquals(100, net.wallet.getBalance());
            }
        }
    }

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static class Config extends ConfigService {
        YamlConfiguration rtp, messages;
        Config() { super(null); } // Allocated without invoking a disk-backed plugin constructor.
        @Override public FileConfiguration getRtpConfig() { return rtp; }
        @Override public FileConfiguration getMessages() { return messages; }
    }

    private static class Scheduler extends FriendlyScheduler {
        Runnable cleanup;
        Runnable entityTimer;
        Runnable entityLater;
        Runnable globalTimer;
        Scheduler() { super(null); }
        @Override public Object runGlobalTimer(Runnable action, long delay, long period) { globalTimer = action; return new Object(); }
        @Override public Object runAsync(Runnable action) { action.run(); return new Object(); }
        @Override public Object runGlobal(Runnable action) { action.run(); return new Object(); }
        @Override public Object runLocation(Location location, Runnable action) { action.run(); return new Object(); }
        @Override public Object runEntity(Entity entity, Runnable action) { action.run(); return new Object(); }
        @Override public Object runEntity(Entity entity, Runnable action, Runnable retired) { action.run(); return new Object(); }
        @Override public Object runEntityLater(Entity entity, Runnable action, long delay) {
            entityLater = action; return new Object();
        }
        @Override public Object runAsyncTimer(Runnable action, long delay, long period) { cleanup = action; return new Object(); }
        @Override public Object runEntityTimer(Entity entity, Runnable action, long delay, long period) {
            entityTimer = action; return new Object();
        }
        @Override public void cancel(Object task) {}
    }

    private static class Economy extends EconomyManager {
        Network network;
        Node node;
        int withdrawals, deposits;
        CompletableFuture<Void> save = CompletableFuture.completedFuture(null);
        Economy() { super(null); }
        @Override public AccountData getCachedAccount(UUID uuid) { return network.wallet; }
        @Override public CompletableFuture<AccountData> loadPlayerAccount(UUID uuid, String name) {
            return CompletableFuture.completedFuture(network.wallet);
        }
        @Override public CompletableFuture<Void> savePlayerAccount(UUID uuid) {
            return save;
        }
        @Override public boolean isOnlineLocally(UUID uuid) { return network.online && network.current == node; }
        @Override public boolean withdraw(UUID uuid, double amount, boolean broadcast) {
            assertTrue(isOnlineLocally(uuid)); withdrawals++;
            return network.wallet.withdraw(amount);
        }
        @Override public boolean deposit(UUID uuid, double amount, boolean broadcast) {
            assertTrue(isOnlineLocally(uuid)); deposits++;
            return network.wallet.deposit(amount);
        }
    }

    private static class Manager extends RtpManager {
        int warmups, searches, teleports;
        boolean nativeTeleport, nativeWarmup;
        CompletableFuture<Boolean> landing = new CompletableFuture<>();
        Manager(FriendlyCore plugin) { super(plugin); }
        @Override public void startCrossServerWarmup(Player player, String server) {
            warmups++;
            if (nativeWarmup) super.startCrossServerWarmup(player, server);
        }
        @Override public long getRemainingCooldownSeconds(UUID uuid) { return 0; }
        @Override public RtpWorldConfig getWorldConfig(String name) {
            return new RtpWorldConfig(name, true, RtpWorldConfig.WorldType.NORMAL, RtpWorldConfig.Shape.SQUARE,
                    100, 5000, 0, 0, List.of(), List.of());
        }
        @Override public CompletableFuture<Boolean> executeTeleportAsync(Player player, Location destination) {
            teleports++;
            return nativeTeleport ? super.executeTeleportAsync(player, destination) : landing;
        }
    }

    private static class Node {
        final FriendlyCore plugin;
        final Config config;
        final Scheduler scheduler = new Scheduler();
        final Manager manager;
        final Economy economy;
        final Redis redis;
        final RtpCrossServerService service;
        final Player player;
        final List<String> actionbars = new ArrayList<>();
        Node(Network network, String server, double cost, boolean refund) throws Exception {
            plugin = allocate(FriendlyCore.class);
            config = allocate(Config.class);
            config.rtp = new YamlConfiguration(); config.messages = new YamlConfiguration();
            config.rtp.set("settings.economy.enabled", true);
            config.rtp.set("settings.economy.cost", cost);
            config.rtp.set("settings.economy.refund-on-cancel", refund);
            config.rtp.set("settings.warmup-seconds", 3);
            config.rtp.set("location-cache.enabled", false);
            economy = allocate(Economy.class); economy.network = network; economy.node = this;
            economy.save = CompletableFuture.completedFuture(null);
            redis = new Redis(network, server);
            manager = new Manager(plugin);
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> network.uuid;
                        case "getWorld" -> network.world;
                        case "getName" -> "Steve";
                        case "isOnline", "isValid" -> network.online;
                        case "hasPermission" -> network.bypassCost && args[0].equals("friendlycore.rtp.bypass.cost");
                        case "getLocation" -> new Location(network.world, 0, 64, 0);
                        case "sendPluginMessage" -> {
                            if (network.failProxy) throw new IllegalStateException("Proxy unavailable");
                            if (args[1].equals("BungeeCord")) network.proxyMessages++;
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "configService", config);
            set(plugin, FriendlyCore.class, "rtpManager", manager);
            set(plugin, FriendlyCore.class, "economyManager", economy);
            set(plugin, FriendlyCore.class, "redisService", redis);
            set(plugin, FriendlyCore.class, "cooldownManager", new CooldownManager());
            set(plugin, FriendlyCore.class, "numberFormatter", new NumberFormatter());
            set(plugin, FriendlyCore.class, "textService", new TextService() {
                @Override public void sendActionBar(Player player, String message, Map<String, String> placeholders) {
                    actionbars.add(message);
                }
                @Override public void sendMessage(org.bukkit.command.CommandSender player, String message, Map<String, String> placeholders) {}
            });
            Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
            set(plugin, JavaPlugin.class, "logger", logger);
            RtpLocationFinder finder = new RtpLocationFinder(plugin, null, null) {
                @Override public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config,
                        int attempts, java.util.function.Consumer<Integer> progress, UUID uuid) {
                    manager.searches++;
                    return CompletableFuture.completedFuture(Optional.of(new Location(world, -16.5, 64, -32.5)));
                }
            };
            set(manager, RtpManager.class, "locationFinder", finder);
            set(manager, RtpManager.class, "safetyValidator", new RtpSafetyValidator(plugin) {
                @Override public boolean isLocationSafe(Location location, Chunk chunk, RtpWorldConfig.WorldType type) {
                    throw new IllegalStateException("Protection hook failed");
                }
            });
            set(manager, RtpManager.class, "locationCache", new RtpLocationCache(plugin, finder));
            set(manager, RtpManager.class, "actionbarAnimator", new RtpActionbarAnimator(plugin) {
                @Override public void renderFailed(Player player) {}
                @Override public void renderWarmup(Player player, int elapsed, int total) {}
                @Override public void renderCancelled(Player player, String message) {}
            });
            manager.reload();
            service = new RtpCrossServerService(plugin);
            set(manager, RtpManager.class, "crossServerService", service);
        }
    }

    // Stub only Redis atomic primitives and native surfaces; request, billing and reconciliation code is production.
    private static class Redis extends RedisService {
        final Network network;
        final String server;
        final List<CrossServerPacket> packets = new ArrayList<>();
        final UnifiedJedis jedis;
        Redis(Network network, String server) {
            super(null); this.network = network; this.server = server;
            jedis = new UnifiedJedis(new redis.clients.jedis.Connection()) {
                private void available() { if (network.redisUnavailable) throw new IllegalStateException("Redis unavailable"); }
                @Override public String get(String key) { available(); return network.values.get(key); }
                @Override public String set(String key, String value, SetParams params) {
                    available(); return network.values.putIfAbsent(key, value) == null ? "OK" : null;
                }
                @Override public Object eval(String script, List<String> keys, List<String> args) {
                    available();
                    return network.values.replace(keys.getFirst(), args.get(0), args.get(1)) ? 1L : 0L;
                }
                @Override public long sadd(String key, String... members) {
                    available(); Collections.addAll(network.sets.computeIfAbsent(key, ignored -> new HashSet<>()), members); return 1;
                }
                @Override public Set<String> smembers(String key) {
                    available(); return Set.copyOf(network.sets.getOrDefault(key, Set.of()));
                }
                @Override public long srem(String key, String... members) {
                    available(); for (String member : members) network.sets.getOrDefault(key, new HashSet<>()).remove(member); return 1;
                }
            };
        }
        @Override public boolean isEnabled() { return true; }
        @Override public String getServerId() { return server; }
        @Override public UnifiedJedis getRedisClient() { return jedis; }
        @Override public void publish(CrossServerPacket packet) { packets.add(packet); }
    }

    private static class Network implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final AccountData wallet = new AccountData(uuid, "Steve", 100);
        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Set<String>> sets = new HashMap<>();
        final List<Node> nodes = new ArrayList<>();
        final Field serverField = Bukkit.class.getDeclaredField("server");
        final Object previousServer;
        final World world;
        final Node origin, destination;
        Node current;
        int proxyMessages;
        boolean online = true, bypassCost, failProxy, redisUnavailable, chunkFailure;
        Network() throws Exception {
            this(World.Environment.NORMAL);
        }
        Network(World.Environment environment) throws Exception {
            world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getName" -> "world";
                        case "getEnvironment" -> environment;
                        case "getUID" -> new UUID(0, 0);
                        case "getChunkAtAsync" -> chunkFailure
                                ? CompletableFuture.failedFuture(new IllegalStateException("Chunk load failed"))
                                : CompletableFuture.completedFuture((Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(),
                                        new Class<?>[]{Chunk.class}, (chunk, operation, arguments) -> null));
                        default -> throw new AssertionError("Manual chunk access must be unnecessary: " + method.getName());
                    });
            serverField.setAccessible(true); previousServer = serverField.get(null);
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getWorld" -> world;
                        case "getWorlds" -> List.of(world);
                        case "getPlayer" -> current == null || !online ? null : current.player;
                        case "getOnlinePlayers" -> current == null || !online ? List.of() : List.of(current.player);
                        case "getPluginManager" -> Proxy.newProxyInstance(org.bukkit.plugin.PluginManager.class.getClassLoader(),
                                new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (pm, pmMethod, pmArgs) -> null);
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
            origin = node("origin", 50, true);
            destination = node("destination", 999, false);
            use(origin);
        }
        Node node(String server, double cost, boolean refund) throws Exception {
            Node node = new Node(this, server, cost, refund); nodes.add(node); return node;
        }
        void use(Node node) { current = node; }
        RtpCrossServerService.Transfer request() {
            use(origin);
            origin.service.requestCrossServerRtp(origin.player, "destination", "world");
            var packet = origin.redis.packets.getLast();
            return read(UUID.fromString(packet.getExtra().split("\\|")[1]));
        }
        RtpCrossServerService.Transfer prepare() {
            return prepare(request());
        }
        RtpCrossServerService.Transfer requestWithoutWorld() {
            use(origin);
            origin.service.requestCrossServerRtp(origin.player, "destination", null);
            var packet = origin.redis.packets.getLast();
            return read(UUID.fromString(packet.getExtra().split("\\|")[1]));
        }
        RtpCrossServerService.Transfer prepare(RtpCrossServerService.Transfer request) {
            use(destination);
            destination.service.handleIncomingRequest(origin.redis.packets.getLast());
            use(origin);
            origin.service.handleIncomingReply(destination.redis.packets.getLast());
            return request;
        }
        CrossServerPacket reply(UUID id, String sender) {
            return new CrossServerPacket(sender, CrossServerPacket.Action.RTP_LOCATION_REPLY, uuid, "Steve", 0, "origin|" + id);
        }
        RtpCrossServerService.Transfer read(UUID id) {
            return new Gson().fromJson(values.get("friendlycore:rtp:request:" + id), RtpCrossServerService.Transfer.class);
        }
        void write(RtpCrossServerService.Transfer transfer) {
            values.put("friendlycore:rtp:request:" + transfer.id(), new Gson().toJson(transfer));
        }
        @Override public void close() throws Exception {
            serverField.set(null, previousServer);
            for (Node node : nodes) node.redis.jedis.close();
        }
    }
}
