package dev.lyy.friendlycore.modules.essentials.vanish;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * High-performance, production-grade Vanish subsystem manager for FriendlyCore.
 * Compatible with Paper 1.21.4 and Folia multithreading.
 */
public class VanishManager {
    public static final long TRANSFER_TIMEOUT_SECONDS = 30L;
    private static final String TRANSFER_PREFIX = "friendlycore:vanish_transfer:";
    private static final String CONSUME_TRANSFER = """
            local value = redis.call('GET', KEYS[1])
            redis.call('DEL', KEYS[1])
            return value
            """;
    private static final String CANCEL_TRANSFER = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pickupEnabledPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> nightVisionPlayers = ConcurrentHashMap.newKeySet();
    private static final long TIER_TTL_MS = 30_000L;
    private final Map<UUID, CachedTier> cachedTiers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> vanishTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> transferArrivals = new ConcurrentHashMap<>();

    private File storageFile;
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);
    private boolean stopping;
    private Object actionbarTask;

    public VanishManager(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    public void initialize() {
        this.storageFile = new File(plugin.getDataFolder(), "modules/essentials/vanished_players.yml");
        loadPersistedState();
        startActionbarReminder();
        plugin.getLogger().info("VanishManager initialized with modern Paper 1.21.4 & Folia support.");
    }

    public synchronized void reload() {
        if (stopping) return;
        // Memory owns the current state; replaying an older file can resurrect vanish.
        if (actionbarTask != null) plugin.getScheduler().cancel(actionbarTask);
        actionbarTask = null;
        startActionbarReminder();
        savePersistedState();
    }

    public void shutdown() {
        if (actionbarTask != null) {
            plugin.getScheduler().cancel(actionbarTask);
            actionbarTask = null;
        }
        CompletableFuture<Void> finalSave;
        synchronized (this) {
            stopping = true;
            // Every transition already queued its snapshot while holding this monitor.
            // Wait for the latest one instead of enqueueing a duplicate shutdown write.
            finalSave = pendingSave;
        }
        try {
            finalSave.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while flushing vanish states", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            plugin.getLogger().log(Level.WARNING, "Vanish persistence did not finish within shutdown budget", e);
        }
        for (UUID uuid : vanishedPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                restorePlayerState(player);
            }
        }
        vanishedPlayers.clear();
        pickupEnabledPlayers.clear();
        nightVisionPlayers.clear();
        cachedTiers.clear();
        vanishTimestamps.clear();
        transferArrivals.clear();
    }

    /** Complete the handoff write before either proxy or FriendlySync can connect. */
    public CompletableFuture<String> prepareTransfer(UUID uuid, String destination) {
        if (!isVanished(uuid)) return CompletableFuture.completedFuture(null);
        String token = UUID.randomUUID().toString();
        try {
            return CompletableFuture.supplyAsync(() -> {
                var redis = plugin.getRedisService();
                if (redis == null || !redis.isEnabled() || redis.getRedisClient() == null) {
                    throw new IllegalStateException("Redis is required to transfer a vanished player safely");
                }
                redis.getRedisClient().setex(transferKey(destination, uuid), TRANSFER_TIMEOUT_SECONDS, token);
                return token;
            }, plugin.getScheduler().getAsyncExecutor());
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public void cancelTransfer(UUID uuid, String destination, String token) {
        if (token == null) return;
        try {
            CompletableFuture.runAsync(() -> plugin.getRedisService().getRedisClient().eval(
                    CANCEL_TRANSFER, List.of(transferKey(destination, uuid)), List.of(token)),
                    plugin.getScheduler().getAsyncExecutor()).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "Could not cancel vanish transfer for " + uuid, error);
                return null;
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            plugin.getLogger().log(Level.WARNING, "Could not schedule vanish transfer cancellation", e);
        }
    }

    /** Runs on AsyncPlayerPreLoginEvent, never on the player/region thread. */
    public void loadTransferArrival(UUID uuid) {
        transferArrivals.remove(uuid);
        var redis = plugin.getRedisService();
        if (redis == null || !redis.isEnabled()) return;
        if (redis.getRedisClient() == null) throw new IllegalStateException("Redis is unavailable");
        Object token = redis.getRedisClient().eval(CONSUME_TRANSFER,
                List.of(transferKey(redis.getServerId(), uuid)), List.of());
        if (token != null) {
            long expires = System.currentTimeMillis() + TRANSFER_TIMEOUT_SECONDS * 1000L;
            transferArrivals.put(uuid, expires);
            plugin.getScheduler().runAsyncLater(() -> transferArrivals.remove(uuid, expires),
                    TRANSFER_TIMEOUT_SECONDS * 20L);
        }
    }

    public boolean consumeTransferArrival(UUID uuid) {
        Long expires = transferArrivals.remove(uuid);
        return expires != null && expires > System.currentTimeMillis();
    }

    private static String transferKey(String destination, UUID uuid) {
        return TRANSFER_PREFIX + destination + ":" + uuid;
    }

    /**
     * Checks if a player is currently in vanish mode.
     */
    public boolean isVanished(UUID uuid) {
        return uuid != null && vanishedPlayers.contains(uuid);
    }

    public boolean isVanished(Player player) {
        return player != null && isVanished(player.getUniqueId());
    }

    /**
     * Evaluates if a viewer can see a specific vanished target based on permissions and vanish tiers.
     */
    public boolean canSee(Player viewer, Player target) {
        if (target == null) return false;
        if (!isVanished(target.getUniqueId())) return true;
        if (viewer == null) return false;
        if (viewer.getUniqueId().equals(target.getUniqueId())) return true;

        if (!viewer.hasPermission("friendlycore.vanish.see")) {
            return false;
        }

        int viewerTier = getVanishTier(viewer);
        int targetTier = getVanishTier(target);
        return viewerTier >= targetTier;
    }

    /**
     * Resolves the highest vanish tier for a player from permission nodes (friendlycore.vanish.tier.<1..100>).
     * Scanning effective permissions is expensive with large permission sets and runs once per online
     * player on every vanish toggle, so results are cached briefly.
     */
    public int getVanishTier(Player player) {
        if (player == null) return 1;
        if (player.isOp()) return 100;

        long now = System.currentTimeMillis();
        CachedTier cached = cachedTiers.get(player.getUniqueId());
        if (cached != null && cached.expiresAt() > now) return cached.tier();

        int maxTier = 1;
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase(Locale.ROOT);
            if (perm.startsWith("friendlycore.vanish.tier.") && pai.getValue()) {
                try {
                    maxTier = Math.max(maxTier, Integer.parseInt(perm.substring("friendlycore.vanish.tier.".length())));
                } catch (NumberFormatException ignored) {}
            }
        }
        // ponytail: TTL instead of hooking LuckPerms recalculation; a tier change can take TIER_TTL_MS to apply.
        cachedTiers.put(player.getUniqueId(), new CachedTier(maxTier, now + TIER_TTL_MS));
        return maxTier;
    }

    private record CachedTier(int tier, long expiresAt) {}

    public void invalidateTier(UUID uuid) {
        if (uuid != null) cachedTiers.remove(uuid);
    }

    /**
     * Toggles vanish mode for a player.
     * @return true if player is now vanished, false if unvanished.
     */
    public boolean toggleVanish(Player player) {
        if (player == null) return false;
        boolean newState = !isVanished(player.getUniqueId());
        setVanished(player, newState, false);
        return newState;
    }

    /**
     * Sets vanish mode for a player.
     */
    public void setVanished(Player player, boolean vanish, boolean silent) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();

        boolean changed;
        synchronized (this) {
            if (stopping) return;
            changed = vanish ? vanishedPlayers.add(uuid) : vanishedPlayers.remove(uuid);
            if (changed) savePersistedState();
        }

        if (vanish) {
            vanishTimestamps.put(uuid, System.currentTimeMillis());
            applyVanishedState(player);
        } else {
            vanishTimestamps.remove(uuid);
            pickupEnabledPlayers.remove(uuid);
            cachedTiers.remove(uuid);
            restorePlayerState(player);
        }

        // Every join passes through here as "not vanished"; showing a player nobody hid is a no-op
        // costing one call per online player, which turns a login wave into O(N^2).
        if (changed || vanish) {
            updateAllVisibilities(player);
        }

        if (!silent) {
            sendVanishMessage(player, vanish);
            notifyStaff(player, vanish);
        }
    }

    private void applyVanishedState(Player player) {
        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) return;

            // 1. Tablist: hidePlayer in updateAllVisibilities already removes the entry for viewers who can't see.

            // 2. Metadata for 3rd party compatibility (Dynmap, BlueMap, DiscordSRV)
            player.setMetadata("vanished", new FixedMetadataValue(plugin, true));

            player.setSleepingIgnored(true);
            player.setCollidable(false);

            if (plugin.getConfigService().get("essentials.yml").getBoolean("vanish.grant-night-vision", true)) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
                nightVisionPlayers.add(player.getUniqueId());
            }
        });
    }

    private void restorePlayerState(Player player) {
        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) return;

            player.removeMetadata("vanished", plugin);

            if (!player.hasPermission("friendlycore.sleepingignored")) {
                player.setSleepingIgnored(false);
            }
            player.setCollidable(true);

            // 3. Remove night vision if given by vanish
            if (nightVisionPlayers.remove(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION);
            }
        });
    }

    /**
     * Updates visibility of a target player for all online players.
     */
    public void updateAllVisibilities(Player target) {
        if (target == null) return;
        boolean targetVanished = isVanished(target.getUniqueId());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            updateSingleVisibility(viewer, target, targetVanished);
        }
    }

    /**
     * Updates visibility of all online vanished players for a joining/updating viewer.
     */
    public void updateAllForViewer(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;

        for (UUID uuid : vanishedPlayers) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && target.isOnline() && !target.equals(viewer)) {
                updateSingleVisibility(viewer, target, true);
            }
        }
    }

    private void updateSingleVisibility(Player viewer, Player target, boolean targetVanished) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) return;

        plugin.getScheduler().runEntity(viewer, () -> {
            if (!viewer.isOnline()) return;
            if (targetVanished) {
                if (canSee(viewer, target)) {
                    viewer.showPlayer(plugin, target);
                } else {
                    viewer.hidePlayer(plugin, target);
                }
            } else {
                viewer.showPlayer(plugin, target);
            }
        });
    }

    public boolean toggleItemPickup(Player player) {
        if (player == null) return false;
        UUID uuid = player.getUniqueId();
        if (pickupEnabledPlayers.contains(uuid)) {
            pickupEnabledPlayers.remove(uuid);
            return false;
        } else {
            pickupEnabledPlayers.add(uuid);
            return true;
        }
    }

    public boolean canPickupItems(Player player) {
        if (player == null) return true;
        if (!isVanished(player.getUniqueId())) return true;
        return pickupEnabledPlayers.contains(player.getUniqueId()) || player.hasPermission("friendlycore.vanish.pickup");
    }

    public long getVanishDuration(UUID uuid) {
        Long start = vanishTimestamps.get(uuid);
        return start != null ? System.currentTimeMillis() - start : 0L;
    }

    public Set<UUID> getVanishedPlayers() {
        return Collections.unmodifiableSet(vanishedPlayers);
    }

    /**
     * Vanished players online on this server. The set itself also holds players who quit while
     * vanished (kept so they are re-shown on their next join) and must not be counted.
     */
    public int getVanishedCount() {
        int count = 0;
        for (UUID uuid : vanishedPlayers) {
            if (Bukkit.getPlayer(uuid) != null) count++;
        }
        return count;
    }

    public void broadcastFakeJoin(Player player) {
        if (player == null) return;
        String format = plugin.getConfigService().getMessages().getString("essentials.vanish.fake-join-format",
                "<dark_gray>[<color:#7BD88F>+</color>]</dark_gray> <color:#D8D0C3>%player%</color>");
        Map<String, String> placeholders = Map.of("player", player.getName(), "displayname", player.getName());
        Component msg = plugin.getTextService().parseForPlayer(player, format, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
    }

    public void broadcastFakeQuit(Player player) {
        if (player == null) return;
        String format = plugin.getConfigService().getMessages().getString("essentials.vanish.fake-quit-format",
                "<dark_gray>[<color:#FF4D5A>-</color>]</dark_gray> <color:#D8D0C3>%player%</color>");
        Map<String, String> placeholders = Map.of("player", player.getName(), "displayname", player.getName());
        Component msg = plugin.getTextService().parseForPlayer(player, format, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(msg);
        }
    }

    private void sendVanishMessage(Player player, boolean vanished) {
        String key = vanished ? "vanish.enabled-self" : "vanish.disabled-self";
        String def = vanished
                ? "<gradient:#6EDBFF:#A7F3FF><bold>👻 ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴠᴀɴɪѕʜ ᴍᴏᴅᴇ <color:#7BD88F>ᴇɴᴀʙʟᴇᴅ</color>. ʏᴏᴜ ᴀʀᴇ ɴᴏᴡ ɪɴᴠɪѕɪʙʟᴇ."
                : "<gradient:#6EDBFF:#A7F3FF><bold>👻 ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴠᴀɴɪѕʜ ᴍᴏᴅᴇ <color:#FF4D5A>ᴅɪѕᴀʙʟᴇᴅ</color>. ʏᴏᴜ ᴀʀᴇ ɴᴏᴡ ᴠɪѕɪʙʟᴇ.";
        String message = plugin.getConfigService().getMessages().getString("essentials." + key, def);
        plugin.getTextService().sendMessage(player, message, Map.of("player", player.getName()));
    }

    private void notifyStaff(Player actor, boolean vanished) {
        boolean staffAlerts = plugin.getConfigService().get("essentials.yml").getBoolean("vanish.staff-alerts", true);
        if (!staffAlerts) return;

        String key = vanished ? "vanish.staff-notify-on" : "vanish.staff-notify-off";
        String def = vanished
                ? "<dark_gray>[<gradient:#6EDBFF:#A7F3FF>ᴠᴀɴɪѕʜ</gradient>]</dark_gray> <color:#E7B85C>%player%</color> <color:#7BD88F>ᴠᴀɴɪѕʜᴇᴅ</color>."
                : "<dark_gray>[<gradient:#6EDBFF:#A7F3FF>ᴠᴀɴɪѕʜ</gradient>]</dark_gray> <color:#E7B85C>%player%</color> <color:#FF4D5A>ᴜɴᴠᴀɴɪѕʜᴇᴅ</color>.";
        String template = plugin.getConfigService().getMessages().getString("essentials." + key, def);
        Map<String, String> placeholders = Map.of("player", actor.getName());

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.equals(actor) && staff.hasPermission("friendlycore.vanish.see")) {
                plugin.getTextService().sendMessage(staff, template, placeholders);
            }
        }
    }

    private void startActionbarReminder() {
        boolean actionbarEnabled = plugin.getConfigService().get("essentials.yml").getBoolean("vanish.actionbar-reminder", true);
        if (!actionbarEnabled) return;

        String text = plugin.getConfigService().getMessages().getString("essentials.vanish.actionbar",
                "<gradient:#6EDBFF:#A7F3FF>👻 ʏᴏᴜ ᴀʀᴇ ᴄᴜʀʀᴇɴᴛʟʏ ᴠᴀɴɪѕʜᴇᴅ</gradient>");

        actionbarTask = plugin.getScheduler().runGlobalTimer(() -> {
            if (vanishedPlayers.isEmpty()) return;
            for (UUID uuid : vanishedPlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    plugin.getTextService().sendActionBar(player, text, Map.of("player", player.getName()));
                }
            }
        }, 40L, 40L);
    }

    private synchronized void loadPersistedState() {
        boolean persist = plugin.getConfigService().get("essentials.yml").getBoolean("vanish.save-state-on-quit", true);
        if (!persist || storageFile == null || !storageFile.exists()) return;

        try {
            YamlConfiguration storageConfig = YamlConfiguration.loadConfiguration(storageFile);
            List<String> list = storageConfig.getStringList("vanished-uuids");
            for (String s : list) {
                try {
                    vanishedPlayers.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load persisted vanish states", e);
        }
    }

    // Called under this manager's monitor so snapshots and writes keep transition order.
    private void savePersistedState() {
        boolean persist = plugin.getConfigService().get("essentials.yml").getBoolean("vanish.save-state-on-quit", true);
        if (!persist || storageFile == null) return;

        List<String> list = vanishedPlayers.stream().map(UUID::toString).toList();
        Runnable save = () -> {
            YamlConfiguration storageConfig = new YamlConfiguration();
            storageConfig.set("vanished-uuids", list);
            try {
                storageConfig.save(storageFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save persisted vanish states", e);
            }
        };
        pendingSave = pendingSave.thenCompose(ignored ->
                CompletableFuture.runAsync(save, plugin.getScheduler().getAsyncExecutor()))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Failed to schedule persisted vanish states", error);
                    return null;
                });
    }
}
