package dev.lyy.friendlycore.modules.tpa.crossserver;

import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * Arrival ticket holding verified destination coordinates for incoming cross-server travelers.
 */
public class TpaTeleportTicket {

    private final UUID travelerUuid;
    private final String travelerName;
    private final UUID hostUuid;
    private final String hostName;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final TpaRequest.Type type;
    private final long createdAt;
    private final long timeoutMs;

    public TpaTeleportTicket(UUID travelerUuid, String travelerName, UUID hostUuid, String hostName,
                             String worldName, double x, double y, double z, float yaw, float pitch,
                             TpaRequest.Type type) {
        this(travelerUuid, travelerName, hostUuid, hostName, worldName, x, y, z, yaw, pitch, type, 60_000L);
    }

    public TpaTeleportTicket(UUID travelerUuid, String travelerName, UUID hostUuid, String hostName,
                             String worldName, double x, double y, double z, float yaw, float pitch,
                             TpaRequest.Type type, long timeoutMs) {
        this.travelerUuid = travelerUuid;
        this.travelerName = travelerName;
        this.hostUuid = hostUuid;
        this.hostName = hostName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.timeoutMs = timeoutMs;
    }

    public UUID getTravelerUuid() {
        return travelerUuid;
    }

    public String getTravelerName() {
        return travelerName;
    }

    public UUID getHostUuid() {
        return hostUuid;
    }

    public String getHostName() {
        return hostName;
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

    public TpaRequest.Type getType() {
        return type;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > timeoutMs;
    }

    public Location toLocation() {
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        // Do not fallback to default world: teleporting to unverified worlds risks void deaths
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }
}
