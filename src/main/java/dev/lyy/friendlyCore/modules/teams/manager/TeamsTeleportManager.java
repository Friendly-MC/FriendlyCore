package dev.lyy.friendlycore.modules.teams.manager;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket;
import dev.lyy.friendlycore.modules.teams.model.TeamHome;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages warmup timers, movement cancellation, and cross-server handoffs for team home teleportation.
 * <p>
 * Safely runs on Folia's EntityScheduler to prevent thread and region desynchronization.
 */
public class TeamsTeleportManager {

    public record PendingCrossTeleport(Location location, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60_000L;
        }
    }

    private final FriendlyCore plugin;
    private final Map<UUID, TeleportSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCrossTeleport> pendingCrossTeleports = new ConcurrentHashMap<>();
    private int teleportTimerSeconds = 5;
    private Object cleanupTask;

    public TeamsTeleportManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        loadConfiguration();
        this.cleanupTask = plugin.getScheduler().runAsyncTimer(this::cleanupExpiredPendingTeleports, 100L, 100L);
    }

    public void shutdown() {
        if (cleanupTask != null) {
            plugin.getScheduler().cancel(cleanupTask);
            cleanupTask = null;
        }
        for (TeleportSession session : activeSessions.values()) {
            session.cancel();
        }
        activeSessions.clear();
        pendingCrossTeleports.clear();
    }

    public void loadConfiguration() {
        FileConfiguration config = plugin.getConfigService().getTeamsConfig();
        this.teleportTimerSeconds = config.getInt("Plugin-Settings.teleportation-timer", 5);
    }

    public boolean isTeleporting(UUID playerUuid) {
        return playerUuid != null && activeSessions.containsKey(playerUuid);
    }

    public void registerPendingTeleport(UUID uuid, Location location) {
        if (uuid == null || location == null) return;
        pendingCrossTeleports.put(uuid, new PendingCrossTeleport(location, System.currentTimeMillis()));

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Location claimedLoc = pollPendingTeleport(uuid);
            if (claimedLoc != null) {
                teleportArrivingPlayer(player, claimedLoc);
            }
        }
    }

    public boolean hasPendingCrossTeleport(UUID uuid) {
        if (uuid == null) return false;
        PendingCrossTeleport pending = pendingCrossTeleports.get(uuid);
        return pending != null && !pending.isExpired();
    }

    public Location pollPendingTeleport(UUID uuid) {
        PendingCrossTeleport pending = pendingCrossTeleports.remove(uuid);
        if (pending != null && !pending.isExpired()) {
            return pending.location();
        }
        return null;
    }

    public void cleanupExpiredPendingTeleports() {
        pendingCrossTeleports.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public void teleportArrivingPlayer(Player player, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) return;

        plugin.getScheduler().runEntityLater(player, () -> {
            if (!player.isOnline()) return;
            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, location).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    TeamMessages.send(plugin, player, "info-teleported-home");
                }
            });
        }, 1L);
    }

    public boolean startTeleport(Player player, TeamHome home) {
        if (player == null || home == null || !canTeleport(player)) return false;
        UUID uuid = player.getUniqueId();

        if (activeSessions.containsKey(uuid)) {
            TeamMessages.send(plugin, player, "info-already-teleporting");
            return false;
        }

        String localServer = plugin.getRedisService() != null && plugin.getRedisService().isEnabled()
                ? plugin.getRedisService().getServerId()
                : "";
        boolean isCrossServer = home.getServer() != null && !home.getServer().isBlank()
                && !localServer.isBlank() && !home.getServer().equalsIgnoreCase(localServer);

        Location destination = home.toLocation();
        if (!isCrossServer && (destination == null || destination.getWorld() == null)) {
            TeamMessages.send(plugin, player, "the-world-for-this-team-home-is");
            return false;
        }

        // Instant teleport if warm-up is disabled or player has bypass permission
        if (teleportTimerSeconds <= 0 || player.hasPermission("zelteams.teleport.bypass") || player.hasPermission("friendlycore.teams.teleport.bypass") || player.hasPermission("friendlycore.teams.admin")) {
            executeTeleport(player, home);
            return true;
        }

        TeleportSession session = new TeleportSession(player, home, teleportTimerSeconds);
        activeSessions.put(uuid, session);
        session.start();
        return true;
    }

    public boolean startTeleport(Player player, Location destination, String homeName) {
        if (player == null || destination == null) return false;
        TeamHome tempHome = TeamHome.fromLocation(homeName != null ? homeName : "home", destination,
                plugin.getRedisService() != null && plugin.getRedisService().isEnabled() ? plugin.getRedisService().getServerId() : "");
        return startTeleport(player, tempHome);
    }

    public void cancelTeleport(UUID uuid, boolean notify) {
        if (uuid == null) return;
        TeleportSession session = activeSessions.remove(uuid);
        if (session != null) {
            session.cancel();
            if (notify) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    TeamMessages.send(plugin, player, "info-teleportation-cancel");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                }
            }
        }
    }

    private boolean canTeleport(Player player) {
        var module = plugin.getTeamsModule();
        if (module == null) return false;
        TeamsManager manager = module.getTeamsManager();
        return manager != null && manager.getTeamByPlayer(player.getUniqueId())
                .map(team -> manager.canUseHome(team, player, false)).orElse(false);
    }

    private void executeTeleport(Player player, TeamHome home) {
        if (player == null || !player.isOnline() || home == null || !canTeleport(player)) return;

        String localServer = plugin.getRedisService() != null && plugin.getRedisService().isEnabled()
                ? plugin.getRedisService().getServerId()
                : "";
        boolean isCrossServer = home.getServer() != null && !home.getServer().isBlank()
                && !localServer.isBlank() && !home.getServer().equalsIgnoreCase(localServer);

        if (isCrossServer && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            // Notify target server of incoming teleport packet
            HomeTeleportPacket packet = new HomeTeleportPacket(
                    home.getServer(), player.getUniqueId(), player.getName(),
                    home.getWorldName(), home.getX(), home.getY(), home.getZ(),
                    home.getYaw(), home.getPitch(), true
            );

            dev.lyy.friendlycore.common.redis.CrossServerPacket crossPacket =
                    new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                            localServer,
                            dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.HOME_CROSS_TELEPORT,
                            player.getUniqueId(),
                            player.getName(),
                            0.0,
                            plugin.getGson().toJson(packet)
                    );

            plugin.getRedisService().publish(crossPacket);

            if (plugin.getTransferFreezeService() != null) {
                plugin.getTransferFreezeService().freeze(player);
            }

            // Connect player to target proxy server on their EntityScheduler
            dev.lyy.friendlycore.common.ProxyTransferUtil.connect(plugin, player, home.getServer());

            TeamMessages.send(plugin, player, "connecting-to-server-for-team-home", "%target_server%", home.getServer(), "%home%", home.getName());
            return;
        }

        Location destination = home.toLocation();
        if (destination == null || destination.getWorld() == null) {
            TeamMessages.send(plugin, player, "the-world-for-this-team-home-is");
            return;
        }

        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, destination).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                TeamMessages.send(plugin, player, "info-teleported-home", "%home%", home.getName());
            }
        });
    }

    private class TeleportSession {
        private final Player player;
        private final TeamHome home;
        private final Location origin;
        private int remainingSeconds;
        private Object taskObject;

        public TeleportSession(Player player, TeamHome home, int seconds) {
            this.player = player;
            this.home = home;
            this.origin = player.getLocation().clone();
            this.remainingSeconds = seconds;
        }

        public void start() {
            TeamMessages.send(plugin, player, "teleporting-in-s-do-not-move", "%remaining_seconds%", String.valueOf(remainingSeconds));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);

            // Execute ticks on player's EntityScheduler for thread safety
            this.taskObject = plugin.getScheduler().runEntityTimer(player, this::tick, 20L, 20L);
        }

        private void tick() {
            if (!player.isOnline() || !player.isValid()) {
                cancel();
                activeSessions.remove(player.getUniqueId());
                return;
            }

            // Cancel if player moves beyond threshold (0.2 blocks) or changes worlds
            if (!player.getWorld().equals(origin.getWorld()) || player.getLocation().distanceSquared(origin) > 0.04) {
                cancelTeleport(player.getUniqueId(), true);
                return;
            }

            remainingSeconds--;

            if (remainingSeconds > 0) {
                TeamMessages.send(plugin, player, "teleporting-in-s-don-t-move-or", "%remaining_seconds%", String.valueOf(remainingSeconds));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
            } else {
                cancel();
                activeSessions.remove(player.getUniqueId());
                executeTeleport(player, home);
            }
        }

        public void cancel() {
            if (taskObject != null) {
                plugin.getScheduler().cancel(taskObject);
                taskObject = null;
            }
        }
    }
}
