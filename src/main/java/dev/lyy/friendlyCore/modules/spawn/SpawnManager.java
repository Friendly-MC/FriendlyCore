package dev.lyy.friendlycore.modules.spawn;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.event.player.AbstractRespawnEvent;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.EffectUtil;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Flying;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for spawn coordinates, cross-server warp routing, first-join handling,
 * respawn redirection, and spawn world protection mechanics.
 */
public class SpawnManager implements Listener {

    private final FriendlyCore plugin;
    private final NamespacedKey firstJoinKey;
    private final NamespacedKey pendingRespawnKey;
    private final NamespacedKey pendingAirborneSpawnKey;
    private final NamespacedKey airborneKey;
    private volatile SpawnData spawnData;
    private volatile String lastRespawnTrace;
    private final ConcurrentHashMap<UUID, SpawnWarmupSession> pendingTeleports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> arrivalTickets = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> deathRejoinQueued = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> crossServerRespawns = ConcurrentHashMap.newKeySet();
    private LastLocationService lastLocationService;
    private Object ticketCleanupTask;

    public boolean isCrossServerRespawning(UUID uuid) {
        return uuid != null && crossServerRespawns.contains(uuid);
    }

    public void markCrossServerRespawn(UUID uuid) {
        if (uuid != null) {
            crossServerRespawns.add(uuid);
        }
    }

    public void clearCrossServerRespawn(UUID uuid) {
        if (uuid != null) {
            crossServerRespawns.remove(uuid);
        }
    }

    public static class SpawnWarmupSession {
        private final UUID playerUuid;
        private final int totalSeconds;
        private final java.util.concurrent.atomic.AtomicInteger remainingSeconds;
        private final long startTimeMs;
        private volatile Object task;

        public SpawnWarmupSession(UUID playerUuid, int totalSeconds, Object task) {
            this.playerUuid = playerUuid;
            this.totalSeconds = totalSeconds;
            this.remainingSeconds = new java.util.concurrent.atomic.AtomicInteger(totalSeconds);
            this.startTimeMs = System.currentTimeMillis();
            this.task = task;
        }

        public UUID getPlayerUuid() { return playerUuid; }
        public int getTotalSeconds() { return totalSeconds; }
        public int getRemainingSeconds() { return Math.max(0, remainingSeconds.get()); }
        public void decrement() { remainingSeconds.decrementAndGet(); }
        public Object getTask() { return task; }
        public void setTask(Object task) { this.task = task; }
    }

