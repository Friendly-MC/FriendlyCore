package dev.lyy.friendlycore.modules.tpa;

import dev.lyy.friendlycore.common.SafeTeleportUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;

import java.util.Collection;

/**
 * Validates destination safety against trap teleports, falling into void/lava,
 * suffocation, lethal liquids, and explosive/hazard entities while preserving
 * exact sub-block coordinates. Delegates core block hazard checks to SafeTeleportUtil.
 */
public class SafeTeleportChecker {

    public static class SafeCheckSettings {
        private volatile boolean safeTeleportEnabled;
        private volatile boolean checkVehicle;
        private volatile boolean checkNearbyEntity;
        private volatile boolean checkFalling;
        private volatile boolean checkWater;
        private volatile boolean checkSign;

        public SafeCheckSettings(FileConfiguration config) {
            update(config);
        }

        public void update(FileConfiguration config) {
            this.safeTeleportEnabled = config.getBoolean("safe-teleport", true);
            this.checkVehicle = config.getBoolean("safe-teleport-checks.check-vehicle", true);
            this.checkNearbyEntity = config.getBoolean("safe-teleport-checks.check-nearby-entity", true);
            this.checkFalling = config.getBoolean("safe-teleport-checks.check-falling", true);
            this.checkWater = config.getBoolean("safe-teleport-checks.check-water", false);
            this.checkSign = config.getBoolean("safe-teleport-checks.check-sign", false);
        }

        public boolean isSafeTeleportEnabled() {
            return safeTeleportEnabled;
        }

        public void setSafeTeleportEnabled(boolean safeTeleportEnabled) {
            this.safeTeleportEnabled = safeTeleportEnabled;
        }

        public boolean isCheckVehicle() {
            return checkVehicle;
        }

        public void setCheckVehicle(boolean checkVehicle) {
            this.checkVehicle = checkVehicle;
        }

        public boolean isCheckNearbyEntity() {
            return checkNearbyEntity;
        }

        public void setCheckNearbyEntity(boolean checkNearbyEntity) {
            this.checkNearbyEntity = checkNearbyEntity;
        }

        public boolean isCheckFalling() {
            return checkFalling;
        }

        public void setCheckFalling(boolean checkFalling) {
            this.checkFalling = checkFalling;
        }

        public boolean isCheckWater() {
            return checkWater;
        }

        public void setCheckWater(boolean checkWater) {
            this.checkWater = checkWater;
        }

        public boolean isCheckSign() {
            return checkSign;
        }

        public void setCheckSign(boolean checkSign) {
            this.checkSign = checkSign;
        }
    }

