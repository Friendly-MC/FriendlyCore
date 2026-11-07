package dev.lyy.friendlycore.modules.kits.cross;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.model.PlayerKitData;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * High-performance cross-server synchronization engine for Kits across proxies and sub-servers.
 */
public class KitCrossServerService {

    private final FriendlyCore plugin;

    public KitCrossServerService(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Broadcasts a player's kit claim and cooldown state to all servers on the network.
     */
    public void broadcastClaim(UUID playerUuid, String playerName, String kitName, long cooldownUntil, boolean oneTimeClaimed, boolean bought) {
        if (plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;

        String serverId = plugin.getRedisService().getServerId();
        String payload = kitName + ";" + cooldownUntil + ";" + oneTimeClaimed + ";" + bought;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.KIT_CLAIM_SYNC, playerUuid, playerName, cooldownUntil, payload);
        plugin.getRedisService().publish(packet);
    }

    /**
     * Broadcasts a kit reset event for a specific player or all players across the network.
     */
    public void broadcastReset(String kitName, UUID targetUuid, String targetName) {
        if (plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;

        String serverId = plugin.getRedisService().getServerId();
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.KIT_RESET_SYNC, targetUuid, targetName, 0, kitName);
        plugin.getRedisService().publish(packet);
    }

    /**
     * Broadcasts a manual cooldown change for a player across the network.
     */
    public void broadcastCooldown(String kitName, UUID targetUuid, String targetName, long cooldownUntil) {
        if (plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;

        String serverId = plugin.getRedisService().getServerId();
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.KIT_COOLDOWN_SYNC, targetUuid, targetName, cooldownUntil, kitName);
        plugin.getRedisService().publish(packet);
    }

    /**
     * Dispatches a kit grant to a player who is online on a remote server.
     */
    public boolean dispatchRemoteGive(CommandSender sender, String kitName, String targetPlayerName, UUID targetUuid, boolean checkCooldown) {
        if (plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return false;
        }
        if (kitName == null || kitName.isBlank() || kitName.length() > 64 || kitName.indexOf(';') >= 0) return false;

        String senderName = sender instanceof Player p ? p.getName() : "CONSOLE";
        String serverId = plugin.getRedisService().getServerId();
        RemoteGivePayload grant = new RemoteGivePayload(UUID.randomUUID().toString(), kitName, checkCooldown, senderName);
        String payload = grant.serialize();

        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.KIT_GIVE_REMOTE, targetUuid, targetPlayerName, 0, payload);
        plugin.getRedisService().publish(packet);
        return true;
    }

