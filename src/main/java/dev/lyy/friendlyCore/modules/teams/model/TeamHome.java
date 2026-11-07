package dev.lyy.friendlycore.modules.teams.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/**
 * Represents a saved team home waypoint, supporting optional cross-server routing metadata.
 */
public class TeamHome {

    private final String name;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final String server;

    public TeamHome(String name, String worldName, double x, double y, double z, float yaw, float pitch) {
        this(name, worldName, x, y, z, yaw, pitch, "");
    }

    public TeamHome(String name, String worldName, double x, double y, double z, float yaw, float pitch, String server) {
        this.name = name != null && !name.isBlank() ? name : "home";
        this.worldName = worldName != null ? worldName : "world";
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.server = server != null ? server : "";
    }

    public static TeamHome fromLocation(String name, Location location) {
        return fromLocation(name, location, "");
    }

    public static TeamHome fromLocation(String name, Location location, String server) {
        if (location == null) return null;
        String worldName = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new TeamHome(name, worldName, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(), server);
    }

    public static TeamHome parse(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            // format: name==worldName;x;y;z;yaw;pitch[;server]
            String name = "home";
            String locData = str;
            if (str.contains("==")) {
                String[] parts = str.split("==", 2);
                name = parts[0];
                locData = parts[1];
            }
            String[] tokens = locData.split(";");
            if (tokens.length >= 4) {
                String world = tokens[0];
                double x = Double.parseDouble(tokens[1]);
                double y = Double.parseDouble(tokens[2]);
                double z = Double.parseDouble(tokens[3]);
                float yaw = tokens.length > 4 ? Float.parseFloat(tokens[4]) : 0.0f;
                float pitch = tokens.length > 5 ? Float.parseFloat(tokens[5]) : 0.0f;
                String server = tokens.length > 6 ? tokens[6] : "";
                return new TeamHome(name, world, x, y, z, yaw, pitch, server);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String serialize() {
        if (server != null && !server.isBlank()) {
            return name + "==" + worldName + ";" + x + ";" + y + ";" + z + ";" + yaw + ";" + pitch + ";" + server;
        }
        return name + "==" + worldName + ";" + x + ";" + y + ";" + z + ";" + yaw + ";" + pitch;
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String getName() {
        return name;
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

    public String getServer() {
        return server;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamHome teamHome)) return false;
        return Objects.equals(name, teamHome.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
