package dev.lyy.friendlycore.modules.spawn;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.SafeTeleportUtil;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Last location is only Redis: null on hub destinations, server+XYZ on gameplay servers.
 *
 * <pre>
 * /spawn, /tp, /tpa → hub     → last location = null
 * Land on economy-b (or quit) → last location = that XYZ
 * Join hub with saved economy → one durable handoff, restore XYZ
 * Connect fails               → clear routing and stay on current backend
 * </pre>
 */
public class LastLocationService implements Listener, PluginMessageListener {

    public static final String ROUTING_META = "friendlycore:last-location-routing";
    private static final String REDIS_KEY_PREFIX = "friendlycore:last_location:";
    private static final String HUB_ARRIVAL_PREFIX = "friendlycore:hub_arrival:";
    private static final long HUB_ARRIVAL_TTL_SECONDS = 30L;
    private static final String SPAWN_RESPAWN_PREFIX = "friendlycore:spawn_respawn:";
    private static final long SPAWN_RESPAWN_TTL_SECONDS = 60L;

    private final FriendlyCore plugin;
    private final SpawnManager spawnManager;
    private final Map<UUID, SpawnData> pendingArrivals = new ConcurrentHashMap<>();
    private final Map<UUID, Object> failsafeTasks = new ConcurrentHashMap<>();
    private volatile String proxyServerName;

    public String getProxyServerName() {
        return proxyServerName;
    }

