package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every landable column of a small, static arena, scanned once and handed out in shuffled order.
 * Replaces live searching for event worlds: no chunk loads or distance checks per teleport.
 */
final class RtpLandingPool {
    private static final int CHUNKS_IN_FLIGHT = 4;
    private static final Set<Material> AIR = Set.of(Material.AIR, Material.CAVE_AIR);

    private final long[] spots;
    private final AtomicInteger cursor = new AtomicInteger();

    RtpLandingPool(long[] spots) {
        this.spots = spots.clone();
        for (int index = this.spots.length - 1; index > 0; index--) {
            int swap = ThreadLocalRandom.current().nextInt(index + 1);
            long value = this.spots[index];
            this.spots[index] = this.spots[swap];
            this.spots[swap] = value;
        }
    }

    int size() {
        return spots.length;
    }

    /** Walks every spot once before reusing any, which spreads arrivals evenly. Null when empty. */
    Location next(World world) {
        if (spots.length == 0) return null;
        long spot = spots[Math.floorMod(cursor.getAndIncrement(), spots.length)];
        return new Location(world, x(spot) + 0.5, y(spot), z(spot) + 0.5);
    }

    static boolean isLandable(Material ground, Material feet, Material head, Set<Material> allowedGround) {
        return allowedGround.contains(ground) && AIR.contains(feet) && AIR.contains(head);
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }

    static int x(long spot) { return (int) (spot >> 38); }
    static int z(long spot) { return (int) (spot << 26 >> 38); }
    static int y(long spot) { return (int) (spot << 52 >> 52); }

    /** Call on the global thread. Chunks must already be generated; missing ones are skipped. */
    static CompletableFuture<RtpLandingPool> build(FriendlyCore plugin, World world, RtpWorldConfig config) {
        WorldBorder border = world.getWorldBorder();
        double borderX = border.getCenter().getX();
        double borderZ = border.getCenter().getZ();
        double borderSize = border.getSize();
        double centerX = config.getCenterX() != 0 ? config.getCenterX() : borderX;
        double centerZ = config.getCenterZ() != 0 ? config.getCenterZ() : borderZ;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        Set<Material> allowedGround = config.getAllowedGround();

        List<int[]> chunks = new ArrayList<>();
        int radius = config.getMaxRadius();
        for (int cx = (int) Math.floor(centerX - radius) >> 4; cx <= (int) Math.floor(centerX + radius) >> 4; cx++) {
            for (int cz = (int) Math.floor(centerZ - radius) >> 4; cz <= (int) Math.floor(centerZ + radius) >> 4; cz++) {
                chunks.add(new int[]{cx, cz});
            }
        }

        List<Long> found = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        // ponytail: fixed batches of 4 chunks; switch to a sliding window if large arenas build too slowly.
        for (int start = 0; start < chunks.size(); start += CHUNKS_IN_FLIGHT) {
            List<int[]> batch = chunks.subList(start, Math.min(start + CHUNKS_IN_FLIGHT, chunks.size()));
            done = done.thenCompose(ignored -> CompletableFuture.allOf(batch.stream().map(chunk ->
                    snapshot(plugin, world, chunk[0], chunk[1]).thenAcceptAsync(snapshot -> {
                        if (snapshot == null) return;
                        for (int rx = 0; rx < 16; rx++) {
                            for (int rz = 0; rz < 16; rz++) {
                                int x = (chunk[0] << 4) + rx;
                                int z = (chunk[1] << 4) + rz;
                                if (!RtpLocationFinder.isWithinBounds(x + 0.5, z + 0.5, borderX, borderZ, borderSize, config)) continue;
                                int ground = snapshot.getHighestBlockYAt(rx, rz);
                                if (ground - 3 < minY || ground + 2 >= maxY) continue;
                                // Mirrors RtpSafetyValidator's End rule (solid support 1-3 below the ground),
                                // so thin island edges never enter the pool only to fail at landing.
                                boolean supported = false;
                                for (int below = 1; below <= 3 && !supported; below++) {
                                    supported = snapshot.getBlockType(rx, ground - below, rz).isSolid();
                                }
                                if (supported && isLandable(snapshot.getBlockType(rx, ground, rz), snapshot.getBlockType(rx, ground + 1, rz),
                                        snapshot.getBlockType(rx, ground + 2, rz), allowedGround)) {
                                    found.add(pack(x, ground + 1, z));
                                }
                            }
                        }
                    }, plugin.getScheduler().getAsyncExecutor())).toArray(CompletableFuture[]::new)));
        }
        return done.thenApply(ignored -> {
            synchronized (found) {
                return new RtpLandingPool(found.stream().mapToLong(Long::longValue).toArray());
            }
        });
    }

    private static CompletableFuture<ChunkSnapshot> snapshot(FriendlyCore plugin, World world, int chunkX, int chunkZ) {
        CompletableFuture<ChunkSnapshot> result = new CompletableFuture<>();
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                result.complete(null);
                return;
            }
            Object task = plugin.getScheduler().runLocation(new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8), () -> {
                try {
                    result.complete(chunk.getChunkSnapshot());
                } catch (RuntimeException snapshotError) {
                    result.complete(null);
                }
            });
            if (task == null && !result.isDone()) result.complete(null);
        });
        return result;
    }
}
