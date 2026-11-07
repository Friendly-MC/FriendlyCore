package dev.lyy.friendlycore.modules.settings;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.settings.events.PlayerSettingChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * High-performance, thread-safe Settings Manager with async DB persistence,
 * in-memory cache, and Redis cross-server synchronization.
 */
public class SettingsManager {

    private final FriendlyCore plugin;
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private SettingsUiRouter uiRouter;

    private void trackInFlight(CompletableFuture<?> future) {
        if (future != null) {
            inFlightSaves.add(future);
            future.whenComplete((res, ex) -> inFlightSaves.remove(future));
        }
    }

    public SettingsManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.uiRouter = new SettingsUiRouter(plugin, this);

        // Schedule auto-save every 5 minutes (6000 ticks)
        plugin.getScheduler().runAsyncTimer(this::saveDirtyBatch, 6000L, 6000L);
    }

    public SettingsUiRouter getUiRouter() {
        return uiRouter;
    }

    /**
     * Load settings for a joining player asynchronously into cache.
     */
    public CompletableFuture<PlayerSettings> loadPlayer(UUID uuid, String username) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        Object loadToken = new Object();
        loadTokens.put(uuid, loadToken);
        return DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadSettings(uuid, username),
                () -> loadTokens.get(uuid) == loadToken).thenApply(settings -> {
            if (loadTokens.get(uuid) != loadToken) return null;
            cache.put(uuid, settings);
            return settings;
        });
    }

    /**
     * Unload settings when a player disconnects, saving any dirty changes.
     */
    public void unloadPlayer(UUID uuid) {
        if (uuid == null) return;
        loadTokens.remove(uuid);
        PlayerSettings settings = cache.remove(uuid);
        if (settings != null && settings.isDirty()) {
            trackInFlight(plugin.getDatabaseManager().saveSettings(settings));
        }
    }

    /**
     * Retrieve a setting value for a player.
     */
    public boolean getSetting(UUID uuid, SettingType type) {
        if (uuid == null || type == null) return false;
        PlayerSettings settings = cache.get(uuid);
        if (settings != null) {
            return settings.get(type);
        }
        return type.getDefaultValue();
    }

    /**
     * Update a setting value for a player, save to DB, and broadcast cross-server.
     */
    public boolean setSetting(UUID uuid, SettingType type, boolean newValue) {
        return setSetting(uuid, type, newValue, false);
    }

    public boolean setSetting(UUID uuid, SettingType type, boolean newValue, boolean fromRemoteSync) {
        if (uuid == null || type == null) return false;

        PlayerSettings settings = cache.get(uuid);
        if (settings == null) return false; // Never persist defaults before the database profile loads.

        boolean oldValue = settings.get(type);
        if (oldValue == newValue && !fromRemoteSync) {
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && !fromRemoteSync) {
            PlayerSettingChangeEvent event = new PlayerSettingChangeEvent(player, type, oldValue, newValue, false);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
            newValue = event.getNewValue();
        }

        settings.set(type, newValue);

        // Async save to database
        trackInFlight(plugin.getDatabaseManager().saveSettings(settings));

        if (!fromRemoteSync && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            String payload = type.getKey() + ":" + newValue;
            CrossServerPacket packet = new CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    CrossServerPacket.Action.SETTINGS_SYNC,
                    uuid,
                    settings.getUsername(),
                    0,
                    payload
            );
            plugin.getRedisService().publish(packet);
        }

        return true;
    }

    /**
     * Toggle a setting for a player.
     */
    public boolean toggleSetting(UUID uuid, SettingType type) {
        boolean nextVal = !getSetting(uuid, type);
        setSetting(uuid, type, nextVal);
        return nextVal;
    }

    /**
     * Handle incoming remote cross-server setting change.
     */
    public void handleRemoteSync(UUID uuid, String username, String payload) {
        if (uuid == null || payload == null || !payload.contains(":")) return;
        String[] parts = payload.split(":", 2);
        SettingType type = SettingType.fromKey(parts[0]);
        if (type == null) return;
        boolean value = Boolean.parseBoolean(parts[1]);

        PlayerSettings settings = cache.get(uuid);
        if (settings != null) {
            settings.set(type, value);
            settings.markSaved(settings.getVersion());
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            plugin.getScheduler().runEntity(player, () -> {
                if (player.isOnline()) {
                    PlayerSettingChangeEvent event = new PlayerSettingChangeEvent(player, type, !value, value, true);
                    Bukkit.getPluginManager().callEvent(event);
                }
            });
        }
    }

    /**
     * Batch save all dirty player settings to database.
     */
    public void saveDirtyBatch() {
        List<PlayerSettings> dirty = new ArrayList<>();
        for (PlayerSettings s : cache.values()) {
            if (s.isDirty()) {
                dirty.add(s);
            }
        }
        if (!dirty.isEmpty()) {
            trackInFlight(plugin.getDatabaseManager().saveSettingsBatch(dirty));
        }
    }

    public void saveAll(boolean async) {
        Collection<PlayerSettings> all = cache.values();
        if (all.isEmpty()) return;
        if (async) {
            trackInFlight(plugin.getDatabaseManager().saveSettingsBatch(new ArrayList<>(all)));
        } else {
            try {
                CompletableFuture<Void> future = plugin.getDatabaseManager().saveSettingsBatch(new ArrayList<>(all));
                trackInFlight(future);
                future.get(10, TimeUnit.SECONDS);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Failed to synchronously save player settings", t);
            }
        }
    }

    public void shutdown() {
        saveAll(false);
        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for settings saves during shutdown", e);
            }
        }
        loadTokens.clear();
        cache.clear();
    }
}
