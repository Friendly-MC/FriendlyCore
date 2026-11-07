package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-generates and caches verified safe landing locations in memory for instant RTP dispatch.
 */
public class RtpLocationCache {

    public record CachedLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
        public Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new Location(world, x, y, z, yaw, pitch);
        }

        public static CachedLocation fromLocation(Location loc) {
            if (loc == null || loc.getWorld() == null) return null;
            return new CachedLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
    }

    private final FriendlyCore plugin;
    private final RtpLocationFinder locationFinder;
    private final Map<String, ConcurrentLinkedQueue<CachedLocation>> cacheMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> refillingMap = new ConcurrentHashMap<>();
    private final AtomicLong currentSession = new AtomicLong(0);
    private final Map<String, CompletableFuture<Optional<Location>>> searches = new ConcurrentHashMap<>();
    private final Map<String, RtpLandingPool> pools = new ConcurrentHashMap<>();

    private boolean enabled;
    private int cacheSize;
    private long refillIntervalTicks;
    private int throttleTicks;
    private int maxParallelWorkers;
    private Object refillTask;

    public RtpLocationCache(FriendlyCore plugin, RtpLocationFinder locationFinder) {
        this.plugin = plugin;
        this.locationFinder = locationFinder;
    }

    public void initialize() {
        shutdown();
        cacheMap.clear();
        refillingMap.clear();

        FileConfiguration config = plugin.getConfigService().getRtpConfig();
        this.enabled = config.getBoolean("location-cache.enabled", true);
        this.cacheSize = Math.max(1, config.getInt("location-cache.cache-size", 20));
        this.refillIntervalTicks = Math.max(20L, config.getLong("location-cache.refill-interval-seconds", 5) * 20L);
        this.throttleTicks = Math.max(1, config.getInt("location-cache.fill-throttle-ticks", 4));
        this.maxParallelWorkers = Math.max(1, config.getInt("location-cache.max-parallel-workers", 2));

        long session = currentSession.get();
        plugin.getScheduler().runGlobal(() -> buildPools(session));

        if (!enabled) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            RtpWorldConfig wConfig = plugin.getRtpManager() != null ? plugin.getRtpManager().getWorldConfig(world.getName()) : null;
            if (wConfig != null && wConfig.isEnabled() && !wConfig.isLandingPool()) {
                cacheMap.put(world.getName(), new ConcurrentLinkedQueue<>());
                refillingMap.put(world.getName(), new AtomicBoolean(false));
            }
        }

        // World/player registry access belongs on the global scheduler. Chunk and
        // column work is still asynchronous/region-routed by RtpLocationFinder.
        this.refillTask = plugin.getScheduler().runGlobalTimer(this::checkAndRefillQueues, 40L, refillIntervalTicks);
    }

    /** The previous pool keeps serving until its rebuild finishes, so a reload never empties an event world. */
    private void buildPools(long session) {
        if (session != currentSession.get() || plugin.getRtpManager() == null) return;
        pools.keySet().removeIf(name -> {
            RtpWorldConfig config = plugin.getRtpManager().getWorldConfig(name);
            return config == null || !config.isEnabled() || !config.isLandingPool();
        });
        for (World world : Bukkit.getWorlds()) {
            RtpWorldConfig config = plugin.getRtpManager().getWorldConfig(world.getName());
            if (config == null || !config.isEnabled() || !config.isLandingPool()) continue;
            long started = System.currentTimeMillis();
            RtpLandingPool.build(plugin, world, config).whenComplete((pool, error) -> {
                if (session != currentSession.get()) return;
                if (error != null) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "RTP landing pool failed for " + world.getName(), error);
                    return;
                }
                pools.put(world.getName(), pool);
                plugin.getLogger().info("RTP landing pool for " + world.getName() + ": " + pool.size() + " spots in "
                        + (System.currentTimeMillis() - started) + "ms" + (pool.size() == 0 ? " (is the area pregenerated?)" : ""));
            });
        }
    }

    public int getPoolSize(String worldName) {
        RtpLandingPool pool = pools.get(worldName);
        return pool == null ? -1 : pool.size();
    }

    private void checkAndRefillQueues() {
        if (!enabled || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }

        long session = currentSession.get();
        int workersStarted = (int) refillingMap.values().stream().filter(AtomicBoolean::get).count();
        if (workersStarted >= maxParallelWorkers) return;

        for (World world : Bukkit.getWorlds()) {
            String worldName = world.getName();
            RtpWorldConfig wConfig = plugin.getRtpManager().getWorldConfig(worldName);
            if (wConfig == null || !wConfig.isEnabled() || wConfig.isLandingPool()) continue;

            ConcurrentLinkedQueue<CachedLocation> queue = cacheMap.computeIfAbsent(worldName, k -> new ConcurrentLinkedQueue<>());
            AtomicBoolean isRefilling = refillingMap.computeIfAbsent(worldName, k -> new AtomicBoolean(false));

            if (queue.size() < cacheSize && isRefilling.compareAndSet(false, true)) {
                workersStarted++;
                refillWorldWorker(worldName, wConfig, cacheSize - queue.size(), session, isRefilling);
                if (workersStarted >= maxParallelWorkers) {
                    break;
                }
            }
        }
    }

    private void refillWorldWorker(String worldName, RtpWorldConfig wConfig, int remainingNeeded, long session,
                                   AtomicBoolean isRefilling) {
        if (session != currentSession.get() || refillingMap.get(worldName) != isRefilling) {
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (remainingNeeded <= 0 || world == null) {
            isRefilling.set(false);
            return;
        }

        int maxAttempts = plugin.getConfigService().getRtpConfig().getInt("settings.max-attempts." + wConfig.getWorldType().name().toLowerCase(), 25);

        try {
            CompletableFuture<Optional<Location>> search = locationFinder.findSafeLocation(world, wConfig, maxAttempts, null, null, throttleTicks);
            searches.put(worldName, search);
            search.whenComplete((locOpt, ex) -> {
                searches.remove(worldName, search);
                if (session != currentSession.get()) return;
                try {
                    if (locOpt != null && locOpt.isPresent()) {
                        ConcurrentLinkedQueue<CachedLocation> queue = cacheMap.get(worldName);
                        if (queue != null && queue.size() < cacheSize) {
                            queue.add(CachedLocation.fromLocation(locOpt.get()));
                        }

                        if (remainingNeeded > 1) {
                            plugin.getScheduler().runGlobalLater(() ->
                                    refillWorldWorker(worldName, wConfig, remainingNeeded - 1, session, isRefilling), throttleTicks);
                        }
                    } else {
                        isRefilling.set(false);
                    }
                } finally {
                    if (locOpt == null || locOpt.isEmpty() || remainingNeeded <= 1) {
                        isRefilling.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            isRefilling.set(false);
        }
    }

    /**
     * Recycles a safe location back into the cache queue (e.g. when warmup is cancelled or player disconnects).
     */
    public boolean recycle(Location loc) {
        if (!enabled || loc == null || loc.getWorld() == null) {
            return false;
        }
        return recycle(CachedLocation.fromLocation(loc));
    }

    public boolean recycle(CachedLocation cached) {
        if (!enabled || cached == null || cached.worldName() == null || pools.containsKey(cached.worldName())) {
            return false;
        }
        ConcurrentLinkedQueue<CachedLocation> queue = cacheMap.computeIfAbsent(cached.worldName(), k -> new ConcurrentLinkedQueue<>());
        if (queue.size() < cacheSize) {
            return queue.offer(cached);
        }
        return false;
    }

    /**
     * Poll a cached safe location for immediate teleportation, validating spreading constraints in real-time.
     */
    public Optional<Location> poll(World world, UUID playerUuid) {
        if (world == null) return Optional.empty();
        RtpLandingPool pool = pools.get(world.getName());
        if (pool != null) return Optional.ofNullable(pool.next(world));
        if (!enabled) {
            return Optional.empty();
        }
        ConcurrentLinkedQueue<CachedLocation> queue = cacheMap.get(world.getName());
        if (queue == null) {
            return Optional.empty();
        }

        RtpSpreadingManager spreadingManager = locationFinder != null ? locationFinder.getSpreadingManager() : null;

        // Try candidate locations, discarding any that now conflict with nearby players
        int attempts = 0;
        while (!queue.isEmpty() && attempts < 5) {
            attempts++;
            CachedLocation cached = queue.poll();
            if (cached == null) break;

            Location loc = cached.toLocation();
            if (loc == null || loc.getWorld() == null) continue;

            if (spreadingManager != null && spreadingManager.isEnabled()) {
                if (spreadingManager.isTooCloseToPlayers(loc, playerUuid)
                        || spreadingManager.isTooCloseToRecent(loc)
                        || spreadingManager.isTooCloseToSpawn(loc)) {
                    continue;
                }
            }

            return Optional.of(loc);
        }

        return Optional.empty();
    }

    public Optional<Location> poll(World world) {
        return poll(world, null);
    }

    public void purgeWorld(String worldName) {
        if (worldName == null) return;
        CompletableFuture<Optional<Location>> search = searches.remove(worldName);
        if (search != null) search.cancel(false);
        cacheMap.remove(worldName);
        refillingMap.remove(worldName);
        pools.remove(worldName);
    }

    public int getCachedCount(String worldName) {
        ConcurrentLinkedQueue<CachedLocation> queue = cacheMap.get(worldName);
        return queue != null ? queue.size() : 0;
    }

    public int getTotalCached() {
        int total = 0;
        for (ConcurrentLinkedQueue<CachedLocation> q : cacheMap.values()) {
            total += q.size();
        }
        return total;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clear() {
        shutdown();
        cacheMap.clear();
        refillingMap.clear();
    }

    public void shutdown() {
        currentSession.incrementAndGet();
        searches.values().forEach(search -> search.cancel(false));
        searches.clear();
        if (refillTask != null) {
            plugin.getScheduler().cancel(refillTask);
            refillTask = null;
        }
    }
}
