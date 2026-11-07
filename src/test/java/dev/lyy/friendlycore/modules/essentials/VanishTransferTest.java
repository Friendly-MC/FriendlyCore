package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.ProxyTransferUtil;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.RedisService;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishListener;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VanishTransferTest {
    @Test
    void transferWaitsForRedisAndColdDestinationRestoresVanishBeforeJoinAnnouncements() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.source.setVanished(runtime.player, true, true);
            runtime.scheduler.paused = true;
            var transfer = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination");
            assertFalse(transfer.isDone());
            assertEquals(0, runtime.connects);
            runtime.scheduler.flush();
            assertTrue(transfer.join());
            assertEquals(1, runtime.connects);
            assertEquals(1, runtime.redis.values.size());
            var event = runtime.arrive();
            assertTrue(runtime.destination.isVanished(runtime.uuid));
            assertNull(event.joinMessage());
            assertTrue(runtime.redis.values.isEmpty(), "Arrival consumes the handoff exactly once");
            runtime.arrive();
            assertFalse(runtime.destination.isVanished(runtime.uuid), "Normal rejoin still resets vanish");
        }
    }

    @Test
    void markerIsBoundToDestinationAndOldCancellationCannotDeleteANewerTransfer() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.source.setVanished(runtime.player, true, true);
            String oldToken = runtime.source.prepareTransfer(runtime.uuid, "destination").join();
            String newToken = runtime.source.prepareTransfer(runtime.uuid, "destination").join();
            runtime.redis.server = "other";
            runtime.destination.loadTransferArrival(runtime.uuid);
            assertFalse(runtime.destination.consumeTransferArrival(runtime.uuid));
            runtime.source.cancelTransfer(runtime.uuid, "destination", oldToken);
            assertEquals(List.of(newToken), List.copyOf(runtime.redis.values.values()));
            runtime.redis.server = "destination";
            runtime.destination.loadTransferArrival(runtime.uuid);
            assertTrue(runtime.destination.consumeTransferArrival(runtime.uuid));
            assertFalse(runtime.destination.consumeTransferArrival(runtime.uuid));
        }
    }

    @Test
    void failedHandoffPreventsConnectAndFailedProxyOrTimeoutClearsMarker() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.source.setVanished(runtime.player, true, true);
            runtime.redis.failWrite = true;
            assertFalse(ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination").join());
            assertEquals(0, runtime.connects);
            runtime.redis.failWrite = false;
            runtime.failProxy = true;
            assertFalse(ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination").join());
            assertTrue(runtime.redis.values.isEmpty());
            runtime.failProxy = false;
            assertTrue(ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination").join());
            List.copyOf(runtime.scheduler.timeouts).forEach(Runnable::run);
            assertTrue(runtime.redis.values.isEmpty());
            assertFalse(runtime.destination.isVanished(runtime.uuid));
        }
    }

    @Test
    void deniedLoginClearsArrivalAndRedisReadFailureDoesNotExposeVanishedPlayer() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.source.setVanished(runtime.player, true, true);
            runtime.source.prepareTransfer(runtime.uuid, "destination").join();
            runtime.redis.server = "destination";
            var preLogin = runtime.preLogin();
            preLogin.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("Denied"));
            runtime.listener.onPreLoginResult(preLogin);
            assertFalse(runtime.destination.consumeTransferArrival(runtime.uuid));
            runtime.redis.failRead = true;
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, runtime.preLogin().getLoginResult());
        }
    }

    @Test
    void friendlySyncStartsOnlyAfterHandoffAndItsFailureCancelsTheMarker() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.sync = allocate(SyncPlugin.class);
            set(runtime.sync, JavaPlugin.class, "isEnabled", true);
            runtime.sync.transfers = new Transfers();
            runtime.source.setVanished(runtime.player, true, true);
            runtime.scheduler.paused = true;
            var transfer = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination");
            assertEquals(0, runtime.sync.transfers.calls);
            runtime.scheduler.flush();
            assertEquals(1, runtime.sync.transfers.calls);
            assertFalse(runtime.redis.values.isEmpty());
            assertEquals(0, runtime.connects);
            runtime.sync.transfers.result.complete(new Failed());
            assertFalse(transfer.join());
            assertTrue(runtime.redis.values.isEmpty());
        }
    }

    @Test
    void vanishChangeWhilePreparingCancelsTransferInsteadOfSendingStaleState() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.source.setVanished(runtime.player, true, true);
            runtime.scheduler.paused = true;
            var transfer = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination");
            runtime.source.setVanished(runtime.player, false, true);
            runtime.scheduler.flush();
            assertFalse(transfer.join());
            assertEquals(0, runtime.connects);
            assertTrue(runtime.redis.values.isEmpty());
        }
    }

    @Test
    void confirmedSyncDispatchKeepsHandoffEvenWhenSourceEntityRetires() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.sync = allocate(SyncPlugin.class);
            set(runtime.sync, JavaPlugin.class, "isEnabled", true);
            runtime.sync.transfers = new Transfers();
            runtime.source.setVanished(runtime.player, true, true);
            var transfer = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "destination");
            runtime.scheduler.retired = true;
            runtime.sync.transfers.result.complete(new Success());
            assertTrue(transfer.join());
            assertFalse(runtime.redis.values.isEmpty());
            runtime.scheduler.retired = false;
            runtime.arrive();
            assertTrue(runtime.destination.isVanished(runtime.uuid));
        }
    }

    public record Failed() {}
    public record Success() {}
    public static class Transfers {
        int calls;
        final CompletableFuture<Object> result = new CompletableFuture<>();
        public CompletableFuture<Object> transfer(Player player, String server) { calls++; return result; }
    }
    public static class SyncPlugin extends JavaPlugin {
        Transfers transfers;
        public Transfers getTransferService() { return transfers; }
    }

    private static class Redis extends RedisService {
        String server = "source";
        boolean failWrite;
        boolean failRead;
        Map<String, String> values = new HashMap<>();
        final UnifiedJedis jedis = new UnifiedJedis(new redis.clients.jedis.Connection()) {
            @Override public String setex(String key, long seconds, String value) {
                if (failWrite) throw new IllegalStateException("Redis unavailable");
                assertEquals(30L, seconds);
                values.put(key, value);
                return "OK";
            }
            @Override public Object eval(String script, List<String> keys, List<String> args) {
                if (failRead) throw new IllegalStateException("Redis unavailable");
                return args.isEmpty() ? values.remove(keys.getFirst())
                        : (values.remove(keys.getFirst(), args.getFirst()) ? 1L : 0L);
            }
        };
        Redis() { super(null); }
        @Override public boolean isEnabled() { return true; }
        @Override public String getServerId() { return server; }
        @Override public UnifiedJedis getRedisClient() { return jedis; }
    }

    private static class Scheduler extends FriendlyScheduler {
        boolean paused;
        boolean retired;
        final List<Runnable> queued = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final ExecutorService executor = new AbstractExecutorService() {
            @Override public void execute(Runnable task) { if (paused) queued.add(task); else task.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        Scheduler() { super(null); }
        @Override public ExecutorService getAsyncExecutor() { return executor; }
        @Override public Object runEntity(Entity entity, Runnable task) { return null; }
        @Override public Object runEntity(Entity entity, Runnable task, Runnable onRetired) {
            if (retired) onRetired.run(); else task.run();
            return null;
        }
        @Override public Object runAsyncLater(Runnable task, long ticks) { timeouts.add(task); return null; }
        void flush() { paused = false; List.copyOf(queued).forEach(Runnable::run); queued.clear(); }
    }

    private static class Config extends ConfigService {
        Config() { super(null); }
        @Override public YamlConfiguration get(String name) { return new YamlConfiguration(); }
    }

    private static class Runtime implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final Redis redis = new Redis();
        final Scheduler scheduler = new Scheduler();
        final VanishManager source = new VanishManager(plugin, null);
        final VanishManager destination = new VanishManager(plugin, null);
        final VanishListener listener = new VanishListener(plugin, destination);
        final Field serverField = Bukkit.class.getDeclaredField("server");
        final Object previousServer;
        final Player player;
        int connects;
        boolean failProxy;
        SyncPlugin sync;

        Runtime() throws Exception {
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "isOnline" -> true;
                        case "hasPermission" -> false;
                        case "sendPluginMessage" -> {
                            assertFalse(redis.values.isEmpty(), "Vanish marker must exist before Connect");
                            if (failProxy) throw new IllegalStateException("Proxy unavailable");
                            connects++;
                            yield null;
                        }
                        default -> null;
                    });
            PluginManager plugins = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                    new Class<?>[]{PluginManager.class}, (object, method, args) -> method.getName().equals("getPlugin") ? sync : null);
            serverField.setAccessible(true);
            previousServer = serverField.get(null);
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (object, method, args) -> switch (method.getName()) {
                        case "getOnlinePlayers" -> List.of();
                        case "getPluginManager" -> plugins;
                        case "createProfile" -> Proxy.newProxyInstance(com.destroystokyo.paper.profile.PlayerProfile.class.getClassLoader(),
                                new Class<?>[]{com.destroystokyo.paper.profile.PlayerProfile.class},
                                (profile, profileMethod, profileArgs) -> profileMethod.getName().equals("getId") ? uuid : null);
                        default -> null;
                    }));
            set(plugin, FriendlyCore.class, "redisService", redis);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "configService", allocate(Config.class));
            set(plugin, FriendlyCore.class, "textService", new TextService() {
                @Override public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) {}
            });
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            set(plugin, JavaPlugin.class, "logger", logger);
            EssentialsManager essentials = allocate(EssentialsManager.class);
            set(essentials, EssentialsManager.class, "vanishManager", source);
            set(plugin, FriendlyCore.class, "essentialsManager", essentials);
        }

        AsyncPlayerPreLoginEvent preLogin() throws Exception {
            var event = new AsyncPlayerPreLoginEvent("Staff", InetAddress.getLoopbackAddress(), uuid, false);
            listener.onAsyncPreLogin(event);
            return event;
        }

        PlayerJoinEvent arrive() throws Exception {
            redis.server = "destination";
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, preLogin().getLoginResult());
            var event = new PlayerJoinEvent(player, Component.text("Joined"));
            listener.onPlayerJoin(event);
            return event;
        }

        @Override public void close() throws Exception { serverField.set(null, previousServer); redis.jedis.close(); }
    }

    private static void set(Object instance, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