    public SpawnManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.firstJoinKey = new NamespacedKey(plugin, "has_joined");
        this.pendingRespawnKey = new NamespacedKey(plugin, "pending_respawn");
        this.pendingAirborneSpawnKey = new NamespacedKey(plugin, "pending_airborne_spawn");
        this.airborneKey = new NamespacedKey(plugin, "airborne");
    }

    public void initialize() {
        loadSpawnLocation();
        this.lastLocationService = new LastLocationService(plugin, this);
        this.lastLocationService.initialize();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new BeaconRewardListener(plugin), plugin);
        startTicketCleanup();
    }

    private void startTicketCleanup() {
        if (ticketCleanupTask != null) {
            plugin.getScheduler().cancel(ticketCleanupTask);
        }
        // Cleanup expired tickets older than 60 seconds (runs every 5 seconds)
        this.ticketCleanupTask = plugin.getScheduler().runAsyncTimer(() -> {
            long now = System.currentTimeMillis();
            arrivalTickets.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
        }, 100L, 100L);
    }

    public void reload() {
        loadSpawnLocation();
    }

    public void shutdown() {
        if (ticketCleanupTask != null) {
            plugin.getScheduler().cancel(ticketCleanupTask);
            ticketCleanupTask = null;
        }
        for (SpawnWarmupSession session : pendingTeleports.values()) {
            if (session.getTask() != null) {
                plugin.getScheduler().cancel(session.getTask());
            }
        }
        pendingTeleports.clear();
        arrivalTickets.clear();
        if (lastLocationService != null) {
            lastLocationService.shutdown();
        }
    }

    public LastLocationService getLastLocationService() {
        return lastLocationService;
    }

    public boolean isTeleporting(UUID uuid) {
        if (uuid == null) return false;
        return pendingTeleports.containsKey(uuid);
    }

    public int getRemainingWarmupSeconds(UUID uuid) {
        if (uuid == null) return 0;
        SpawnWarmupSession session = pendingTeleports.get(uuid);
        return session != null ? session.getRemainingSeconds() : 0;
    }

    public long getRemainingCooldownSeconds(UUID uuid) {
        if (uuid == null) return 0;
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        int cooldownSeconds = config.getInt("spawn.cooldown-seconds", 5);
        if (cooldownSeconds <= 0) return 0;
        return plugin.getCooldownManager().getRemainingSeconds("spawn", uuid, cooldownSeconds * 1000L);
    }

    public boolean isOnCooldown(UUID uuid) {
        if (uuid == null) return false;
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        int cooldownSeconds = config.getInt("spawn.cooldown-seconds", 5);
        if (cooldownSeconds <= 0) return false;
        return plugin.getCooldownManager().isOnCooldown("spawn", uuid, cooldownSeconds * 1000L);
    }

    public void loadSpawnLocation() {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        String defaultServer = resolveLocalServerId();
        ConfigurationSection section = config.getConfigurationSection("spawn.location");
        if (section != null) {
            this.spawnData = SpawnData.fromConfig(section, defaultServer);
        } else if (config.contains("spawn.location.world")) {
            // Legacy / flat config fallback
            String world = config.getString("spawn.location.world", "spawn");
            double x = config.getDouble("spawn.location.x", 0.5);
            double y = config.getDouble("spawn.location.y", 64.0);
            double z = config.getDouble("spawn.location.z", 0.5);
            float yaw = (float) config.getDouble("spawn.location.yaw", 0.0);
            float pitch = (float) config.getDouble("spawn.location.pitch", 0.0);
            this.spawnData = new SpawnData(defaultServer, world, x, y, z, yaw, pitch);
        }
    }

    public SpawnData getSpawnData() {
        if (spawnData == null) {
            loadSpawnLocation();
        }
        return spawnData;
    }

    public Location getSpawnLocation() {
        SpawnData data = getSpawnData();
        return data != null ? data.toLocation() : null;
    }

    public String resolveLocalServerId() {
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            return plugin.getRedisService().getServerId();
        }
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        return config.getString("spawn.cross-server.fallback-server", "lobby");
    }

    public boolean isLocalServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return true;
        }
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("spawn.cross-server.enabled", true)) {
            return true;
        }
        String localId = resolveLocalServerId();
        if (serverName.equalsIgnoreCase(localId)) {
            return true;
        }
        if (lastLocationService != null) {
            String proxy = lastLocationService.getProxyServerName();
            if (proxy != null && !proxy.isBlank() && serverName.equalsIgnoreCase(proxy)) {
                return true;
            }
        }
        return false;
    }

    public void setSpawnLocation(Location location) {
        setSpawnLocation(location, resolveLocalServerId());
    }

    public void setSpawnLocation(Location location, String serverName) {
        if (location == null || location.getWorld() == null) return;

        String server = (serverName != null && !serverName.isEmpty()) ? serverName : resolveLocalServerId();
        this.spawnData = SpawnData.fromLocation(server, location);

        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        ConfigurationSection section = config.getConfigurationSection("spawn.location");
        if (section == null) {
            section = config.createSection("spawn.location");
        }
        spawnData.writeToConfig(section);
        plugin.getConfigService().save("spawn.yml");

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            CrossServerPacket packet = new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.SPAWN_SYNC,
                    null,
                    null,
                    0.0,
                    spawnData.toJson()
            );
            plugin.getRedisService().publish(packet);
        }
    }

    public void handleIncomingSpawnSync(CrossServerPacket packet) {
        if (packet == null || packet.getExtra() == null) return;
        SpawnData remoteData = SpawnData.fromJson(packet.getExtra());
        if (remoteData != null && remoteData.server() != null && !remoteData.server().isBlank()
                && remoteData.world() != null && !remoteData.world().isBlank()) {
            this.spawnData = remoteData;
            // Keep /setspawn from reverting on other backends after a restart.
            FileConfiguration config = plugin.getConfigService().getSpawnConfig();
            ConfigurationSection section = config.getConfigurationSection("spawn.location");
            if (section == null) section = config.createSection("spawn.location");
            remoteData.writeToConfig(section);
            plugin.getConfigService().save("spawn.yml");
        }
    }

    public void handleIncomingSpawnTicket(CrossServerPacket packet) {
        if (packet == null || packet.getTargetUuid() == null) return;
        String targetServer = packet.getExtra();
        // Only the explicitly targeted server may honor the ticket; a missing or
        // empty target must never be treated as "any server".
        if (targetServer == null || targetServer.isBlank() || !isLocalServer(targetServer)) {
            return;
        }
        arrivalTickets.put(packet.getTargetUuid(), System.currentTimeMillis());
        if (lastLocationService != null && lastLocationService.isEnabled()) {
            lastLocationService.clearLastLocation(packet.getTargetUuid());
        }

        Player player = Bukkit.getPlayer(packet.getTargetUuid());
        if (player != null && player.isOnline()) {
            arrivalTickets.remove(packet.getTargetUuid());
            SpawnData data = getSpawnData();
            if (data != null) {
                Location loc = data.toLocation();
                if (loc != null && loc.getWorld() != null) {
                    plugin.getScheduler().runEntityLater(player, () -> {
                        if (player.isOnline()) {
                            executeLocalTeleport(player, loc);
                        }
                    }, 2L);
                }
            }
        }
    }

    public void teleportToSpawn(Player player, boolean bypassWarmup) {
        if (player == null) return;

        SpawnData targetSpawn = getSpawnData();
        if (targetSpawn == null || targetSpawn.world() == null || targetSpawn.world().isEmpty()) {
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("spawn.spawn-not-set",
                            "<red>Spawn location is not set yet! Please contact an administrator.</red>"), null);
            return;
        }

        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        int cooldownSeconds = config.getInt("spawn.cooldown-seconds", 5);
        if (!bypassWarmup && !player.hasPermission("friendlycore.bypass.cooldown") && cooldownSeconds > 0) {
            if (plugin.getCooldownManager().isOnCooldown("spawn", player.getUniqueId(), cooldownSeconds * 1000L)) {
                long remaining = plugin.getCooldownManager().getRemainingSeconds("spawn", player.getUniqueId(), cooldownSeconds * 1000L);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("spawn.cooldown",
                                "<red>Please wait <yellow>%seconds%s</yellow> before teleporting to spawn again.</red>"),
                        Map.of("%seconds%", String.valueOf(remaining), "%second%", String.valueOf(remaining), "%time%", String.valueOf(remaining)));
                return;
            }
        }

        if (pendingTeleports.containsKey(player.getUniqueId())) {
            return;
        }

        int warmupSeconds = bypassWarmup ? 0 : config.getInt("spawn.warmup-seconds", 3);

        if (warmupSeconds <= 0) {
            dispatchSpawnTeleport(player, targetSpawn);
            return;
        }

        SpawnWarmupSession session = new SpawnWarmupSession(player.getUniqueId(), warmupSeconds, null);
        pendingTeleports.put(player.getUniqueId(), session);
        freezeTransfer(player);

        String warmupSound = config.getString("spawn.sound.warmup", "BLOCK_NOTE_BLOCK_PLING");

        Object task = plugin.getScheduler().runEntityTimer(player, () -> {
            int rem = session.getRemainingSeconds();
            if (rem > 0) {
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("spawn.teleporting",
                                "<gradient:#00d2ff:#3a7bd5><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleporting to spawn in <#00d2ff>%seconds%</#00d2ff>... <red>Don't move!</red></gray>"),
                        Map.of("%seconds%", String.valueOf(rem), "%second%", String.valueOf(rem), "%time%", String.valueOf(rem)));
                float pitch = 1.0f + (warmupSeconds - rem) * 0.2f;
                TextService.playSound(player, warmupSound, 1.0f, pitch);
                session.decrement();
            } else {
                SpawnWarmupSession registered = pendingTeleports.remove(player.getUniqueId());
                if (registered != null && registered.getTask() != null) {
                    plugin.getScheduler().cancel(registered.getTask());
                }
                if (player.isOnline()) {
                    dispatchSpawnTeleport(player, targetSpawn);
                }
            }
        }, 0L, 20L);

        if (task != null) {
            session.setTask(task);
        }
    }

    public void dispatchSpawnTeleport(Player player, SpawnData data) {
        if (player == null || data == null) return;
        freezeTransfer(player);
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        int cooldownSeconds = config.getInt("spawn.cooldown-seconds", 5);

        if (!player.hasPermission("friendlycore.bypass.cooldown") && cooldownSeconds > 0) {
            plugin.getCooldownManager().setCooldown("spawn", player.getUniqueId());
        }

        String localServer = resolveLocalServerId();
        boolean isLocal = isLocalServer(data.server());

        if (lastLocationService != null && lastLocationService.isEnabled()) {
            lastLocationService.clearLastLocation(player.getUniqueId());
            if (!isLocal && data.server() != null && !data.server().isBlank()) {
                lastLocationService.notifyGoingTo(player, data.server());
            }
        }

        if (isLocal) {
            Location destination = data.toLocation();
            if (destination == null || destination.getWorld() == null) {
                unfreezeTransfer(player);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("spawn.spawn-not-set",
                                "<red>Spawn location is not set yet! Please contact an administrator.</red>"), null);
                return;
            }
            executeLocalTeleport(player, destination);
        } else {
            plugin.getTextService().sendActionBar(player,
                    plugin.getConfigService().getMessages().getString("spawn.teleport-cross-server",
                            "<gradient:#00d2ff:#3a7bd5><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleporting to spawn...</gray>"),
                    Map.of("%server%", data.server() != null ? data.server() : ""));

            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                CrossServerPacket packet = new CrossServerPacket(
                        localServer,
                        CrossServerPacket.Action.SPAWN_TICKET,
                        player.getUniqueId(),
                        player.getName(),
                        0.0,
                        data.server()
                );
                plugin.getRedisService().publish(packet);
            }

            // Send player to proxy server via BungeeCord channel
            sendPlayerToProxyServer(player, data.server());
        }
    }

    public void dispatchCrossServerRespawn(Player player, SpawnData data) {
        if (player == null || data == null) return;
        UUID uuid = player.getUniqueId();
        markCrossServerRespawn(uuid);
        freezeTransfer(player);

        java.util.concurrent.CompletableFuture<Boolean> marker =
                java.util.concurrent.CompletableFuture.completedFuture(false);
        if (lastLocationService != null) {
            marker = lastLocationService.markSpawnRespawn(uuid);
        }

        marker.whenComplete((saved, error) -> plugin.getScheduler().runEntityLater(player, () -> {
            if (!Boolean.TRUE.equals(saved) || error != null) {
                clearCrossServerRespawn(uuid);
                clearPendingRespawn(player);
                unfreezeTransfer(player);
                plugin.getLogger().warning("Could not confirm respawn routing for " + player.getName()
                        + "; keeping them on this server's spawn. Check that Redis is enabled and reachable on both backends.");
            } else if (player.isOnline()) {
                lastLocationService.clearLastLocation(uuid);
                if (data.server() != null && !data.server().isBlank()) {
                    lastLocationService.notifyGoingTo(player, data.server());
                }
                plugin.getRedisService().publish(new CrossServerPacket(
                        resolveLocalServerId(), CrossServerPacket.Action.SPAWN_TICKET,
                        uuid, player.getName(), 0.0, data.server()));
                sendPlayerToProxyServer(player, data.server());
            }
        }, 1L));
    }

    private void executeLocalTeleport(Player player, Location destination) {
        if (player == null || !player.isOnline() || destination == null || destination.getWorld() == null) return;
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();

        freezeTransfer(player);
        dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, destination).thenAccept(success -> {
            unfreezeTransfer(player);
            if (Boolean.TRUE.equals(success)) {
                if (hasPendingAirborneSpawn(player)) {
                    player.setGliding(false);
                }
                player.getPersistentDataContainer().remove(pendingAirborneSpawnKey);
                player.getPersistentDataContainer().remove(airborneKey);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString("spawn.teleport-success",
                                "<gradient:#00e676:#00b0ff><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleported to spawn!</gray>"), null);

                String tpSound = config.getString("spawn.sound.teleport", "ENTITY_ENDERMAN_TELEPORT");
                TextService.playSound(destination, tpSound, 1.0f, 1.0f);
            }
            if (lastLocationService != null) {
                lastLocationService.finishRouting(player);
            }
        });
    }

    public java.util.concurrent.CompletableFuture<Boolean> sendPlayerToProxyServer(Player player, String serverName) {
        if (player == null || !player.isOnline()) return java.util.concurrent.CompletableFuture.completedFuture(false);
        freezeTransfer(player);
        String playerName = player.getName();
        return dev.lyy.friendlycore.common.ProxyTransferUtil.connect(plugin, player, serverName).whenComplete((sent, error) -> {
            if (!Boolean.TRUE.equals(sent) || error != null) {
                plugin.getLogger().warning("[Spawn] Proxy Connect request was not sent for " + playerName
                        + " to " + serverName + (error != null ? ": " + error.getMessage() : ""));
            } else if (plugin.getConfigService().getMainConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[Spawn] Proxy Connect request sent for " + playerName + " to " + serverName);
            }
        });
    }

    public void cancelTeleport(UUID uuid, String reasonKey, String fallback) {
        SpawnWarmupSession session = pendingTeleports.remove(uuid);
        if (session != null) {
            if (session.getTask() != null) {
                plugin.getScheduler().cancel(session.getTask());
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                unfreezeTransfer(player);
                plugin.getTextService().sendActionBar(player,
                        plugin.getConfigService().getMessages().getString(reasonKey, fallback), null);
            } else {
                unfreezeTransfer(uuid);
            }
        }
    }

    private void freezeTransfer(Player player) {
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().freeze(player);
        }
    }

    private void unfreezeTransfer(Player player) {
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().unfreeze(player);
        }
    }

    private void unfreezeTransfer(UUID uuid) {
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().unfreeze(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        debugRespawn(event, config);
        if (!shouldOverrideRespawn(config, event.getRespawnReason(), event.isBedSpawn(), event.isAnchorSpawn())) {
            clearPendingRespawn(player);
            return;
        }

        SpawnData targetSpawn = getSpawnData();
        if (targetSpawn == null) {
            clearPendingRespawn(player);
            return;
        }

        boolean isLocal = isLocalServer(targetSpawn.server());
        if (isLocal) {
            Location loc = targetSpawn.toLocation();
            if (loc != null && loc.getWorld() != null) {
                event.setRespawnLocation(loc);
            }
            clearPendingRespawn(player);
        } else {
            Location fallback = player.getWorld() != null ? player.getWorld().getSpawnLocation() : event.getRespawnLocation();
            if (fallback != null && fallback.getWorld() != null) {
                event.setRespawnLocation(fallback);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        debugRespawn(event, config);
        if (!player.isOnline()
                || !shouldOverrideRespawn(config, event.getRespawnReason(), event.isBedSpawn(), event.isAnchorSpawn())) {
            return;
        }
        SpawnData targetSpawn = getSpawnData();
        if (targetSpawn == null) return;
        clearPendingRespawn(player);
        // The entity is alive now; scheduling during PlayerRespawnEvent can silently lose the transfer.
        if (!isLocalServer(targetSpawn.server())) {
            dispatchCrossServerRespawn(player, targetSpawn);
        } else if (event.getRespawnLocation().equals(targetSpawn.toLocation())) {
            TextService.playSound(player, config.getString("respawn.sound", "ENTITY_PLAYER_LEVELUP"), 1.0f, 1.0f);
        }
    }

    private static boolean shouldOverrideRespawn(FileConfiguration config, PlayerRespawnEvent.RespawnReason reason,
                                               boolean bedSpawn, boolean anchorSpawn) {
        String mode = config.getString("respawn.mode", "NO_BED").toUpperCase(Locale.ROOT);
        return !"DISABLED".equals(mode)
                && (reason == PlayerRespawnEvent.RespawnReason.DEATH
                    // Player.spigot().respawn() labels a death respawn as PLUGIN.
                    || reason == PlayerRespawnEvent.RespawnReason.PLUGIN
                    || (reason == PlayerRespawnEvent.RespawnReason.END_PORTAL
                        && config.getBoolean("respawn.override-end-portal", false)))
                && (!"NO_BED".equals(mode) || (!bedSpawn && !anchorSpawn));
    }

    public String getLastRespawnTrace() {
        String trace = lastRespawnTrace;
        return trace != null ? trace : "[Spawn] No respawn event observed with debug enabled since startup/reload.";
    }

    private void debugRespawn(AbstractRespawnEvent event, FileConfiguration config) {
        debugRespawn(event.getEventName(), event.getPlayer(), event.getRespawnReason(),
                event.isBedSpawn(), event.isAnchorSpawn(), config);
    }

    private void debugRespawn(String phase, Player player, PlayerRespawnEvent.RespawnReason reason,
                              boolean bed, boolean anchor, FileConfiguration config) {
        if (!plugin.getConfigService().getMainConfig().getBoolean("debug", false)) return;
        SpawnData target = getSpawnData();
        boolean override = shouldOverrideRespawn(config, reason, bed, anchor);
        lastRespawnTrace = "[Spawn] " + phase + " player=" + player.getName()
                + " reason=" + reason + " mode=" + config.getString("respawn.mode", "NO_BED")
                + " saved-spawn=" + (bed || anchor)
                + " online=" + player.isOnline() + " dead=" + player.isDead()
                + " valid=" + player.isValid()
                + " local=" + resolveLocalServerId() + " target=" + (target != null ? target.server() : "unset")
                + " route=" + (!override ? "vanilla" : target == null ? "unset" : isLocalServer(target.server()) ? "local" : "proxy")
                + " redis=" + (plugin.getRedisService() != null && plugin.getRedisService().isEnabled());
        plugin.getLogger().info(lastRespawnTrace);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Under FriendlySync the local markers are stale and synced ones ride along on RTP/TPA
        // transfers, so they would bounce an intentional arrival to spawn. Network airborne
        // logouts already land at spawn because quit clears the last location.
        boolean airborneRejoin = hasPendingAirborneSpawn(player) && canRecoverAirborne(player) && !isDataSynced();
        // Protect the saved airborne position before waiting for Redis or chunk loading.
        if (airborneRejoin) {
            player.getPersistentDataContainer().set(pendingAirborneSpawnKey, PersistentDataType.BYTE, (byte) 1);
            player.getPersistentDataContainer().remove(airborneKey);
            freezeTransfer(player);
        } else if (hasPendingAirborneSpawn(player)) {
            player.getPersistentDataContainer().remove(pendingAirborneSpawnKey);
            player.getPersistentDataContainer().remove(airborneKey);
        }
        UUID uuid = player.getUniqueId();
        Long ticketTime = arrivalTickets.remove(uuid);
        boolean hasTicket = (ticketTime != null && (System.currentTimeMillis() - ticketTime) <= 60_000L);
        boolean localRespawn = isCrossServerRespawning(uuid);
        LastLocationService service = lastLocationService;
        if (localRespawn || service == null || airborneRejoin) {
            handlePlayerJoin(player, hasTicket, localRespawn, airborneRejoin);
            return;
        }
        // Redis pool validation and GET can wait on sockets; never do either on a player thread.
        plugin.getScheduler().runAsync(() -> {
            boolean respawnArrival = service.hasSpawnRespawn(uuid);
            plugin.getScheduler().runEntity(player, () -> {
                if (player.isOnline()) {
                    handlePlayerJoin(player, hasTicket, respawnArrival, false);
                }
            });
        });
    }

    private void handlePlayerJoin(Player player, boolean hasTicket, boolean isRespawnArrival, boolean airborneRejoin) {
        UUID uuid = player.getUniqueId();
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();

        if (hasTicket || isRespawnArrival) {
            if (lastLocationService != null && lastLocationService.isEnabled()) {
                lastLocationService.clearLastLocation(uuid);
                lastLocationService.clearSpawnRespawn(uuid);
            }
            clearCrossServerRespawn(uuid);
            clearPendingRespawn(player);

            SpawnData data = getSpawnData();
            if (data != null) {
                Location loc = data.toLocation();
                if (loc != null && loc.getWorld() != null) {
                    plugin.getScheduler().runEntityLater(player, () -> {
                        if (player.isOnline()) {
                            executeLocalTeleport(player, loc);
                            if (isRespawnArrival) {
                                String sound = config.getString("respawn.sound", "ENTITY_PLAYER_LEVELUP");
                                TextService.playSound(player, sound, 1.0f, 1.0f);
                            }
                        }
                    }, 2L);
                    return;
                }
            }
        }

        if (airborneRejoin && !player.isDead()) {
            SpawnData data = getSpawnData();
            if (data != null) {
                dispatchSpawnTeleport(player, data);
                return;
            }
            unfreezeTransfer(player);
        }

        boolean isFirstJoin = !player.hasPlayedBefore() &&
                !player.getPersistentDataContainer().has(firstJoinKey, PersistentDataType.BYTE);

        if (isFirstJoin) {
            player.getPersistentDataContainer().set(firstJoinKey, PersistentDataType.BYTE, (byte) 1);
        }

        boolean lastLocationHandled = lastLocationService != null
                && lastLocationService.tryHandleJoin(player);

        String joinMode = config.getString("join-spawn.mode", "FIRST_JOIN").toUpperCase(Locale.ROOT);
        String bypassPerm = config.getString("join-spawn.bypass-permission", "friendlycore.spawn.joinbypass");
        boolean shouldTeleport = false;

        if (!lastLocationHandled && !player.hasPermission(bypassPerm)) {
            if ("ALWAYS".equals(joinMode)) {
                shouldTeleport = true;
            } else if ("FIRST_JOIN".equals(joinMode) && isFirstJoin) {
                shouldTeleport = true;
            } else if ("FORCE_WORLDS".equals(joinMode)) {
                List<String> forceWorlds = config.getStringList("join-spawn.force-spawn-worlds");
                String currentWorld = player.getWorld().getName();
                if (forceWorlds.contains(currentWorld) || forceWorlds.contains(currentWorld.toLowerCase(Locale.ROOT))) {
                    shouldTeleport = true;
                }
            }
        }

        long delayTicks = Math.max(1L, config.getLong("join-spawn.delay-ticks", 2L));

        if (shouldTeleport) {
            plugin.getScheduler().runEntityLater(player, () -> {
                if (!player.isOnline()) return;
                SpawnData data = getSpawnData();
                if (data != null) {
                    dispatchSpawnTeleport(player, data);
                }
                if (isFirstJoin) {
                    playFirstJoinEffects(player);
                }
            }, delayTicks);
        } else if (isFirstJoin) {
            plugin.getScheduler().runEntityLater(player, () -> {
                if (player.isOnline()) {
                    playFirstJoinEffects(player);
                }
            }, delayTicks);
        }

        scheduleDeathRejoin(player, config);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        markPendingRespawn(player);
        if (!plugin.getScheduler().isFolia()) return;
        // Folia's async respawn bypasses both Bukkit respawn events. Its entity
        // scheduler follows the player across regions and pauses during placement.
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (!player.isOnline() || !hasPendingRespawn(player)) {
                task.cancel();
                return;
            }
            if (player.isDead() || !player.isValid()) return;
            task.cancel();
            clearPendingRespawn(player);
            FileConfiguration config = plugin.getConfigService().getSpawnConfig();
            // Vanilla already validated the bed/anchor and cleared invalid spawn
            // records. Do not load or inspect a bed in another Folia region here.
            boolean savedSpawn = player.getRespawnLocation(false) != null;
            debugRespawn("FoliaRespawnComplete", player, PlayerRespawnEvent.RespawnReason.DEATH,
                    savedSpawn, false, config);
            if (!shouldOverrideRespawn(config, PlayerRespawnEvent.RespawnReason.DEATH, savedSpawn, false)) return;
            SpawnData target = getSpawnData();
            if (target == null) return;
            if (isLocalServer(target.server())) {
                executeLocalTeleport(player, target.toLocation());
            } else {
                dispatchCrossServerRespawn(player, target);
            }
        }, null, 1L, 1L);
    }

    public void markPendingRespawn(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.getPersistentDataContainer().set(pendingRespawnKey, PersistentDataType.BYTE, (byte) 1);
        } catch (Throwable ignored) {}
        try {
            player.setMetadata("friendlycore:pending-respawn", new FixedMetadataValue(plugin, true));
        } catch (Throwable ignored) {}
    }

    public boolean hasPendingRespawn(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (player.getPersistentDataContainer().has(pendingRespawnKey, PersistentDataType.BYTE)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            return player.hasMetadata("friendlycore:pending-respawn");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void clearPendingRespawn(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.getPersistentDataContainer().remove(pendingRespawnKey);
        } catch (Throwable ignored) {}
        try {
            player.removeMetadata("friendlycore:pending-respawn", plugin);
        } catch (Throwable ignored) {}
    }

    private void scheduleDeathRejoin(Player player, FileConfiguration config) {
        boolean dead;
        try {
            dead = player.isDead();
        } catch (Throwable t) {
            dead = false;
        }
        if (!dead && !hasPendingRespawn(player)) {
            return;
        }
        deathRejoinQueued.add(player.getUniqueId());
        long delayTicks = Math.max(6L, config.getLong("join-spawn.delay-ticks", 2L));
        plugin.getScheduler().runEntityLater(player, () -> handleDeathRejoin(player), delayTicks);
    }

    private void handleDeathRejoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!deathRejoinQueued.remove(player.getUniqueId())) {
            return;
        }
        boolean dead;
        try {
            dead = player.isDead();
        } catch (Throwable t) {
            dead = false;
        }
        if (dead) {
            try {
                player.spigot().respawn();
                // Respawn events are fired synchronously and the handlers own
                // the local/cross-server routing. Continuing here would enqueue a
                // second transfer for remote spawn servers.
                return;
            } catch (Throwable ignored) {}
        }
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        String respawnMode = config.getString("respawn.mode", "NO_BED").toUpperCase(Locale.ROOT);
        if ("DISABLED".equals(respawnMode)) {
            clearPendingRespawn(player);
            if (lastLocationService != null) {
                lastLocationService.finishRouting(player);
            }
            return;
        }
        SpawnData spawnData = getSpawnData();

        if (spawnData != null && !isLocalServer(spawnData.server())) {
            boolean useBed = "NO_BED".equals(respawnMode) && tryGetRespawnLocation(player) != null;
            if (!useBed) {
                clearPendingRespawn(player);
                dispatchCrossServerRespawn(player, spawnData);
                return;
            }
        }

        Location dest = resolveDeathRejoinSpawn(player, null);
        clearPendingRespawn(player);
        if (lastLocationService != null) {
            lastLocationService.finishRouting(player);
        }
        if (dest != null && dest.getWorld() != null) {
            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest);
        }
    }

    Location resolveDeathRejoinSpawn(Player player, Location bedOverride) {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        String respawnMode = config.getString("respawn.mode", "NO_BED");
        Location bed = tryGetRespawnLocation(player);
        if (bed == null) {
            bed = bedOverride;
        }
        Location landsSpawn = null;
        try {
            if (plugin.getHookManager() != null && plugin.getHookManager().getProtectionHook() != null) {
                landsSpawn = plugin.getHookManager().getProtectionHook().getLandsSpawn(player);
            }
        } catch (Throwable ignored) {}
        Location configured = null;
        SpawnData spawnData = getSpawnData();
        String spawnWorld = spawnData != null ? spawnData.world() : null;
        if (spawnData != null) {
            configured = spawnData.toLocation();
        }
        Location worldSpawn = null;
        try {
            if (player.getWorld() != null) {
                worldSpawn = player.getWorld().getSpawnLocation();
            }
        } catch (Throwable ignored) {}
        String currentWorld = null;
        try {
            currentWorld = player.getWorld() != null ? player.getWorld().getName() : null;
        } catch (Throwable ignored) {}
        return DeathRejoinSpawn.pick(respawnMode, bed, landsSpawn, configured, worldSpawn, currentWorld, spawnWorld);
    }

    @SuppressWarnings("deprecation")
    private static Location tryGetRespawnLocation(Player player) {
        if (player == null) return null;
        try {
            // Paper/Folia safe method: returns bed/anchor location without synchronous chunk loading
            try {
                java.lang.reflect.Method m = player.getClass().getMethod("getPotentialRespawnLocation");
                Object loc = m.invoke(player);
                if (loc instanceof Location l) {
                    return l;
                }
            } catch (Throwable ignored) {}

            return player.getRespawnLocation();
        } catch (Throwable ignored) {
            try {
                return player.getBedSpawnLocation();
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private void playFirstJoinEffects(Player player) {
        if (player == null || !player.isOnline()) return;
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();

        int uniqueJoins = plugin.getJoinQuitManager() != null ? plugin.getJoinQuitManager().getUniqueJoinsCount() : Bukkit.getOnlinePlayers().size();
        Map<String, String> placeholders = Map.of(
                "%player%", player.getName(),
                "%unique_joins%", String.valueOf(uniqueJoins)
        );

        if (config.getBoolean("first-join.broadcast-enabled", true)) {
            String broadcast = plugin.getConfigService().getMessages().getString("spawn.first-join-broadcast",
                    "<gradient:#00e676:#00b0ff><bold>WELCOME</bold></gradient> <dark_gray>»</dark_gray> <gray>Welcome <white>%player%</white> to the server! <dark_gray>(<#00e676>#%unique_joins%</#00e676>)</dark_gray></gray>");
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.getTextService().sendMessage(p, broadcast, placeholders);
            }
        }

        if (config.getBoolean("first-join.title.enabled", true)) {
            String title = config.getString("first-join.title.title", "<gradient:#00e676:#00b0ff><bold>WELCOME!</bold></gradient>");
            String subtitle = config.getString("first-join.title.subtitle", "<gray>Welcome to the server, <white>%player%</white>!</gray>");
            int fadeIn = config.getInt("first-join.title.fade-in-ticks", 10);
            int stay = config.getInt("first-join.title.stay-ticks", 70);
            int fadeOut = config.getInt("first-join.title.fade-out-ticks", 20);
            plugin.getTextService().sendTitle(player, title, subtitle, fadeIn, stay, fadeOut, placeholders);
        }

        if (config.getBoolean("first-join.actionbar.enabled", true)) {
            String actionbar = config.getString("first-join.actionbar.message", "<#00e676>Enjoy your adventure on the server!</#00e676>");
            plugin.getTextService().sendActionBar(player, actionbar, placeholders);
        }

        if (config.getBoolean("first-join.sound.enabled", true)) {
            String soundName = config.getString("first-join.sound.name", "UI_TOAST_CHALLENGE_COMPLETE");
            float volume = (float) config.getDouble("first-join.sound.volume", 1.0);
            float pitch = (float) config.getDouble("first-join.sound.pitch", 1.0);
            TextService.playSound(player, soundName, volume, pitch);
        }

        if (config.getBoolean("first-join.particles.enabled", true)) {
            String particleName = config.getString("first-join.particles.type", "TOTEM_OF_UNDYING");
            int count = config.getInt("first-join.particles.count", 40);
            double ox = config.getDouble("first-join.particles.offset-x", 0.6);
            double oy = config.getDouble("first-join.particles.offset-y", 1.0);
            double oz = config.getDouble("first-join.particles.offset-z", 0.6);
            double speed = config.getDouble("first-join.particles.speed", 0.1);

            Particle particle = EffectUtil.parseParticle(particleName, Particle.TOTEM_OF_UNDYING);
            EffectUtil.spawnParticle(player, particle, player.getLocation(), count, ox, oy, oz, speed);
        }

        if (config.getBoolean("first-join.firework.enabled", true)) {
            Location loc = player.getLocation();
            if (loc.getWorld() != null) {
                Firework firework = (Firework) loc.getWorld().spawnEntity(loc, EntityType.FIREWORK_ROCKET);
                firework.setMetadata("friendlycore_no_damage", new FixedMetadataValue(plugin, true));
                FireworkMeta meta = firework.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.fromRGB(0, 230, 118), Color.fromRGB(0, 176, 255))
                        .withFade(Color.fromRGB(255, 255, 255))
                        .trail(true)
                        .build());
                meta.setPower(1);
                firework.setFireworkMeta(meta);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Paper disables plugins before quit events during shutdown; persist the state while moving.
        updateAirborneMarker(event.getPlayer());
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("spawn.cancel-on-move", true)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            cancelTeleport(event.getPlayer().getUniqueId(), "spawn.teleport-cancelled-move",
                    "<gradient:#ff5252:#ff1744><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleportation cancelled because you moved!</gray>");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.getTransferFreezeService() != null && plugin.getTransferFreezeService().isFrozen(player)) {
                return;
            }
            FileConfiguration config = plugin.getConfigService().getSpawnConfig();
            if (!config.getBoolean("spawn.cancel-on-damage", true)) return;

            cancelTeleport(player.getUniqueId(), "spawn.teleport-cancelled-damage",
                    "<gradient:#ff5252:#ff1744><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleportation cancelled because you took damage!</gray>");
        }
    }

    public boolean hasPendingAirborneSpawn(Player player) {
        return player.getPersistentDataContainer().has(pendingAirborneSpawnKey, PersistentDataType.BYTE)
                || player.getPersistentDataContainer().has(airborneKey, PersistentDataType.BYTE);
    }

    /** FriendlySync/MC-Data-Bridge owns PDC, so this server's copy is not authoritative at join. */
    private boolean isDataSynced() {
        return plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook().isEnabled();
    }

    private boolean canRecoverAirborne(Player player) {
        if (player.isDead() || hasPendingRespawn(player)) return false;
        var pvp = plugin.getPvpManager();
        return pvp == null || (!pvp.getCombatTagManager().isTagged(player.getUniqueId())
                && !pvp.getCombatNpcManager().hasCombatLogout(player.getUniqueId()));
    }

    private static boolean isAirborne(Player player) {
        return !player.isDead() && !player.isOnGround() && !player.isInWater()
                && !player.isInLava() && !player.isInsideVehicle() && !player.isClimbing();
    }

    private void updateAirborneMarker(Player player) {
        var data = player.getPersistentDataContainer();
        if (isAirborne(player)) {
            if (data.has(airborneKey, PersistentDataType.BYTE)) return;
            data.set(airborneKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            data.remove(airborneKey);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Intentional transfers keep their own destination, even when the player is airborne.
        // A synced pending marker is never recovered (see onPlayerJoin); only live air counts there.
        boolean airborneRecovery = canRecoverAirborne(player) && ((!isDataSynced()
                && player.getPersistentDataContainer().has(pendingAirborneSpawnKey, PersistentDataType.BYTE))
                || (isAirborne(player)
                    && (plugin.getTransferFreezeService() == null || !plugin.getTransferFreezeService().isFrozen(player))
                    && (lastLocationService == null || !lastLocationService.isRouting(player))));
        if (airborneRecovery) {
            player.getPersistentDataContainer().set(pendingAirborneSpawnKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(pendingAirborneSpawnKey);
        }
        player.getPersistentDataContainer().remove(airborneKey);
        SpawnWarmupSession session = pendingTeleports.remove(uuid);
        if (session != null && session.getTask() != null) {
            plugin.getScheduler().cancel(session.getTask());
        }
        arrivalTickets.remove(uuid);
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(uuid);
        if (lastLocationService != null) {
            if (airborneRecovery) {
                lastLocationService.clearLastLocation(uuid);
            } else {
                lastLocationService.saveCurrentLocation(player);
            }
        }
        clearCrossServerRespawn(uuid);
    }

    public static boolean isHostileEntity(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Monster) return true;
        if (entity instanceof Slime) return true;
        if (entity instanceof Flying) return true;
        if (entity instanceof Hoglin || (entity.getType() != null && entity.getType().name().equals("ZOGLIN"))) return true;
        if (entity instanceof Boss) return true;
        if (entity instanceof Shulker) return true;
        EntityType type = entity.getType();
        if (type == EntityType.ENDERMITE || type == EntityType.SILVERFISH) return true;
        try {
            if (entity instanceof org.bukkit.entity.Enemy) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isProtectedWorld(FileConfiguration config, String worldName) {
        if (worldName == null || config == null) return false;
        List<String> protectedWorlds = config.getStringList("mob-protection.spawn-worlds");
        if (protectedWorlds.isEmpty()) {
            return worldName.equalsIgnoreCase("spawn");
        }
        return protectedWorlds.stream().anyMatch(configured ->
                configured.equalsIgnoreCase(worldName) || configured.equalsIgnoreCase("*") || configured.equalsIgnoreCase("all"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("mob-protection.enabled", false)) return;

        Location loc = event.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        if (!isProtectedWorld(config, loc.getWorld().getName())) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        EntityType entityType = event.getEntityType();
        boolean isHostile = isHostileEntity(event.getEntity());

        if (entityType == EntityType.ENDERMITE && (reason == CreatureSpawnEvent.SpawnReason.ENDER_PEARL || config.getBoolean("mob-protection.block-ender-pearl-endermite", true))) {
            event.setCancelled(true);
            return;
        }

        if (entityType == EntityType.SILVERFISH && (reason == CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK || reason == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS || reason == CreatureSpawnEvent.SpawnReason.POTION_EFFECT || config.getBoolean("mob-protection.block-silverfish-infestation", true))) {
            event.setCancelled(true);
            return;
        }

        // Allow administrative and plugin custom spawns if configured
        boolean allowPlugin = config.getBoolean("mob-protection.allow-plugin-spawns", true);
        if (allowPlugin && (reason == CreatureSpawnEvent.SpawnReason.CUSTOM || reason == CreatureSpawnEvent.SpawnReason.COMMAND)) {
            return;
        }

        List<String> blockedEntities = config.getStringList("mob-protection.blocked-entities");
        if (blockedEntities != null && !blockedEntities.isEmpty()) {
            for (String blocked : blockedEntities) {
                if (blocked != null && blocked.equalsIgnoreCase(entityType.name())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Block hostile monsters from spawning in protected spawn worlds
        if (isHostile && config.getBoolean("mob-protection.block-all-hostile", true)) {
            event.setCancelled(true);
            return;
        }

        List<String> configReasons = config.getStringList("mob-protection.blocked-reasons");
        if (configReasons != null && !configReasons.isEmpty()) {
            for (String r : configReasons) {
                if (r != null && r.equalsIgnoreCase(reason.name())) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (isHostile) {
            EnumSet<CreatureSpawnEvent.SpawnReason> blockedReasons = EnumSet.of(
                    CreatureSpawnEvent.SpawnReason.NATURAL,
                    CreatureSpawnEvent.SpawnReason.PATROL,
                    CreatureSpawnEvent.SpawnReason.RAID,
                    CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
                    CreatureSpawnEvent.SpawnReason.ENDER_PEARL,
                    CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK,
                    CreatureSpawnEvent.SpawnReason.POTION_EFFECT,
                    CreatureSpawnEvent.SpawnReason.INFECTION,
                    CreatureSpawnEvent.SpawnReason.DROWNED,
                    CreatureSpawnEvent.SpawnReason.PIGLIN_ZOMBIFIED,
                    CreatureSpawnEvent.SpawnReason.TRAP,
                    CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
                    CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
                    CreatureSpawnEvent.SpawnReason.SPAWNER,
                    CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
                    CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
                    CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
                    CreatureSpawnEvent.SpawnReason.CHUNK_GEN,
                    CreatureSpawnEvent.SpawnReason.SLIME_SPLIT,
                    CreatureSpawnEvent.SpawnReason.EXPLOSION,
                    CreatureSpawnEvent.SpawnReason.LIGHTNING,
                    CreatureSpawnEvent.SpawnReason.DEFAULT);
            if (blockedReasons.contains(reason)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("mob-protection.enabled", false)) return;
        if (!isHostileEntity(event.getEntity())) return;

        Location loc = event.getEntity().getLocation();
        if (loc.getWorld() == null || !isProtectedWorld(config, loc.getWorld().getName())) return;

        event.setCancelled(true);
        event.setTarget(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHostileDamage(EntityDamageByEntityEvent event) {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("mob-protection.enabled", false)) return;
        if (!isHostileEntity(event.getDamager())) return;

        Location loc = event.getEntity().getLocation();
        if (loc.getWorld() == null || !isProtectedWorld(config, loc.getWorld().getName())) return;

        event.setCancelled(true);
    }
}
