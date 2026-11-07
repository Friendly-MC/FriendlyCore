package dev.lyy.friendlycore.modules.spawn;

import java.util.Collection;

/**
 * Decides whether a stored logout should stay at the configured spawn point or be
 * sent back to another backend (economy, overworld, etc.).
 */
public final class LastLocationPolicy {

    private LastLocationPolicy() {}

    /**
     * No saved destination, or the saved destination is the spawn hub itself.
     * Decision is by <strong>server id</strong> (spawn-eco vs economy-b), not by
     * world name — a world called {@code spawn} on economy-b must still transfer.
     */
    public static boolean isSpawnLogout(SpawnData last, SpawnData configuredSpawn,
                                       Collection<String> hubServers, Collection<String> spawnWorlds) {
        if (last == null) {
            return true;
        }
        if (configuredSpawn != null && equalsIgnoreCase(last.server(), configuredSpawn.server())) {
            return true;
        }
        if (containsIgnoreCase(hubServers, last.server())) {
            return true;
        }
        // Server id missing: only then fall back to spawn-world names
        if (last.server() == null || last.server().isBlank()) {
            if (configuredSpawn != null && equalsIgnoreCase(last.world(), configuredSpawn.world())) {
                return true;
            }
            return containsIgnoreCase(spawnWorlds, last.world());
        }
        return false;
    }

    /**
     * True when the stored logout belongs to a different backend than the one
     * the player just joined (typically the spawn hub).
     */
    public static boolean shouldRouteToRemote(SpawnData last, String localServer) {
        return shouldRouteToRemote(last, localServer, null);
    }

    /**
     * Same as {@link #shouldRouteToRemote(SpawnData, String)} but also treats the
     * Velocity/Bungee {@code GetServer} name as local so a mismatched
     * {@code redis.server-id} does not bounce the player.
     */
    public static boolean shouldRouteToRemote(SpawnData last, String localServer, String proxyServerName) {
        if (last == null || last.server() == null || last.server().isBlank()) {
            return false;
        }
        if (equalsIgnoreCase(last.server(), localServer) || equalsIgnoreCase(last.server(), proxyServerName)) {
            return false;
        }
        return true;
    }

    /**
     * Connect to a saved remote backend only from the spawn hub. Doing this on
     * economy/overworld would bounce a player who just used a server selector.
     */
    public static boolean shouldForwardFromHub(boolean hub, SpawnData last, String localServer, String proxyServerName) {
        return hub && shouldRouteToRemote(last, localServer, proxyServerName);
    }

    /**
     * Saved coordinates belong to their own backend. Reusing another server's coordinates
     * in a same-named local world (every backend has {@code world} and usually
     * {@code world_the_end}) would pull portal, selector and RTP arrivals into the wrong place.
     */
    public static boolean canRestoreHere(SpawnData last, String localServer, String proxyServerName) {
        return last != null && !shouldRouteToRemote(last, localServer, proxyServerName);
    }

    /**
     * True when the player is already on the saved backend. Connecting again
     * makes Velocity reconnect them and loops {@code transfer -> server}.
     */
    public static boolean alreadyOnSavedServer(SpawnData last, String localServer, String proxyServerName) {
        return last != null && last.server() != null && !last.server().isBlank()
                && (equalsIgnoreCase(last.server(), localServer)
                || equalsIgnoreCase(last.server(), proxyServerName));
    }

    /**
     * Hub servers receive every proxy login and then forward players onward.
     */
    public static boolean isHubServer(String localServer, SpawnData configuredSpawn, Collection<String> hubServers) {
        if (containsIgnoreCase(hubServers, localServer)) {
            return true;
        }
        return configuredSpawn != null && equalsIgnoreCase(localServer, configuredSpawn.server());
    }

    /**
     * True when this join should run hub forwarding: known hub server id, proxy
     * name in the hub list, or the player is standing in a configured spawn world.
     */
    public static boolean isHubContext(String localServer, String localWorld, String proxyServerName,
                                       SpawnData configuredSpawn, Collection<String> hubServers,
                                       Collection<String> spawnWorlds) {
        if (isHubServer(localServer, configuredSpawn, hubServers)
                || isHubServer(proxyServerName, configuredSpawn, hubServers)) {
            return true;
        }
        if (containsIgnoreCase(spawnWorlds, localWorld)) {
            return true;
        }
        return configuredSpawn != null && equalsIgnoreCase(localWorld, configuredSpawn.world());
    }

    private static boolean containsIgnoreCase(Collection<String> values, String candidate) {
        if (values == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }
}
