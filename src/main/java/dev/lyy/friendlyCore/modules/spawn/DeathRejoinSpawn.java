package dev.lyy.friendlycore.modules.spawn;

import org.bukkit.Location;

/**
 * Picks where a player should appear after dying, then disconnecting before (or instead of)
 * clicking respawn. Death coordinates must never win.
 *
 * <p>Priority: bed / respawn anchor, then Lands spawn, then the configured spawn point
 * when they died in the spawn world, otherwise the current world's spawn.
 */
public final class DeathRejoinSpawn {

    private DeathRejoinSpawn() {}

    public static Location pick(String respawnMode, Location bedOrAnchor, Location landsSpawn,
                                Location configuredSpawn, Location worldSpawn,
                                String currentWorld, String configuredSpawnWorld) {
        String mode = respawnMode != null ? respawnMode.toUpperCase(java.util.Locale.ROOT) : "NO_BED";
        if ("ALWAYS".equals(mode)) {
            if (isUsable(configuredSpawn)) {
                return configuredSpawn;
            }
            return isUsable(worldSpawn) ? worldSpawn : configuredSpawn;
        }

        if (isUsable(bedOrAnchor)) {
            return bedOrAnchor;
        }
        if (isUsable(landsSpawn)) {
            return landsSpawn;
        }
        if (isUsable(configuredSpawn)) {
            return configuredSpawn;
        }
        return isUsable(worldSpawn) ? worldSpawn : configuredSpawn;
    }

    public static Location pick(Location bedOrAnchor, Location landsSpawn, Location configuredSpawn,
                                Location worldSpawn, String currentWorld, String configuredSpawnWorld) {
        if (isUsable(bedOrAnchor)) {
            return bedOrAnchor;
        }
        if (isUsable(landsSpawn)) {
            return landsSpawn;
        }
        if (isUsable(configuredSpawn) && sameWorld(currentWorld, configuredSpawnWorld)) {
            return configuredSpawn;
        }
        return isUsable(worldSpawn) ? worldSpawn : configuredSpawn;
    }

    private static boolean isUsable(Location location) {
        return location != null && location.getWorld() != null;
    }

    private static boolean sameWorld(String currentWorld, String configuredSpawnWorld) {
        if (currentWorld == null || configuredSpawnWorld == null) {
            return false;
        }
        return currentWorld.equalsIgnoreCase(configuredSpawnWorld);
    }
}