    public LastLocationService(FriendlyCore plugin, SpawnManager spawnManager) {
        this.plugin = plugin;
        this.spawnManager = spawnManager;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "BungeeCord", this);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not listen for BungeeCord GetServer replies", t);
        }
    }

    public void shutdown() {
        pendingArrivals.clear();
        for (Object task : failsafeTasks.values()) {
            plugin.getScheduler().cancel(task);
        }
        failsafeTasks.clear();
        try {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "BungeeCord", this);
        } catch (Throwable ignored) {}
    }

    public boolean isEnabled() {
        return plugin.getConfigService().getSpawnConfig().getBoolean("join-spawn.last-location.enabled", true);
    }

    /**
     * Destination is the hub ({@code /spawn}, TPA/TP to spawn): last location = null.
     * Gameplay destinations are written when the player is actually on that server.
     */
    public void notifyGoingTo(Player player, String targetServer) {
        if (!isEnabled() || player == null || targetServer == null || targetServer.isBlank()) {
            return;
        }
        if (!isHubDestination(targetServer)) {
            return;
        }
        clearLastLocation(player.getUniqueId());
        markHubArrival(player.getUniqueId());
        plugin.getLogger().info("[LastLocation] going to hub; last=null " + player.getName());
    }

    public boolean isHubDestination(String targetServer) {
        if (targetServer == null || targetServer.isBlank()) {
            return false;
        }
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        return LastLocationPolicy.isHubServer(
                targetServer,
                spawnManager.getSpawnData(),
                config.getStringList("join-spawn.last-location.hub-servers"));
    }

    public boolean isRouting(Player player) {
        return player != null && player.hasMetadata(ROUTING_META);
    }

    /**
     * {@code /spawn} (and other hub teleports) set last location to null so the
     * next hub login is not auto-sent back to economy.
     */
    public void clearLastLocation(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            try {
                plugin.getRedisService().deletePersistent(REDIS_KEY_PREFIX + uuid);
                plugin.getLogger().info("[LastLocation] cleared " + uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("[LastLocation] Failed to clear last location for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void markHubArrival(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            try {
                plugin.getRedisService().setPersistent(HUB_ARRIVAL_PREFIX + uuid, "1", HUB_ARRIVAL_TTL_SECONDS);
            } catch (Exception e) {
                plugin.getLogger().warning("[LastLocation] Failed to mark hub arrival for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void clearHubArrival(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            try {
                plugin.getRedisService().deletePersistent(HUB_ARRIVAL_PREFIX + uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("[LastLocation] Failed to clear hub arrival for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private boolean hasHubArrival(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return false;
        }
        String value = plugin.getRedisService().getPersistent(HUB_ARRIVAL_PREFIX + uuid);
        return value != null && !value.isBlank();
    }

    public java.util.concurrent.CompletableFuture<Boolean> markSpawnRespawn(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                plugin.getRedisService().setPersistent(SPAWN_RESPAWN_PREFIX + uuid, "1", SPAWN_RESPAWN_TTL_SECONDS);
                return "1".equals(plugin.getRedisService().getPersistent(SPAWN_RESPAWN_PREFIX + uuid));
            }, plugin.getScheduler().getAsyncExecutor());
        } catch (java.util.concurrent.RejectedExecutionException e) {
            plugin.getLogger().warning("[LastLocation] Could not schedule spawn respawn marker for " + uuid);
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
    }

    public void clearSpawnRespawn(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            try {
                plugin.getRedisService().deletePersistent(SPAWN_RESPAWN_PREFIX + uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("[LastLocation] Failed to clear spawn respawn for " + uuid + ": " + e.getMessage());
            }
        });
    }

    public boolean hasSpawnRespawn(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return false;
        }
        String value = plugin.getRedisService().getPersistent(SPAWN_RESPAWN_PREFIX + uuid);
        return value != null && !value.isBlank();
    }

    /**
     * True when this join is an intentional teleport (spawn/TPA/home/RTP/admin),
     * not a cold login that should restore last location.
     */
    private boolean isInboundTeleport(UUID uuid) {
        return isInboundTeleport(uuid, hasHubArrival(uuid), hasSpawnRespawn(uuid));
    }

    private boolean isInboundTeleport(UUID uuid, boolean hubArrival, boolean spawnRespawn) {
        if (uuid == null) {
            return false;
        }
        if (hubArrival || spawnRespawn) {
            return true;
        }
        try {
            if (plugin.getTpaManager() != null && plugin.getTpaManager().getCrossServerService() != null
                    && plugin.getTpaManager().getCrossServerService().hasTicket(uuid)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (plugin.getHomesManager() != null && plugin.getHomesManager().hasPendingCrossTeleport(uuid)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (plugin.getTeamsModule() != null && plugin.getTeamsModule().getTeleportManager() != null
                    && plugin.getTeamsModule().getTeleportManager().hasPendingCrossTeleport(uuid)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (plugin.getRtpManager() != null && plugin.getRtpManager().getCrossServerService() != null
                    && (plugin.getRtpManager().getCrossServerService().isReturnArrival(uuid)
                    || plugin.getRtpManager().getCrossServerService().getTicket(uuid) != null)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (plugin.getEssentialsManager() != null && plugin.getEssentialsManager().getCrossServerService() != null) {
                dev.lyy.friendlycore.modules.essentials.model.AdminTeleportTicket ticket =
                        plugin.getEssentialsManager().getCrossServerService().getArrivalTicket(uuid);
                if (ticket != null && !ticket.isExpired()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Write last location while the player is on a gameplay backend (quit or landing).
     * Hub backends never write here — hub destinations use {@link #clearLastLocation}.
     */
    public void saveCurrentLocation(Player player) {
        if (!isEnabled() || player == null) {
            return;
        }
        if (isHubBackend()) {
            return;
        }
        boolean dead = false;
        try {
            dead = player.isDead();
        } catch (Throwable ignored) {}
        boolean pendingDeath = (spawnManager != null && (spawnManager.hasPendingRespawn(player) || spawnManager.isCrossServerRespawning(player.getUniqueId())));
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        if (dead || pendingDeath) {
            clearLastLocation(uuid);
            return;
        }
        Location loc = null;
        try {
            loc = player.getLocation();
        } catch (Throwable ignored) {}
        if (loc == null) {
            return;
        }
        SpawnData current = SpawnData.fromLocation(resolveSaveServerId(), loc);
        if (current == null) {
            return;
        }

        plugin.getScheduler().runAsync(() -> {
            if (hasHubArrival(uuid) || hasSpawnRespawn(uuid)) {
                return;
            }
            saveLastLocationNow(uuid, current);
            plugin.getLogger().info("[LastLocation] save " + name + " " + describe(current));
        });
    }

    public void saveLastLocation(UUID uuid, SpawnData data) {
        plugin.getScheduler().runAsync(() -> saveLastLocationNow(uuid, data));
    }

    private void saveLastLocationNow(UUID uuid, SpawnData data) {
        if (uuid == null || data == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return;
        }
        try {
            plugin.getRedisService().setPersistent(REDIS_KEY_PREFIX + uuid, data.toJson());
        } catch (Exception e) {
            plugin.getLogger().warning("[LastLocation] Failed to save last location for " + uuid + ": " + e.getMessage());
        }
    }

    public void handleIncomingTicket(CrossServerPacket packet) {
        if (packet == null || packet.getTargetUuid() == null || packet.getExtra() == null) {
            return;
        }
        SpawnData data = SpawnData.fromJson(packet.getExtra());
        if (data == null || !isLocalDestination(data)) {
            return;
        }
        pendingArrivals.put(packet.getTargetUuid(), data);
        Player online = Bukkit.getPlayer(packet.getTargetUuid());
        if (online != null && online.isOnline()) {
            plugin.getScheduler().runEntity(online, () -> completeArrival(online, data));
        }
    }

    /**
     * @return true when last-location handling took over so join-spawn ALWAYS
     * should not also teleport the player to spawn.
     */
    public boolean tryHandleJoin(Player player) {
        if (player == null || !isEnabled()) {
            return false;
        }
        try {
            if (player.isDead()) {
                finishRouting(player);
                return false;
            }
        } catch (Throwable ignored) {}

        requestProxyServerName(player);
        if (!isHubBackend()) {
            clearHubArrival(player.getUniqueId());
        }

        SpawnData arrival = pendingArrivals.remove(player.getUniqueId());
        if (arrival != null) {
            applyBlindness(player);
            markRouting(player);
            scheduleFailsafe(player);
            plugin.getScheduler().runEntityLater(player, () -> completeArrival(player, arrival), 2L);
            return true;
        }

        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        boolean hub = isHub(player, config);

        if (hub) {
            applyBlindness(player);
            markRouting(player);
            scheduleFailsafe(player);
            long delay = Math.max(2L, config.getLong("join-spawn.delay-ticks", 2L));
            plugin.getScheduler().runEntityLater(player, () -> beginJoinRouting(player, config, 0), delay);
        } else {
            beginJoinRouting(player, config, 0);
        }
        return true;
    }

    private void beginJoinRouting(Player player, FileConfiguration config, int attempt) {
        if (player == null || !player.isOnline()) {
            return;
        }
        requestProxyServerName(player);
        plugin.getScheduler().runAsync(() -> {
            UUID uuid = player.getUniqueId();
            boolean hub = isHub(player, config);
            boolean hubArrival = hasHubArrival(uuid);
            boolean spawnRespawn = hasSpawnRespawn(uuid);
            boolean crossRespawn = (spawnManager != null && spawnManager.isCrossServerRespawning(uuid));
            boolean inbound = hubArrival || spawnRespawn || isInboundTeleport(uuid, hubArrival, spawnRespawn);

            if (hub && inbound) {
                clearLastLocation(uuid);
            }
            if (spawnRespawn) {
                clearSpawnRespawn(uuid);
            }

            SpawnData ticket = pendingArrivals.remove(uuid);
            SpawnData last = (inbound || ticket != null) ? ticket : loadLastLocation(uuid);

            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (inbound) {
                    boolean spawnArrival = hubArrival || spawnRespawn || crossRespawn;
                    if (hub && spawnArrival) {
                        teleportToConfiguredSpawn(player);
                    } else {
                        finishRouting(player);
                    }
                    plugin.getLogger().info("[LastLocation] " + player.getName()
                            + " inbound teleport hub=" + hub + (hub ? " last=null" : ""));
                    return;
                }

                if (last == null && attempt < 1) {
                    plugin.getScheduler().runEntityLater(player, () -> beginJoinRouting(player, config, attempt + 1), 8L);
                    return;
                }
                if (hub && LastLocationPolicy.shouldRouteToRemote(last, spawnManager.resolveLocalServerId(), proxyServerName)
                        && (proxyServerName == null || proxyServerName.isBlank()) && attempt < 4) {
                    requestProxyServerName(player);
                    plugin.getScheduler().runEntityLater(player, () -> beginJoinRouting(player, config, attempt + 1), 5L);
                    return;
                }
                routePlayer(player, last, config);
            });
        });
    }

    private void routePlayer(Player player, SpawnData last, FileConfiguration config) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String local = spawnManager.resolveLocalServerId();
        String proxy = proxyServerName;
        SpawnData configured = spawnManager.getSpawnData();
        List<String> hubs = config.getStringList("join-spawn.last-location.hub-servers");
        List<String> spawnWorlds = config.getStringList("join-spawn.last-location.spawn-worlds");
        boolean hub = isHub(player, config);

        if (LastLocationPolicy.alreadyOnSavedServer(last, local, proxy)) {
            plugin.getLogger().info("[LastLocation] " + player.getName()
                    + " last=" + describe(last) + " action=STAY");
            finishRouting(player);
            return;
        }

        if (LastLocationPolicy.isSpawnLogout(last, configured, hubs, spawnWorlds)) {
            plugin.getLogger().info("[LastLocation] " + player.getName()
                    + " last=" + describe(last) + " action=" + (hub ? "SPAWN" : "STAY"));
            if (hub) {
                teleportToConfiguredSpawn(player);
            } else {
                finishRouting(player);
            }
            return;
        }

        if (LastLocationPolicy.shouldForwardFromHub(hub, last, local, proxy)) {
            sendToRemote(player, last);
            return;
        }

        if (!LastLocationPolicy.canRestoreHere(last, local, proxy)) {
            // A gameplay backend joined from elsewhere (End portal, selector): the player meant to be here.
            plugin.getLogger().info("[LastLocation] " + player.getName()
                    + " last=" + describe(last) + " local=" + local + " action=STAY (saved on another server)");
            finishRouting(player);
            return;
        }

        plugin.getLogger().info("[LastLocation] " + player.getName()
                + " last=" + describe(last) + " local=" + local + " action=RESTORE");
        completeArrival(player, last);
    }

    private void sendToRemote(Player player, SpawnData last) {
        String target = last != null ? last.server() : null;
        if (player == null || !player.isOnline() || target == null || target.isBlank() || isLocalServer(target)) {
            finishRouting(player);
            return;
        }
        markRouting(player);
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new CrossServerPacket(
                    spawnManager.resolveLocalServerId(),
                    CrossServerPacket.Action.LAST_LOCATION_TICKET,
                    player.getUniqueId(),
                    player.getName(),
                    0.0,
                    last.toJson()
            ));
        }
        applyBlindness(player);
        plugin.getLogger().info("[LastLocation] " + player.getName() + " CONNECT " + target);
        Object failsafe = failsafeTasks.remove(player.getUniqueId());
        if (failsafe != null) plugin.getScheduler().cancel(failsafe);
        spawnManager.sendPlayerToProxyServer(player, target).whenComplete((sent, error) ->
                plugin.getScheduler().runEntity(player, () -> {
                    if (!Boolean.TRUE.equals(sent) || error != null) finishRouting(player);
                    else scheduleFailsafe(player);
                }));
    }

    private void teleportToConfiguredSpawn(Player player) {
        SpawnData spawn = spawnManager.getSpawnData();
        Location dest = spawn != null ? spawn.toLocation() : null;
        if (dest == null || dest.getWorld() == null) {
            finishRouting(player);
            return;
        }
        SafeTeleportUtil.teleportAsync(player, dest).whenComplete((success, error) -> {
            plugin.getScheduler().runEntity(player, () -> finishRouting(player));
        });
    }

    private void completeArrival(Player player, SpawnData data) {
        completeArrival(player, data, 0);
    }

    private void completeArrival(Player player, SpawnData data, int attempt) {
        if (player == null || !player.isOnline() || data == null) {
            finishRouting(player);
            return;
        }
        pendingArrivals.remove(player.getUniqueId());
        Location dest = data.toLocation();
        if (dest == null || dest.getWorld() == null) {
            if (isLocalDestination(data)) {
                finishRouting(player);
                return;
            }
            if (attempt < 8) {
                plugin.getScheduler().runEntityLater(player, () -> completeArrival(player, data, attempt + 1), 10L);
                return;
            }
            plugin.getLogger().warning("[LastLocation] " + player.getName()
                    + " world '" + data.world() + "' is not loaded here; staying put.");
            finishRouting(player);
            return;
        }
        applyBlindness(player);
        markRouting(player);
        SafeTeleportUtil.teleportAsync(player, dest).whenComplete((success, error) -> {
            plugin.getScheduler().runEntity(player, () -> finishRouting(player));
        });
    }

    private SpawnData loadLastLocation(UUID uuid) {
        if (uuid == null || plugin.getRedisService() == null || !plugin.getRedisService().isEnabled()) {
            return null;
        }
        return SpawnData.fromJson(plugin.getRedisService().getPersistent(REDIS_KEY_PREFIX + uuid));
    }

    private boolean isLocalDestination(SpawnData data) {
        if (data == null) {
            return false;
        }
        return data.server() == null || data.server().isBlank() || isLocalServer(data.server());
    }

    private boolean isLocalServer(String server) {
        return server != null && !server.isBlank()
                && (server.equalsIgnoreCase(spawnManager.resolveLocalServerId())
                || server.equalsIgnoreCase(proxyServerName));
    }

    private boolean isHub(Player player, FileConfiguration config) {
        String world = player.getWorld() != null ? player.getWorld().getName() : "";
        return LastLocationPolicy.isHubContext(
                spawnManager.resolveLocalServerId(),
                world,
                proxyServerName,
                spawnManager.getSpawnData(),
                config.getStringList("join-spawn.last-location.hub-servers"),
                config.getStringList("join-spawn.last-location.spawn-worlds"));
    }

    private boolean isHubBackend() {
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        List<String> hubs = config.getStringList("join-spawn.last-location.hub-servers");
        SpawnData configured = spawnManager.getSpawnData();
        return LastLocationPolicy.isHubServer(spawnManager.resolveLocalServerId(), configured, hubs)
                || LastLocationPolicy.isHubServer(proxyServerName, configured, hubs);
    }

    private String resolveSaveServerId() {
        if (proxyServerName != null && !proxyServerName.isBlank()) {
            return proxyServerName;
        }
        return spawnManager.resolveLocalServerId();
    }

    private void requestProxyServerName(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("GetServer");
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Throwable ignored) {}
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (channel == null || message == null) {
            return;
        }
        if (!"BungeeCord".equals(channel) && !"bungeecord:main".equalsIgnoreCase(channel)) {
            return;
        }
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if ("GetServer".equals(sub)) {
                String name = in.readUTF();
                if (name != null && !name.isBlank()) {
                    this.proxyServerName = name;
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinBlindness(PlayerJoinEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        requestProxyServerName(player);
        if (!isHub(player, plugin.getConfigService().getSpawnConfig())) {
            return;
        }
        applyBlindness(player);
        markRouting(player);
        scheduleFailsafe(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoveWhileRouting(PlayerMoveEvent event) {
        if (!event.getPlayer().hasMetadata(ROUTING_META)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        // Allow pure head rotation / look without fighting packets
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        // Allow downward gravity and vertical falling without fighting client physics
        boolean falling = to.getY() <= from.getY();
        boolean sameHorizontalBlock = from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ();
        if (sameHorizontalBlock && falling) {
            return;
        }
        // Allow micro-movements within the same horizontal block when vertical delta is tiny (< 0.1)
        if (sameHorizontalBlock && Math.abs(to.getY() - from.getY()) < 0.1) {
            return;
        }
        // Only clamp horizontal runaway or upward flight, preserving view angles and allowing gravity
        // to land safely to prevent anti-cheat BadPacketsA violations and rubberbanding
        Location reset = from.clone();
        reset.setYaw(to.getYaw());
        reset.setPitch(to.getPitch());
        if (falling) {
            reset.setY(to.getY());
        }
        event.setTo(reset);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitWhileRouting(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player != null && player.hasMetadata(ROUTING_META)) {
            finishRouting(player);
        }
    }

    private void applyBlindness(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        int ticks = Math.max(40, config.getInt("join-spawn.last-location.blindness-ticks", 80));
        try {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 1, false, false, false));
            if (config.getBoolean("join-spawn.last-location.darkness", true)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, false));
            }
            // Safely restrict client movement without packet fighting
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 255, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 200, false, false, false));
        } catch (Throwable ignored) {}
    }

    private void clearBlindness(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.removePotionEffect(PotionEffectType.DARKNESS);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
        } catch (Throwable ignored) {}
    }

    private void markRouting(Player player) {
        if (player != null) {
            player.setMetadata(ROUTING_META, new FixedMetadataValue(plugin, true));
        }
    }

    private void scheduleFailsafe(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Object previous = failsafeTasks.remove(uuid);
        if (previous != null) {
            plugin.getScheduler().cancel(previous);
        }
        long ticks = Math.max(40L, plugin.getConfigService().getSpawnConfig()
                .getLong("join-spawn.last-location.failsafe-ticks", 160L));
        Object task = plugin.getScheduler().runEntityLater(player, () -> {
            failsafeTasks.remove(uuid);
            if (player.isOnline() && player.hasMetadata(ROUTING_META)) {
                plugin.getLogger().warning("[LastLocation] " + player.getName() + " failsafe; staying put");
                finishRouting(player);
            }
        }, ticks);
        if (task != null) {
            failsafeTasks.put(uuid, task);
        }
    }

    public void finishRouting(Player player) {
        if (player != null) {
            Object failsafe = failsafeTasks.remove(player.getUniqueId());
            if (failsafe != null) {
                plugin.getScheduler().cancel(failsafe);
            }
        }
        clearRouting(player);
        clearBlindness(player);
        if (plugin.getTransferFreezeService() != null) {
            plugin.getTransferFreezeService().unfreeze(player);
        }
        if (player != null && player.isOnline() && !isHubBackend()) {
            saveCurrentLocation(player);
        }
        try {
            if (player != null && player.isOnline()) {
                player.resetTitle();
            }
        } catch (Throwable ignored) {}
    }

    private void clearRouting(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.removeMetadata(ROUTING_META, plugin);
        } catch (Throwable ignored) {}
    }

    private static String describe(SpawnData data) {
        if (data == null) {
            return "none";
        }
        return data.server() + ":" + data.world()
                + " " + (int) data.x() + "," + (int) data.y() + "," + (int) data.z();
    }
}
