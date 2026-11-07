package dev.lyy.friendlycore.modules.homes;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket;
import dev.lyy.friendlycore.modules.homes.gui.HomeGui;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import dev.lyy.friendlycore.modules.homes.model.SelectedHomeRename;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages player homes, in-memory caches, database synchronization,
 * warmup countdowns, combat tagging, and cross-server Redis teleports.
 */
public class HomesManager {

    private final FriendlyCore plugin;

    private final Map<UUID, PlayerHomes> profileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<PlayerHomes>> inFlightLoads = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, HomeTeleportCountdown> activeTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, SelectedHomeRename> activeRenames = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCrossTeleport> pendingCrossTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combatTagMap = new ConcurrentHashMap<>();

    private String serverId = "";
    private boolean enableConfirmationGui = true;
    private long teleportTime = 5;
    private double cancelOnMoveThreshold = 0.5;
    private long combatDelay = 5;
    private int heightLimit = 255;
    private boolean disableSubcommands = false;
    private boolean disableHomeRenaming = false;
    private boolean allowChatMessages = true;
    private boolean allowActionBarMessages = true;
    private List<String> blacklistedWorlds = new ArrayList<>();
    private boolean setHomeOnBedInteract = false;
    private double setHomeCost = 0.0;
    private double teleportCost = 0.0;

    private boolean removeHomeAfterLosePerm = true;
    private String cooldownBypassPerm = "friendlycore.homes.nocooldown";
    private String homeGuiPerm = "friendlycore.homes.use";
    private String homeAdminPerm = "friendlycore.homes.admin";
    private final List<LimitEntry> homeLimits = new ArrayList<>();

    private Object autoSaveTask;
    private Object cleanupTask;

    public record LimitEntry(String group, String permission, int limit) {}

