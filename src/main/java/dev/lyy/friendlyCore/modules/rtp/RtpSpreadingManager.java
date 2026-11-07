package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages spatial distribution algorithms and tracks recent teleport landings
 * to prevent player clustering across world regions.
 */
public class RtpSpreadingManager implements Listener {

    public enum DistributionAlgorithm {
        SECTOR_ROUND_ROBIN,
        GOLDEN_ANGLE,
        UNIFORM_RANDOM;

        public static DistributionAlgorithm fromString(String str) {
            if (str == null) return SECTOR_ROUND_ROBIN;
            try {
                return DistributionAlgorithm.valueOf(str.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return SECTOR_ROUND_ROBIN;
            }
        }
    }

    public record RecentTeleport(double x, double z, long timestamp) {}
    private record PlayerPosition(UUID worldId, double x, double z) {}

    private final FriendlyCore plugin;

    private boolean enabled;
    private double minPlayerDistance;
    private double minPlayerDistanceSq;
    private double minRecentDistance;
    private double minRecentDistanceSq;
    private long recentCacheDurationMs;
    private double minSpawnDistance;
    private double minSpawnDistanceSq;
    private DistributionAlgorithm defaultAlgorithm;
    private int sectorCount;

    // In-memory recent teleports per world (worldName lower-cased -> queue of recent teleports)
    private final Map<String, ConcurrentLinkedDeque<RecentTeleport>> recentTeleports = new ConcurrentHashMap<>();

    // Sector & Golden angle counters per world
    private final Map<String, AtomicInteger> sectorCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> goldenCounters = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPosition> playerPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Location> landingReservations = new ConcurrentHashMap<>();

    public synchronized boolean reserveLanding(UUID uuid, Location location) {
        // ponytail: short global reservation lock; use per-world locks if RTP throughput causes contention.
        if (!enabled) return true;
        if (isTooCloseToSpawn(location) || isTooCloseToRecent(location) || isTooCloseToPlayers(location, uuid)) return false;
        double separationSq = Math.max(minPlayerDistanceSq, minRecentDistanceSq);
        for (Location reserved : landingReservations.values()) {
            if (reserved.getWorld() != location.getWorld()) continue;
            double dx = reserved.getX() - location.getX();
            double dz = reserved.getZ() - location.getZ();
            if (dx * dx + dz * dz < separationSq) return false;
        }
        landingReservations.put(uuid, location);
        return true;
    }

    public synchronized void releaseLanding(UUID uuid, Location location) {
        if (landingReservations.get(uuid) == location) landingReservations.remove(uuid);
    }

    public RtpSpreadingManager(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getScheduler().runEntity(player, () -> updatePlayerPosition(player, player.getLocation()));
        }
    }

    public void reload() {
        FileConfiguration config = plugin.getConfigService().getRtpConfig();

        this.enabled = config.getBoolean("spreading.enabled", true);
        this.minPlayerDistance = Math.max(0.0, config.getDouble("spreading.min-distance-from-players", 200.0));
        this.minPlayerDistanceSq = this.minPlayerDistance * this.minPlayerDistance;

        this.minRecentDistance = Math.max(0.0, config.getDouble("spreading.min-distance-between-recent", 250.0));
        this.minRecentDistanceSq = this.minRecentDistance * this.minRecentDistance;

        this.recentCacheDurationMs = Math.max(10L, config.getLong("spreading.recent-cache-duration-seconds", 600L)) * 1000L;

        this.minSpawnDistance = Math.max(0.0, config.getDouble("spreading.min-distance-from-spawn", 200.0));
        this.minSpawnDistanceSq = this.minSpawnDistance * this.minSpawnDistance;

        String algoStr = config.getString("spreading.distribution-algorithm", "SECTOR_ROUND_ROBIN");
        this.defaultAlgorithm = DistributionAlgorithm.fromString(algoStr);

        this.sectorCount = Math.max(2, Math.min(64, config.getInt("spreading.sector-count", 8)));

        cleanupExpired();
    }

    /**
     * Checks if the given location is too close to any online player in the target world.
     */
    public boolean isTooCloseToPlayers(Location location, UUID ignorePlayerUuid) {
        if (!enabled || minPlayerDistance <= 0.0 || location == null || location.getWorld() == null) {
            return false;
        }

        UUID worldId = location.getWorld().getUID();
        double locX = location.getX();
        double locZ = location.getZ();

        for (Map.Entry<UUID, PlayerPosition> entry : playerPositions.entrySet()) {
            if (ignorePlayerUuid != null && entry.getKey().equals(ignorePlayerUuid)) {
                continue;
            }
            PlayerPosition position = entry.getValue();
            if (position == null || !position.worldId().equals(worldId)) {
                continue;
            }
            double dx = position.x() - locX;
            double dz = position.z() - locZ;
            if ((dx * dx) + (dz * dz) < minPlayerDistanceSq) {
                return true;
            }
        }

        return false;
    }

