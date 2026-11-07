package dev.lyy.friendlycore.modules.tpa.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.tpa.SafeTeleportChecker;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaTeleportTicket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.Map;

/**
 * Manages player profile loading, session tear-down, and cross-server arrival ticket redemption upon join.
 */
public class TpaPlayerListener implements Listener {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpaPlayerListener(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Asynchronously load player preferences and daily counts from database
        tpaManager.loadProfileAsync(player);

        // Redeem pending cross-server arrival ticket if player connected via proxy transfer
        if (tpaManager.getCrossServerService() != null) {
            TpaTeleportTicket ticket = tpaManager.getCrossServerService().consumeTicket(player.getUniqueId());
            if (ticket != null) {
                plugin.getScheduler().runEntityLater(player, () -> {
                    if (!player.isOnline()) return;

                    Player host = ticket.getHostUuid() != null ? Bukkit.getPlayer(ticket.getHostUuid()) : null;
                    if (host == null && ticket.getHostName() != null) {
                        host = Bukkit.getPlayerExact(ticket.getHostName());
                    }

                    if (host != null && host.isOnline() && host.getWorld() != null) {
                        Player finalHost = host;
                        plugin.getScheduler().runEntity(finalHost, () -> {
                            if (!player.isOnline()) return;
                            Location targetLoc = finalHost.isOnline() && finalHost.getWorld() != null
                                     ? finalHost.getLocation().clone()
                                    : ticket.toLocation();
                            if (targetLoc != null && targetLoc.getWorld() != null) {
                                executeArrivalTeleport(player, finalHost, targetLoc, ticket);
                            }
                        });
                    } else {
                        Location targetLoc = ticket.toLocation();
                        if (targetLoc != null && targetLoc.getWorld() != null) {
                            executeArrivalTeleport(player, null, targetLoc, ticket);
                        }
                    }
                }, 3L);
            }
        }
    }

    private void executeArrivalTeleport(Player player, Player host, Location targetLoc, TpaTeleportTicket ticket) {
        // Handoff to destination region scheduler on Folia to validate landing safety
        plugin.getScheduler().runLocation(targetLoc, () -> {
            if (!player.isOnline()) return;

            Location safeDest = SafeTeleportChecker.getSafeDestination(player, host, targetLoc, tpaManager.getSafeSettings());
            Location finalDest = (safeDest != null) ? safeDest : targetLoc;

            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, finalDest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    String sound = tpaManager.getConfig().getString("sounds.accept-teleport", "entity.experience_orb.pickup");
                    TextService.playSound(player, sound, 1.0f, 1.0f);
                    tpaManager.sendConfigMessage(player, "tpa.accepted-teleport", Map.of("%player%", ticket.getHostName() != null ? ticket.getHostName() : ""));
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        tpaManager.unloadProfile(player);
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(player.getUniqueId());
    }
}
