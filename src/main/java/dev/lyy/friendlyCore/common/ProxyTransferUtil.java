package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.joinquit.cross.JoinQuitCrossServerService;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Prepare the announcement marker, then confirm FriendlySync's durable handoff before Connect. */
public final class ProxyTransferUtil {
    private ProxyTransferUtil() {}

    public static CompletableFuture<Boolean> connect(FriendlyCore plugin, Player player, String serverName) {
        return connect(plugin, player, serverName, null);
    }

    public static CompletableFuture<Boolean> connect(FriendlyCore plugin, Player player, String serverName, UUID rtpRequest) {
        if (player == null || serverName == null || serverName.isBlank()) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        connectWhenReady(plugin, player, serverName, rtpRequest, result, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15));
        return result;
    }

    private static void connectWhenReady(FriendlyCore plugin, Player player, String serverName,
                                         UUID rtpRequest, CompletableFuture<Boolean> result, long deadline) {
        plugin.getScheduler().runEntity(player, () -> {
            if (result.isDone()) return;
            if (!player.isOnline()) { result.complete(false); return; }
            if (System.nanoTime() >= deadline) { failed(plugin, player); result.complete(false); return; }
            if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
                plugin.getScheduler().runEntityLater(player,
                        () -> connectWhenReady(plugin, player, serverName, rtpRequest, result, deadline),
                        () -> result.complete(false), 2L);
                return;
            }
            UUID uuid = player.getUniqueId();
            JoinQuitCrossServerService service = plugin.getJoinQuitManager() != null
                    ? plugin.getJoinQuitManager().getCrossServerService() : null;
            if (service == null || !service.isCrossServerActive()) {
                send(plugin, player, serverName, null, null, rtpRequest).thenAccept(result::complete);
            } else if (!service.hasPreparedTransfer(uuid)) {
                service.prepareTransfer(uuid).thenAccept(token -> plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline()) { result.complete(false); return; }
                    if (token != null && service.hasPreparedTransfer(uuid)) {
                        send(plugin, player, serverName, service, token, rtpRequest).thenAccept(result::complete);
                    } else {
                        if (!service.hasPreparedTransfer(uuid)) failed(plugin, player);
                        result.complete(false);
                    }
                }, () -> result.complete(false)));
            } else {
                result.complete(false);
            }
        }, () -> result.complete(false));
    }

    private static CompletableFuture<Boolean> send(FriendlyCore plugin, Player player, String serverName,
                                JoinQuitCrossServerService service, String token, UUID rtpRequest) {
        var essentials = plugin.getEssentialsManager();
        var vanish = essentials != null ? essentials.getVanishManager() : null;
        if (vanish == null) return sendPrepared(plugin, player, serverName, service, token, rtpRequest);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        UUID uuid = player.getUniqueId();
        vanish.prepareTransfer(uuid, serverName).whenComplete((vanishToken, error) ->
                plugin.getScheduler().runEntity(player, () -> {
                    if (error != null || !player.isOnline() || (vanishToken != null) != vanish.isVanished(uuid)) {
                        vanish.cancelTransfer(uuid, serverName, vanishToken);
                        if (service != null) service.cancelPreparedTransfer(uuid, token);
                        if (error != null) plugin.getLogger().log(Level.WARNING, "Could not prepare vanish transfer for " + uuid, error);
                        if (player.isOnline()) failed(plugin, player);
                        result.complete(false);
                        return;
                    }
                    if (vanishToken != null) plugin.getScheduler().runAsyncLater(
                            () -> vanish.cancelTransfer(uuid, serverName, vanishToken),
                            dev.lyy.friendlycore.modules.essentials.vanish.VanishManager.TRANSFER_TIMEOUT_SECONDS * 20L);
                    sendPrepared(plugin, player, serverName, service, token, rtpRequest).whenComplete((sent, sendError) -> {
                        if (sendError != null || !Boolean.TRUE.equals(sent)) vanish.cancelTransfer(uuid, serverName, vanishToken);
                        result.complete(sendError == null && Boolean.TRUE.equals(sent));
                    });
                }, () -> {
                    vanish.cancelTransfer(uuid, serverName, vanishToken);
                    if (service != null) service.cancelPreparedTransfer(uuid, token);
                    result.complete(false);
                }));
        return result;
    }

    private static CompletableFuture<Boolean> sendPrepared(FriendlyCore plugin, Player player, String serverName,
                                JoinQuitCrossServerService service, String token, UUID rtpRequest) {
        try {
            var sync = org.bukkit.Bukkit.getPluginManager().getPlugin("FriendlySync");
            if (sync != null) {
                if (!sync.isEnabled()) throw new IllegalStateException("FriendlySync is disabled");
                var transfers = sync.getClass().getMethod("getTransferService").invoke(sync);
                var transfer = (java.util.concurrent.CompletionStage<?>) (rtpRequest == null
                        ? transfers.getClass().getMethod("transfer", Player.class, String.class).invoke(transfers, player, serverName)
                        : transfers.getClass().getMethod("transferRtp", Player.class, String.class, UUID.class)
                            .invoke(transfers, player, serverName, rtpRequest));
                CompletableFuture<Boolean> completion = new CompletableFuture<>();
                transfer.whenComplete((outcome, error) -> {
                    boolean sent = error == null && outcome != null && outcome.getClass().getSimpleName().equals("Success");
                    // A confirmed dispatch remains successful when the source entity retires.
                    if (sent) {
                        completion.complete(true);
                        return;
                    }
                    if (service != null) service.cancelPreparedTransfer(player.getUniqueId(), token);
                    plugin.getLogger().warning("[Transfer] FriendlySync could not send " + player.getName()
                            + " to " + serverName + ": " + (error != null ? error : outcome));
                    plugin.getScheduler().runEntity(player, () -> {
                        failed(plugin, player);
                        completion.complete(false);
                    }, () -> completion.complete(false));
                });
                if (service != null) plugin.getScheduler().runEntityLater(player,
                        () -> service.cancelPreparedTransfer(player.getUniqueId(), token),
                        JoinQuitCrossServerService.TRANSFER_TIMEOUT_SECONDS * 20L);
                return completion;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (rtpRequest != null) {
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeByte(1);
                    out.writeLong(rtpRequest.getMostSignificantBits());
                    out.writeLong(rtpRequest.getLeastSignificantBits());
                    out.writeUTF(serverName);
                }
                player.sendPluginMessage(plugin, "friendlycore:rtp_return", bytes.toByteArray());
                bytes = new ByteArrayOutputStream();
            }
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("Connect");
                out.writeUTF(serverName);
            }
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            if (rtpRequest != null) plugin.getScheduler().runEntityLater(player, () -> {
                if (player.isOnline()) player.kick(net.kyori.adventure.text.Component.text("friendlycore:rtp-return:" + rtpRequest));
            }, 200L);
            // A rejected proxy request must not leave a marker attached to a still-online player.
            if (service != null) plugin.getScheduler().runEntityLater(player,
                    () -> service.cancelPreparedTransfer(player.getUniqueId(), token),
                    JoinQuitCrossServerService.TRANSFER_TIMEOUT_SECONDS * 20L);
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            if (service != null) service.cancelPreparedTransfer(player.getUniqueId(), token);
            plugin.getLogger().log(Level.WARNING, "Failed to connect player to " + serverName, e);
            failed(plugin, player);
            return CompletableFuture.completedFuture(false);
        }
    }

    private static void failed(FriendlyCore plugin, Player player) {
        if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().unfreeze(player);
        plugin.getTextService().sendMessage(player, "<red>Unable to connect to the server. Please try again.</red>", null);
    }
}
