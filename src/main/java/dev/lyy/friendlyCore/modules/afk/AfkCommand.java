package dev.lyy.friendlycore.modules.afk;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ProxyTransferUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** A cancellable warmup followed by the shared durable proxy transfer. */
public final class AfkCommand implements CommandExecutor, TabCompleter, Listener {
    private final FriendlyCore plugin;
    private static final class Pending {
        Object task, countdown;
        int secondsLeft;
        boolean transferring;
    }
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public AfkCommand(FriendlyCore plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { Messages.send(plugin, sender, "core", "afk-players-only"); return true; }
        if (!player.hasPermission("friendlycore.afk.use") && !player.hasPermission("friendlycore.admin")) {
            message(player, "afk-no-permission"); return true;
        }
        if (args.length != 0) { message(player, "afk-usage"); return true; }
        var config = plugin.getConfigService().getMainConfig();
        if (!config.getBoolean("afk.enabled", true)) { message(player, "afk-disabled"); return true; }
        String target = config.getString("afk.server-id", "afk").strip();
        if (target.isEmpty()) { message(player, "afk-not-configured"); return true; }
        String local = plugin.getRedisService() != null ? plugin.getRedisService().getServerId()
                : config.getString("redis.server-id", "server-1");
        if (target.equalsIgnoreCase(local)) { message(player, "afk-already-there"); return true; }
        if (!canTransfer(player)) { message(player, "afk-blocked"); return true; }
        UUID uuid = player.getUniqueId();
        if (pending.containsKey(uuid)) { message(player, "afk-in-progress"); return true; }
        long cooldown = Math.clamp(config.getLong("afk.cooldown-seconds", 3), 0, 3600) * 1000L;
        if (!player.hasPermission("friendlycore.bypass.cooldown")
                && !plugin.getCooldownManager().tryAcquire("afk", uuid, cooldown)) {
            message(player, "afk-cooldown", "%time%",
                    String.valueOf(plugin.getCooldownManager().getRemainingSeconds("afk", uuid, cooldown))); return true;
        }
        Pending request = new Pending();
        if (pending.putIfAbsent(uuid, request) != null) return true;
        int seconds = player.hasPermission("friendlycore.bypass.warmup") ? 0
                : Math.clamp(config.getInt("afk.warmup-seconds", 3), 0, 60);
        if (seconds == 0) transfer(player, target, request);
        else {
            message(player, "afk-warmup", "%seconds%", String.valueOf(seconds), "%server%", target);
            request.task = plugin.getScheduler().runEntityLater(player, () -> transfer(player, target, request),
                    () -> pending.remove(uuid, request), seconds * 20L);
            if (request.task == null) { pending.remove(uuid, request); return true; }
            request.secondsLeft = seconds;
            request.countdown = plugin.getScheduler().runEntityTimer(player, () -> tick(player, target, request), 20L, 20L);
        }
        return true;
    }

    private void tick(Player player, String target, Pending request) {
        if (pending.get(player.getUniqueId()) != request || request.transferring || --request.secondsLeft <= 0) {
            plugin.getScheduler().cancel(request.countdown);
            return;
        }
        message(player, "afk-countdown", "%seconds%", String.valueOf(request.secondsLeft), "%server%", target);
    }

    private void stop(Pending request) {
        plugin.getScheduler().cancel(request.task);
        plugin.getScheduler().cancel(request.countdown);
    }

    private boolean canTransfer(Player player) {
        return player.isOnline() && !player.isDead() && player.getHealth() > 0
                && (plugin.getPvpManager() == null || !plugin.getPvpManager().getCombatTagManager().isTagged(player.getUniqueId()))
                && (plugin.getTransferFreezeService() == null || !plugin.getTransferFreezeService().isFrozen(player))
                && (plugin.getSpawnManager() == null || !plugin.getSpawnManager().isTeleporting(player.getUniqueId()))
                && (plugin.getRtpManager() == null || (!plugin.getRtpManager().isWarmupActive(player.getUniqueId())
                    && (plugin.getRtpManager().getCrossServerService() == null
                        || !plugin.getRtpManager().getCrossServerService().hasPendingRequest(player.getUniqueId()))))
                && !plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player);
    }

    private void transfer(Player player, String target, Pending request) {
        UUID uuid = player.getUniqueId();
        if (pending.get(uuid) != request) return;
        if (!canTransfer(player)) { cancel(player); return; }
        request.transferring = true;
        message(player, "afk-connecting", "%server%", target);
        // Save Core's account before the inventory handoff. No database work runs on the entity thread.
        if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().freeze(player);
        try {
            var saved = plugin.getEconomyManager() == null ? CompletableFuture.<Void>completedFuture(null)
                : plugin.getEconomyManager().savePlayerAccount(uuid);
            saved.thenCompose(ignored -> ProxyTransferUtil.connect(plugin, player, target)).whenComplete((sent, error) ->
                plugin.getScheduler().runEntity(player, () -> {
                    pending.remove(uuid, request);
                    if (error != null || !Boolean.TRUE.equals(sent)) {
                        if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().unfreeze(player);
                        message(player, "afk-failed", "%server%", target);
                    }
                }, () -> pending.remove(uuid, request)));
        } catch (RuntimeException e) {
            pending.remove(uuid, request);
            if (plugin.getTransferFreezeService() != null) plugin.getTransferFreezeService().unfreeze(player);
            message(player, "afk-save-failed");
        }
    }

    private void cancel(Player player) {
        Pending request = pending.get(player.getUniqueId());
        if (request == null || request.transferring || !pending.remove(player.getUniqueId(), request)) return;
        stop(request);
        message(player, "afk-cancelled");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        if (to != null && (from.getWorld() != to.getWorld() || from.getX() != to.getX()
                || from.getY() != to.getY() || from.getZ() != to.getZ())) cancel(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0) cancel(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Pending request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) stop(request);
    }

    public void shutdown() {
        pending.values().forEach(this::stop);
        pending.clear();
    }

    private void message(Player player, String key, String... placeholders) { Messages.send(plugin, player, "core", key, placeholders); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
