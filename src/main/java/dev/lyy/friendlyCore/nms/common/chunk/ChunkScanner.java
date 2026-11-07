package dev.lyy.friendlycore.nms.common.chunk;

import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Direct NMS PalettedContainer chunk scanner for ultra-fast RTP safe-ground search and custom tool batch scans.
 */
public interface ChunkScanner {

    /**
     * Fast safe-location query directly against LevelChunkSection PalettedContainer.
     *
     * @return Y coordinate of safe ground, or -1 if unsafe/unsuitable.
     */
    int findHighestSafeBlock(World world, int x, int z, int minSectionY, int maxSectionY);

    /**
     * Checks if a specific coordinate is safe for RTP without allocating Bukkit Block objects.
     */
    boolean isLocationSafe(World world, int x, int y, int z);

    /**
     * Direct Section palette iteration for batch block breakdown (Choppers/Drills/LeafDecay).
     */
    void fastSectionScan(Chunk chunk, int sectionIndex, SectionBlockVisitor visitor);

    @FunctionalInterface
    interface SectionBlockVisitor {
        void visit(int localX, int localY, int localZ, String blockTypeKey, boolean isAir, boolean isSolid);
    }
}
