package dev.lyy.friendlycore.modules.homes.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

/**
 * Data model representing a saved player home location.
 */
public class HomeData {

    private final UUID playerUuid;
    private final int slot;
    private String name;
    private String serverId;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private final long createdAt;

    public HomeData(UUID playerUuid, int slot, String name, String serverId,
                    String worldName, double x, double y, double z, float yaw, float pitch, long createdAt) {
        this.playerUuid = playerUuid;
        this.slot = slot;
        this.name = name != null ? name : "";
        this.serverId = serverId != null ? serverId : "";
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    public HomeData(UUID playerUuid, int slot, String name, String serverId, Location location) {
        this(playerUuid, slot, name, serverId,
                location.getWorld() != null ? location.getWorld().getName() : "world",
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), System.currentTimeMillis());
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public int getSlot() {
        return slot;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getDisplayName() {
        return (name != null && !name.isEmpty()) ? name : String.valueOf(slot);
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId != null ? serverId : "";
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void updateLocation(String serverId, Location location) {
        this.serverId = serverId != null ? serverId : "";
        this.worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
    }

    /**
     * Attempts to resolve the Bukkit Location on this local server.
     * Returns null if world is not loaded or this home belongs to another server.
     */
    public Location toBukkitLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public boolean isSameServer(String currentServerId) {
        if (this.serverId == null || this.serverId.isEmpty()) return true;
        if (currentServerId == null || currentServerId.isEmpty()) return true;
        return this.serverId.equalsIgnoreCase(currentServerId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HomeData homeData = (HomeData) o;
        return slot == homeData.slot && Objects.equals(playerUuid, homeData.playerUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerUuid, slot);
    }
}
