package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Validates potential RTP landing spots against world boundaries, environmental hazards,
 * suffocating blocks, liquids, and regional claim protection plugins.
 */
public class RtpSafetyValidator {

    private final FriendlyCore plugin;
    private final Set<Material> blacklistedBlocks = EnumSet.noneOf(Material.class);
    private List<String> blacklistedRegions = List.of();

    public RtpSafetyValidator(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        blacklistedBlocks.clear();
        List<String> list = plugin.getConfigService().getRtpConfig().getStringList("blacklisted-blocks");
        for (String item : list) {
            try {
                Material mat = Material.valueOf(item.trim().toUpperCase());
                blacklistedBlocks.add(mat);
            } catch (IllegalArgumentException ignored) {}
        }
        this.blacklistedRegions = plugin.getConfigService().getRtpConfig().getStringList("blacklisted-regions");
    }

    /**
     * Checks if a location is safe for a player to teleport to.
     */
    public boolean isLocationSafe(Location location, Chunk chunk, RtpWorldConfig.WorldType worldType) {
        if (location == null || chunk == null || location.getWorld() == null) {
            return false;
        }

        World world = location.getWorld();
        int relX = location.getBlockX() & 15;
        int relZ = location.getBlockZ() & 15;
        int y = location.getBlockY();

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        // Ensure vertical bounds leave room for player body height
        if (y <= minHeight + 3 || y >= maxHeight - 3) {
            return false;
        }

        // Verify position falls safely within world border limits
        WorldBorder border = world.getWorldBorder();
        double radius = border.getSize() / 2.0;
        Location center = border.getCenter();
        double dx = Math.abs(location.getX() - center.getX());
        double dz = Math.abs(location.getZ() - center.getZ());
        if (dx >= radius - 16 || dz >= radius - 16) {
            return false;
        }

        switch (worldType) {
            case NETHER -> {
                // Avoid Nether ceiling bedrock and lava ocean level
                if (y < 35 || y > 120) return false;
            }
            case THE_END -> {
                if (y < 20 || y > 95) return false;
            }
            default -> {
                if (y < minHeight + 5 || y <= world.getHighestBlockYAt(
                        location.getBlockX(), location.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES)) return false;
            }
        }

        Block groundBlock = chunk.getBlock(relX, y - 1, relZ);
        Block feetBlock = chunk.getBlock(relX, y, relZ);
        Block headBlock = chunk.getBlock(relX, y + 1, relZ);

        // Material solidity alone misses collision shapes such as layered snow.
        if (!feetBlock.isPassable() || !headBlock.isPassable()) {
            return false;
        }

        Material groundMat = groundBlock.getType();
        Material feetMat = feetBlock.getType();
        Material headMat = headBlock.getType();

        if (!groundMat.isSolid() || groundMat.isAir() || isDangerous(groundMat)) {
            return false;
        }

        if (groundMat == Material.WATER || groundMat == Material.LAVA ||
            feetMat == Material.WATER || feetMat == Material.LAVA ||
            headMat == Material.WATER || headMat == Material.LAVA) {
            return false;
        }

        if (isDangerous(feetMat) || isDangerous(headMat)) {
            return false;
        }

        // Verify Nether ceiling clearance and ensure no lava pockets directly overhead
        if (worldType == RtpWorldConfig.WorldType.NETHER) {
            if (groundMat == Material.BEDROCK) {
                return false;
            }
            if (y + 2 < maxHeight) {
                Block overhead = chunk.getBlock(relX, y + 2, relZ);
                if (overhead.getType().isSolid()) return false;
            }
            if (y + 3 < maxHeight) {
                Block lavaCeiling = chunk.getBlock(relX, y + 3, relZ);
                if (lavaCeiling.getType() == Material.LAVA) return false;
            }
        }

        // Ensure solid End island foundation and prevent void-adjacent edge drops
        if (worldType == RtpWorldConfig.WorldType.THE_END) {
            boolean solidBelow = false;
            for (int dy = 2; dy <= 4; dy++) {
                if (chunk.getBlock(relX, y - dy, relZ).getType().isSolid()) {
                    solidBelow = true;
                    break;
                }
            }
            if (!solidBelow) return false;
        }

        // Check for adjacent fire, magma, or lava flows
        if (hasAdjacentHazard(chunk, relX, y, relZ)) {
            return false;
        }

        // Check claim protection plugins (WorldGuard, Lands, GriefPrevention)
        if (plugin.getHookManager() != null && plugin.getHookManager().getProtectionHook() != null) {
            if (plugin.getHookManager().getProtectionHook().isRegionBlocked(location, blacklistedRegions)) {
                return false;
            }
        }

        return true;
    }

    private boolean isDangerous(Material material) {
        if (material == null) return false;
        if (blacklistedBlocks.contains(material)) return true;
        return switch (material) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH,
                 CAMPFIRE, SOUL_CAMPFIRE, WITHER_ROSE, POWDER_SNOW, VOID_AIR,
                 END_PORTAL, NETHER_PORTAL, BARRIER, POINTED_DRIPSTONE,
                 SCULK_SHRIEKER, SCULK_SENSOR, COBWEB, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> true;
            default -> false;
        };
    }

    private boolean hasAdjacentHazard(Chunk chunk, int relX, int y, int relZ) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int nx = relX + ox;
                int nz = relZ + oz;
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                    Material matAtFeet = chunk.getBlock(nx, y, nz).getType();
                    Material matAtGround = chunk.getBlock(nx, y - 1, nz).getType();
                    if (matAtFeet == Material.LAVA || matAtGround == Material.LAVA ||
                        matAtFeet == Material.FIRE || matAtGround == Material.FIRE ||
                        matAtFeet == Material.MAGMA_BLOCK || matAtGround == Material.MAGMA_BLOCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
