package dev.lyy.friendlycore.modules.tpa;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaCrossServerService;
import dev.lyy.friendlycore.modules.tpa.gui.TpaAdminGui;
import dev.lyy.friendlycore.modules.tpa.gui.TpaAdminSafeChecksGui;
import dev.lyy.friendlycore.modules.tpa.gui.TpaConfirmGui;
import dev.lyy.friendlycore.modules.tpa.gui.TpaSettingGui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Main service manager for the TPA module.
 * Coordinates player profiles, local and cross-server teleport requests, cooldowns,
 * safe landing checks, and warmup countdowns.
 */
public class TpaManager {

    private final FriendlyCore plugin;
    private final Map<UUID, TpaProfile> profileCache = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Map<UUID, TpaCountdownTask> activeCountdowns = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();
    private final SafeTeleportChecker.SafeCheckSettings safeSettings;
    private final TpaCrossServerService crossServerService;

    private void trackInFlight(CompletableFuture<?> future) {
        if (future != null) {
            inFlightSaves.add(future);
            future.whenComplete((res, ex) -> inFlightSaves.remove(future));
        }
    }

    private Object cleanupTask;
    private Object autoSaveTask;

    public TpaManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.safeSettings = new SafeTeleportChecker.SafeCheckSettings(getConfig());
        this.crossServerService = new TpaCrossServerService(plugin, this);
    }

    public void initialize() {
        reload();

        // Expired request cleaner running every 5 seconds
        this.cleanupTask = plugin.getScheduler().runAsyncTimer(this::cleanupExpiredRequests, 100L, 100L);

        // Periodic batch persistence for modified profiles running every 60 seconds
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(this::saveAllDirtyProfiles, 1200L, 1200L);

        plugin.getLogger().info("TPA Module initialized successfully.");
    }

    public void reload() {
        safeSettings.update(getConfig());
    }

    public FileConfiguration getConfig() {
        return plugin.getConfigService().get("tpa.yml");
    }

    public SafeTeleportChecker.SafeCheckSettings getSafeSettings() {
        return safeSettings;
    }

    public TpaCrossServerService getCrossServerService() {
        return crossServerService;
    }

    public Map<UUID, TpaCountdownTask> getActiveCountdownMap() {
        return activeCountdowns;
    }

    public boolean isTeleporting(UUID uuid) {
        return uuid != null && activeCountdowns.containsKey(uuid);
    }

    public int getRemainingCountdownSeconds(UUID uuid) {
        if (uuid == null) return 0;
        TpaCountdownTask task = activeCountdowns.get(uuid);
        return task != null ? task.getRemainingSeconds() : 0;
    }

    public long getRemainingRequestCooldownSeconds(UUID uuid) {
        if (uuid == null) return 0;
        long cdMillis = getConfig().getLong("cooldown", 30000L);
        if (cdMillis <= 0) return 0;
        return plugin.getCooldownManager().getRemainingSeconds("tpa_request", uuid, cdMillis);
    }

    public boolean isRequestOnCooldown(UUID uuid) {
        if (uuid == null) return false;
        long cdMillis = getConfig().getLong("cooldown", 30000L);
        if (cdMillis <= 0) return false;
        return plugin.getCooldownManager().isOnCooldown("tpa_request", uuid, cdMillis);
    }

    public TpaProfile getProfile(Player player) {
        if (player == null) return null;
        return getProfile(player.getUniqueId(), player.getName());
    }

    public TpaProfile getProfile(UUID uuid, String username) {
        return profileCache.computeIfAbsent(uuid, k -> {
            TpaProfile profile = TpaProfile.pending(uuid, username);
            Object loadToken = new Object();
            loadTokens.put(uuid, loadToken);
            DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadTpaProfile(uuid, username),
                () -> loadTokens.get(uuid) == loadToken).thenAccept(loaded -> {
                if (loaded != null && loadTokens.get(uuid) == loadToken) {
                    profile.restore(loaded);
                }
            });
            return profile;
        });
    }

    public void loadProfileAsync(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Object loadToken = new Object();
        loadTokens.put(uuid, loadToken);
        DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadTpaProfile(uuid, name),
                () -> loadTokens.get(uuid) == loadToken).thenAccept(loaded -> {
            if (loaded != null && loadTokens.get(uuid) == loadToken) {
                TpaProfile existing = profileCache.get(uuid);
                if (existing != null) {
                    existing.restore(loaded);
                } else {
                    profileCache.put(uuid, loaded);
                }
            }
        });
    }

    public void unloadProfile(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        loadTokens.remove(uuid);
        TpaProfile profile = profileCache.remove(uuid);
        if (profile != null) {
            cancelOutgoingRequests(player, profile, false);
            // Clean incoming requests sent to this player
            for (TpaRequest inReq : profile.getIncomingRequests().values()) {
                inReq.setStatus(TpaRequest.Status.CANCELLED);
                TpaProfile senderProf = profileCache.get(inReq.getSenderUuid());
                if (senderProf != null) {
                    senderProf.removeOutgoingRequest(uuid);
                }
            }
            profile.getIncomingRequests().clear();

            if (profile.isDirty()) {
                trackInFlight(plugin.getDatabaseManager().saveTpaProfile(profile));
            }
        }
        removeActiveCountdown(uuid);
    }

    public CompletableFuture<Void> saveAllDirtyProfiles() {
        List<TpaProfile> dirty = new ArrayList<>();
        for (TpaProfile profile : profileCache.values()) {
            if (profile.isDirty()) {
                dirty.add(profile);
            }
        }
        if (!dirty.isEmpty()) {
            CompletableFuture<Void> future = plugin.getDatabaseManager().saveTpaProfilesBatch(dirty);
            trackInFlight(future);
            return future;
        }
        return CompletableFuture.completedFuture(null);
    }

    public void sendRequest(Player sender, String targetName, TpaRequest.Type type) {
        sendRequest(sender, targetName, type, false);
    }

    public void sendRequest(Player sender, String targetName, TpaRequest.Type type, boolean guiConfirmed) {
        if (sender == null || !sender.isOnline() || targetName == null || targetName.isEmpty()) return;

        Player localTarget = Bukkit.getPlayerExact(targetName);
        if (localTarget == null) {
            localTarget = Bukkit.getPlayer(targetName);
        }

        if (localTarget != null && localTarget.isOnline()) {
            sendRequest(sender, localTarget, type, guiConfirmed);
            return;
        }

        // Search proxy network via Redis when target is not on the local server
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            Set<String> netOnline = plugin.getRedisService().getNetworkOnlinePlayers();
            boolean found = false;
            String matchedName = targetName;
            for (String n : netOnline) {
                if (n.equalsIgnoreCase(targetName)) {
                    found = true;
                    matchedName = n;
                    break;
                }
            }

            if (found) {
                UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(matchedName);
                crossServerService.sendCrossServerRequest(sender, matchedName, targetUuid, type, guiConfirmed);
                return;
            }
        }

        sendConfigMessage(sender, "tpa.user-not-found", Map.of("%player%", targetName));
    }

    public void sendRequest(Player sender, Player target, TpaRequest.Type type) {
        sendRequest(sender, target, type, false);
    }

    public void sendRequest(Player sender, Player target, TpaRequest.Type type, boolean guiConfirmed) {
        if (sender == null || target == null || !sender.isOnline() || !target.isOnline()) {
            sendConfigMessage(sender, "tpa.user-not-found", Map.of("%player%", target != null ? target.getName() : "Unknown"));
            return;
        }

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sendConfigMessage(sender, "tpa.self-teleport", Map.of());
            return;
        }

        List<String> blacklistedWorlds = getConfig().getStringList("blacklisted-worlds");
        if (isWorldBlacklisted(sender.getWorld().getName(), blacklistedWorlds) || isWorldBlacklisted(target.getWorld().getName(), blacklistedWorlds)) {
            sendConfigMessage(sender, "tpa.blacklisted-world", Map.of());
            return;
        }

        List<String> blacklistedRegions = getConfig().getStringList("world-guard.blacklisted-regions");
        if (plugin.getHookManager().getProtectionHook().isRegionBlocked(sender.getLocation(), blacklistedRegions) ||
                plugin.getHookManager().getProtectionHook().isRegionBlocked(target.getLocation(), blacklistedRegions)) {
            sendConfigMessage(sender, "tpa.blacklisted-region", Map.of());
            return;
        }

        TpaProfile senderProfile = getProfile(sender);
        TpaProfile targetProfile = getProfile(target);

        int limit = getConfig().getInt("teleport-limitations", -1);
        if (limit > 0 && !sender.hasPermission("friendlycore.bypass.cooldown") && !sender.hasPermission("donuttpa.admin")) {
            if (senderProfile.getDailyCount() >= limit) {
                sendConfigMessage(sender, "tpa.daily-limit-reached", Map.of("%limit%", String.valueOf(limit)));
                return;
            }
        }

        if (type == TpaRequest.Type.TPA && !targetProfile.isTpaEnabled()) {
            sendConfigMessage(sender, "tpa.block-tpa-request", Map.of("%player%", target.getName()));
            return;
        }
        if (type == TpaRequest.Type.TPHERE && !targetProfile.isTpaHereEnabled()) {
            sendConfigMessage(sender, "tpa.block-tphere-request", Map.of("%player%", target.getName()));
            return;
        }

        long cooldownDuration = getConfig().getLong("request-cooldown", 30000L);
        if (cooldownDuration > 0 && !sender.hasPermission("friendlycore.bypass.cooldown")) {
            if (senderProfile.isOnCooldown(target.getUniqueId())) {
                sendConfigMessage(sender, "tpa.request-cooldown", Map.of("%player%", target.getName()));
                return;
            }
        }

        // Prevent duplicate requests
        if (senderProfile.getOutgoingRequests().containsKey(target.getUniqueId())) {
            sendConfigMessage(sender, "tpa.already-sent-request", Map.of("%player%", target.getName()));
            return;
        }

        // Confirmation GUI prompt if enabled in player settings
        boolean disableGuiGlobal = getConfig().getBoolean("disable-tpa-gui", false);
        if (!disableGuiGlobal && senderProfile.isShowGui() && !guiConfirmed) {
            new TpaConfirmGui(plugin, this, sender, target, type, TpaConfirmGui.GuiMode.REQUEST).open();
            return;
        }

        senderProfile.setCooldown(target.getUniqueId(), cooldownDuration);

        // Direct teleportation if target has auto-accept toggled on
        if (targetProfile.isTpAutoEnabled() && type == TpaRequest.Type.TPA) {
            executeTeleport(sender, target.getLocation(), target, () -> {
                sendConfigMessage(sender, "tpa.accepted-teleport", Map.of("%player%", target.getName()));
                sendConfigMessage(target, "tpa.accepted-teleport", Map.of("%player%", sender.getName()));
            });
            return;
        }

        // Register new request in sender and recipient profiles
        TpaRequest request = new TpaRequest(sender.getUniqueId(), sender.getName(), target.getUniqueId(), target.getName(), type);
        request.setInitialLocation(sender.getLocation().clone());
        request.setOriginServer(crossServerService.getLocalServerId());
        request.setTargetServer(crossServerService.getLocalServerId());

        senderProfile.addOutgoingRequest(request);
        targetProfile.addIncomingRequest(request);

        if (type == TpaRequest.Type.TPA) {
            sendConfigMessage(sender, "tpa.sent-request", Map.of("%player%", target.getName()));
            sendConfigMessage(sender, "tpa.tpa-request-sender-actionbar", Map.of("%player%", target.getName()));
        } else {
            sendConfigMessage(sender, "tpa.sent-here-request", Map.of("%player%", target.getName()));
            sendConfigMessage(sender, "tpa.tpa-here-request-sender-actionbar", Map.of("%player%", target.getName()));
        }

        if (type == TpaRequest.Type.TPA) {
            sendConfigMessage(target, "tpa.receive-request", Map.of("%player%", sender.getName()));
            sendConfigMessage(target, "tpa.tpa-request-requester-actionbar", Map.of("%player%", sender.getName()));
            String sound = getConfig().getString("sounds.tpa-receive", "entity.experience_orb.pickup");
            TextService.playSound(target, sound, 1.0f, 1.0f);
        } else {
            sendConfigMessage(target, "tpa.receive-here-request", Map.of("%player%", sender.getName()));
            sendConfigMessage(target, "tpa.tpa-here-request-requester-actionbar", Map.of("%player%", sender.getName()));
            String sound = getConfig().getString("sounds.tpa-receive", "entity.experience_orb.pickup");
            TextService.playSound(target, sound, 1.0f, 1.0f);
        }
    }

    public void acceptRequest(Player target, String senderName) {
        if (target == null || !target.isOnline()) return;
        TpaProfile targetProfile = getProfile(target);

        TpaRequest request = null;
        if (senderName != null && !senderName.isEmpty()) {
            for (TpaRequest req : targetProfile.getIncomingRequests().values()) {
                if (req.getSenderName().equalsIgnoreCase(senderName)) {
                    request = req;
                    break;
                }
            }
        } else {
            long maxTime = -1;
            for (TpaRequest req : targetProfile.getIncomingRequests().values()) {
                if (req.getStatus() == TpaRequest.Status.PENDING && req.getCreatedAt() > maxTime) {
                    maxTime = req.getCreatedAt();
                    request = req;
                }
            }
        }

        if (request == null || request.getStatus() != TpaRequest.Status.PENDING) {
            sendConfigMessage(target, "tpa.non-valid-request", Map.of());
            return;
        }

        // Forward to cross-server service if request originated from another server
        if (request.isCrossServer() || (request.getOriginServer() != null && !request.getOriginServer().equalsIgnoreCase(crossServerService.getLocalServerId()))) {
            crossServerService.acceptCrossServerRequest(target, request);
            return;
        }

        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender == null || !sender.isOnline()) {
            targetProfile.removeIncomingRequest(request.getSenderUuid());
            sendConfigMessage(target, "tpa.user-not-found", Map.of("%player%", request.getSenderName()));
            return;
        }

        List<String> blacklistedWorlds = getConfig().getStringList("blacklisted-worlds");
        if (isWorldBlacklisted(sender.getWorld().getName(), blacklistedWorlds) || isWorldBlacklisted(target.getWorld().getName(), blacklistedWorlds)) {
            sendConfigMessage(target, "tpa.blacklisted-world", Map.of());
            return;
        }

        List<String> blacklistedRegions = getConfig().getStringList("world-guard.blacklisted-regions");
        if (plugin.getHookManager().getProtectionHook().isRegionBlocked(sender.getLocation(), blacklistedRegions) ||
                plugin.getHookManager().getProtectionHook().isRegionBlocked(target.getLocation(), blacklistedRegions)) {
            sendConfigMessage(target, "tpa.blacklisted-region", Map.of());
            return;
        }

        request.setStatus(TpaRequest.Status.ACCEPTED);
        targetProfile.removeIncomingRequest(sender.getUniqueId());
        TpaProfile senderProfile = getProfile(sender);
        if (senderProfile != null) {
            senderProfile.removeOutgoingRequest(target.getUniqueId());
        }

        if (request.getType() == TpaRequest.Type.TPA || request.getType() == TpaRequest.Type.TPAALL) {
            sendConfigMessage(target, "tpa.accepted-teleport", Map.of("%player%", sender.getName()));
            executeTeleport(sender, target.getLocation(), target, () -> {
                sendConfigMessage(sender, "tpa.accepted-teleport", Map.of("%player%", target.getName()));
            });

            if (request.getType() == TpaRequest.Type.TPAALL) {
                cancelOutgoingRequests(sender, false);
            }
        } else {
            sendConfigMessage(sender, "tpa.tpa-here-accept", Map.of("%player%", target.getName()));
            sendConfigMessage(sender, "tpa.tpa-here-accept-actionbar", Map.of("%player%", target.getName()));
            executeTeleport(target, sender.getLocation(), sender, () -> {
                sendConfigMessage(target, "tpa.accepted-teleport", Map.of("%player%", sender.getName()));
            });
        }
    }

    public void denyRequest(Player target, String senderName) {
        if (target == null || !target.isOnline()) return;
        TpaProfile targetProfile = getProfile(target);

        TpaRequest request = null;
        if (senderName != null && !senderName.isEmpty()) {
            for (TpaRequest req : targetProfile.getIncomingRequests().values()) {
                if (req.getSenderName().equalsIgnoreCase(senderName)) {
                    request = req;
                    break;
                }
            }
        } else {
            long maxTime = -1;
            for (TpaRequest req : targetProfile.getIncomingRequests().values()) {
                if (req.getStatus() == TpaRequest.Status.PENDING && req.getCreatedAt() > maxTime) {
                    maxTime = req.getCreatedAt();
                    request = req;
                }
            }
        }

        if (request == null) {
            sendConfigMessage(target, "tpa.no-requests-found", Map.of());
            return;
        }

        if (request.isCrossServer() || (request.getOriginServer() != null && !request.getOriginServer().equalsIgnoreCase(crossServerService.getLocalServerId()))) {
            crossServerService.denyCrossServerRequest(target, request);
            return;
        }

        request.setStatus(TpaRequest.Status.DECLINED);
        targetProfile.removeIncomingRequest(request.getSenderUuid());

        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender != null && sender.isOnline()) {
            TpaProfile senderProfile = getProfile(sender);
            if (senderProfile != null) {
                senderProfile.removeOutgoingRequest(target.getUniqueId());
            }
            sendConfigMessage(sender, "tpa.cancelled-request-sender", Map.of("%player%", target.getName()));
        }

        sendConfigMessage(target, "tpa.cancelled-request", Map.of("%player%", request.getSenderName()));
    }

    public void cancelOutgoingRequests(Player sender, boolean notify) {
        if (sender == null) return;
        cancelOutgoingRequests(sender, profileCache.get(sender.getUniqueId()), notify);
    }

    private void cancelOutgoingRequests(Player sender, TpaProfile profile, boolean notify) {
        if (profile == null || profile.getOutgoingRequests().isEmpty()) {
            if (notify && sender.isOnline()) {
                sendConfigMessage(sender, "tpa.cancel-requests-failed", Map.of());
            }
            return;
        }

        Map<UUID, TpaRequest> out = profile.getOutgoingRequests();
        for (TpaRequest req : out.values()) {
            req.setStatus(TpaRequest.Status.CANCELLED);
            if (req.isCrossServer() || (req.getTargetServer() != null && !req.getTargetServer().equalsIgnoreCase(crossServerService.getLocalServerId()))) {
                crossServerService.cancelCrossServerRequest(sender, req);
            } else {
                TpaProfile targetProf = profileCache.get(req.getTargetUuid());
                if (targetProf != null) {
                    targetProf.removeIncomingRequest(sender.getUniqueId());
                }
            }
        }

        out.clear();
        if (notify && sender.isOnline()) {
            sendConfigMessage(sender, "tpa.cancel-requests", Map.of());
        }
    }

    public void sendTpaAll(Player sender) {
        if (sender == null || !sender.isOnline()) return;

        Set<String> allPlayerNames = new LinkedHashSet<>();
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            allPlayerNames.addAll(plugin.getRedisService().getOnlinePlayerNames());
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                allPlayerNames.add(p.getName());
            }
        }

        allPlayerNames.remove(sender.getName());

        if (allPlayerNames.isEmpty()) {
            sendConfigMessage(sender, "tpa.no-players-online", Map.of());
            return;
        }

        for (String targetName : allPlayerNames) {
            sendRequest(sender, targetName, TpaRequest.Type.TPAALL, true);
        }

        sendConfigMessage(sender, "tpa.tpaall-sent", Map.of("%count%", String.valueOf(allPlayerNames.size())));
        String sound = getConfig().getString("sounds.tpaall", "entity.experience_orb.pickup");
        TextService.playSound(sender, sound, 1.0f, 1.0f);
    }

    public void teleportHereAll(Player sender) {
        if (sender == null || !sender.isOnline()) return;

        List<String> blacklistedWorlds = getConfig().getStringList("blacklisted-worlds");
        if (isWorldBlacklisted(sender.getWorld().getName(), blacklistedWorlds)) {
            sendConfigMessage(sender, "tpa.blacklisted-world", Map.of());
            return;
        }

        int count = 0;
        Location dest = sender.getLocation();
        if (dest.getWorld() != null) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!target.getUniqueId().equals(sender.getUniqueId())) {
                    dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(target, dest).thenAccept(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            sendConfigMessage(target, "tpa.force-teleported", Map.of("%player%", sender.getName()));
                        }
                    });
                }
            }
        }
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!target.getUniqueId().equals(sender.getUniqueId())) {
                count++;
            }
        }

        if (count == 0) {
            sendConfigMessage(sender, "tpa.no-players-online", Map.of());
            return;
        }

        sendConfigMessage(sender, "tpa.tphereall-success", Map.of("%count%", String.valueOf(count)));
    }

    public void executeTeleport(Player traveler, Location destination, Player anchorPlayer, Runnable onStart) {
        if (traveler == null || !traveler.isOnline() || destination == null || destination.getWorld() == null) return;

        plugin.getScheduler().runEntity(traveler, () -> {
            if (!traveler.isOnline()) return;

            if (onStart != null) {
                onStart.run();
            }

            long duration = traveler.hasPermission("friendlycore.bypass.warmup") ? 0 : getConfig().getLong("tpa-countdown-seconds", 5L);
            boolean smooth = getConfig().getBoolean("use-smooth-countdown", true);
            double walkRange = getConfig().getDouble("allowed-walk-range", 1.0);

            removeActiveCountdown(traveler.getUniqueId());

            UUID anchorUuid = (anchorPlayer != null) ? anchorPlayer.getUniqueId() : null;
            TpaCountdownTask countdownTask = new TpaCountdownTask(plugin, this, traveler, anchorUuid, destination, duration, smooth, walkRange, success -> {
                if (success) {
                    TpaProfile prof = getProfile(traveler);
                    if (prof != null) {
                        prof.incrementDailyCount();
                    }
                }
            });

            activeCountdowns.put(traveler.getUniqueId(), countdownTask);
            countdownTask.start();
        });
    }

    public void removeActiveCountdown(UUID uuid) {
        TpaCountdownTask task = activeCountdowns.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    public boolean hasActiveCountdown(UUID uuid) {
        return activeCountdowns.containsKey(uuid);
    }

    public TpaCountdownTask getActiveCountdown(UUID uuid) {
        return activeCountdowns.get(uuid);
    }

    public boolean isWorldBlacklisted(String worldName, List<String> blacklistedWorlds) {
        if (worldName == null || blacklistedWorlds == null) return false;
        for (String w : blacklistedWorlds) {
            if (worldName.equalsIgnoreCase(w)) {
                return true;
            }
        }
        return false;
    }

    private void cleanupExpiredRequests() {
        long clearTimeSeconds = getConfig().getLong("data.clear-time", 120L);
        for (TpaProfile profile : profileCache.values()) {
            profile.getIncomingRequests().values().removeIf(req -> req.isExpired(clearTimeSeconds));
            profile.getOutgoingRequests().values().removeIf(req -> req.isExpired(clearTimeSeconds));
        }
    }

    public void openSettingGui(Player player) {
        new TpaSettingGui(plugin, this, player).open();
    }

    public void openAdminGui(Player player) {
        new TpaAdminGui(plugin, this, player).open();
    }

    public void openAdminSafeChecksGui(Player player) {
        new TpaAdminSafeChecksGui(plugin, this, player).open();
    }

    public void sendConfigMessage(Player player, String path, Map<String, String> placeholders) {
        if (player == null || !player.isOnline()) return;
        FileConfiguration msgConfig = plugin.getConfigService().getMessages();
        if (!msgConfig.isConfigurationSection(path)) {
            String fallback = msgConfig.getString(path);
            if (fallback != null) {
                plugin.getTextService().sendMessage(player, fallback, placeholders);
            }
            return;
        }

        boolean enabled = msgConfig.getBoolean(path + ".enabled", true);
        if (!enabled) return;

        String type = msgConfig.getString(path + ".type", "chat").toLowerCase(Locale.ROOT);
        String message = msgConfig.getString(path + ".message", "");

        if (!message.isEmpty()) {
            if (type.equals("actionbar")) {
                plugin.getTextService().sendActionBar(player, message, placeholders);
            } else {
                plugin.getTextService().sendMessage(player, message, placeholders);
            }
        }

        if (msgConfig.getBoolean(path + ".sound.enabled", false)) {
            String key = msgConfig.getString(path + ".sound.key", "entity.experience_orb.pickup");
            float vol = (float) msgConfig.getDouble(path + ".sound.volume", 1.0);
            float pitch = (float) msgConfig.getDouble(path + ".sound.pitch", 1.0);
            TextService.playSound(player, key, vol, pitch);
        }
    }

    public String getWorldNickname(String worldName) {
        if (worldName == null) return "Unknown";
        return getConfig().getString("world-nicknames." + worldName, worldName);
    }

    public Material getWorldIcon(String worldName) {
        if (worldName == null) return Material.GRASS_BLOCK;
        String matName = getConfig().getString("world-icons." + worldName, "GRASS_BLOCK");
        Material mat = Material.matchMaterial(matName);
        return mat != null ? mat : Material.GRASS_BLOCK;
    }

    public void shutdown() {
        if (crossServerService != null) {
            crossServerService.shutdown();
        }
        if (cleanupTask != null) {
            plugin.getScheduler().cancel(cleanupTask);
        }
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
        }
        for (TpaCountdownTask task : activeCountdowns.values()) {
            task.cancel();
        }
        activeCountdowns.clear();
        try {
            saveAllDirtyProfiles().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush TPA profiles during shutdown", e);
        }
        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for TPA profile saves during shutdown", e);
            }
        }
        loadTokens.clear();
        profileCache.clear();
    }
}
