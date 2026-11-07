package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Asynchronously locates safe landing coordinates across Overworld, Nether, and End dimensions
 * using spatial dispersion and player avoidance algorithms.
 */
public class RtpLocationFinder {

    private static final double GOLDEN_ANGLE = 2.399963229728653; // ~137.508 degrees in radians

    private final FriendlyCore plugin;
    private final RtpSafetyValidator safetyValidator;
    private final RtpSpreadingManager spreadingManager;
    private final Set<CompletableFuture<Optional<Location>>> searches = ConcurrentHashMap.newKeySet();

    public RtpLocationFinder(FriendlyCore plugin, RtpSafetyValidator safetyValidator, RtpSpreadingManager spreadingManager) {
        this.plugin = plugin;
        this.safetyValidator = safetyValidator;
        this.spreadingManager = spreadingManager;
    }

    /**
     * Asynchronously finds a safe location in the given world for a specific player (or null for pre-cache).
     */
    public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config, int maxAttempts,
                                                                 Consumer<Integer> onAttempt, UUID playerUuid) {
        return findSafeLocation(world, config, maxAttempts, onAttempt, playerUuid, 1L);
    }

    public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config, int maxAttempts,
                                                                 Consumer<Integer> onAttempt, UUID playerUuid, long retryDelayTicks) {
        CompletableFuture<Optional<Location>> future = new CompletableFuture<>();
        var settings = plugin.getConfigService().getRtpConfig();
        synchronized (searches) {
            if (searches.size() >= Math.max(1, settings.getInt("settings.max-concurrent-searches", 4))) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            searches.add(future);
        }
        AtomicReference<CompletableFuture<Chunk>> pendingChunk = new AtomicReference<>();
        future.whenComplete((result, error) -> {
            synchronized (pendingChunk) {
                CompletableFuture<Chunk> chunk = pendingChunk.get();
                // Keep an already-submitted load counted until Paper completes it.
                if (chunk == null) searches.remove(future);
                else chunk.whenComplete((loaded, failure) -> searches.remove(future));
            }
        });
        future.completeOnTimeout(Optional.empty(), Math.max(1, settings.getInt("settings.search-timeout-seconds", 10)), TimeUnit.SECONDS);
        findLocationRecursive(world, config, 1, maxAttempts, onAttempt, playerUuid, future,
                Math.max(1L, retryDelayTicks), pendingChunk);
        return future;
    }

    public CompletableFuture<Optional<Location>> findSafeLocation(World world, RtpWorldConfig config, int maxAttempts, Consumer<Integer> onAttempt) {
        return findSafeLocation(world, config, maxAttempts, onAttempt, null);
    }

    private void findLocationRecursive(World world, RtpWorldConfig config, int currentAttempt, int maxAttempts,
                                        Consumer<Integer> onAttempt, UUID playerUuid, CompletableFuture<Optional<Location>> future,
                                        long retryDelayTicks, AtomicReference<CompletableFuture<Chunk>> pendingChunk) {
        if (future.isDone()) return;
        if (currentAttempt > maxAttempts) {
            future.complete(Optional.empty());
            return;
        }

        try {
            if (onAttempt != null) {
                onAttempt.accept(currentAttempt);
            }

            // Calculate uniform coordinates within configured radius, shape, and world border limits
            int[] coords = generateRandomCoordinates(world, config);
            if (coords == null) {
                future.complete(Optional.empty());
                return;
            }
            int targetX = coords[0];
            int targetZ = coords[1];

            int chunkX = targetX >> 4;
            int chunkZ = targetZ >> 4;

            CompletableFuture<Chunk> loading;
            synchronized (pendingChunk) {
                if (future.isDone()) return;
                loading = world.getChunkAtAsync(chunkX, chunkZ, true);
                pendingChunk.set(loading);
            }
            loading.handle((chunk, ex) -> {
                if (future.isDone()) return null;
                if (ex != null || chunk == null) {
                    plugin.getScheduler().runGlobalLater(() ->
                            findLocationRecursive(world, config, currentAttempt + 1, maxAttempts, onAttempt, playerUuid, future, retryDelayTicks, pendingChunk), retryDelayTicks);
                    return null;
                }

                // Inspect block columns on the appropriate region thread on Folia
                Location chunkLoc = new Location(world, targetX, 64, targetZ);
                plugin.getScheduler().runLocation(chunkLoc, () -> {
                    if (future.isDone()) return;
                    try {
                        Optional<Location> candidate = scanColumnForSafeSpot(world, chunk, targetX, targetZ, config.getWorldType());
                        if (candidate.isPresent()) {
                            Location loc = candidate.get();
                            Biome biome = chunk.getBlock(targetX & 15, loc.getBlockY(), targetZ & 15).getBiome();
                            if (isWithinBounds(loc, config) && config.isBiomeAllowed(biome.getKey().getKey())
                                    && safetyValidator.isLocationSafe(loc, chunk, config.getWorldType())
                                    && isSpreadingSafe(loc, playerUuid)) {
                                future.complete(Optional.of(loc));
                                return;
                            }
                        }

                        // Fast local fallback scan within the same chunk (+/- 3 blocks)
                        Optional<Location> localFallback = scanLocalArea(world, chunk, targetX, targetZ, config.getWorldType());
                        if (localFallback.isPresent()) {
                            Location fLoc = localFallback.get();
                            Biome fBiome = chunk.getBlock(fLoc.getBlockX() & 15, fLoc.getBlockY(), fLoc.getBlockZ() & 15).getBiome();
                            if (isWithinBounds(fLoc, config) && config.isBiomeAllowed(fBiome.getKey().getKey())
                                    && safetyValidator.isLocationSafe(fLoc, chunk, config.getWorldType())
                                    && isSpreadingSafe(fLoc, playerUuid)) {
                                future.complete(Optional.of(fLoc));
                                return;
                            }
                        }

                        plugin.getScheduler().runGlobalLater(() ->
                                findLocationRecursive(world, config, currentAttempt + 1, maxAttempts, onAttempt, playerUuid, future, retryDelayTicks, pendingChunk), retryDelayTicks);
                    } catch (Exception t) {
                        plugin.getScheduler().runGlobalLater(() ->
                                findLocationRecursive(world, config, currentAttempt + 1, maxAttempts, onAttempt, playerUuid, future, retryDelayTicks, pendingChunk), retryDelayTicks);
                    }
                });
                return null;
            }).exceptionally(error -> { future.completeExceptionally(error); return null; });
        } catch (Exception error) {
            future.completeExceptionally(error);
        }
    }

    public void cancelAll() {
        for (CompletableFuture<Optional<Location>> search : searches) search.cancel(false);
    }

    private boolean isSpreadingSafe(Location loc, UUID playerUuid) {
        if (spreadingManager == null || !spreadingManager.isEnabled()) {
            return true;
        }
        if (spreadingManager.isTooCloseToSpawn(loc)) {
            return false;
        }
        if (spreadingManager.isTooCloseToRecent(loc)) {
            return false;
        }
        if (spreadingManager.isTooCloseToPlayers(loc, playerUuid)) {
            return false;
        }
        return true;
    }

    /**
     * Generates mathematically uniform random 2D coordinate $(X, Z)$ based on shape, radius, and spreading algorithm.
     */
    public int[] generateRandomCoordinates(World world, RtpWorldConfig config) {
        WorldBorder border = world.getWorldBorder();
        double borderSize = border.getSize() / 2.0;
        Location borderCenter = border.getCenter();

        double maxR = config.getMaxRadius();
        double minR = config.getMinRadius();
        if (borderSize <= 16 || maxR <= 0) return null;

        double cX = config.getCenterX() != 0 ? config.getCenterX() : borderCenter.getX();
        double cZ = config.getCenterZ() != 0 ? config.getCenterZ() : borderCenter.getZ();

        RtpSpreadingManager.DistributionAlgorithm algo = config.getDistributionAlgorithm();

        double targetX;
        double targetZ;

        for (int sample = 0; sample < 64; sample++) {
            if (config.getShape() == RtpWorldConfig.Shape.CIRCLE) {
                double angle;
                double u;

                switch (algo) {
                    case SECTOR_ROUND_ROBIN -> {
                        int sectorCount = spreadingManager != null ? spreadingManager.getSectorCount() : 8;
                        int sector = spreadingManager != null ? spreadingManager.getNextSector(world.getName()) : ThreadLocalRandom.current().nextInt(sectorCount);
                        double sectorSpan = (2.0 * Math.PI) / sectorCount;
                        angle = (sector * sectorSpan) + (ThreadLocalRandom.current().nextDouble() * sectorSpan);
                        u = ThreadLocalRandom.current().nextDouble();
                    }
                    case GOLDEN_ANGLE -> {
                        int goldenIndex = spreadingManager != null ? spreadingManager.getNextGoldenIndex(world.getName()) : ThreadLocalRandom.current().nextInt(1000) + 1;
                        angle = (goldenIndex * GOLDEN_ANGLE) % (2.0 * Math.PI);
                        u = ((goldenIndex * 0.618033988749895) % 1.0 + ThreadLocalRandom.current().nextDouble() * 0.1) % 1.0;
                    }
                    default -> {
                        angle = ThreadLocalRandom.current().nextDouble(2.0 * Math.PI);
                        u = ThreadLocalRandom.current().nextDouble();
                    }
                }

                // True uniform annulus distribution: r = sqrt(u * (maxR^2 - minR^2) + minR^2)
                double r = Math.sqrt(u * (maxR * maxR - minR * minR) + (minR * minR));
                targetX = cX + (r * Math.cos(angle));
                targetZ = cZ + (r * Math.sin(angle));
            } else {
                switch (algo) {
                    case SECTOR_ROUND_ROBIN -> {
                        int sectorCount = spreadingManager != null ? spreadingManager.getSectorCount() : 8;
                        int sector = spreadingManager != null ? spreadingManager.getNextSector(world.getName()) : ThreadLocalRandom.current().nextInt(sectorCount);
                        double sectorSpan = (2.0 * Math.PI) / sectorCount;
                        double angle = (sector * sectorSpan) + (ThreadLocalRandom.current().nextDouble() * sectorSpan);

                        double cos = Math.cos(angle);
                        double sin = Math.sin(angle);
                        double maxCosSin = Math.max(Math.abs(cos), Math.abs(sin));
                        if (maxCosSin < 1e-6) maxCosSin = 1.0;

                        double rMinTheta = minR / maxCosSin;
                        double rMaxTheta = maxR / maxCosSin;
                        double u = ThreadLocalRandom.current().nextDouble();
                        double r = Math.sqrt(u * (rMaxTheta * rMaxTheta - rMinTheta * rMinTheta) + (rMinTheta * rMinTheta));

                        targetX = cX + (r * cos);
                        targetZ = cZ + (r * sin);
                    }
                    case GOLDEN_ANGLE -> {
                        int goldenIndex = spreadingManager != null ? spreadingManager.getNextGoldenIndex(world.getName()) : ThreadLocalRandom.current().nextInt(1000) + 1;
                        double angle = (goldenIndex * GOLDEN_ANGLE) % (2.0 * Math.PI);
                        double u = ((goldenIndex * 0.618033988749895) % 1.0 + ThreadLocalRandom.current().nextDouble() * 0.1) % 1.0;

                        double cos = Math.cos(angle);
                        double sin = Math.sin(angle);
                        double maxCosSin = Math.max(Math.abs(cos), Math.abs(sin));
                        if (maxCosSin < 1e-6) maxCosSin = 1.0;

                        double rMinTheta = minR / maxCosSin;
                        double rMaxTheta = maxR / maxCosSin;
                        double r = Math.sqrt(u * (rMaxTheta * rMaxTheta - rMinTheta * rMinTheta) + (rMinTheta * rMinTheta));

                        targetX = cX + (r * cos);
                        targetZ = cZ + (r * sin);
                    }
                    default -> {
                        if (maxR == minR) {
                            double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
                            double scale = maxR / Math.max(Math.abs(Math.cos(angle)), Math.abs(Math.sin(angle)));
                            targetX = cX + scale * Math.cos(angle);
                            targetZ = cZ + scale * Math.sin(angle);
                            break;
                        }
                        // Uniform hollow square distribution across 4 outer bounding bands
                        double totalWeight = 2.0 * (maxR + minR);
                        double wTop = maxR / totalWeight;
                        double wBottom = maxR / totalWeight;
                        double wLeft = minR / totalWeight;

                        double roll = ThreadLocalRandom.current().nextDouble();
                        if (roll < wTop) {
                            targetX = cX + ThreadLocalRandom.current().nextDouble(-maxR, maxR);
                            targetZ = cZ + ThreadLocalRandom.current().nextDouble(minR, maxR);
                        } else if (roll < wTop + wBottom) {
                            targetX = cX + ThreadLocalRandom.current().nextDouble(-maxR, maxR);
                            targetZ = cZ + ThreadLocalRandom.current().nextDouble(-maxR, -minR);
                        } else if (roll < wTop + wBottom + wLeft) {
                            targetX = cX + ThreadLocalRandom.current().nextDouble(-maxR, -minR);
                            targetZ = cZ + ThreadLocalRandom.current().nextDouble(-minR, minR);
                        } else {
                            targetX = cX + ThreadLocalRandom.current().nextDouble(minR, maxR);
                            targetZ = cZ + ThreadLocalRandom.current().nextDouble(-minR, minR);
                        }
                    }
                }
            }

            int finalX = (int) Math.floor(targetX);
            int finalZ = (int) Math.floor(targetZ);
            if (isWithinBounds(new Location(world, finalX + 0.5, 64, finalZ + 0.5), config)) {
                return new int[]{finalX, finalZ};
            }
        }
        // ponytail: bounded rejection sampling; compute the intersection if tiny valid areas need support.
        return null;
    }

    private boolean isWithinBounds(Location location, RtpWorldConfig config) {
        WorldBorder border = location.getWorld().getWorldBorder();
        Location center = border.getCenter();
        return isWithinBounds(location.getX(), location.getZ(), center.getX(), center.getZ(), border.getSize(), config);
    }

    /** Keeps 16 blocks inside the border and inside the configured min/max radius. */
    static boolean isWithinBounds(double x, double z, double borderX, double borderZ, double borderSize, RtpWorldConfig config) {
        double radius = borderSize / 2 - 16;
        if (Math.abs(x - borderX) >= radius || Math.abs(z - borderZ) >= radius) return false;
        double dx = Math.abs(x - (config.getCenterX() == 0 ? borderX : config.getCenterX()));
        double dz = Math.abs(z - (config.getCenterZ() == 0 ? borderZ : config.getCenterZ()));
        double distance = config.getShape() == RtpWorldConfig.Shape.CIRCLE ? Math.hypot(dx, dz) : Math.max(dx, dz);
        return distance >= config.getMinRadius() && distance <= config.getMaxRadius();
    }

    private Optional<Location> scanColumnForSafeSpot(World world, Chunk chunk, int worldX, int worldZ, RtpWorldConfig.WorldType worldType) {
        int relX = worldX & 15;
        int relZ = worldZ & 15;

        switch (worldType) {
            case NETHER -> {
                // Nether: Scan from Y=120 down to Y=35 (above lava lake level)
                for (int y = 120; y >= 35; y--) {
                    Block ground = chunk.getBlock(relX, y - 1, relZ);
                    Block feet = chunk.getBlock(relX, y, relZ);
                    Block head = chunk.getBlock(relX, y + 1, relZ);

                    if (ground.getType().isSolid() && ground.getType() != Material.BEDROCK &&
                        ground.getType() != Material.LAVA && ground.getType() != Material.MAGMA_BLOCK &&
                        !feet.getType().isSolid() && !head.getType().isSolid() &&
                        feet.getType() != Material.LAVA && head.getType() != Material.LAVA) {
                        return Optional.of(new Location(world, worldX + 0.5, (double) y, worldZ + 0.5));
                    }
                }
                return Optional.empty();
            }
            case THE_END -> {
                // The End: Scan from Y=85 down to Y=20
                for (int y = 85; y >= 20; y--) {
                    Block ground = chunk.getBlock(relX, y - 1, relZ);
                    Block feet = chunk.getBlock(relX, y, relZ);
                    Block head = chunk.getBlock(relX, y + 1, relZ);

                    if ((ground.getType() == Material.END_STONE || ground.getType() == Material.OBSIDIAN || ground.getType().isSolid()) &&
                        !feet.getType().isSolid() && !head.getType().isSolid()) {
                        return Optional.of(new Location(world, worldX + 0.5, (double) y, worldZ + 0.5));
                    }
                }
                return Optional.empty();
            }
            default -> {
                int highestY;
                try {
                    highestY = world.getHighestBlockYAt(worldX, worldZ);
                } catch (Throwable t) {
                    highestY = 260;
                }

                int startY = Math.min(highestY + 1, world.getMaxHeight() - 2);
                int minY = Math.max(world.getMinHeight() + 5, Math.max(50, highestY - 20));

                for (int y = startY; y >= minY; y--) {
                    Block ground = chunk.getBlock(relX, y - 1, relZ);
                    Block feet = chunk.getBlock(relX, y, relZ);
                    Block head = chunk.getBlock(relX, y + 1, relZ);

                    Material gMat = ground.getType();
                    Material fMat = feet.getType();
                    Material hMat = head.getType();

                    // Abort column if surface is liquid (ocean or lava lake)
                    if (gMat == Material.WATER || fMat == Material.WATER || hMat == Material.WATER ||
                        gMat == Material.LAVA || fMat == Material.LAVA || hMat == Material.LAVA) {
                        return Optional.empty();
                    }

                    if (gMat.isSolid() && !fMat.isSolid() && !hMat.isSolid()) {
                        return Optional.of(new Location(world, worldX + 0.5, (double) y, worldZ + 0.5));
                    }
                }
                return Optional.empty();
            }
        }
    }

    private Optional<Location> scanLocalArea(World world, Chunk chunk, int worldX, int worldZ, RtpWorldConfig.WorldType worldType) {
        int relX = worldX & 15;
        int relZ = worldZ & 15;
        int[] offsets = {-3, 3, -2, 2};

        for (int ox : offsets) {
            for (int oz : offsets) {
                int testRelX = relX + ox;
                int testRelZ = relZ + oz;
                if (testRelX >= 0 && testRelX < 16 && testRelZ >= 0 && testRelZ < 16) {
                    int nx = (chunk.getX() << 4) + testRelX;
                    int nz = (chunk.getZ() << 4) + testRelZ;
                    Optional<Location> candidate = scanColumnForSafeSpot(world, chunk, nx, nz, worldType);
                    if (candidate.isPresent()) {
                        return candidate;
                    }
                }
            }
        }
        return Optional.empty();
    }

    public RtpSpreadingManager getSpreadingManager() {
        return spreadingManager;
    }
}
