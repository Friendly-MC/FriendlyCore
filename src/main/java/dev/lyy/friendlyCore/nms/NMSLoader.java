package dev.lyy.friendlycore.nms;

import dev.lyy.friendlycore.nms.common.NMSBridge;
import dev.lyy.friendlycore.nms.common.fallback.BukkitFallbackNMSBridge;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dynamic version detector and fail-safe NMS bridge loader.
 * Exclusively handles Minecraft 1.21.11 (Java 21) and Minecraft 26.2 (Java 25) with automatic fallback.
 */
public final class NMSLoader {

    private static final String BRIDGE_1_21_R4 = "dev.lyy.friendlycore.nms.v1_21_R4.NMSBridge_v1_21_R4";
    private static final String BRIDGE_26_2 = "dev.lyy.friendlycore.nms.v26_2.NMSBridge_v26_2";

    private static NMSBridge activeBridge;

    private NMSLoader() {}

    public static synchronized NMSBridge load(Plugin plugin) {
        if (activeBridge != null) {
            return activeBridge;
        }

        Logger logger = plugin.getLogger();
        String mcVersion = Bukkit.getMinecraftVersion();
        String serverVersion = Bukkit.getVersion();

        logger.info("[FriendlyCore-NMS] Detecting server environment: MC " + mcVersion + " (" + serverVersion + ")");

        if (mcVersion.startsWith("26.") || mcVersion.contains("26.2")) {
            try {
                Class<?> clazz = Class.forName(BRIDGE_26_2);
                activeBridge = (NMSBridge) clazz.getConstructor().newInstance();
                logger.info("[FriendlyCore-NMS] Successfully loaded native NMS Bridge for Minecraft 26.2 (Java 25 / Native Mojang).");
                return activeBridge;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[FriendlyCore-NMS] Failed to initialize 26.2 NMS Bridge. Falling back to Bukkit.", t);
            }
        }

        if (mcVersion.startsWith("1.21")) {
            try {
                Class<?> clazz = Class.forName(BRIDGE_1_21_R4);
                activeBridge = (NMSBridge) clazz.getConstructor().newInstance();
                logger.info("[FriendlyCore-NMS] Successfully loaded NMS Bridge for Minecraft 1.21.11 / 1.21.x (v1_21_R4 / Java 21).");
                return activeBridge;
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[FriendlyCore-NMS] Failed to initialize 1.21.x NMS Bridge. Falling back to Bukkit.", t);
            }
        }

        logger.warning("[FriendlyCore-NMS] Version '" + mcVersion + "' running in generic compatibility mode. Initializing Bukkit fallback bridge.");
        activeBridge = new BukkitFallbackNMSBridge(plugin);
        return activeBridge;
    }

    public static NMSBridge get() {
        if (activeBridge == null) {
            throw new IllegalStateException("NMSBridge has not been initialized yet! Ensure NMSLoader.load() was called during plugin startup.");
        }
        return activeBridge;
    }
}
