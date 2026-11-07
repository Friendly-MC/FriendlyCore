package dev.lyy.friendlycore.modules.homes;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Handles warmup timers for home teleports, tracking movement cancellations,
 * title/actionbar countdown broadcasts, and safe handoff for leashed entities.
 */
public class HomeTeleportCountdown {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;
    private final Player player;
    private final HomeData home;
    private final Location startLocation;
    private final long totalDurationMs;
    private final double allowedWalkRange;
    private final Consumer<Boolean> onComplete;

    private long remainingMs;
    private int lastBroadcastSecond = -1;
    private Object scheduledTask;
    private volatile boolean cancelled = false;

    public HomeTeleportCountdown(FriendlyCore plugin, HomesManager homesManager, Player player,
                                 HomeData home, long durationSeconds, double allowedWalkRange,
                                 Consumer<Boolean> onComplete) {
        this.plugin = plugin;
        this.homesManager = homesManager;
        this.player = player;
        this.home = home;
        this.startLocation = player.getLocation().clone();
        this.totalDurationMs = durationSeconds * 1000L;
        this.remainingMs = totalDurationMs;
        this.allowedWalkRange = allowedWalkRange;
        this.onComplete = onComplete;
    }

    public void start() {
        if (totalDurationMs <= 0) {
            finish(true);
            return;
        }

        long intervalTicks = 20L;
        long intervalMs = intervalTicks * 50L;

        broadcastCountdown();

        this.scheduledTask = plugin.getScheduler().runEntityTimer(player, () -> {
            if (cancelled) return;

            if (!player.isOnline()) {
                cancel();
                return;
            }

            if (startLocation.getWorld() == null || !startLocation.getWorld().equals(player.getWorld())) {
                notifyMovementCancel();
                cancel();
                return;
            }

            // Check if player exceeded maximum allowed movement radius
            double distanceSq = startLocation.distanceSquared(player.getLocation());
            if (distanceSq > (allowedWalkRange * allowedWalkRange)) {
                notifyMovementCancel();
                cancel();
                return;
            }

            remainingMs -= intervalMs;

            if (remainingMs <= 0) {
                cancelTaskOnly();
                finish(true);
            } else {
                broadcastCountdown();
            }
        }, intervalTicks, intervalTicks);
    }

    private void broadcastCountdown() {
        double secondsRemaining = Math.max(0.0, remainingMs / 1000.0);
        int currentSecond = (int) Math.ceil(secondsRemaining);

        if (currentSecond != lastBroadcastSecond && currentSecond > 0) {
            lastBroadcastSecond = currentSecond;
            Map<String, String> placeholders = Map.of(
                    "%time%", String.valueOf(currentSecond),
                    "%seconds%", String.valueOf(currentSecond),
                    "%second%", String.valueOf(currentSecond),
                    "%home%", home.getDisplayName(),
                    "%slot%", String.valueOf(home.getSlot())
            );

            homesManager.sendHomeMessage(player, "teleporting-chat", placeholders);
            homesManager.sendHomeActionBar(player, "teleporting-action-bar", placeholders);
            homesManager.playSound(player, "timer");
        }
    }

    private void notifyMovementCancel() {
        homesManager.sendHomeMessage(player, "teleport-cancel-chat", Map.of());
        homesManager.sendHomeActionBar(player, "teleport-cancel-action-bar", Map.of());
        homesManager.playSound(player, "error");
    }

    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        cancelTaskOnly();
        homesManager.removeActiveTeleport(player.getUniqueId());
        if (onComplete != null) {
            onComplete.accept(false);
        }
    }

    private void cancelTaskOnly() {
        if (scheduledTask != null) {
            plugin.getScheduler().cancel(scheduledTask);
            scheduledTask = null;
        }
    }

    private void finish(boolean success) {
        if (cancelled) return;
        homesManager.removeActiveTeleport(player.getUniqueId());
        if (onComplete != null) {
            onComplete.accept(success);
        }
    }

    public int getRemainingSeconds() {
        return (int) Math.ceil(Math.max(0.0, remainingMs / 1000.0));
    }

    /**
     * Teleports leashed entities attached to the player along with them safely using SafeTeleportUtil.
     */
    public static void teleportLeashedEntities(Player player, Location destination, Consumer<Boolean> callback) {
        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportWithLeashedEntities(player, destination, callback);
    }
}
