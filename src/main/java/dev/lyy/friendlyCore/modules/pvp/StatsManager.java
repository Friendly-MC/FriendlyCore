package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Caches player PvP combat stats in memory and periodically flushes modified records to the database.
 */
public class StatsManager implements Listener {

    private final FriendlyCore plugin;
    private final ConcurrentHashMap<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private Object autoSaveTask;

    private void trackInFlight(CompletableFuture<?> future) {
        if (future != null) {
            inFlightSaves.add(future);
            future.whenComplete((res, ex) -> inFlightSaves.remove(future));
        }
    }

    public StatsManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        int saveInterval = plugin.getConfigService().getMainConfig().getInt("optimization.save-interval-seconds", 60);
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(
                () -> saveAllDirty().thenRun(this::cleanupOfflineStats),
                saveInterval * 20L,
                saveInterval * 20L
        );

        for (Player online : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(online.getUniqueId());
            loadPlayerStats(online.getUniqueId(), online.getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        onlinePlayers.add(p.getUniqueId());
        loadPlayerStats(p.getUniqueId(), p.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        onlinePlayers.remove(uuid);
        PlayerStats stats = statsCache.get(uuid);
        // Pending counters need the stored totals before they can be saved, even after quit.
        if (stats != null && stats.isAwaitingLoad()) return;
        loadTokens.remove(uuid);
        if (stats != null) {
            if (stats.isDirty()) {
                CompletableFuture<Void> future = plugin.getDatabaseManager().saveStatsBatch(List.of(stats));
                trackInFlight(future);
                future.thenRun(() -> {
                    if (!onlinePlayers.contains(uuid)) {
                        statsCache.remove(uuid);
                    }
                });
            } else {
                statsCache.remove(uuid);
            }
        }
    }

    public CompletableFuture<PlayerStats> loadPlayerStats(UUID uuid, String username) {
        Object loadToken = new Object();
        loadTokens.put(uuid, loadToken);
        return dev.lyy.friendlycore.common.database.DatabaseReadRetry.load(plugin,
            () -> plugin.getDatabaseManager().loadStats(uuid, username),
            () -> loadTokens.get(uuid) == loadToken).thenApply(stats -> {
            if (stats == null || loadTokens.get(uuid) != loadToken) return null;
            return statsCache.compute(uuid, (key, existing) -> {
                if (existing == null) return stats;
                if (existing.isAwaitingLoad()) existing.restore(stats);
                return existing.isDirty() ? existing : stats;
            });
        });
    }

    public PlayerStats getStats(UUID uuid, String username) {
        if (uuid == null) return new PlayerStats(UUID.randomUUID(), "Unknown", 0, 0, 0, 0);
        return statsCache.computeIfAbsent(uuid, k -> {
            PlayerStats fallback = PlayerStats.pending(uuid, username);
            Object token = loadTokens.computeIfAbsent(uuid, ignored -> new Object());
            dev.lyy.friendlycore.common.database.DatabaseReadRetry.load(plugin,
                () -> plugin.getDatabaseManager().loadStats(uuid, username),
                () -> loadTokens.get(uuid) == token).thenAccept(loaded -> {
                if (loaded != null) fallback.restore(loaded);
            });
            return fallback;
        });
    }

    public CompletableFuture<Void> saveAllDirty() {
        List<PlayerStats> dirtyList = new ArrayList<>();
        for (PlayerStats stats : statsCache.values()) {
            if (stats.isDirty()) {
                dirtyList.add(stats);
            }
        }
        if (!dirtyList.isEmpty()) {
            CompletableFuture<Void> future = plugin.getDatabaseManager().saveStatsBatch(dirtyList);
            trackInFlight(future);
            return future;
        }
        return CompletableFuture.completedFuture(null);
    }

    private void cleanupOfflineStats() {
        statsCache.entrySet().removeIf(entry ->
                !onlinePlayers.contains(entry.getKey()) && !entry.getValue().isAwaitingLoad() && !entry.getValue().isDirty());
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
        }
        try {
            saveAllDirty().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save dirty stats during shutdown", e);
        }
        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for stats saves during shutdown", e);
            }
        }
        loadTokens.clear();
        onlinePlayers.clear();
        statsCache.clear();
    }
}