    /**
     * Checks whether a teleport to the target location / player is safe based on active checks.
     */
    public static boolean isTeleportSafe(Player traveler, Player targetPlayer, Location destination, SafeCheckSettings settings) {
        if (destination == null || destination.getWorld() == null) {
            return false;
        }

        if (!settings.isSafeTeleportEnabled()) {
            return true;
        }

        // Prevent teleporting players that are currently inside vehicles
        if (settings.isCheckVehicle()) {
            if (traveler != null && traveler.isInsideVehicle()) {
                return false;
            }
            if (targetPlayer != null && targetPlayer.isInsideVehicle()) {
                return false;
            }
        }

        try {
            World world = destination.getWorld();
            double x = destination.getX();
            double y = destination.getY();
            double z = destination.getZ();
            int blockX = destination.getBlockX();
            int blockY = destination.getBlockY();
            int blockZ = destination.getBlockZ();

            // World height and Nether ceiling bounds check
            int maxY = (world.getEnvironment() == World.Environment.NETHER) ? 125 : world.getMaxHeight() - 1;
            if (y <= world.getMinHeight() + 1 || y >= maxY) {
                return false;
            }

            // Test player hitbox corners (0.6 width x 1.8 height)
            double[] offsets = {-0.29, 0.29};
            for (double ox : offsets) {
                for (double oz : offsets) {
                    int bx = (int) Math.floor(x + ox);
                    int bz = (int) Math.floor(z + oz);

                    Block cornerFeet = world.getBlockAt(bx, (int) Math.floor(y + 0.1), bz);
                    Block cornerTorso = world.getBlockAt(bx, (int) Math.floor(y + 1.0), bz);
                    Block cornerHead = world.getBlockAt(bx, (int) Math.floor(y + 1.8), bz);

                    // Lethal hazard checks (lava, fire, magma, sweet berry bushes, etc.)
                    if (isHazard(cornerFeet.getType()) || isHazard(cornerTorso.getType()) || isHazard(cornerHead.getType())) {
                        return false;
                    }

                    if (isSuffocating(cornerFeet) || isSuffocating(cornerTorso) || isSuffocating(cornerHead)) {
                        return false;
                    }
                }
            }

            Block feetBlock = world.getBlockAt(blockX, (int) Math.floor(y + 0.1), blockZ);
            Block headBlock = world.getBlockAt(blockX, (int) Math.floor(y + 1.6), blockZ);
            Block groundBlock = world.getBlockAt(blockX, (int) Math.floor(y - 0.05), blockZ);

            if (isHazard(groundBlock.getType())) {
                return false;
            }

            if (settings.isCheckFalling()) {
                boolean hasGroundSupport = isWalkableGround(groundBlock)
                        || isWalkableGround(world.getBlockAt(blockX, blockY - 1, blockZ))
                        || isWalkableGround(feetBlock);

                if (!hasGroundSupport) {
                    if (targetPlayer != null && targetPlayer.isOnline() && (targetPlayer.isFlying() || targetPlayer.isGliding())) {
                        // Target is actively flying or gliding in creative / elytra mode
                    } else {
                        // Verify solid ground exists within a reasonable fall distance (3 blocks)
                        boolean foundGround = false;
                        for (int dy = 1; dy <= 3; dy++) {
                            Block below = world.getBlockAt(blockX, blockY - dy, blockZ);
                            if (isWalkableGround(below)) {
                                foundGround = true;
                                break;
                            }
                        }
                        if (!foundGround) {
                            return false;
                        }
                    }
                }
            }

            if (settings.isCheckWater()) {
                if (isDeepWater(feetBlock) && isDeepWater(headBlock)) {
                    return false;
                }
            }

            // Trap sign check (e.g. signs suspended over open void or deep shafts)
            if (settings.isCheckSign()) {
                if (isSign(groundBlock.getType()) && world.getBlockAt(blockX, blockY - 2, blockZ).getType().isAir()) {
                    return false;
                }
            }

            // Scan for dangerous explosive or hostile entities nearby
            if (settings.isCheckNearbyEntity()) {
                try {
                    Collection<Entity> nearby = world.getNearbyEntities(destination, 1.5, 1.5, 1.5);
                    for (Entity entity : nearby) {
                        if (traveler != null && entity.getUniqueId().equals(traveler.getUniqueId())) continue;
                        if (targetPlayer != null && entity.getUniqueId().equals(targetPlayer.getUniqueId())) continue;

                        if (isDangerousEntity(entity)) {
                            return false;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Resolves a safe location near destination preserving exact sub-block precision.
     */
    public static Location getSafeDestination(Player traveler, Player targetPlayer, Location destination, SafeCheckSettings settings) {
        if (destination == null || destination.getWorld() == null) return null;

        if (isTeleportSafe(traveler, targetPlayer, destination, settings)) {
            return destination.clone();
        }

        if (!settings.isSafeTeleportEnabled()) {
            return destination.clone();
        }

        World world = destination.getWorld();
        double x = destination.getX();
        double y = destination.getY();
        double z = destination.getZ();
        float yaw = destination.getYaw();
        float pitch = destination.getPitch();

        // Search vertical offsets (+1..+3 upwards first, then -1..-2 downwards) while maintaining exact sub-block coordinates
        for (int dy : new int[]{1, 2, 3, -1, -2}) {
            double testY = y + dy;
            if (testY <= world.getMinHeight() + 1 || testY >= world.getMaxHeight() - 2) continue;

            Location testLoc = new Location(world, x, testY, z, yaw, pitch);
            if (isTeleportSafe(traveler, targetPlayer, testLoc, settings)) {
                return testLoc;
            }
        }

        return null;
    }

    public static boolean isSuffocating(Block block) {
        return SafeTeleportUtil.isSuffocating(block);
    }

    public static boolean isWalkableGround(Block block) {
        return SafeTeleportUtil.isWalkableGround(block);
    }

    public static boolean isHazard(Material mat) {
        return SafeTeleportUtil.isHazard(mat);
    }

    public static boolean isDeepWater(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat == Material.WATER || mat == Material.BUBBLE_COLUMN) return true;
        BlockData data = block.getBlockData();
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    public static boolean isSign(Material mat) {
        return mat != null && mat.name().contains("SIGN");
    }

    public static boolean isDangerousEntity(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof TNTPrimed) return true;
        if (entity instanceof FallingBlock fb && (fb.getBlockData().getMaterial() == Material.ANVIL || fb.getBlockData().getMaterial() == Material.POINTED_DRIPSTONE)) {
            return true;
        }
        String typeName = entity.getType().name();
        return typeName.contains("CRYSTAL")
                || typeName.contains("MINECART_TNT")
                || typeName.contains("WITHER_SKULL")
                || typeName.contains("FIREBALL")
                || typeName.contains("WITHER")
                || typeName.contains("WARDEN");
    }
}
