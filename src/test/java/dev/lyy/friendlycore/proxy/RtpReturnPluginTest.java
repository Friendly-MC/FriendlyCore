package dev.lyy.friendlycore.proxy;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RtpReturnPluginTest {
    final UUID uuid = UUID.randomUUID(), request = UUID.randomUUID();
    final RegisteredServer source = server(), destination = server(), lobby = server();
    ServerConnection current;
    final Player player = player();
    Player activePlayer = player;
    final ServerConnection backend = connection(player, source);
    final ProxyServer proxy = stub(ProxyServer.class, (object, method, args) -> switch (method.getName()) {
        case "getServer" -> Optional.ofNullable(Map.of("economy-a", destination, "spawn-a", source).get(args[0]));
        case "getPlayer" -> Optional.ofNullable(activePlayer);
        default -> throw new AssertionError(method.getName());
    });
    final RtpReturnPlugin plugin = new RtpReturnPlugin(proxy);

    RtpReturnPluginTest() { current = backend; }

    @Test
    void guiceConstructsPluginWithInjectedProxy() throws Exception {
        var injector = com.google.inject.Guice.createInjector(new com.google.inject.AbstractModule() {
            @Override protected void configure() {
                binder().requireAtInjectOnConstructors();
                bind(ProxyServer.class).toInstance(proxy);
                bind(RtpReturnPlugin.class).in(com.google.inject.Singleton.class);
            }
        });
        var loaded = injector.getInstance(RtpReturnPlugin.class);
        assertSame(loaded, injector.getInstance(RtpReturnPlugin.class));
        loaded.message(new PluginMessageEvent(backend, player, RtpReturnPlugin.CHANNEL, packet(request, "economy-a")));
        var rejection = kick(player, destination, true, "destination unavailable");
        loaded.kicked(rejection);
        assertInstanceOf(KickedFromServerEvent.Notify.class, rejection.getResult());
    }

    @Test
    void rejectedDestinationWaitsForTaggedSourceKickThenReturnsOnlyOnce() throws Exception {
        arm(backend, player, packet(request, "economy-a"));
        var rejection = kick(player, destination, true, "whitelist rejection");
        rejection.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby));
        plugin.kicked(rejection);
        assertInstanceOf(KickedFromServerEvent.Notify.class, rejection.getResult());
        var watchdog = kick(player, source, false, "friendlycore:rtp-return:" + request);
        plugin.kicked(watchdog);
        assertSame(source, assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, watchdog.getResult()).getServer());
        var second = kick(player, source, true, "source rejected the return");
        plugin.kicked(second);
        assertSame(lobby, assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, second.getResult()).getServer());
    }

    @Test
    void disconnectedSourceAllowsOneDirectReturnAfterDestinationRejection() throws Exception {
        arm(backend, player, packet(request, "economy-a"));
        current = null;
        var rejection = kick(player, destination, true, "target offline");
        plugin.kicked(rejection);
        assertSame(source, assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, rejection.getResult()).getServer());
    }

    @Test
    void normalLobbyLoginAndUnrelatedKicksKeepTheirOriginalRouting() throws Exception {
        var normal = kick(player, source, false, "banned");
        plugin.kicked(normal);
        assertSame(lobby, assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, normal.getResult()).getServer());
        arm(backend, player, packet(request, "economy-a"));
        for (var event : new KickedFromServerEvent[]{kick(player, source, false, "banned"),
                kick(player, lobby, true, "offline"), kick(player, source, false, "friendlycore:rtp-return:" + UUID.randomUUID())}) {
            var original = event.getResult();
            plugin.kicked(event);
            assertSame(original, event.getResult());
        }
    }

    @Test
    void successfulTargetJoinAndClientDisconnectDiscardReturnAuthorization() throws Exception {
        arm(backend, player, packet(request, "economy-a"));
        plugin.connected(new ServerConnectedEvent(player, destination, source));
        assertIgnoredReturn(player);
        arm(backend, player, packet(request, "economy-a"));
        plugin.disconnected(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertIgnoredReturn(player);
    }

    @Test
    void oldPlayerConnectionCannotRedirectReconnectedPlayerOrRemoveNewAuthorization() throws Exception {
        arm(backend, player, packet(request, "economy-a"));
        Player reconnected = player();
        assertIgnoredReturn(reconnected);
        activePlayer = reconnected;
        current = connection(reconnected, source);
        arm(current, reconnected, packet(request, "economy-a"));
        plugin.disconnected(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        assertIgnoredReturn(player);
        var watchdog = kick(reconnected, source, false, "friendlycore:rtp-return:" + request);
        plugin.kicked(watchdog);
        assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, watchdog.getResult());
        assertSame(source, ((KickedFromServerEvent.RedirectPlayer) watchdog.getResult()).getServer());
    }

    @Test
    void clientSpoofingMalformedPacketsWrongCarrierAndOldBackendCannotAuthorizeReturn() throws Exception {
        var spoof = new PluginMessageEvent(player, backend, RtpReturnPlugin.CHANNEL, packet(request, "economy-a"));
        plugin.message(spoof);
        assertFalse(spoof.getResult().isAllowed());
        assertIgnoredReturn(player);
        arm(backend, player, new byte[]{1});
        arm(backend, player, packet(request, "missing"));
        arm(backend, player, packet(request, "spawn-a"));
        arm(backend, player(), packet(request, "economy-a"));
        arm(connection(player, source), player, packet(request, "economy-a"));
        arm(backend, player, new byte[513]);
        byte[] trailing = java.util.Arrays.copyOf(packet(request, "economy-a"), packet(request, "economy-a").length + 1);
        arm(backend, player, trailing);
        assertIgnoredReturn(player);
    }

    @Test
    void expiredAuthorizationCannotChangeFallback() throws Exception {
        arm(backend, player, packet(request, "economy-a"));
        var field = RtpReturnPlugin.class.getDeclaredField("pending"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (Map<UUID, Object>) field.get(plugin);
        Object old = entries.get(uuid);
        var constructor = old.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
        entries.put(uuid, constructor.newInstance(player, request, backend, destination, System.nanoTime() - 1));
        assertIgnoredReturn(player);
    }

    private void assertIgnoredReturn(Player candidate) {
        var event = kick(candidate, source, false, "friendlycore:rtp-return:" + request);
        var original = event.getResult();
        plugin.kicked(event);
        assertSame(original, event.getResult());
    }

    private void arm(ServerConnection connection, Player carrier, byte[] bytes) {
        var event = new PluginMessageEvent(connection, carrier, RtpReturnPlugin.CHANNEL, bytes);
        plugin.message(event);
        assertFalse(event.getResult().isAllowed());
    }

    private KickedFromServerEvent kick(Player candidate, RegisteredServer kicked, boolean duringConnect, String reason) {
        return new KickedFromServerEvent(candidate, kicked, Component.text(reason), duringConnect,
                KickedFromServerEvent.RedirectPlayer.create(lobby));
    }

    static byte[] packet(UUID id, String target) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeByte(1); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); out.writeUTF(target);
        }
        return bytes.toByteArray();
    }

    @Test
    void repeatedConnectKicksTriggerGlobalCascadeLoopBreaker() throws Exception {
        Player testPlayer = player();
        RegisteredServer serverA = server();
        RegisteredServer serverB = server();

        var kick1 = kick(testPlayer, serverA, true, "offline");
        plugin.kicked(kick1);
        assertFalse(kick1.getResult() instanceof KickedFromServerEvent.DisconnectPlayer);

        var kick2 = kick(testPlayer, serverB, true, "too many packets");
        plugin.kicked(kick2);
        assertFalse(kick2.getResult() instanceof KickedFromServerEvent.DisconnectPlayer);

        var kick3 = kick(testPlayer, serverA, true, "internal error");
        plugin.kicked(kick3);
        assertTrue(kick3.getResult() instanceof KickedFromServerEvent.DisconnectPlayer,
                "Third connect kick within 10s must disconnect player to break CPU storm loop");
    }

    private Player player() {
        return stub(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getCurrentServer" -> Optional.ofNullable(current);
            default -> identity(object, method.getName(), args);
        });
    }

    private static RegisteredServer server() {
        return stub(RegisteredServer.class, (object, method, args) -> identity(object, method.getName(), args));
    }

    private static ServerConnection connection(Player player, RegisteredServer server) {
        return stub(ServerConnection.class, (object, method, args) -> switch (method.getName()) {
            case "getPlayer" -> player;
            case "getServer" -> server;
            default -> identity(object, method.getName(), args);
        });
    }

    private static Object identity(Object object, String method, Object[] args) {
        return switch (method) {
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            case "toString" -> "stub";
            default -> throw new AssertionError(method);
        };
    }

    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
