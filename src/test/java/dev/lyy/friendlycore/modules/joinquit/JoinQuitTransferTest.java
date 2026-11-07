package dev.lyy.friendlycore.modules.joinquit;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.ProxyTransferUtil;
import dev.lyy.friendlycore.common.TransferFreezeService;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.common.redis.RedisService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class JoinQuitTransferTest {
    @Test
    void spawnOverworldSpawnAnnouncesOnlyNetworkEntryAndExit() throws Exception {
        try (Runtime runtime = new Runtime()) {
            set(runtime.manager, JoinQuitManager.class, "suppressVanilla", true);
            runtime.join();
            assertEquals(1, runtime.messages.size());
            assertEquals(CrossServerPacket.Action.NETWORK_JOIN, runtime.redis.packets.getFirst().getAction());
            runtime.quit();
            assertEquals(1, runtime.messages.size(), "Quit must wait for transfer detection");
            runtime.redis.server = "overworld";
            runtime.join();
            runtime.quit();
            runtime.redis.server = "spawn";
            runtime.join();
            runtime.scheduler.flushQuits();
            assertEquals(1, runtime.messages.size(), "Backend switches must stay silent locally");
            assertEquals(1, runtime.redis.packets.size(), "Backend switches must stay silent remotely");
            runtime.quit();
            runtime.scheduler.flushQuits();
            assertEquals(2, runtime.messages.size());
            assertEquals(CrossServerPacket.Action.NETWORK_QUIT, runtime.redis.packets.getLast().getAction());
        }
    }

    @Test
    void disabledMessagesStillTrackTransfersAndOlderQuitCannotClaimNewerQuit() throws Exception {
        try (Runtime runtime = new Runtime()) {
            set(runtime.manager, JoinQuitManager.class, "suppressVanilla", true);
            set(runtime.manager, JoinQuitManager.class, "quitEnabled", false);
            runtime.quit();
            runtime.redis.server = "overworld";
            runtime.join();
            assertTrue(runtime.messages.isEmpty());
            assertTrue(runtime.redis.packets.isEmpty());
            set(runtime.manager, JoinQuitManager.class, "joinEnabled", false);
            set(runtime.manager, JoinQuitManager.class, "quitEnabled", true);
            runtime.quit();
            runtime.join();
            runtime.scheduler.flushQuits();
            assertTrue(runtime.redis.packets.isEmpty(), "Disabled joins must still cancel pending quits");
            runtime.quit();
            Runnable olderQuit = runtime.scheduler.quits.removeFirst();
            runtime.quit();
            olderQuit.run();
            assertTrue(runtime.redis.packets.isEmpty(), "Older quit must not claim the newer token");
            runtime.scheduler.flushQuits();
            assertEquals(1, runtime.redis.packets.size());
        }
    }

    @Test
    void standaloneServerKeepsImmediateLocalMessages() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.redis.enabled = false;
            runtime.join(); runtime.quit();
            assertEquals(2, runtime.messages.size());
            assertTrue(runtime.redis.packets.isEmpty());
        }
    }

    @Test
    void silentStaffAnnouncementsUseTheSameTransferGate() throws Exception {
        try (Runtime runtime = new Runtime()) {
            set(runtime.manager, JoinQuitManager.class, "suppressVanilla", true);
            runtime.silent = true;
            runtime.join();
            assertEquals(List.of("staff joined"), runtime.messages);
            runtime.quit(); runtime.redis.server = "overworld"; runtime.join();
            runtime.scheduler.flushQuits();
            assertEquals(1, runtime.messages.size());
            runtime.quit(); runtime.scheduler.flushQuits();
            assertEquals(List.of("staff joined", "staff quit"), runtime.messages);
        }
    }

    @Test
    void networkModeSuppressesVanillaEvenWhenLocalSuppressionIsOff() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); runtime.quit();
            assertEquals(1, runtime.messages.size());
        }
    }

    @Test
    void proxyMarkerExistsBeforeConnectAndSlowArrivalDoesNotAnnounceQuit() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join();
            runtime.scheduler.paused = true;
            ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld");
            assertTrue(runtime.proxyMessages.isEmpty(), "Do not send Connect before Redis completes");
            runtime.scheduler.resume();
            assertEquals(1, runtime.proxyMessages.size());
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(runtime.proxyMessages.getFirst()))) {
                assertEquals("Connect", input.readUTF()); assertEquals("overworld", input.readUTF());
            }
            runtime.quit();
            runtime.scheduler.flushQuits();
            assertEquals(1, runtime.messages.size(), "The old three-second window must not announce a transfer quit");
            assertEquals(1, runtime.scheduler.transferQuits.size());
            runtime.redis.server = "overworld"; runtime.join();
            runtime.scheduler.flushTransferQuits();
            assertEquals(1, runtime.messages.size()); assertEquals(1, runtime.redis.packets.size());
            runtime.quit(); runtime.scheduler.flushQuits();
            assertEquals(2, runtime.messages.size());
        }
    }

    @Test
    void destinationArrivalBeforeSourceQuitCannotRecreateConsumedMarker() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld");
            runtime.redis.server = "overworld"; runtime.join();
            runtime.redis.server = "spawn"; runtime.quit();
            runtime.scheduler.flushTransferQuits();
            assertEquals(1, runtime.messages.size()); assertEquals(1, runtime.redis.packets.size());
        }
    }

    @Test
    void transferWithoutArrivalEventuallyAnnouncesDisconnect() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld"); runtime.quit();
            runtime.scheduler.flushQuits(); assertEquals(1, runtime.messages.size());
            runtime.scheduler.flushTransferQuits(); assertEquals(2, runtime.messages.size());
            assertEquals(CrossServerPacket.Action.NETWORK_QUIT, runtime.redis.packets.getLast().getAction());
        }
    }

    @Test
    void rejectedProxySendClearsMarkerAndNormalQuitStillWorks() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); runtime.failProxySend = true;
            ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld");
            assertFalse(runtime.manager.getCrossServerService().hasPreparedTransfer(runtime.player.getUniqueId()));
            assertEquals(1, runtime.unfreezes);
            runtime.quit(); runtime.scheduler.flushQuits();
            assertEquals(3, runtime.messages.size(), "Join, connection failure and real quit");
            assertEquals(CrossServerPacket.Action.NETWORK_QUIT, runtime.redis.packets.getLast().getAction());
        }
    }

    @Test
    void rejectedRequestWhilePlayerRemainsOnlineExpiresPreparation() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld");
            runtime.scheduler.connectTimeouts.removeFirst().run();
            assertFalse(runtime.manager.getCrossServerService().hasPreparedTransfer(runtime.player.getUniqueId()));
            runtime.quit(); runtime.scheduler.flushQuits();
            assertEquals(2, runtime.messages.size());
        }
    }

    @Test
    void redisPreparationFailureDoesNotSendConnectOrLeavePlayerFrozen() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.join(); runtime.redis.failWrite = true;
            ProxyTransferUtil.connect(runtime.plugin, runtime.player, "overworld");
            assertTrue(runtime.proxyMessages.isEmpty()); assertEquals(1, runtime.unfreezes);
            assertFalse(runtime.manager.getCrossServerService().hasPreparedTransfer(runtime.player.getUniqueId()));
            runtime.redis.failWrite = false; runtime.quit(); runtime.scheduler.flushQuits();
            assertEquals(CrossServerPacket.Action.NETWORK_QUIT, runtime.redis.packets.getLast().getAction());
        }
    }

    @Test
    void redisCheckFailureDoesNotMisclassifyArrivalAsFreshJoin() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.redis.failRead = true; runtime.join();
            assertTrue(runtime.redis.packets.isEmpty()); assertTrue(runtime.messages.isEmpty());
        }
    }

    private static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    // Atomic Redis operations are stubbed; the listener, dispatch and rendering are production code.
    private static class Redis extends RedisService {
        String server = "spawn";
        boolean enabled = true;
        boolean failWrite;
        boolean failRead;
        final List<CrossServerPacket> packets = new ArrayList<>();
        final Map<String, String> values = new HashMap<>();
        final UnifiedJedis jedis = new UnifiedJedis(new redis.clients.jedis.Connection()) {
            @Override public String get(String key) { return values.get(key); }
            @Override public long del(String key) { return values.remove(key) != null ? 1 : 0; }
            @Override public String setex(String key, long seconds, String value) {
                if (failWrite) throw new IllegalStateException("Simulated Redis write failure");
                values.put(key, value); return "OK";
            }
            @Override public Object eval(String script, List<String> keys, List<String> args) {
                if (failRead) throw new IllegalStateException("Simulated Redis read failure");
                String key = keys.getFirst();
                if (args.isEmpty()) return values.remove(key);
                return values.remove(key, args.getFirst()) ? 1L : 0L;
            }
        };
        Redis() { super(null); }
        @Override public boolean isEnabled() { return enabled; }
        @Override public String getServerId() { return server; }
        @Override public UnifiedJedis getRedisClient() { return jedis; }
        @Override public void publish(CrossServerPacket packet) { packets.add(packet); }
    }

    private static class Scheduler extends FriendlyScheduler {
        final List<Runnable> quits = new ArrayList<>();
        final List<Runnable> transferQuits = new ArrayList<>();
        final List<Runnable> connectTimeouts = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        boolean paused;
        final ExecutorService direct = new AbstractExecutorService() {
            @Override public void execute(Runnable task) { if (paused) queued.add(task); else task.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        Scheduler() { super(null); }
        @Override public ExecutorService getAsyncExecutor() { return direct; }
        @Override public Object runGlobal(Runnable task) { task.run(); return null; }
        @Override public Object runEntity(Entity entity, Runnable task) { task.run(); return null; }
        @Override public Object runEntity(Entity entity, Runnable task, Runnable retired) { return runEntity(entity, task); }
        @Override public Object runAsyncLater(Runnable task, long ticks) {
            if (ticks == 60) quits.add(task);
            if (ticks == 600) transferQuits.add(task);
            return null;
        }
        @Override public Object runEntityLater(Entity entity, Runnable task, long ticks) {
            connectTimeouts.add(task); return null;
        }
        void flushQuits() { List.copyOf(quits).forEach(Runnable::run); quits.clear(); }
        void flushTransferQuits() { List.copyOf(transferQuits).forEach(Runnable::run); transferQuits.clear(); }
        void resume() { paused = false; List.copyOf(queued).forEach(Runnable::run); queued.clear(); }
    }

    private static class Runtime implements AutoCloseable {
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final Redis redis = new Redis();
        final Scheduler scheduler = new Scheduler();
        final JoinQuitManager manager = new JoinQuitManager(plugin);
        final JoinQuitListener listener = new JoinQuitListener(plugin, manager);
        final List<String> messages = new ArrayList<>();
        final List<byte[]> proxyMessages = new ArrayList<>();
        final Field serverField = Bukkit.class.getDeclaredField("server");
        final Object previousServer;
        final Player player;
        boolean silent;
        boolean failProxySend;
        int unfreezes;

        Runtime() throws Exception {
            UUID uuid = UUID.randomUUID();
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getName", "getDisplayName" -> "Steve";
                        case "hasPlayedBefore", "isOnline" -> true;
                        case "hasPermission" -> silent;
                        case "sendPluginMessage" -> {
                            assertFalse(redis.values.isEmpty(), "Marker must exist before proxy message");
                            if (failProxySend) throw new IllegalStateException("Simulated proxy send failure");
                            proxyMessages.add((byte[]) args[2]); yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Player observer = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> new UUID(0, 1);
                        case "hasPermission", "isOnline" -> true;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            serverField.setAccessible(true); previousServer = serverField.get(null);
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getOnlinePlayers" -> List.of(observer);
                        case "getMaxPlayers" -> 100;
                        case "getPluginManager" -> Proxy.newProxyInstance(org.bukkit.plugin.PluginManager.class.getClassLoader(),
                                new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (pm, pmMethod, pmArgs) -> null);
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
            set(plugin, FriendlyCore.class, "redisService", redis);
            set(plugin, FriendlyCore.class, "joinQuitManager", manager);
            Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF);
            set(plugin, JavaPlugin.class, "logger", logger);
            set(plugin, FriendlyCore.class, "transferFreezeService", new TransferFreezeService(null) {
                @Override public void unfreeze(Player target) { unfreezes++; }
            });
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "textService", new TextService() {
                @Override public void sendMessage(CommandSender target, String message, Map<String, String> placeholders) {
                    messages.add(message);
                }
            });
            set(manager, JoinQuitManager.class, "enabled", true);
            set(manager, JoinQuitManager.class, "joinEnabled", true);
            set(manager, JoinQuitManager.class, "quitEnabled", true);
            set(manager, JoinQuitManager.class, "joinMessage", "joined");
            set(manager, JoinQuitManager.class, "quitMessage", "quit");
            set(manager, JoinQuitManager.class, "joinSound", "none");
            set(manager, JoinQuitManager.class, "quitSound", "none");
            set(manager, JoinQuitManager.class, "silentPermission", "silent");
            set(manager, JoinQuitManager.class, "staffJoinNotify", "staff joined");
            set(manager, JoinQuitManager.class, "staffQuitNotify", "staff quit");
            set(manager, JoinQuitManager.class, "staffNotifyPermission", "staff");
        }

        void join() throws Exception {
            // A cold backend limiter ensures it cannot hide broken transfer detection.
            Field broadcasts = manager.getCrossServerService().getClass().getDeclaredField("recentBroadcasts");
            broadcasts.setAccessible(true);
            ((Map<?, ?>) broadcasts.get(manager.getCrossServerService())).clear();
            PlayerJoinEvent event = new PlayerJoinEvent(player, "vanilla");
            listener.onPlayerJoin(event);
            if (redis.enabled) assertNull(event.joinMessage(), "Redis mode must suppress vanilla too");
        }
        void quit() {
            PlayerQuitEvent event = new PlayerQuitEvent(player, "vanilla");
            listener.onPlayerQuit(event);
            if (redis.enabled) assertNull(event.quitMessage());
        }
        @Override public void close() throws Exception {
            serverField.set(null, previousServer); redis.jedis.close();
        }
    }
}