    public HomesManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        reload();

        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        } catch (Exception ignored) {}

        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(this::flushDirtyProfiles, 1200L, 1200L);
        // Start 5-second periodic pending teleports cleanup task
        this.cleanupTask = plugin.getScheduler().runAsyncTimer(this::cleanupExpiredPendingTeleports, 100L, 100L);
    }

    public void reload() {
        FileConfiguration config = plugin.getConfigService().getHomesConfig();
        this.serverId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");
        this.enableConfirmationGui = config.getBoolean("enable-confirmation-gui", true);
        this.teleportTime = config.getLong("teleport-time", 5);
        this.cancelOnMoveThreshold = config.getDouble("cancel-on-move-threshold", 0.5);
        this.combatDelay = config.getLong("combat-delay", 5);
        this.heightLimit = config.getInt("height-limit", 255);
        this.disableSubcommands = config.getBoolean("disable-subcommands", false);
        this.disableHomeRenaming = config.getBoolean("disable-home-renaming", false);
        this.allowChatMessages = config.getBoolean("allow-chat-messages", true);
        this.allowActionBarMessages = config.getBoolean("allow-action-bar-messages", true);
        this.blacklistedWorlds = config.getStringList("blacklisted-worlds");
        this.setHomeOnBedInteract = config.getBoolean("set-home-on-bed-interact", false);
        this.setHomeCost = config.getDouble("sethome-cost", 0.0);
        this.teleportCost = config.getDouble("teleport-cost", 0.0);
        this.removeHomeAfterLosePerm = config.getBoolean("remove-home-after-lose-perm", true);
        this.cooldownBypassPerm = config.getString("cooldown-bypass-permission", "friendlycore.homes.nocooldown");
        this.homeGuiPerm = config.getString("home-gui-permission", "friendlycore.homes.use");
        this.homeAdminPerm = config.getString("home-admin-permission", "friendlycore.homes.admin");

        homeLimits.clear();
        ConfigurationSection limitsSec = config.getConfigurationSection("limits");
        if (limitsSec != null) {
            for (String group : limitsSec.getKeys(false)) {
                String perm = limitsSec.getString(group + ".permission", "friendlycore.homes.limit." + group);
                int limit = limitsSec.getInt(group + ".limit", 1);
                homeLimits.add(new LimitEntry(group, perm, limit));
            }
        }
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
            autoSaveTask = null;
        }
        if (cleanupTask != null) {
            plugin.getScheduler().cancel(cleanupTask);
            cleanupTask = null;
        }

        for (HomeTeleportCountdown countdown : activeTeleports.values()) {
            countdown.cancel();
        }
        activeTeleports.clear();
        activeRenames.clear();

        // Flush dirty profiles synchronously before shutdown
        flushDirtyProfiles();

        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(CompletableFuture[]::new))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for in-flight home saves on shutdown", e);
            }
        }

        loadTokens.clear();
        inFlightLoads.clear();
        profileCache.clear();
    }

    public void flushDirtyProfiles() {
        List<PlayerHomes> dirtyProfiles = new ArrayList<>();
        for (PlayerHomes profile : profileCache.values()) {
            if (profile.isDirty()) {
                dirtyProfiles.add(profile);
            }
        }
        if (!dirtyProfiles.isEmpty()) {
            try {
                CompletableFuture<Void> batch = plugin.getDatabaseManager().savePlayerHomesBatch(dirtyProfiles);
                if (batch != null) {
                    inFlightSaves.add(batch);
                    batch.whenComplete((r, e) -> inFlightSaves.remove(batch));
                    batch.get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save dirty homes on shutdown", e);
            }
        }
    }

    public CompletableFuture<PlayerHomes> loadPlayerHomes(UUID uuid, String username) {
        PlayerHomes cached = profileCache.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<PlayerHomes> load = inFlightLoads.computeIfAbsent(uuid, k -> {
            Object loadToken = new Object();
            loadTokens.put(uuid, loadToken);
            return DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadPlayerHomes(uuid, username),
                () -> loadTokens.get(uuid) == loadToken).thenApply(profile -> {
                if (profile != null && loadTokens.get(uuid) == loadToken) {
                    profileCache.put(uuid, profile);
                }
                return profile;
            });
        });
        return load.whenComplete((res, err) -> inFlightLoads.remove(uuid, load));
    }

    public boolean isLoading(UUID uuid) {
        return uuid != null && inFlightLoads.containsKey(uuid);
    }

    public PlayerHomes getPlayerHomes(UUID uuid) {
        return profileCache.get(uuid);
    }

    public void unloadPlayer(UUID uuid) {
        loadTokens.remove(uuid);
        inFlightLoads.remove(uuid);
        PlayerHomes profile = profileCache.remove(uuid);
        if (profile != null && profile.isDirty()) {
            CompletableFuture<Void> save = plugin.getDatabaseManager().savePlayerHomes(profile);
            if (save != null) {
                inFlightSaves.add(save);
                save.whenComplete((r, e) -> inFlightSaves.remove(save));
            }
        }
        activeTeleports.remove(uuid);
        activeRenames.remove(uuid);
        combatTagMap.remove(uuid);
    }

    public void savePlayerHomesAsync(PlayerHomes profile) {
        if (profile != null) {
            CompletableFuture<Void> save = plugin.getDatabaseManager().savePlayerHomes(profile);
            if (save != null) {
                inFlightSaves.add(save);
                save.whenComplete((r, e) -> inFlightSaves.remove(save));
            }
        }
    }

    public boolean hasHomeUsePermission(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        return player.hasPermission(homeGuiPerm)
                || player.hasPermission("home.use")
                || player.hasPermission("friendlycore.homes.use")
                || player.hasPermission("modernhome.use")
                || hasHomeAdminPermission(player);
    }

    public boolean hasHomeAdminPermission(org.bukkit.command.CommandSender sender) {
        return sender.hasPermission(homeAdminPerm)
                || sender.hasPermission("home.admin")
                || sender.hasPermission("friendlycore.homes.admin")
                || sender.hasPermission("modernhome.admin")
                || sender.hasPermission("friendlycore.admin")
                || sender.isOp();
    }

    public boolean hasCooldownBypassPermission(Player player) {
        return player.hasPermission(cooldownBypassPerm)
                || player.hasPermission("home.no-cooldown")
                || player.hasPermission("home.nocooldown")
                || player.hasPermission("friendlycore.homes.nocooldown")
                || player.hasPermission("modernhome.nocooldown")
                || hasHomeAdminPermission(player);
    }

    public int getMaxHomes(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return 1;
        }
        if (hasHomeAdminPermission(player)) {
            return 54;
        }

        int max = 1;
        // Check dynamic permission nodes (home.limit.X, modernhome.limit.X, friendlycore.homes.limit.X, etc.)
        for (PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase();
            if (pai.getValue()) {
                if (perm.startsWith("home.limit.") || perm.startsWith("modernhome.limit.") ||
                        perm.startsWith("friendlycore.homes.limit.") || perm.startsWith("friendlycore.home.limit.") ||
                        perm.startsWith("homes.limit.")) {
                    try {
                        String numStr = perm.substring(perm.lastIndexOf('.') + 1);
                        int val = Integer.parseInt(numStr);
                        if (val > max) max = val;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        for (LimitEntry entry : homeLimits) {
            String perm = entry.permission();
            if (player.hasPermission(perm)) {
                if (entry.limit() > max) {
                    max = entry.limit();
                }
            }
        }
        return max;
    }

    public void checkAndEnforceHomeLimits(Player player, PlayerHomes profile) {
        if (!removeHomeAfterLosePerm || profile == null || hasHomeAdminPermission(player)) {
            return;
        }
        int maxHomes = getMaxHomes(player.getUniqueId());
        List<Integer> slotsToRemove = new ArrayList<>();
        for (HomeData home : profile.getHomes().values()) {
            if (home.getSlot() > maxHomes) {
                slotsToRemove.add(home.getSlot());
            }
        }
        if (!slotsToRemove.isEmpty()) {
            for (int slot : slotsToRemove) {
                profile.removeHome(slot);
                plugin.getDatabaseManager().deleteHome(player.getUniqueId(), slot);
            }
            savePlayerHomesAsync(profile);
        }
    }

    public boolean canSetHomeAt(Player player, Location location) {
        if (location.getY() > heightLimit) {
            return false;
        }
        if (location.getWorld() != null && blacklistedWorlds.contains(location.getWorld().getName())) {
            return false;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard") && !hasHomeAdminPermission(player)) {
            try {
                // WorldGuard check can be executed if class present
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInstance);
                Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                Object query = container.getClass().getMethod("createQuery").invoke(container);
                Class<?> adapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Object loc = adapterClass.getMethod("adapt", Location.class).invoke(null, location);
                Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
                Object localPlayer = wgPluginClass.getMethod("wrapPlayer", Player.class).invoke(null, player);

                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                Object buildFlag = flagsClass.getField("BUILD").get(null);
                Object testState = query.getClass().getMethod("testState", Class.forName("com.sk89q.worldedit.util.Location"),
                        Class.forName("com.sk89q.worldguard.LocalPlayer"),
                        Class.forName("com.sk89q.worldguard.protection.flags.StateFlag[]")
                ).invoke(query, loc, localPlayer, java.lang.reflect.Array.newInstance(Class.forName("com.sk89q.worldguard.protection.flags.StateFlag"), 0));

                if (Boolean.FALSE.equals(testState)) {
                    return false;
                }
            } catch (Throwable ignored) {}
        }
        return true;
    }

    public boolean setHomeAtLocation(Player player, int slot, String name, Location location) {
        if (location.getY() > heightLimit) {
            sendHomeMessage(player, "height-limit-reached", Map.of("%limit%", String.valueOf(heightLimit)));
            playSound(player, "error");
            return false;
        }

        if (location.getWorld() != null && blacklistedWorlds.contains(location.getWorld().getName())) {
            sendHomeMessage(player, "blacklisted_world", Map.of());
            playSound(player, "error");
            return false;
        }

        if (!canSetHomeAt(player, location)) {
            sendHomeMessage(player, "not-allowed-set-home", Map.of());
            playSound(player, "error");
            return false;
        }

        int maxHomes = getMaxHomes(player.getUniqueId());
        PlayerHomes profile = getPlayerHomes(player.getUniqueId());
        if (profile == null) {
            if (isLoading(player.getUniqueId())) {
                Messages.send(plugin, player, "homes", "your-homes-are-still-loading-please");
                playSound(player, "error");
                return false;
            }
            profile = new PlayerHomes(player.getUniqueId(), player.getName());
            profileCache.put(player.getUniqueId(), profile);
        }

        if (slot <= 0) {
            slot = profile.getNextAvailableSlot(maxHomes);
            if (slot <= 0) {
                sendHomeMessage(player, "reached_max_number_of_homes", Map.of("%max_homes%", String.valueOf(maxHomes)));
                playSound(player, "error");
                return false;
            }
        }

        if (slot > maxHomes && !hasHomeAdminPermission(player)) {
            sendHomeMessage(player, "reached_max_number_of_homes", Map.of("%max_homes%", String.valueOf(maxHomes)));
            playSound(player, "error");
            return false;
        }

        if (setHomeCost > 0.0 && plugin.getHookManager() != null && plugin.getHookManager().isVaultEconomyAvailable() && !hasHomeAdminPermission(player)) {
            if (!plugin.getHookManager().getVaultEconomy().has(player, setHomeCost)) {
                sendHomeMessage(player, "insufficient-funds", Map.of("%cost%", String.valueOf(setHomeCost)));
                playSound(player, "error");
                return false;
            }
            plugin.getHookManager().getVaultEconomy().withdrawPlayer(player, setHomeCost);
        }

        HomeData home = new HomeData(player.getUniqueId(), slot, name, getServerId(), location);
        profile.setHome(home);
        savePlayerHomesAsync(profile);

        playSound(player, "ui-click");
        sendHomeMessage(player, "home-set-chat", Map.of("%home%", String.valueOf(slot), "%name%", home.getDisplayName()));
        sendHomeActionBar(player, "home-set-actionbar", Map.of("%home%", String.valueOf(slot), "%name%", home.getDisplayName()));
        return true;
    }

    public void teleportPlayerToHome(Player player, HomeData home) {
        if (home == null) {
            sendHomeMessage(player, "home-not-found", Map.of());
            playSound(player, "error");
            return;
        }

        if (activeTeleports.containsKey(player.getUniqueId())) {
            sendHomeMessage(player, "already-teleport-chat", Map.of());
            playSound(player, "error");
            return;
        }

        if (combatDelay > 0 && isInCombat(player.getUniqueId()) && !hasCooldownBypassPermission(player)) {
            sendHomeMessage(player, "is-in-combat", Map.of());
            playSound(player, "error");
            return;
        }

        if (teleportCost > 0.0 && plugin.getHookManager() != null && plugin.getHookManager().isVaultEconomyAvailable() && !hasHomeAdminPermission(player)) {
            if (!plugin.getHookManager().getVaultEconomy().has(player, teleportCost)) {
                sendHomeMessage(player, "insufficient-funds", Map.of("%cost%", String.valueOf(teleportCost)));
                playSound(player, "error");
                return;
            }
        }

        long warmupSec = (hasCooldownBypassPermission(player) || teleportTime <= 0) ? 0 : teleportTime;

        HomeTeleportCountdown countdown = new HomeTeleportCountdown(
                plugin, this, player, home, warmupSec, cancelOnMoveThreshold,
                success -> {
                    if (!success) return;
                    executeHomeTeleport(player, home);
                }
        );

        activeTeleports.put(player.getUniqueId(), countdown);
        countdown.start();
    }

    private void executeHomeTeleport(Player player, HomeData home) {
        if (!player.isOnline()) return;

        // Economy cost deduction on successful warmup completion
        if (teleportCost > 0.0 && plugin.getHookManager() != null && plugin.getHookManager().isVaultEconomyAvailable() && !hasHomeAdminPermission(player)) {
            if (!plugin.getHookManager().getVaultEconomy().has(player, teleportCost)) {
                sendHomeMessage(player, "insufficient-funds", Map.of("%cost%", String.valueOf(teleportCost)));
                playSound(player, "error");
                return;
            }
            plugin.getHookManager().getVaultEconomy().withdrawPlayer(player, teleportCost);
        }

        if (home.isSameServer(getServerId())) {
            Location loc = home.toBukkitLocation();
            if (loc == null || loc.getWorld() == null) {
                sendHomeMessage(player, "home-not-found", Map.of());
                playSound(player, "error");
                return;
            }

            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportWithLeashedEntities(player, loc, success -> {
                if (Boolean.TRUE.equals(success)) {
                    playSound(player, "teleported");
                    sendHomeMessage(player, "home-teleport-chat", Map.of("%home%", home.getDisplayName()));
                    sendHomeActionBar(player, "teleported-action-bar", Map.of("%home%", home.getDisplayName()));
                }
            });
            return;
        }

        // Cross-server teleportation via Redis packet and proxy server transfer
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            HomeTeleportPacket packet = new HomeTeleportPacket(
                    home.getServerId(), player.getUniqueId(), player.getName(),
                    home.getWorldName(), home.getX(), home.getY(), home.getZ(),
                    home.getYaw(), home.getPitch()
            );

            dev.lyy.friendlycore.common.redis.CrossServerPacket crossPacket = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.HOME_CROSS_TELEPORT,
                    player.getUniqueId(),
                    player.getName(),
                    0.0,
                    plugin.getGson().toJson(packet)
            );

            plugin.getRedisService().publish(crossPacket);

            if (plugin.getTransferFreezeService() != null) {
                plugin.getTransferFreezeService().freeze(player);
            }

            // Connect player to destination server via proxy plugin message
            dev.lyy.friendlycore.common.ProxyTransferUtil.connect(plugin, player, home.getServerId());

            sendHomeMessage(player, "home-teleporting-chat", Map.of("%home%", home.getDisplayName(), "%server%", home.getServerId()));
        } else {
            plugin.getLogger().warning("Cross-server home teleport requested for " + player.getName() + " but Redis is unavailable.");
            sendHomeMessage(player, "home-not-found", Map.of());
        }
    }

    public record PendingCrossTeleport(Location location, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 60_000L;
        }
    }

    public void registerPendingTeleport(UUID uuid, Location location) {
        if (uuid == null || location == null) return;
        pendingCrossTeleports.put(uuid, new PendingCrossTeleport(location, System.currentTimeMillis()));

        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Location claimedLoc = pollPendingTeleport(uuid);
            if (claimedLoc != null) {
                dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, claimedLoc).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        playSound(player, "teleported");
                        sendHomeMessage(player, "home-teleport-chat", Map.of("%home%", ""));
                        sendHomeActionBar(player, "teleported-action-bar", Map.of("%home%", ""));
                    }
                });
            }
        }
    }

    public boolean hasPendingCrossTeleport(UUID uuid) {
        if (uuid == null) return false;
        PendingCrossTeleport pending = pendingCrossTeleports.get(uuid);
        return pending != null && !pending.isExpired();
    }

    public Location pollPendingTeleport(UUID uuid) {
        PendingCrossTeleport pending = pendingCrossTeleports.remove(uuid);
        if (pending != null && !pending.isExpired()) {
            return pending.location();
        }
        return null;
    }

    public void cleanupExpiredPendingTeleports() {
        pendingCrossTeleports.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public void removeActiveTeleport(UUID uuid) {
        activeTeleports.remove(uuid);
    }

    public void tagCombat(UUID uuid) {
        combatTagMap.put(uuid, System.currentTimeMillis());
    }

    public boolean isInCombat(UUID uuid) {
        Long last = combatTagMap.get(uuid);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) < (combatDelay * 1000L);
    }

    public boolean isTeleporting(UUID uuid) {
        return uuid != null && activeTeleports.containsKey(uuid);
    }

    public int getRemainingCountdownSeconds(UUID uuid) {
        if (uuid == null) return 0;
        HomeTeleportCountdown countdown = activeTeleports.get(uuid);
        return countdown != null ? countdown.getRemainingSeconds() : 0;
    }

    public int getRemainingCombatSeconds(UUID uuid) {
        if (uuid == null || combatDelay <= 0) return 0;
        Long last = combatTagMap.get(uuid);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        long totalMs = combatDelay * 1000L;
        if (elapsed >= totalMs) return 0;
        return (int) Math.ceil((totalMs - elapsed) / 1000.0);
    }

    public void startChatRename(Player viewer, UUID targetUuid, int slot) {
        activeRenames.put(viewer.getUniqueId(), new SelectedHomeRename(targetUuid, slot));
        FileConfiguration invConfig = plugin.getConfigService().get("modules/homes/inventories/home.yml");
        String chatPrompt = invConfig.getString("home_rename", "<gray>Type new home name in chat (type <red>cancel</red> to abort):");
        String actionPrompt = invConfig.getString("home_rename_actionbar", "<yellow>Type new home name in chat...");

        if (allowChatMessages && chatPrompt != null && !chatPrompt.isEmpty()) {
            viewer.sendMessage(plugin.getTextService().parse(chatPrompt));
        }
        if (allowActionBarMessages && actionPrompt != null && !actionPrompt.isEmpty()) {
            viewer.sendActionBar(plugin.getTextService().parse(actionPrompt));
        }
        playSound(viewer, "ui-open");
    }

    public SelectedHomeRename getChatRename(UUID uuid) {
        return activeRenames.get(uuid);
    }

    public void removeChatRename(UUID uuid) {
        activeRenames.remove(uuid);
    }

    public void sendHomeMessage(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        if (!allowChatMessages && sender instanceof Player) return;
        String msg = plugin.getConfigService().get("modules/homes/messages.yml").getString(key);
        if (msg != null && !msg.isEmpty() && !msg.equalsIgnoreCase("DISABLED")) {
            String prefix = plugin.getConfigService().get("modules/homes/messages.yml").getString("prefix", "");
            plugin.getTextService().sendMessage(sender, prefix + msg, placeholders);
        }
    }

    public void sendHomeActionBar(Player player, String key, Map<String, String> placeholders) {
        if (!allowActionBarMessages) return;
        String msg = plugin.getConfigService().get("modules/homes/messages.yml").getString(key);
        if (msg != null && !msg.isEmpty() && !msg.equalsIgnoreCase("DISABLED")) {
            plugin.getTextService().sendActionBar(player, msg, placeholders);
        }
    }

    public void playSound(Player player, String soundKey) {
        FileConfiguration soundsConfig = plugin.getConfigService().get("modules/homes/sounds.yml");
        ConfigurationSection sec = soundsConfig.getConfigurationSection(soundKey);
        if (sec == null) return;
        String soundName = sec.getString("sound");
        if (soundName == null || soundName.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            float volume = (float) sec.getDouble("volume", 1.0);
            float pitch = (float) sec.getDouble("pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {}
    }

    public String getServerId() {
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled() && plugin.getRedisService().getServerId() != null && !plugin.getRedisService().getServerId().isEmpty()) {
            return plugin.getRedisService().getServerId();
        }
        return serverId != null ? serverId : "server-1";
    }
    public boolean isEnableConfirmationGui() { return enableConfirmationGui; }
    public boolean isDisableSubcommands() { return disableSubcommands; }
    public boolean isDisableHomeRenaming() { return disableHomeRenaming; }
    public boolean isAllowChatMessages() { return allowChatMessages; }
    public boolean isAllowActionBarMessages() { return allowActionBarMessages; }
    public boolean isSetHomeOnBedInteract() { return setHomeOnBedInteract; }
    public String getHomeGuiPerm() { return homeGuiPerm; }
    public String getHomeAdminPerm() { return homeAdminPerm; }
    public String getCooldownBypassPerm() { return cooldownBypassPerm; }
}