    /**
     * Broadcasts a kit reload notification so all server instances reload their kit definitions.
     */
    public void broadcastReload() {
        if (plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) return;

        String serverId = plugin.getRedisService().getServerId();
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.KIT_RELOAD_SYNC, null, null, 0, serverId);
        plugin.getRedisService().publish(packet);
    }

    /**
     * Handles incoming Redis packets from other server instances.
     */
    public void handleIncomingPacket(CrossServerPacket packet) {
        if (packet == null || packet.getAction() == null) return;

        switch (packet.getAction()) {
            case KIT_CLAIM_SYNC -> handleClaimSync(packet);
            case KIT_RESET_SYNC -> handleResetSync(packet);
            case KIT_COOLDOWN_SYNC -> handleCooldownSync(packet);
            case KIT_GIVE_REMOTE -> handleRemoteGive(packet);
            case KIT_RELOAD_SYNC -> handleReloadSync(packet);
        }
    }

    private void handleClaimSync(CrossServerPacket packet) {
        UUID uuid = packet.getTargetUuid();
        if (uuid == null) return;

        String extra = packet.getExtra();
        if (extra == null || !extra.contains(";")) return;

        String[] parts = extra.split(";", -1);
        if (parts.length != 4) return;

        String kitName = parts[0];
        long cooldownUntil;
        try {
            cooldownUntil = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            cooldownUntil = (long) packet.getAmount();
        }
        boolean oneTime = Boolean.parseBoolean(parts[2]);
        boolean bought = Boolean.parseBoolean(parts[3]);

        plugin.getKitManager().updateLocalRecord(uuid, kitName, cooldownUntil, oneTime, bought);
    }

    private void handleResetSync(CrossServerPacket packet) {
        String kitName = packet.getExtra();
        if (kitName == null || kitName.isEmpty()) return;

        UUID uuid = packet.getTargetUuid();
        if (uuid == null || "*".equals(packet.getTargetName())) {
            plugin.getKitManager().resetLocalCacheForAll(kitName);
        } else {
            plugin.getKitManager().resetLocalCache(kitName, uuid);
        }
    }

    private void handleCooldownSync(CrossServerPacket packet) {
        UUID uuid = packet.getTargetUuid();
        String kitName = packet.getExtra();
        if (uuid == null || kitName == null) return;

        long cooldownUntil = (long) packet.getAmount();
        plugin.getKitManager().updateLocalCooldown(uuid, kitName, cooldownUntil);
    }

    private void handleRemoteGive(CrossServerPacket packet) {
        RemoteGivePayload grant = RemoteGivePayload.parse(packet.getExtra());
        if (grant == null) return;
        String grantId = grant.grantId();
        String kitName = grant.kitName();
        boolean checkCooldown = grant.checkCooldown();
        String senderName = grant.senderName();

        Player target = null;
        if (packet.getTargetUuid() != null) {
            target = Bukkit.getPlayer(packet.getTargetUuid());
        }
        if (target == null && packet.getTargetName() != null) {
            target = Bukkit.getPlayer(packet.getTargetName());
        }

        if (target != null && target.isOnline()) {
            Player finalTarget = target;
            Kit kit = plugin.getKitManager().getKit(kitName);
            if (kit != null) {
                // Claim the grant only after the target is confirmed online and we are
                // about to deliver it, so a grant is never "consumed but not delivered".
                plugin.getScheduler().runEntity(finalTarget, () -> {
                    if (!finalTarget.isOnline()) return;
                    plugin.getRedisService().tryConsumeKitGrant(grantId).thenAccept(consumed -> {
                        if (!consumed) return;
                        plugin.getScheduler().runEntity(finalTarget, () -> {
                            if (!finalTarget.isOnline()) {
                                plugin.getLogger().warning("[Kit Cross-Server] Grant " + grantId + " was consumed but target disconnected before delivery.");
                                return;
                            }
                            plugin.getKitManager().claimKitAsync(finalTarget, kit, !checkCooldown, false)
                                    .whenComplete((result, failure) -> {
                                        if (failure == null && result != null && result.isSuccess()) {
                                            plugin.getLogger().info("[Kit Cross-Server] Delivered grant " + grantId + " for kit '" + kitName + "' to " + finalTarget.getName() + " (Dispatched by " + senderName + " on " + packet.getSourceServer() + ")");
                                        } else {
                                            String reason = failure != null ? failure.getMessage()
                                                    : result != null ? result.status().name() : "unknown";
                                            plugin.getLogger().warning("[Kit Cross-Server] Grant " + grantId + " was consumed but delivery failed: " + reason);
                                        }
                                    });
                        });
                    });
                });
            }
        }
    }

    private void handleReloadSync(CrossServerPacket packet) {
        plugin.getLogger().info("[Kit Cross-Server] Received remote kits reload signal from server: " + packet.getSourceServer());
        plugin.getScheduler().runAsync(() -> {
            plugin.getKitManager().loadSettings();
            plugin.getKitManager().loadKits();
            if (plugin.getKitGuiManager() != null) {
                plugin.getKitGuiManager().loadConfig();
            }
        });
    }

    static record RemoteGivePayload(String grantId, String kitName, boolean checkCooldown, String senderName) {
        String serialize() {
            return grantId + ";" + kitName + ";" + checkCooldown + ";" + senderName;
        }

        static RemoteGivePayload parse(String payload) {
            if (payload == null || payload.length() > 256) return null;
            String[] parts = payload.split(";", -1);
            if (parts.length != 4 || parts[1].isBlank() || parts[1].length() > 64 || parts[3].length() > 16) return null;
            try {
                UUID.fromString(parts[0]);
            } catch (IllegalArgumentException invalidGrantId) {
                return null;
            }
            if (!"true".equalsIgnoreCase(parts[2]) && !"false".equalsIgnoreCase(parts[2])) return null;
            return new RemoteGivePayload(parts[0], parts[1], Boolean.parseBoolean(parts[2]), parts[3]);
        }
    }

    public void shutdown() {
        // Any cleanup if required
    }
}
