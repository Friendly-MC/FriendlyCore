package dev.lyy.friendlycore.modules.essentials.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Represents a pending cross-server teleport ticket issued for administrative teleports.
 */
public class AdminTeleportTicket {

    private UUID playerUuid;
    private String playerName;
    private UUID targetPlayerUuid;
    private String targetPlayerName;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private long timestamp;

    public AdminTeleportTicket() {}

    public AdminTeleportTicket(UUID playerUuid, String playerName, UUID targetPlayerUuid, String targetPlayerName,
                               String worldName, double x, double y, double z, float yaw, float pitch) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.targetPlayerUuid = targetPlayerUuid;
        this.targetPlayerName = targetPlayerName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getTargetPlayerUuid() {
        return targetPlayerUuid;
    }

    public String getTargetPlayerName() {
        return targetPlayerName;
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

    public boolean isExpired() {
        return System.currentTimeMillis() - timestamp > 45000L; // 45 seconds validity window
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
        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }
}