    private void updatePlayerPosition(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) return;
        playerPositions.put(player.getUniqueId(),
                new PlayerPosition(location.getWorld().getUID(), location.getX(), location.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updatePlayerPosition(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) return;
        updatePlayerPosition(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) updatePlayerPosition(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updatePlayerPosition(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        playerPositions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Checks if the given location is too close to any recent RTP landing spots in that world.
     */
    public boolean isTooCloseToRecent(Location location) {
        if (!enabled || minRecentDistance <= 0.0 || location == null || location.getWorld() == null) {
            return false;
        }

        String worldKey = location.getWorld().getName().toLowerCase();
        ConcurrentLinkedDeque<RecentTeleport> queue = recentTeleports.get(worldKey);
        if (queue == null || queue.isEmpty()) {
            return false;
        }

        long now = System.currentTimeMillis();
        double locX = location.getX();
        double locZ = location.getZ();

        for (RecentTeleport recent : queue) {
            if (now - recent.timestamp() > recentCacheDurationMs) {
                continue;
            }

            double dx = recent.x() - locX;
            double dz = recent.z() - locZ;
            double distSq = (dx * dx) + (dz * dz);

            if (distSq < minRecentDistanceSq) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the given location is too close to the world spawn location.
     */
    public boolean isTooCloseToSpawn(Location location) {
        if (!enabled || minSpawnDistance <= 0.0 || location == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        Location spawnLoc = world.getSpawnLocation();
        if (spawnLoc == null) {
            return false;
        }

        double dx = spawnLoc.getX() - location.getX();
        double dz = spawnLoc.getZ() - location.getZ();
        double distSq = (dx * dx) + (dz * dz);

        return distSq < minSpawnDistanceSq;
    }

    /**
     * Records a successful teleport landing location to prevent subsequent players from clustering there.
     */
    public void recordTeleport(Location location) {
        if (location == null || location.getWorld() == null) return;

        String worldKey = location.getWorld().getName().toLowerCase();
        ConcurrentLinkedDeque<RecentTeleport> queue = recentTeleports.computeIfAbsent(worldKey, k -> new ConcurrentLinkedDeque<>());

        long now = System.currentTimeMillis();
        queue.addFirst(new RecentTeleport(location.getX(), location.getZ(), now));

        while (queue.size() > 200) {
            queue.pollLast();
        }
    }

    /**
     * Atomically advances and returns the next sector index for a given world.
     */
    public int getNextSector(String worldName) {
        if (worldName == null) return 0;
        AtomicInteger counter = sectorCounters.computeIfAbsent(worldName.toLowerCase(), k -> new AtomicInteger(0));
        int val = counter.getAndIncrement();
        if (val < 0) {
            counter.set(0);
            val = 0;
        }
        return Math.abs(val) % Math.max(1, sectorCount);
    }

    /**
     * Atomically advances and returns the next golden angle sequence index for a given world.
     */
    public int getNextGoldenIndex(String worldName) {
        if (worldName == null) return 1;
        AtomicInteger counter = goldenCounters.computeIfAbsent(worldName.toLowerCase(), k -> new AtomicInteger(1));
        int val = counter.getAndIncrement();
        if (val <= 0) {
            counter.set(1);
            val = 1;
        }
        return val;
    }

    /**
     * Periodic lock-free cleanup of expired recent teleports.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (ConcurrentLinkedDeque<RecentTeleport> queue : recentTeleports.values()) {
            Iterator<RecentTeleport> it = queue.iterator();
            while (it.hasNext()) {
                RecentTeleport item = it.next();
                if (now - item.timestamp() > recentCacheDurationMs) {
                    it.remove();
                }
            }
        }
    }

    public int getRecentCount(String worldName) {
        if (worldName == null) return 0;
        ConcurrentLinkedDeque<RecentTeleport> q = recentTeleports.get(worldName.toLowerCase());
        return q != null ? q.size() : 0;
    }

    public int getTotalRecentCount() {
        int total = 0;
        for (ConcurrentLinkedDeque<RecentTeleport> q : recentTeleports.values()) {
            total += q.size();
        }
        return total;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getMinPlayerDistance() {
        return minPlayerDistance;
    }

    public double getMinRecentDistance() {
        return minRecentDistance;
    }

    public long getRecentCacheDurationMs() {
        return recentCacheDurationMs;
    }

    public double getMinSpawnDistance() {
        return minSpawnDistance;
    }

    public DistributionAlgorithm getDefaultAlgorithm() {
        return defaultAlgorithm;
    }

    public int getSectorCount() {
        return sectorCount;
    }

    public void clear() {
        landingReservations.clear();
        recentTeleports.clear();
        sectorCounters.clear();
        goldenCounters.clear();
        playerPositions.clear();
    }
}
