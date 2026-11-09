package dev.lyy.friendlycore.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Uses only current proxy connections; no persistent routing state can affect a later login. */
public final class RtpReturnPlugin {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("friendlycore:rtp_return");
    private record Pending(Player player, UUID request, ServerConnection source, RegisteredServer target, long deadline) {}
    private final ProxyServer proxy;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentRedirects = new ConcurrentHashMap<>();
    private final Map<UUID, long[]> connectKickTracker = new ConcurrentHashMap<>();

    @Inject
    public RtpReturnPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
    }

    @Subscribe
    public void message(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        // Consume the channel before checking source: clients cannot forge backend return requests.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)
                || event.getTarget() != source.getPlayer()
                || source.getPlayer().getCurrentServer().orElse(null) != source
                || proxy.getPlayer(source.getPlayer().getUniqueId()).orElse(null) != source.getPlayer()
                || event.getData().length > 512) return;
        try (var input = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            if (input.readUnsignedByte() != 1) return;
            UUID request = new UUID(input.readLong(), input.readLong());
            RegisteredServer target = proxy.getServer(input.readUTF()).orElse(null);
            if (input.available() != 0 || target == null || target.equals(source.getServer())) return;
            Player player = source.getPlayer();
            long now = System.nanoTime();
            pending.put(player.getUniqueId(), new Pending(player, request, source, target, now + TimeUnit.SECONDS.toNanos(60)));
        } catch (IOException ignored) {
            // Invalid packets never authorize routing.
        }
    }

    private Pending current(Player player) {
        Pending transfer = pending.get(player.getUniqueId());
        if (transfer != null && transfer.player() != player) return null;
        if (transfer != null && System.nanoTime() >= transfer.deadline()) {
            pending.remove(player.getUniqueId(), transfer);
            return null;
        }
        return transfer;
    }

    @Subscribe(order = PostOrder.LAST)
    public void kicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();

        // Global cascade loop breaker: if any player is kicked during server connect 3+ times within 10 seconds,
        // disconnect them cleanly so Velocity Netty threads don't spin in an infinite bounce loop consuming 100% CPU.
        if (event.kickedDuringServerConnect()) {
            long now = System.currentTimeMillis();
            long[] tracker = connectKickTracker.compute(player.getUniqueId(), (k, v) -> {
                if (v == null || (now - v[0]) > 10_000L) {
                    return new long[]{now, 1};
                }
                v[1]++;
                return v;
            });
            if (tracker != null && tracker[1] >= 3) {
                connectKickTracker.remove(player.getUniqueId());
                pending.remove(player.getUniqueId());
                recentRedirects.remove(player.getUniqueId());
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                        Component.text("Server connection failed. Please reconnect in a moment.")));
                return;
            }
        }

        Pending transfer = current(player);
        if (transfer == null) return;

        // Loop detection: if redirected back within the last 10 seconds and kicked again during connect, disconnect cleanly
        long now = System.currentTimeMillis();
        Long lastRedirect = recentRedirects.get(player.getUniqueId());
        if (lastRedirect != null && (now - lastRedirect) < 10_000L && event.kickedDuringServerConnect()) {
            pending.remove(player.getUniqueId());
            recentRedirects.remove(player.getUniqueId());
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                    Component.text("Connection loop detected while returning. Please reconnect.")));
            return;
        }

        String reason = event.getServerKickReason().map(PlainTextComponentSerializer.plainText()::serialize).orElse("");
        if (!event.kickedDuringServerConnect() && event.getServer().equals(transfer.source().getServer())
                && reason.equals("friendlycore:rtp-return:" + transfer.request())) {
            if (pending.remove(player.getUniqueId(), transfer)) {
                recentRedirects.put(player.getUniqueId(), now);
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(transfer.source().getServer(),
                        Component.text("RTP could not connect. Returned to your previous server.")));
            }
        } else if (event.kickedDuringServerConnect() && event.getServer().equals(transfer.target())) {
            if (player.getCurrentServer().orElse(null) == transfer.source()) {
                // Sync has already released this source. Its tagged watchdog kick starts a fresh login.
                event.setResult(KickedFromServerEvent.Notify.create(Component.text("RTP could not connect. Returning to your previous server...")));
            } else if (pending.remove(player.getUniqueId(), transfer)) {
                recentRedirects.put(player.getUniqueId(), now);
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(transfer.source().getServer()));
            }
        }
    }

    @Subscribe
    public void connected(ServerConnectedEvent event) {
        Pending transfer = current(event.getPlayer());
        if (transfer != null && !event.getServer().equals(transfer.source().getServer())) {
            pending.remove(event.getPlayer().getUniqueId(), transfer);
        }
        recentRedirects.remove(event.getPlayer().getUniqueId());
        connectKickTracker.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void disconnected(DisconnectEvent event) {
        Pending transfer = pending.get(event.getPlayer().getUniqueId());
        if (transfer != null && transfer.player() == event.getPlayer()) pending.remove(event.getPlayer().getUniqueId(), transfer);
        recentRedirects.remove(event.getPlayer().getUniqueId());
        connectKickTracker.remove(event.getPlayer().getUniqueId());
    }
}
