package dev.lyy.friendlycore.common.hooks;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Reflection-based hook for MC-Data-Bridge integration.
 * Detects cross-server player synchronization locks to prevent modifying inventory or economy data in-flight.
 */
public class McDataBridgeHook {

    private final FriendlyCore plugin;
    private boolean enabled = false;
    private boolean friendlySyncEnabled = false;
    private Plugin friendlySyncPlugin = null;
    private Object friendlySyncApi = null;
    private Method friendlySyncIsPlayerReadyMethod = null;

    private boolean dataBridgeEnabled = false;
    private Plugin dataBridgePlugin = null;
    private Object dataBridgeApi = null;
    private Method isLockedMethod = null;

    public McDataBridgeHook(FriendlyCore plugin) {
        this.plugin = plugin;
        checkHook();
    }

    public void checkHook() {
        if (Bukkit.getServer() == null) return;

        this.friendlySyncPlugin = null;
        this.friendlySyncEnabled = false;
        this.friendlySyncApi = null;
        this.friendlySyncIsPlayerReadyMethod = null;
        this.dataBridgePlugin = null;
        this.dataBridgeEnabled = false;
        this.dataBridgeApi = null;
        this.isLockedMethod = null;

        Plugin fsPlugin = Bukkit.getPluginManager().getPlugin("FriendlySync");
        if (fsPlugin != null && fsPlugin.isEnabled()) {
            this.friendlySyncPlugin = fsPlugin;
            this.friendlySyncEnabled = true;
            try {
                Class<?> fsClass = Class.forName("dev.lyy.friendlysync.api.FriendlySync");
                Method getMethod = fsClass.getMethod("get");
                this.friendlySyncApi = getMethod.invoke(null);
                if (this.friendlySyncApi != null) {
                    this.friendlySyncIsPlayerReadyMethod = this.friendlySyncApi.getClass().getMethod("isPlayerReady", UUID.class);
                }
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().info("Successfully hooked into FriendlySync for network data synchronization safety.");
                }
            } catch (Throwable t) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().log(Level.FINE, "FriendlySync present, will resolve API lazily.", t);
                }
            }
        }

        String[] pluginNames = {"MC-Data-Bridge", "MCDataBridge", "DataBridge", "mc-data-bridge"};
        for (String name : pluginNames) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null && p.isEnabled()) {
                this.dataBridgePlugin = p;
                this.dataBridgeEnabled = true;
                break;
            }
        }

        if (dataBridgeEnabled && dataBridgePlugin != null) {
            try {
                // Inspect plugin instance or static accessor for the lock manager API
                Class<?> bridgeClass = dataBridgePlugin.getClass();
                try {
                    Method getApiMethod = bridgeClass.getMethod("getAPI");
                    this.dataBridgeApi = getApiMethod.invoke(null);
                } catch (NoSuchMethodException ignored) {
                    try {
                        Method getInstanceMethod = bridgeClass.getMethod("getInstance");
                        this.dataBridgeApi = getInstanceMethod.invoke(null);
                    } catch (NoSuchMethodException ignored2) {
                        this.dataBridgeApi = dataBridgePlugin;
                    }
                }

                if (dataBridgeApi != null) {
                    Class<?> apiClass = dataBridgeApi.getClass();
                    for (Method m : apiClass.getMethods()) {
                        if (m.getName().equalsIgnoreCase("isLocked") || m.getName().equalsIgnoreCase("isPlayerLocked") || m.getName().equalsIgnoreCase("isSyncing")) {
                            if (m.getParameterCount() == 1) {
                                this.isLockedMethod = m;
                                break;
                            }
                        }
                    }
                }
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().info("Successfully hooked into MC-Data-Bridge for network data synchronization safety.");
                }
            } catch (Throwable t) {
                if (plugin != null && plugin.getLogger() != null) {
                    plugin.getLogger().log(Level.FINE, "MC-Data-Bridge present, using metadata and event fallback detection.", t);
                }
            }
        }

        this.enabled = this.friendlySyncEnabled || this.dataBridgeEnabled;
    }

    public boolean isEnabled() {
        if (!enabled && Bukkit.getServer() != null) {
            checkHook();
        }
        return enabled;
    }

    private boolean isFriendlySyncLocked(UUID uuid) {
        if (uuid == null) return false;
        if (friendlySyncPlugin != null) {
            try {
                if (!friendlySyncPlugin.isEnabled()) {
                    this.friendlySyncPlugin = null;
                    this.friendlySyncEnabled = false;
                    this.friendlySyncApi = null;
                    this.friendlySyncIsPlayerReadyMethod = null;
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!friendlySyncEnabled && Bukkit.getServer() != null) {
            try {
                Plugin fsPlugin = Bukkit.getPluginManager().getPlugin("FriendlySync");
                if (fsPlugin != null && fsPlugin.isEnabled()) {
                    this.friendlySyncPlugin = fsPlugin;
                    this.friendlySyncEnabled = true;
                    this.enabled = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!friendlySyncEnabled) return false;

        try {
            if (friendlySyncApi == null || friendlySyncIsPlayerReadyMethod == null) {
                Class<?> fsClass = Class.forName("dev.lyy.friendlysync.api.FriendlySync");
                Method getMethod = fsClass.getMethod("get");
                this.friendlySyncApi = getMethod.invoke(null);
                if (this.friendlySyncApi != null) {
                    this.friendlySyncIsPlayerReadyMethod = this.friendlySyncApi.getClass().getMethod("isPlayerReady", UUID.class);
                    this.friendlySyncIsPlayerReadyMethod.setAccessible(true);
                }
            }
            if (friendlySyncApi != null && friendlySyncIsPlayerReadyMethod != null) {
                Boolean ready = (Boolean) friendlySyncIsPlayerReadyMethod.invoke(friendlySyncApi, uuid);
                if (ready != null) {
                    return !ready;
                }
            }
        } catch (Throwable t) {
            this.friendlySyncApi = null;
            this.friendlySyncIsPlayerReadyMethod = null;
            // If FriendlySync.get() fails or is not initialized, attempt JoinLockService check via plugin instance
            try {
                if (friendlySyncPlugin != null && friendlySyncPlugin.isEnabled()) {
                    Method getLockMethod = friendlySyncPlugin.getClass().getMethod("getJoinLockService");
                    getLockMethod.setAccessible(true);
                    Object lockService = getLockMethod.invoke(friendlySyncPlugin);
                    if (lockService != null) {
                        Method isLockedMethod = lockService.getClass().getMethod("isLocked", UUID.class);
                        isLockedMethod.setAccessible(true);
                        Boolean locked = (Boolean) isLockedMethod.invoke(lockService, uuid);
                        if (locked != null) {
                            return locked;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // If FriendlySync is active but throws, fail-safe by locking to protect player data
            return true;
        }
        return false;
    }

    /**
     * Checks if a player's data is currently locked or undergoing cross-server transfer by UUID.
     */
    public boolean isLocked(UUID uuid) {
        if (uuid == null) return false;
        if (isFriendlySyncLocked(uuid)) {
            return true;
        }
        if (Bukkit.getServer() != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                return isPlayerLocked(player);
            }
        }
        return false;
    }

    /**
     * Checks if a player's data is currently locked or undergoing cross-server transfer.
     */
    public boolean isPlayerLocked(Player player) {
        if (player == null) return false;

        if (isFriendlySyncLocked(player.getUniqueId())) {
            return true;
        }

        if (!enabled) return false;

        if (dataBridgeApi != null && isLockedMethod != null) {
            try {
                Class<?> paramType = isLockedMethod.getParameterTypes()[0];
                Object arg = (paramType.equals(UUID.class)) ? player.getUniqueId() : player;
                Object result = isLockedMethod.invoke(dataBridgeApi, arg);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }

        // Fallback to metadata flags set on joining players during network handshakes
        if (player.hasMetadata("databridge:locked") ||
                player.hasMetadata("mcdatabridge:locked") ||
                player.hasMetadata("databridge:syncing") ||
                player.hasMetadata("mcdatabridge:syncing")) {
            return true;
        }

        return false;
    }

    /**
     * Clears the synced inventory after a combat-logger NPC is slain, so a later
     * join cannot restore items that already dropped on the ground.
     */
    public void recordOfflineDeathDrop(UUID uuid) {
        if (uuid == null) return;
        checkHook();
        if (!enabled || dataBridgePlugin == null) return;

        try {
            Method method = dataBridgePlugin.getClass().getMethod("recordOfflineDeathDrop", UUID.class);
            method.invoke(dataBridgePlugin, uuid);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "MC-Data-Bridge does not support combat-log death persistence.", t);
        }
    }
}
