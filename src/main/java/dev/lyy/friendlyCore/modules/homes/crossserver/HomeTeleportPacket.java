package dev.lyy.friendlycore.modules.homes.crossserver;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Data packet used for dispatching cross-server home teleport requests over Redis Pub/Sub.
 */
public class HomeTeleportPacket {

    private String targetServer;
    private UUID playerUuid;
    private String playerName;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private long timestamp;
    private boolean teamHome;

    public HomeTeleportPacket() {}

    public HomeTeleportPacket(String targetServer, UUID playerUuid, String playerName,
                              String worldName, double x, double y, double z, float yaw, float pitch) {
        this(targetServer, playerUuid, playerName, worldName, x, y, z, yaw, pitch, false);
    }

    public HomeTeleportPacket(String targetServer, UUID playerUuid, String playerName,
                              String worldName, double x, double y, double z, float yaw, float pitch, boolean teamHome) {
        this.targetServer = targetServer;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.timestamp = System.currentTimeMillis();
        this.teamHome = teamHome;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getWorldName() {
        return worldName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isTeamHome() {
        return teamHome;
    }

    public void setTeamHome(boolean teamHome) {
        this.teamHome = teamHome;
    }

    public Location toLocation() {
        World world = null;
        if (worldName != null && !worldName.isEmpty()) {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                for (World w : Bukkit.getWorlds()) {
                    if (w.getName().equalsIgnoreCase(worldName) ||
                        w.getName().replace("_", "").equalsIgnoreCase(worldName.replace("_", ""))) {
                        world = w;
                        break;
                    }
                }
            }
        }
        // Refuse to fall back to an arbitrary world: teleporting to the same
        // coordinates in the wrong world can strand the player in the void.
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }
}
