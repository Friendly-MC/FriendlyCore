package dev.lyy.friendlycore.modules.tpa.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tpa.TpaCountdownTask;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Listens for player movement and damage events during teleport warmup countdowns.
 */
public class TpaMoveListener implements Listener {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpaMoveListener(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        TpaCountdownTask task = tpaManager.getActiveCountdown(player.getUniqueId());
        if (task == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Cancel countdown immediately if any coordinate changed and walk range is zero
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            double walkRange = tpaManager.getConfig().getDouble("allowed-walk-range", 1.0);
            if (walkRange == 0) {
                task.notifyMovementCancel();
                task.cancel();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        TpaCountdownTask task = tpaManager.getActiveCountdown(player.getUniqueId());
        if (task != null) {
            task.notifyDamageCancel();
        }
    }
}
