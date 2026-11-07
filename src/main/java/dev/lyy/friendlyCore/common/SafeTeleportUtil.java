package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Thread-safe teleportation helper for Folia and Paper.
 * Handles debouncing to prevent overlapping region handoffs, asynchronous chunk loading,
 * vehicle dismounting, fall-distance/velocity resets, and destination hazard validation.
 */
public final class SafeTeleportUtil {

    private static final Set<UUID> IN_FLIGHT_TELEPORTS = ConcurrentHashMap.newKeySet();

    private SafeTeleportUtil() {}

    /**
     * Checks if an entity currently has an asynchronous teleportation in progress.
     */
    public static boolean isTeleporting(UUID uuid) {
        return uuid != null && IN_FLIGHT_TELEPORTS.contains(uuid);
    }

    /**
     * Clears an entity's in-flight teleport state manually (e.g. on quit or task cancellation).
     */
    public static void clearInFlight(UUID uuid) {
        if (uuid != null) {
            IN_FLIGHT_TELEPORTS.remove(uuid);
        }
    }

    /**
     * Clears all in-flight teleport records (e.g. on plugin disable or reload).
     */
    public static void clearAll() {
        IN_FLIGHT_TELEPORTS.clear();
    }

    /**
     * Asynchronously teleports an entity to a target destination with in-flight debouncing,
     * chunk pre-loading, vehicle dismounting, and zeroed velocity.
     *
     * @param entity      The entity to teleport.
     * @param destination The destination location.
     * @return CompletableFuture completing with true if teleported successfully, false otherwise.
     */
    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location destination) {
        return teleportAsync(entity, destination, true);
    }

    /**
     * Asynchronously teleports an entity to a target destination with in-flight debouncing,
     * optional chunk pre-loading, vehicle dismounting, and zeroed velocity.
     *
     * @param entity       The entity to teleport.
     * @param destination  The destination location.
     * @param preloadChunk Whether to asynchronously preload the destination chunk prior to handoff.
     * @return CompletableFuture completing with true if teleported successfully, false otherwise.
     */
    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location destination, boolean preloadChunk) {
        if (entity == null || !entity.isValid() || destination == null || destination.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        if (entity instanceof Player player && !player.isOnline()) {
            return CompletableFuture.completedFuture(false);
        }

        UUID uuid = entity.getUniqueId();

        // Guard against overlapping region transitions on Folia
        if (!IN_FLIGHT_TELEPORTS.add(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        FriendlyCore plugin = FriendlyCore.getInstance();
        if (plugin == null || plugin.getScheduler() == null) {
            IN_FLIGHT_TELEPORTS.remove(uuid);
            return CompletableFuture.completedFuture(false);
        }

        Runnable teleportExecution = () -> {
            plugin.getScheduler().runEntity(entity, () -> {
                if (!entity.isValid() || (entity instanceof Player p && !p.isOnline())) {
                    IN_FLIGHT_TELEPORTS.remove(uuid);
                    future.complete(false);
                    return;
                }

                if (entity instanceof Player player && !canEnterWorld(player, destination.getWorld(),
                        Bukkit.getPluginManager().isPluginEnabled("Worlds"))) {
                    Messages.send(plugin, player, "core", "teleport-world-no-permission");
                    IN_FLIGHT_TELEPORTS.remove(uuid);
                    future.complete(false);
                    return;
                }

                try {
                    // Dismount vehicles and eject passengers before handoff
                    if (entity.isInsideVehicle()) {
                        entity.leaveVehicle();
                    }
                    if (!entity.getPassengers().isEmpty()) {
                        entity.eject();
                    }

                    // Reset physics before handoff
                    entity.setFallDistance(0.0f);
                    entity.setVelocity(new Vector(0, 0, 0));

                    // Dispatch async teleport
                    CompletableFuture<Boolean> tpFuture;
                    try {
                        tpFuture = entity.teleportAsync(destination);
                    } catch (Throwable t) {
                        IN_FLIGHT_TELEPORTS.remove(uuid);
                        future.complete(false);
                        return;
                    }

                    if (tpFuture == null) {
                        IN_FLIGHT_TELEPORTS.remove(uuid);
                        future.complete(false);
                        return;
                    }

                    tpFuture.whenComplete((success, error) -> {
                        IN_FLIGHT_TELEPORTS.remove(uuid);
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            future.complete(false);
                            return;
                        }

                        // Reset physics on arrival in the target region thread
                        plugin.getScheduler().runEntity(entity, () -> {
                            if (entity.isValid()) {
                                entity.setFallDistance(0.0f);
                                entity.setVelocity(new Vector(0, 0, 0));
                            }
                            future.complete(true);
                        }, () -> future.complete(true));
                    });
                } catch (Throwable t) {
                    IN_FLIGHT_TELEPORTS.remove(uuid);
                    future.complete(false);
                }
            }, () -> {
                IN_FLIGHT_TELEPORTS.remove(uuid);
                future.complete(false);
            });
        };

        if (preloadChunk) {
            int chunkX = destination.getBlockX() >> 4;
            int chunkZ = destination.getBlockZ() >> 4;
            destination.getWorld().getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
                teleportExecution.run();
            }).exceptionally(ex -> {
                IN_FLIGHT_TELEPORTS.remove(uuid);
                future.complete(false);
                return null;
            });
        } else {
            teleportExecution.run();
        }

        return future;
    }

    static boolean canEnterWorld(Player player, World world, boolean worldsEnabled) {
        return !worldsEnabled || player.getWorld() == world
                || player.hasPermission("worlds.enter." + world.getKey());
    }

    /**
     * Teleports a player with sequential handling of attached leashed entities.
     * Leashed mobs are collected safely on the player's entity scheduler thread, the player
     * is teleported first, and only after arrival at the destination region are all leashed mobs
     * teleported and re-attached.
     *
     * @param player      The player to teleport.
     * @param destination The destination location.
     * @return CompletableFuture completing with true if player and transitions succeeded, false otherwise.
     */
    public static CompletableFuture<Boolean> teleportWithLeashedEntities(Player player, Location destination) {
        if (player == null || !player.isOnline() || destination == null || destination.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        FriendlyCore plugin = FriendlyCore.getInstance();
        if (plugin == null || plugin.getScheduler() == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Collect leashed entities on the player's region thread
        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                future.complete(false);
                return;
            }

            List<LivingEntity> leashed = new ArrayList<>();
            try {
                for (Entity nearby : player.getNearbyEntities(10, 10, 10)) {
                    if (nearby instanceof LivingEntity living && living.isLeashed() && player.equals(living.getLeashHolder())) {
                        leashed.add(living);
                    }
                }
            } catch (Throwable ignored) {}

            teleportAsync(player, destination).whenComplete((success, error) -> {
                if (error != null || !Boolean.TRUE.equals(success) || !player.isOnline()) {
                    future.complete(false);
                    return;
                }

                // Teleport and re-attach leashed mobs after the player has safely arrived
                if (!leashed.isEmpty()) {
                    for (LivingEntity living : leashed) {
                        if (living.isValid()) {
                            teleportAsync(living, destination).thenAccept(mobSuccess -> {
                                if (Boolean.TRUE.equals(mobSuccess)) {
                                    plugin.getScheduler().runEntity(living, () -> {
                                        if (living.isValid() && player.isOnline()) {
                                            try {
                                                living.setLeashHolder(player);
                                            } catch (Throwable ignored) {}
                                        }
                                    });
                                }
                            });
                        }
                    }
                }

                future.complete(true);
            });
        }, () -> future.complete(false));

        return future;
    }

    /**
     * Teleports a player with sequential handling of attached leashed entities (Consumer callback overload).
     *
     * @param player      The player to teleport.
     * @param destination The destination location.
     * @param callback    Optional completion callback receiving true on success, false on failure.
     */
    public static void teleportWithLeashedEntities(Player player, Location destination, Consumer<Boolean> callback) {
        teleportWithLeashedEntities(player, destination).thenAccept(success -> {
            if (callback != null) {
                callback.accept(success);
            }
        });
    }

    /**
     * Asynchronously resolves a safe destination location, ensuring Folia region thread alignment before checking blocks.
     */
    public static CompletableFuture<Location> getSafeDestinationAsync(Location destination) {
        if (destination == null || destination.getWorld() == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Location> future = new CompletableFuture<>();
        FriendlyCore plugin = FriendlyCore.getInstance();
        if (plugin != null && plugin.getScheduler() != null) {
            plugin.getScheduler().runLocation(destination, () -> {
                try {
                    Location safe = getSafeDestination(destination);
                    future.complete(safe);
                } catch (Throwable t) {
                    future.complete(null);
                }
            });
        } else {
            try {
                future.complete(getSafeDestination(destination));
            } catch (Throwable t) {
                future.complete(null);
            }
        }
        return future;
    }

    /**
     * Validates whether a destination location is safe for entity teleportation
     * (checks height bounds, suffocation, hazards, and walkable ground support).
     */
    public static boolean isLocationSafe(Location destination) {
        if (destination == null || destination.getWorld() == null) {
            return false;
        }

        World world = destination.getWorld();
        double x = destination.getX();
        double y = destination.getY();
        double z = destination.getZ();
        int blockX = destination.getBlockX();
        int blockY = destination.getBlockY();
        int blockZ = destination.getBlockZ();

        int maxY = (world.getEnvironment() == World.Environment.NETHER) ? 125 : world.getMaxHeight() - 1;
        if (y <= world.getMinHeight() + 1 || y >= maxY) {
            return false;
        }

        // Sample bounding box corners to detect nearby hazards or suffocation risks
        double[] offsets = {-0.29, 0.29};
        for (double ox : offsets) {
            for (double oz : offsets) {
                int bx = (int) Math.floor(x + ox);
                int bz = (int) Math.floor(z + oz);

                Block cornerFeet = world.getBlockAt(bx, (int) Math.floor(y + 0.1), bz);
                Block cornerTorso = world.getBlockAt(bx, (int) Math.floor(y + 1.0), bz);
                Block cornerHead = world.getBlockAt(bx, (int) Math.floor(y + 1.8), bz);

                if (isHazard(cornerFeet.getType()) || isHazard(cornerTorso.getType()) || isHazard(cornerHead.getType())) {
                    return false;
                }
                if (isSuffocating(cornerFeet) || isSuffocating(cornerTorso) || isSuffocating(cornerHead)) {
                    return false;
                }
            }
        }

        Block feetBlock = world.getBlockAt(blockX, (int) Math.floor(y + 0.1), blockZ);
        Block groundBlock = world.getBlockAt(blockX, (int) Math.floor(y - 0.05), blockZ);

        if (isHazard(groundBlock.getType())) {
            return false;
        }

        // Verify supporting ground beneath the landing position
        boolean hasGround = isWalkableGround(groundBlock)
                || isWalkableGround(world.getBlockAt(blockX, blockY - 1, blockZ))
                || isWalkableGround(feetBlock);

        if (!hasGround) {
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

        return true;
    }

    /**
     * Resolves a safe location near destination preserving exact sub-block coordinates.
     * Searches vertical offsets in order (+1, +2, +3, -1, -2, -3).
     *
     * @param destination Target destination.
     * @return Safe location with sub-block precision, or null if no safe spot found.
     */
    public static Location getSafeDestination(Location destination) {
        if (destination == null || destination.getWorld() == null) return null;

        if (isLocationSafe(destination)) {
            return destination.clone();
        }

        World world = destination.getWorld();
        double x = destination.getX();
        double y = destination.getY();
        double z = destination.getZ();
        float yaw = destination.getYaw();
        float pitch = destination.getPitch();

        for (int dy : new int[]{1, 2, 3, -1, -2, -3}) {
            double testY = y + dy;
            if (testY <= world.getMinHeight() + 1 || testY >= world.getMaxHeight() - 2) continue;

            Location testLoc = new Location(world, x, testY, z, yaw, pitch);
            if (isLocationSafe(testLoc)) {
                return testLoc;
            }
        }

        return null;
    }

    /**
     * Finds the highest safe surface location at the specified (X, Z) coordinates.
     * Useful for /top command.
     */
    public static Location getHighestSafeLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        World world = location.getWorld();
        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return null;
        }
        int maxHeight = world.getEnvironment() == World.Environment.NETHER ? 125 : world.getMaxHeight() - 3;
        int minHeight = world.getMinHeight() + 1;

        for (int y = maxHeight; y >= minHeight; y--) {
            Block ground = world.getBlockAt(blockX, y, blockZ);
            if (isWalkableGround(ground)) {
                Block feet = world.getBlockAt(blockX, y + 1, blockZ);
                Block head = world.getBlockAt(blockX, y + 2, blockZ);
                if (!isSuffocating(feet) && !isSuffocating(head) && !isHazard(feet.getType()) && !isHazard(head.getType())) {
                    return new Location(world, location.getX(), y + 1.0, location.getZ(), location.getYaw(), location.getPitch());
                }
            }
        }
        return null;
    }

    /**
     * Finds the lowest safe floor location below the player's current height.
     * Useful for /bottom command.
     */
    public static Location getLowestSafeLocation(Location location) {
        if (location == null || location.getWorld() == null) return null;
        World world = location.getWorld();
        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return null;
        }
        int minHeight = world.getMinHeight() + 1;
        int currentY = Math.min(location.getBlockY(), world.getEnvironment() == World.Environment.NETHER ? 125 : world.getMaxHeight() - 3);

        for (int y = minHeight; y < currentY; y++) {
            Block ground = world.getBlockAt(blockX, y, blockZ);
            if (isWalkableGround(ground)) {
                Block feet = world.getBlockAt(blockX, y + 1, blockZ);
                Block head = world.getBlockAt(blockX, y + 2, blockZ);
                if (!isSuffocating(feet) && !isSuffocating(head) && !isHazard(feet.getType()) && !isHazard(head.getType())) {
                    return new Location(world, location.getX(), y + 1.0, location.getZ(), location.getYaw(), location.getPitch());
                }
            }
        }
        return null;
    }

    /**
     * Checks if a block will cause suffocation damage to an entity.
     */
    public static boolean isSuffocating(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat.isAir() || block.isPassable()) return false;
        return mat.isSolid() && mat.isOccluding();
    }

    /**
     * Checks if a block is safe and solid enough to stand on.
     */
    public static boolean isWalkableGround(Block block) {
        if (block == null) return false;
        Material mat = block.getType();
        if (mat.isAir() || isHazard(mat)) return false;
        if (mat.isSolid()) return true;

        String name = mat.name();
        return name.contains("CARPET")
                || name.contains("SLAB")
                || name.contains("STAIR")
                || name.contains("TRAPDOOR")
                || name.contains("FENCE")
                || name.contains("WALL")
                || name.contains("BED")
                || name.contains("GATE")
                || name.contains("SCAFFOLDING")
                || name.contains("SNOW")
                || name.contains("LADDER")
                || name.contains("VINE")
                || name.contains("CHEST")
                || name.contains("SHULKER_BOX")
                || name.contains("GLASS")
                || name.contains("FARMLAND")
                || name.contains("DIRT_PATH")
                || name.contains("LILY_PAD")
                || name.contains("HONEY_BLOCK")
                || name.contains("SLIME_BLOCK")
                || name.contains("AMETHYST")
                || name.contains("LEAVES")
                || name.contains("DAYLIGHT_DETECTOR")
                || name.contains("HOPPER")
                || name.contains("CAULDRON")
                || name.contains("END_PORTAL_FRAME")
                || name.contains("BREWING_STAND")
                || name.contains("LANTERN")
                || name.contains("BELL")
                || name.contains("ENCHANTING_TABLE")
                || name.contains("ANVIL")
                || name.contains("MUD")
                || name.contains("SOUL_SAND")
                || name.contains("SOUL_SOIL");
    }

    /**
     * Checks if a material represents a lethal environmental hazard.
     */
    public static boolean isHazard(Material mat) {
        if (mat == null) return false;
        return mat == Material.LAVA
                || mat == Material.FIRE
                || mat == Material.SOUL_FIRE
                || mat == Material.MAGMA_BLOCK
                || mat == Material.POINTED_DRIPSTONE
                || mat == Material.CAMPFIRE
                || mat == Material.SOUL_CAMPFIRE
                || mat == Material.SWEET_BERRY_BUSH
                || mat == Material.WITHER_ROSE
                || mat == Material.CACTUS
                || mat == Material.POWDER_SNOW
                || mat == Material.NETHER_PORTAL
                || mat == Material.END_PORTAL
                || mat == Material.END_GATEWAY
                || mat == Material.VOID_AIR;
    }
}
