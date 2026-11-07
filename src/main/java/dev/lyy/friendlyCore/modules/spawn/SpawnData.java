package dev.lyy.friendlycore.modules.spawn;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable record representing a network spawn point across proxy and game server instances.
 */
public record SpawnData(String server, String world, double x, double y, double z, float yaw, float pitch) {

    private static final Gson GSON = new Gson();

    /**
     * Converts this SpawnData into a local Bukkit Location.
     *
     * @return Bukkit Location if world exists locally, otherwise null.
     */
    public Location toLocation() {
        if (world == null || world.isEmpty()) return null;
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) return null;
        return new Location(bukkitWorld, x, y, z, yaw, pitch);
    }

    /**
     * Creates a SpawnData from a local Bukkit Location and server ID.
     */
    public static SpawnData fromLocation(String server, Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new SpawnData(
                server != null ? server : "",
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch()
        );
    }

    /**
     * Reads SpawnData from a YAML configuration section.
     */
    public static SpawnData fromConfig(ConfigurationSection section, String defaultServer) {
        if (section == null) return null;
        String server = section.getString("server", defaultServer != null ? defaultServer : "lobby");
        String world = section.getString("world", "spawn");
        double x = section.getDouble("x", 0.5);
        double y = section.getDouble("y", 64.0);
        double z = section.getDouble("z", 0.5);
        float yaw = (float) section.getDouble("yaw", 0.0);
        float pitch = (float) section.getDouble("pitch", 0.0);
        return new SpawnData(server, world, x, y, z, yaw, pitch);
    }

    /**
     * Writes this SpawnData to a YAML configuration section.
     */
    public void writeToConfig(ConfigurationSection section) {
        if (section == null) return;
        section.set("server", server);
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", (double) yaw);
        section.set("pitch", (double) pitch);
    }

    /**
     * Serializes this SpawnData to a JSON string.
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Deserializes a SpawnData from a JSON string.
     */
    public static SpawnData fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, SpawnData.class);
        } catch (Exception e) {
            return null;
        }
    }
}
