package dev.lyy.friendlycore.modules.afk;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.*;
import dev.lyy.friendlycore.common.hooks.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AfkTransferTest {
    @Test
    void lastLocationWaitsForHandoffAndStartsOnlyOneConnect() throws Exception {
        for (boolean sent : new boolean[]{true, false}) {
            try (Runtime runtime = new Runtime()) {
                var config = (Config) runtime.plugin.getConfigService();
                config.main.set("join-spawn.last-location.hub-servers", List.of("spawn-a"));
                var spawn = allocate(RoutingSpawn.class);
                spawn.handoff = new CompletableFuture<>();
                var locations = new dev.lyy.friendlycore.modules.spawn.LastLocationService(runtime.plugin, spawn);
                var send = locations.getClass().getDeclaredMethod("sendToRemote", Player.class,
                        dev.lyy.friendlycore.modules.spawn.SpawnData.class);
                send.setAccessible(true);
                send.invoke(locations, runtime.player,
                        new dev.lyy.friendlycore.modules.spawn.SpawnData("economy-a", "world", 0, 64, 0, 0, 0));
                assertEquals(1, spawn.calls);
                assertNull(runtime.scheduler.warmup, "No retry or fallback timer may run during the save");
                spawn.handoff.complete(sent);
                if (sent) {
                    assertEquals(160L, runtime.scheduler.delay);
                    runtime.scheduler.warmup.run();
                } else {
                    assertNull(runtime.scheduler.warmup);
                    assertEquals(1, runtime.unfreezes);
                }
                assertEquals(1, spawn.calls);
            }
        }
    }

    static class RoutingSpawn extends dev.lyy.friendlycore.modules.spawn.SpawnManager {
        int calls;
        CompletableFuture<Boolean> handoff;
        RoutingSpawn() { super(null); }
        @Override public String resolveLocalServerId() { return "spawn-a"; }
        @Override public dev.lyy.friendlycore.modules.spawn.SpawnData getSpawnData() { return null; }
        @Override public CompletableFuture<Boolean> sendPlayerToProxyServer(Player player, String server) {
            assertEquals("economy-a", server);
            calls++;
            return handoff;
        }
    }
    @Test
    void defaultWarmupWaitsThreeSecondsThenUsesSyncInsteadOfRawProxyAndRejectsDuplicates() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.command();
            assertEquals(60, runtime.scheduler.delay);
            assertEquals(0, runtime.transfers.calls);
            runtime.command();
            runtime.scheduler.warmup.run();
            assertEquals(1, runtime.transfers.calls);
            assertEquals("afk", runtime.transfers.target);
            assertEquals(0, runtime.proxyPackets);
            runtime.command();
            assertEquals(1, runtime.transfers.calls);
            runtime.transfers.result.complete(new Success());
            assertEquals(0, runtime.unfreezes);
        }
    }

    @Test
    void messagesComeFromMessagesYmlAndHonourChatActionBarOrBoth() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.command();
            String warmup = Config.MESSAGES.getString("afk-warmup.text");
            assertEquals(List.of(warmup), runtime.chat, "BOTH sends to chat");
            assertEquals(List.of(warmup), runtime.actionBar, "BOTH sends to the action bar");

            Config.MESSAGES.set("afk-in-progress.display-type", "ACTION_BAR");
            try {
                runtime.command();
                assertEquals(List.of(warmup), runtime.chat, "ACTION_BAR skips chat");
                assertEquals(Config.MESSAGES.getString("afk-in-progress.text"), runtime.actionBar.get(1));
            } finally {
                Config.MESSAGES.set("afk-in-progress.display-type", "CHAT");
            }
            Config.MESSAGES.set("afk-in-progress.enabled", false);
            try {
                runtime.command();
                assertEquals(1, runtime.chat.size());
                assertEquals(2, runtime.actionBar.size(), "Disabled messages are not sent");
            } finally {
                Config.MESSAGES.set("afk-in-progress.enabled", true);
            }
        }
    }

    @Test
    void warmupCountsDownOnTheActionBarAndStopsWhenCancelled() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.command();
            String tick = Config.MESSAGES.getString("afk-countdown.text");
            runtime.scheduler.countdown.run();
            runtime.scheduler.countdown.run();
            runtime.scheduler.countdown.run();
            assertEquals(2, runtime.actionBar.stream().filter(tick::equals).count(), "3s warmup shows 2 then 1");
            assertFalse(runtime.chat.contains(tick), "The countdown is action bar only by default");

            runtime.plugin.getCooldownManager().resetCooldown("afk", runtime.uuid);
            runtime.afk.onQuit(new PlayerQuitEvent(runtime.player, (String) null));
            runtime.command();
            runtime.afk.onQuit(new PlayerQuitEvent(runtime.player, (String) null));
            runtime.scheduler.countdown.run();
            assertEquals(2, runtime.actionBar.stream().filter(tick::equals).count(), "No ticks after the request ends");
        }
    }

    @Test
    void movementAndQuitCancelWarmupEvenIfItsCallbackRunsLate() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.command();
            var world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (object, method, args) -> method.getName().equals("equals") ? object == args[0] : null);
            runtime.afk.onMove(new PlayerMoveEvent(runtime.player, new Location(world, 0, 64, 0), new Location(world, 1, 64, 0)));
            runtime.scheduler.warmup.run();
            assertEquals(0, runtime.transfers.calls);
            runtime.plugin.getCooldownManager().resetCooldown("afk", runtime.uuid);
            runtime.command();
            runtime.afk.onQuit(new PlayerQuitEvent(runtime.player, (String) null));
            runtime.scheduler.warmup.run();
            assertEquals(0, runtime.transfers.calls);
        }
    }

    @Test
    void lockedPlayersAndDisabledSyncCannotDispatchProxyTransfer() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.locked = true;
            runtime.command();
            assertNull(runtime.scheduler.warmup);
            runtime.locked = false;
            set(runtime.sync, JavaPlugin.class, "isEnabled", false);
            runtime.command();
            runtime.scheduler.warmup.run();
            assertEquals(0, runtime.transfers.calls);
            assertEquals(0, runtime.proxyPackets);
            assertTrue(runtime.unfreezes > 0);
        }
    }

    @Test
    void proxySenderWaitsForLoadingBeforeInvokingSyncTransfer() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.locked = true;
            var connection = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "economy-a");
            assertFalse(connection.isDone());
            assertEquals(0, runtime.transfers.calls);
            assertEquals(2L, runtime.scheduler.delay);
            runtime.scheduler.warmup.run();
            assertEquals(0, runtime.transfers.calls);
            runtime.locked = false;
            runtime.scheduler.warmup.run();
            assertEquals(1, runtime.transfers.calls);
            assertEquals("economy-a", runtime.transfers.target);
            runtime.transfers.result.complete(new Success());
            assertTrue(connection.join());
            assertEquals(0, runtime.proxyPackets);
        }
    }

    @Test
    void failedSyncTransferResolvesCallerAndUnfreezesCoreWithoutRawConnectFallback() throws Exception {
        try (Runtime runtime = new Runtime()) {
            var connection = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "afk");
            assertFalse(connection.isDone());
            runtime.transfers.result.complete(new Failed());
            assertFalse(connection.join());
            assertEquals(0, runtime.proxyPackets);
            assertEquals(1, runtime.unfreezes);
        }
    }

    public record Success() {}
    public record Failed() {}

    @Test
    void rtpPassesItsRequestToSyncWithoutBypassingDurableHandoff() throws Exception {
        try (Runtime runtime = new Runtime()) {
            UUID request = UUID.randomUUID();
            var connection = ProxyTransferUtil.connect(runtime.plugin, runtime.player, "economy-a", request);
            assertEquals(request, runtime.transfers.rtpRequest);
            assertEquals(1, runtime.transfers.calls);
            assertFalse(connection.isDone());
            assertEquals(0, runtime.proxyPackets);
            runtime.transfers.result.complete(new Success());
            assertTrue(connection.join());
            assertEquals(0, runtime.proxyPackets);
        }
    }

    public static class Transfers {
        int calls;
        String target;
        UUID rtpRequest;
        final CompletableFuture<Object> result = new CompletableFuture<>();
        public CompletableFuture<Object> transfer(Player player, String target) {
            calls++; this.target = target; return result;
        }
        public CompletableFuture<Object> transferRtp(Player player, String target, UUID request) {
            rtpRequest = request;
            return transfer(player, target);
        }
    }
    public static class SyncPlugin extends JavaPlugin {
        Transfers transfers;
        public Transfers getTransferService() { return transfers; }
    }
    static class Config extends ConfigService {
        YamlConfiguration main;
        static final YamlConfiguration MESSAGES = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                Objects.requireNonNull(AfkTransferTest.class.getResourceAsStream("/messages.yml")), java.nio.charset.StandardCharsets.UTF_8));
        Config() { super(null); }
        @Override public YamlConfiguration get(String fileName) { return MESSAGES; }
        @Override public YamlConfiguration getMainConfig() { return main; }
        @Override public YamlConfiguration getSpawnConfig() { return main; }
    }
    static class Scheduler extends FriendlyScheduler {
        Runnable warmup;
        long delay;
        Scheduler() { super(null); }
        @Override public Object runEntity(Entity player, Runnable task, Runnable retired) { task.run(); return new Object(); }
        @Override public Object runEntityLater(Entity player, Runnable task, Runnable retired, long ticks) {
            warmup = task; delay = ticks; return new Object();
        }
        Runnable countdown;
        @Override public Object runEntityTimer(Entity player, Runnable task, long delay, long period) {
            assertEquals(20L, delay); assertEquals(20L, period);
            countdown = task; return new Object();
        }
        @Override public void cancel(Object task) {}
    }
    static class Runtime implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final SyncPlugin sync = allocate(SyncPlugin.class);
        final Transfers transfers = new Transfers();
        final Scheduler scheduler = new Scheduler();
        final Field serverField = Bukkit.class.getDeclaredField("server");
        final Object previous;
        final Player player;
        final AfkCommand afk;
        int proxyPackets;
        final List<String> chat = new ArrayList<>(), actionBar = new ArrayList<>();
        int unfreezes;
        boolean locked;
        Runtime() throws Exception {
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getName" -> "Player";
                case "isOnline" -> true;
                case "isDead", "hasMetadata" -> false;
                case "getHealth" -> 20.0;
                case "hasPermission" -> args[0].equals("friendlycore.afk.use");
                case "sendPluginMessage" -> { proxyPackets++; yield null; }
                default -> null;
            });
            PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                    (object, method, args) -> method.getName().equals("getPlugin") ? sync : null);
            serverField.setAccessible(true); previous = serverField.get(null);
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                    (object, method, args) -> method.getName().equals("getPluginManager") ? manager : null));
            sync.transfers = transfers;
            set(sync, JavaPlugin.class, "isEnabled", true);
            set(plugin, JavaPlugin.class, "logger", Logger.getAnonymousLogger());
            var config = allocate(Config.class); config.main = new YamlConfiguration();
            set(plugin, FriendlyCore.class, "configService", config);
            set(plugin, FriendlyCore.class, "scheduler", scheduler);
            set(plugin, FriendlyCore.class, "cooldownManager", new CooldownManager());
            set(plugin, FriendlyCore.class, "textService", new TextService() {
                @Override public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) { chat.add(message); }
                @Override public void sendActionBar(Player player, String message, Map<String, String> placeholders) { actionBar.add(message); }
            });
            set(plugin, FriendlyCore.class, "transferFreezeService", new TransferFreezeService(null) {
                @Override public void freeze(Player player) {}
                @Override public void unfreeze(Player player) { unfreezes++; }
            });
            var hooks = allocate(HookManager.class);
            set(hooks, HookManager.class, "mcDataBridgeHook", new McDataBridgeHook(null) {
                @Override public void checkHook() {}
                @Override public boolean isPlayerLocked(Player player) { return locked; }
            });
            set(plugin, FriendlyCore.class, "hookManager", hooks);
            afk = new AfkCommand(plugin);
        }
        void command() { afk.onCommand(player, null, "afk", new String[0]); }
        @Override public void close() throws Exception { afk.shutdown(); scheduler.shutdown(); serverField.set(null, previous); }
    }
    static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    @Test
    void teleportCancelsWarmupButLookingAroundDoesNot() throws Exception {
        try (Runtime runtime = new Runtime()) {
            runtime.command();
            var from = new Location(null, 0, 64, 0);
            var looking = new Location(null, 0, 64, 0, 90, 20);
            runtime.afk.onMove(new PlayerMoveEvent(runtime.player, from, looking));
            runtime.afk.onTeleport(new org.bukkit.event.player.PlayerTeleportEvent(runtime.player, from, looking));
            runtime.scheduler.warmup.run();
            assertEquals(0, runtime.transfers.calls);
        }
    }}
