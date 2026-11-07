package dev.lyy.friendlycore.modules.tools;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Incremental, chunk-owned decay scans around removed logs. Vanilla handles overflow. */
public class LeafDecayManager {
    private static final int SCANS_PER_BATCH = 128;
    private static final int SCANS_PER_TICK = 1024;
    private static final int MAX_ACTIVE_CHUNKS = 128;
    private static final int MAX_LOGS_PER_CHUNK = 256;
    private final FriendlyCore plugin;
    private final ConcurrentHashMap<ChunkKey, Scan> scans = new ConcurrentHashMap<>();
    private int budgetTick = Integer.MIN_VALUE;
    private int remainingBudget;

    public LeafDecayManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void scheduleLeafDecay(List<Block> logs) {
        if (logs == null || logs.isEmpty()) return;
        var config = plugin.getConfigService().getToolsConfig();
        if (!config.getBoolean("chopper.leaf-decay.enabled", true)) return;
        int minDelay = Math.max(1, Math.min(1200, config.getInt("chopper.leaf-decay.min-delay-ticks", 20)));
        int maxDelay = Math.max(minDelay, Math.min(1200, config.getInt("chopper.leaf-decay.max-delay-ticks", 80)));
        long delay = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
        // Limit admission as well as processing; an oversized chopper configuration
        // cannot allocate a giant volume or an unbounded collection of timer tasks.
        for (Block log : logs.subList(0, Math.min(MAX_LOGS_PER_CHUNK, logs.size()))) {
            Location origin = log.getLocation();
            World world = origin.getWorld();
            if (world == null) continue;
            for (int cx = (origin.getBlockX() - 4) >> 4; cx <= (origin.getBlockX() + 4) >> 4; cx++) {
                for (int cz = (origin.getBlockZ() - 4) >> 4; cz <= (origin.getBlockZ() + 4) >> 4; cz++) {
                    if (!world.isChunkLoaded(cx, cz)) continue;
                    ChunkKey key = new ChunkKey(world.getUID(), cx, cz);
                    boolean[] created = {false};
                    Scan scan = scans.compute(key, (ignored, current) -> {
                        if (current == null) {
                            if (scans.size() >= MAX_ACTIVE_CHUNKS) return null;
                            current = new Scan(key, world);
                            created[0] = true;
                        }
                        current.add(origin);
                        return current;
                    });
                    if (created[0] && scan != null) scan.schedule(delay);
                }
            }
        }
    }

    private synchronized boolean takeBudget() {
        int tick = Bukkit.getCurrentTick();
        if (tick != budgetTick) {
            budgetTick = tick;
            remainingBudget = SCANS_PER_TICK;
        }
        if (remainingBudget == 0) return false;
        remainingBudget--;
        return true;
    }

    private record ChunkKey(UUID world, int x, int z) {}

    private final class Scan implements Runnable {
        private final ChunkKey key;
        private final World world;
        private final Location anchor;
        private final ArrayDeque<Location> origins = new ArrayDeque<>();
        private final BitSet visited = new BitSet();
        private int admitted;
        private int offset;

        private Scan(ChunkKey key, World world) {
            this.key = key;
            this.world = world;
            this.anchor = new Location(world, (key.x() << 4) + 8, world.getMinHeight(), (key.z() << 4) + 8);
        }

        private synchronized void add(Location location) {
            if (admitted < MAX_LOGS_PER_CHUNK) {
                origins.add(location);
                admitted++;
            }
        }

        private void schedule(long delay) {
            try {
                if (plugin.getScheduler().runLocationLater(anchor, this, delay) == null) scans.remove(key, this);
            } catch (RuntimeException error) {
                scans.remove(key, this);
                throw error;
            }
        }

        @Override
        public void run() {
            try {
                runBatch();
            } catch (RuntimeException error) {
                scans.remove(key, this);
                throw error;
            }
        }

        private void runBatch() {
            if (!world.isChunkLoaded(key.x(), key.z())
                    || (plugin.getScheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(anchor))) {
                scans.remove(key, this);
                return;
            }
            int broken = 0;
            for (int attempted = 0; attempted < SCANS_PER_BATCH && broken < 2; attempted++) {
                if (!takeBudget()) break;
                Location origin;
                int index;
                synchronized (this) {
                    origin = origins.peek();
                    if (origin == null) break;
                    index = offset++;
                    if (offset == 729) {
                        origins.remove();
                        offset = 0;
                    }
                }
                int x = origin.getBlockX() + index / 81 - 4;
                int y = origin.getBlockY() + (index / 9) % 9 - 2;
                int z = origin.getBlockZ() + index % 9 - 4;
                if ((x >> 4) != key.x() || (z >> 4) != key.z()
                        || y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                int position = leafIndex(x, y, z, world.getMinHeight());
                if (visited.get(position)) continue;
                visited.set(position);
                Block block = world.getBlockAt(x, y, z);
                // Native distance includes surviving logs and adjacent canopies. A stale
                // distance is left to vanilla rather than breaking potentially supported leaves.
                if (block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent() && leaves.getDistance() >= 7) {
                    if (block.breakNaturally()) broken++;
                }
            }
            boolean[] more = {false};
            scans.computeIfPresent(key, (ignored, current) -> {
                if (current != this) return current;
                synchronized (this) {
                    more[0] = !origins.isEmpty();
                }
                return more[0] ? this : null;
            });
            if (more[0]) schedule(1L);
        }
    }
    private static int leafIndex(int x, int y, int z, int minY) {
        // Chunk-local bits avoid allocating a boxed Long and hash entry per leaf.
        return (y - minY) * 256 + ((x & 15) << 4) + (z & 15);
    }

}
