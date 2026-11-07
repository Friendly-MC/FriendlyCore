package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Listens for player movement and damage during RTP warmups, handles post-teleport
 * invulnerability grace periods, and claims cross-server tickets upon login.
 */
public class RtpListener implements Listener {

    private final FriendlyCore plugin;
    private final RtpManager rtpManager;

    public RtpListener(FriendlyCore plugin, RtpManager rtpManager) {
        this.plugin = plugin;
        this.rtpManager = rtpManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!rtpManager.isWarmupActive(uuid) || !rtpManager.isCancelOnMove()) {
            return;
        }

        Location from = rtpManager.getWarmupStartLocation(uuid);
        Location to = event.getTo();
        if (from == null || to == null) return;

        if (from.getWorld() != to.getWorld()) {
            rtpManager.cancelWarmup(player, "rtp.cancelled-movement", "&#ff5252You moved! Teleportation cancelled.");
            return;
        }

        double distanceSq = from.distanceSquared(to);
        double threshold = rtpManager.getMoveThreshold();
        if (distanceSq > (threshold * threshold)) {
            rtpManager.cancelWarmup(player, "rtp.cancelled-movement", "&#ff5252You moved! Teleportation cancelled.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (rtpManager.isInvulnerable(uuid)) {
            event.setCancelled(true);
            return;
        }

        // Cancel warmup if player takes damage while waiting
        if (rtpManager.isWarmupActive(uuid) && rtpManager.isCancelOnDamage()) {
            rtpManager.cancelWarmup(player, "rtp.cancelled-damage", "&#ff5252You took damage! Teleportation cancelled.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        rtpManager.getCrossServerService().clearReturnArrival(player);
        rtpManager.cancelWarmup(player, null, null);
        rtpManager.removeInvulnerable(player.getUniqueId());
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        rtpManager.getCrossServerService().handleArrival(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        if (event.getWorld() != null && rtpManager.getLocationCache() != null) {
            rtpManager.getLocationCache().purgeWorld(event.getWorld().getName());
        }
    }
}
