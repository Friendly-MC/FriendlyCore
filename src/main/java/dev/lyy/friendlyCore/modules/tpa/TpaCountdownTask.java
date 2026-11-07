package dev.lyy.friendlycore.modules.tpa;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manages the warmup countdown timer for teleportation.
 * Handles interval action bar updates, movement and damage detection, Folia entity
 * thread binding, and dynamic target position resolution upon completion.
 */
public class TpaCountdownTask {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;
    private final Player player;
    private final UUID targetPlayerUuid;
    private final Location destination;
    private final Location startLocation;
    private final long totalDurationMs;
    private final boolean useSmoothCountdown;
    private final double allowedWalkRange;
    private final Consumer<Boolean> onComplete;

    private long remainingMs;
    private int lastBroadcastSecond = -1;
    private Object scheduledTask;
    private volatile boolean cancelled = false;

    public TpaCountdownTask(FriendlyCore plugin, TpaManager tpaManager, Player player, UUID targetPlayerUuid,
                            Location destination, long durationSeconds, boolean useSmoothCountdown,
                            double allowedWalkRange, Consumer<Boolean> onComplete) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
        this.player = player;
        this.targetPlayerUuid = targetPlayerUuid;
        this.destination = destination != null ? destination.clone() : null;
        this.startLocation = player.getLocation().clone();
        this.totalDurationMs = durationSeconds * 1000L;
        this.remainingMs = totalDurationMs;
        this.useSmoothCountdown = useSmoothCountdown;
        this.allowedWalkRange = allowedWalkRange;
        this.onComplete = onComplete;
    }

    public TpaCountdownTask(FriendlyCore plugin, TpaManager tpaManager, Player player, Location destination,
                            long durationSeconds, boolean useSmoothCountdown, double allowedWalkRange,
                            Consumer<Boolean> onComplete) {
        this(plugin, tpaManager, player, null, destination, durationSeconds, useSmoothCountdown, allowedWalkRange, onComplete);
    }

    public void start() {
        if (totalDurationMs <= 0) {
            finish(true);
            return;
        }

        // Tick every 50ms for smooth action bar animations, or every 1000ms for standard intervals
        long intervalTicks = useSmoothCountdown ? 1L : 20L;
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

            // Cancel if player moved beyond the allowed radius
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

        String timeStr = String.valueOf(currentSecond);
        FileConfiguration messages = plugin.getConfigService().getMessages();

        Map<String, String> timePlaceholders = Map.of(
                "%time%", timeStr,
                "%seconds%", timeStr,
                "%second%", timeStr
        );

        // Smooth action bar notification on each tick
        if (useSmoothCountdown) {
            String abMsg = messages.getString("tpa.teleport-countdown-actionbar.message", "&7Teleporting in &b%time%&7 seconds.");
            plugin.getTextService().sendActionBar(player, abMsg, timePlaceholders);
        }

        // Chat message and sound cues on whole second boundaries
        if (currentSecond != lastBroadcastSecond) {
            lastBroadcastSecond = currentSecond;

            if (!useSmoothCountdown) {
                String chatMsg = messages.getString("tpa.teleport-countdown.message", "&7Teleporting in &b%time%&7 seconds.");
                plugin.getTextService().sendMessage(player, chatMsg, timePlaceholders);
            }

            String soundKey = tpaManager.getConfig().getString("sounds.count-down", "entity.enderman.teleport");
            TextService.playSound(player, soundKey, 1.0f, 1.0f);
        }
    }

    public void notifyDamageCancel() {
        if (cancelled) return;
        FileConfiguration messages = plugin.getConfigService().getMessages();
        String cancelMsg = messages.getString("tpa.teleport-cancelled-damage",
                messages.getString("spawn.teleport-cancelled-damage", "&#ff3845Teleportation cancelled because you took damage!"));
        plugin.getTextService().sendMessage(player, cancelMsg, Map.of());

        String errorSound = tpaManager.getConfig().getString("sounds.error", "entity.villager.no");
        TextService.playSound(player, errorSound, 1.0f, 1.0f);
        cancel();
    }

    public void notifyMovementCancel() {
        FileConfiguration messages = plugin.getConfigService().getMessages();
        String cancelBar = messages.getString("tpa.teleport-movement-cancellation-action-bar.message", "&cTeleportation has been canceled as you have moved!");
        plugin.getTextService().sendActionBar(player, cancelBar, Map.of());

        String cancelMsg = messages.getString("tpa.teleport-cancelled.message", "&cTeleport has been cancelled!");
        plugin.getTextService().sendMessage(player, cancelMsg, Map.of());

        String errorSound = tpaManager.getConfig().getString("sounds.error", "entity.villager.no");
        TextService.playSound(player, errorSound, 1.0f, 1.0f);
    }

    public void cancel() {
        if (cancelled) return;
        this.cancelled = true;
        cancelTaskOnly();
        tpaManager.removeActiveCountdown(player.getUniqueId());
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
        this.cancelled = true;
        tpaManager.removeActiveCountdown(player.getUniqueId());

        if (!success || !player.isOnline()) {
            if (onComplete != null) {
                onComplete.accept(false);
            }
            return;
        }

        // Live target tracking for online player on the local server
        if (targetPlayerUuid != null) {
            Player target = Bukkit.getPlayer(targetPlayerUuid);
            if (target != null && target.isOnline()) {
                plugin.getScheduler().runEntity(target, () -> {
                    if (!target.isOnline() || !player.isOnline()) {
                        if (onComplete != null) onComplete.accept(false);
                        return;
                    }

                    Location liveLoc = target.getLocation().clone();
                    if (liveLoc.getWorld() == null) {
                        if (onComplete != null) onComplete.accept(false);
                        return;
                    }

                    List<String> blacklistedWorlds = tpaManager.getConfig().getStringList("blacklisted-worlds");
                    if (tpaManager.isWorldBlacklisted(liveLoc.getWorld().getName(), blacklistedWorlds)) {
                        tpaManager.sendConfigMessage(player, "tpa.blacklisted-world", Map.of());
                        if (onComplete != null) onComplete.accept(false);
                        return;
                    }

                    List<String> blacklistedRegions = tpaManager.getConfig().getStringList("world-guard.blacklisted-regions");
                    if (plugin.getHookManager().getProtectionHook().isRegionBlocked(liveLoc, blacklistedRegions)) {
                        tpaManager.sendConfigMessage(player, "tpa.blacklisted-region", Map.of());
                        if (onComplete != null) onComplete.accept(false);
                        return;
                    }

                    // Handoff to target location thread on Folia to evaluate safety and teleport
                    plugin.getScheduler().runLocation(liveLoc, () -> {
                        if (!player.isOnline() || !target.isOnline()) {
                            if (onComplete != null) onComplete.accept(false);
                            return;
                        }

                        Location safeDest = SafeTeleportChecker.getSafeDestination(player, target, liveLoc, tpaManager.getSafeSettings());
                        if (safeDest == null) {
                            tpaManager.sendConfigMessage(player, "tpa.teleport-fail-safe", Map.of());
                            if (onComplete != null) onComplete.accept(false);
                            return;
                        }

                        executePlayerTeleport(safeDest);
                    });
                });
                return;
            }
        }

        if (destination != null && destination.getWorld() != null) {
            plugin.getScheduler().runLocation(destination, () -> {
                if (!player.isOnline()) {
                    if (onComplete != null) onComplete.accept(false);
                    return;
                }

                Location safeDest = SafeTeleportChecker.getSafeDestination(player, null, destination, tpaManager.getSafeSettings());
                if (safeDest == null) {
                    tpaManager.sendConfigMessage(player, "tpa.teleport-fail-safe", Map.of());
                    if (onComplete != null) onComplete.accept(false);
                    return;
                }

                executePlayerTeleport(safeDest);
            });
            return;
        }

        // Cross-server warmup complete; ready for proxy transport
        if (onComplete != null) {
            onComplete.accept(true);
        }
    }

    private void executePlayerTeleport(Location safeDest) {
        if (!player.isOnline() || safeDest == null || safeDest.getWorld() == null) {
            if (onComplete != null) onComplete.accept(false);
            return;
        }

        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, safeDest).thenAccept(tpSuccess -> {
            if (Boolean.TRUE.equals(tpSuccess)) {
                String acceptSound = tpaManager.getConfig().getString("sounds.accept-teleport", "entity.experience_orb.pickup");
                TextService.playSound(player, acceptSound, 1.0f, 1.0f);
                if (onComplete != null) {
                    onComplete.accept(true);
                }
            } else {
                if (onComplete != null) {
                    onComplete.accept(false);
                }
            }
        });
    }

    public UUID getPlayerUuid() {
        return player.getUniqueId();
    }

    public UUID getTargetPlayerUuid() {
        return targetPlayerUuid;
    }

    public int getRemainingSeconds() {
        return (int) Math.ceil(Math.max(0.0, remainingMs / 1000.0));
    }
}
