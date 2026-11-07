package dev.lyy.friendlycore.common.hooks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection-based hook for Geyser and Floodgate to detect Bedrock edition clients.
 */
public class FloodgateHook {

    private boolean floodgateAvailable = false;
    private Object floodgateApiInstance = null;
    private Method isFloodgatePlayerMethod = null;

    private boolean geyserAvailable = false;
    private Object geyserApiInstance = null;
    private Method isBedrockPlayerMethod = null;

    public FloodgateHook() {
        checkHooks();
    }

    public void checkHooks() {
        if (Bukkit.getServer() == null) {
            this.floodgateAvailable = false;
            this.geyserAvailable = false;
            return;
        }

        this.floodgateAvailable = Bukkit.getPluginManager().isPluginEnabled("floodgate");
        if (floodgateAvailable) {
            try {
                Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstanceMethod = floodgateApiClass.getMethod("getInstance");
                this.floodgateApiInstance = getInstanceMethod.invoke(null);
                this.isFloodgatePlayerMethod = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Throwable ignored) {
                this.floodgateAvailable = false;
            }
        }

        this.geyserAvailable = Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
        if (geyserAvailable) {
            try {
                Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
                Method apiMethod = geyserApiClass.getMethod("api");
                this.geyserApiInstance = apiMethod.invoke(null);
                this.isBedrockPlayerMethod = geyserApiClass.getMethod("isBedrockPlayer", UUID.class);
            } catch (Throwable ignored) {
                this.geyserAvailable = false;
            }
        }
    }

    /**
     * Checks if a player UUID belongs to a Bedrock client via Floodgate or Geyser.
     */
    public boolean isBedrockPlayer(UUID uuid) {
        if (uuid == null) return false;

        if (floodgateAvailable && floodgateApiInstance != null && isFloodgatePlayerMethod != null) {
            try {
                Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, uuid);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        if (geyserAvailable && geyserApiInstance != null && isBedrockPlayerMethod != null) {
            try {
                Object result = isBedrockPlayerMethod.invoke(geyserApiInstance, uuid);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    /**
     * Checks if a player is connected via Bedrock edition, falling back to name prefix checks.
     */
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        if (isBedrockPlayer(player.getUniqueId())) {
            return true;
        }
        // Fallback to standard Bedrock prefix conventions (e.g. "." or "*")
        String name = player.getName();
        return name != null && (name.startsWith(".") || name.startsWith("*"));
    }

    public boolean isFloodgateAvailable() {
        return floodgateAvailable;
    }

    public boolean isGeyserAvailable() {
        return geyserAvailable;
    }
}
