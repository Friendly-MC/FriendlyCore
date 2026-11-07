package dev.lyy.friendlycore.modules.kits;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.database.DatabaseReadRetry;
import dev.lyy.friendlycore.common.database.KitClaimReservation;
import dev.lyy.friendlycore.modules.kits.model.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * High-performance Kit Manager featuring Paper/Folia-safe entity delivery,
 * asynchronous SQLite/MySQL storage, and rich PlayerKits2 capability parity.
 */
public class KitManager {

    private final FriendlyCore plugin;
    private final ConcurrentHashMap<String, Kit> kitMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerKitData> playerDataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> loadTokens = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightSaves = ConcurrentHashMap.newKeySet();

    private final KitActionExecutor actionExecutor;
    private final KitRequirementChecker requirementChecker;
    private final dev.lyy.friendlycore.modules.kits.cross.KitCrossServerService crossServerService;
    private final dev.lyy.friendlycore.modules.kits.gui.editor.KitEditorManager editorManager;

    private boolean dropItemsIfFull = false;
    private boolean closeInventoryOnClaim = false;
    private boolean kitPreviewEnabled = true;
    private boolean kitPreviewRequiresPermission = false;
    private String firstJoinKit = "none";
    private Object autoSaveTask;
    private final org.bukkit.NamespacedKey firstJoinKitKey;
    private final Object kitSaveQueueLock = new Object();
    private CompletableFuture<Void> kitSaveTail = CompletableFuture.completedFuture(null);
    private final ConcurrentHashMap<UUID, Set<PendingClaim>> inFlightClaims = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlightClaimKeys = new ConcurrentHashMap<>();
    private static final int MAX_AMOUNT_PER_KIT_ITEM = 4096;

    public KitManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.actionExecutor = new KitActionExecutor(plugin);
        this.requirementChecker = new KitRequirementChecker(plugin);
        this.crossServerService = new dev.lyy.friendlycore.modules.kits.cross.KitCrossServerService(plugin);
        this.editorManager = new dev.lyy.friendlycore.modules.kits.gui.editor.KitEditorManager(plugin);
        this.firstJoinKitKey = plugin != null ? new org.bukkit.NamespacedKey(plugin, "first_join_kit_claimed") : null;
    }

    public void initialize() {
        loadSettings();
        loadKits();

        // Periodic async dirty player kit data flusher and offline cleanup (every 60s)
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(() -> {
            flushDirtyPlayerKitData();
            cleanupOfflineData();
        }, 1200L, 1200L);
    }

    public void shutdown() {
        if (crossServerService != null) {
            crossServerService.shutdown();
        }
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
            autoSaveTask = null;
        }
        try {
            CompletableFuture<Void> pendingKitSave;
            synchronized (kitSaveQueueLock) {
                pendingKitSave = kitSaveTail;
            }
            pendingKitSave.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush kit definitions during shutdown", e);
        }
        try {
            CompletableFuture<Void> future = flushDirtyPlayerKitData();
            if (future != null) {
                inFlightSaves.add(future);
                future.whenComplete((r, e) -> inFlightSaves.remove(future));
                future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush player kit data during shutdown", e);
        }
        if (!inFlightSaves.isEmpty()) {
            try {
                CompletableFuture.allOf(inFlightSaves.toArray(CompletableFuture[]::new))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Timed out waiting for in-flight kit saves during shutdown", e);
            }
        }
        loadTokens.clear();
        playerDataCache.clear();
    }

    public void loadSettings() {
        FileConfiguration kitsConfig = plugin.getConfigService().getKitsConfig();
        FileConfiguration mainConfig = plugin.getConfigService().getMainConfig();

        this.dropItemsIfFull = kitsConfig.getBoolean("settings.drop-items-if-full-inventory",
                mainConfig.getBoolean("kits.drop-items-if-full-inventory", false));
        this.closeInventoryOnClaim = kitsConfig.getBoolean("settings.close-inventory-on-claim",
                mainConfig.getBoolean("kits.close-inventory-on-claim", false));
        this.kitPreviewEnabled = kitsConfig.getBoolean("settings.kit-preview",
                mainConfig.getBoolean("kits.kit-preview", true));
        this.kitPreviewRequiresPermission = kitsConfig.getBoolean("settings.kit-preview-requires-permission",
                mainConfig.getBoolean("kits.kit-preview-requires-permission", false));
        this.firstJoinKit = kitsConfig.getString("settings.first-join-kit",
                mainConfig.getString("kits.first-join-kit", "none"));
    }

    public void loadKits() {
        // Build off to the side so claims during a reload never see a missing kit.
        Map<String, Kit> loaded = new HashMap<>();
        FileConfiguration config = plugin.getConfigService().getKitsConfig();
        ConfigurationSection section = config.getConfigurationSection("kits");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Kit kit = parseKitFromSection(section.getConfigurationSection(key), key);
                if (kit != null) {
                    loaded.put(key.toLowerCase(Locale.ROOT), kit);
                }
            }
        }

        // Also check if there is an external modules/kits/kits/ or kits/ directory
        File[] searchDirs = {
                new File(plugin.getDataFolder(), "modules/kits/kits"),
                new File(plugin.getDataFolder(), "kits")
        };

        for (File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName().substring(0, file.getName().length() - 4);
                        try {
                            YamlConfiguration kitConfig = ConfigService.loadStrict(file);
                            Kit kit = parseKitFromSection(kitConfig, name);
                            if (kit != null) {
                                loaded.put(name.toLowerCase(Locale.ROOT), kit);
                            }
                        } catch (Exception e) {
                            // Keep the previously loaded kit rather than replacing it with an empty one.
                            Kit previous = kitMap.get(name.toLowerCase(Locale.ROOT));
                            if (previous != null) loaded.put(name.toLowerCase(Locale.ROOT), previous);
                            plugin.getLogger().log(Level.WARNING, "Failed to load kit from " + file.getName()
                                    + (previous != null ? "; keeping the last loaded version" : ""), e);
                        }
                    }
                }
            }
        }

        kitMap.keySet().retainAll(loaded.keySet());
        kitMap.putAll(loaded);
        plugin.getLogger().info("Loaded " + kitMap.size() + " kits successfully.");
    }

    public Kit parseKitFromSection(ConfigurationSection section, String key) {
        if (section == null) return null;

        String name = section.getString("name", key);
        Kit kit = new Kit(name);

        kit.setCooldownSeconds(section.getLong("cooldown", section.getLong("cooldownSeconds", 0)));
        kit.setPermissionRequired(section.getBoolean("permission_required", section.getBoolean("permission-required", true)));
        kit.setCustomPermission(section.getString("custom_permission", section.getString("permission", "friendlycore.kits." + key.toLowerCase(Locale.ROOT))));
        kit.setOneTime(section.getBoolean("one_time", section.getBoolean("one-time", false)));
        kit.setAutoArmor(section.getBoolean("auto_armor", section.getBoolean("auto-armor", false)));
        kit.setClearInventory(section.getBoolean("clear_inventory", section.getBoolean("clear-inventory", false)));
        kit.setSaveOriginalItems(section.getBoolean("save_original_items", false));
        kit.setAllowPlaceholdersOnOriginalItems(section.getBoolean("allow_placeholders_on_original_items", false));

        List<KitItem> items = new ArrayList<>();
        if (section.contains("items-base64")) {
            String base64 = section.getString("items-base64", "");
            List<ItemStack> decoded = KitSerializer.fromBase64(base64);
            for (ItemStack is : decoded) {
                items.add(new KitItem(is));
            }
        } else if (section.contains("items")) {
            ConfigurationSection itemsSec = section.getConfigurationSection("items");
            if (itemsSec != null) {
                for (String itemKey : itemsSec.getKeys(false)) {
                    KitItem ki = KitItemParser.loadKitItem(section, "items." + itemKey);
                    if (ki != null) items.add(ki);
                }
            }
        }
        kit.setKitItems(items);

        kit.setClaimActions(loadActions(section, "actions.claim"));
        kit.setErrorActions(loadActions(section, "actions.error"));

        if (section.contains("display.default")) kit.setDisplayItemDefault(KitItemParser.loadKitItem(section, "display.default"));
        if (section.contains("display.no_permission")) kit.setDisplayItemNoPermission(KitItemParser.loadKitItem(section, "display.no_permission"));
        if (section.contains("display.cooldown")) kit.setDisplayItemCooldown(KitItemParser.loadKitItem(section, "display.cooldown"));
        if (section.contains("display.one_time")) kit.setDisplayItemOneTime(KitItemParser.loadKitItem(section, "display.one_time"));
        if (section.contains("display.one_time_requirements")) kit.setDisplayItemOneTimeRequirements(KitItemParser.loadKitItem(section, "display.one_time_requirements"));

        if (section.contains("requirements")) {
            boolean oneTimeReq = section.getBoolean("requirements.one_time_requirements", false);
            double price = section.getDouble("requirements.price", 0.0);
            List<String> extra = section.getStringList("requirements.extra_requirements");
            List<String> msg = section.getStringList("requirements.message");
            List<String> actionsOnBuy = section.getStringList("requirements.actions_on_buy");
            kit.setRequirements(new KitRequirements(oneTimeReq, extra, msg, actionsOnBuy, price));
        }

        return kit;
    }

    private List<KitAction> loadActions(ConfigurationSection section, String path) {
        List<KitAction> actions = new ArrayList<>();
        ConfigurationSection actSec = section.getConfigurationSection(path);
        if (actSec != null) {
            for (String key : actSec.getKeys(false)) {
                String fullPath = path + "." + key;
                String actionText = section.getString(fullPath + ".action", section.getString(fullPath, ""));
                boolean execBefore = section.getBoolean(fullPath + ".execute_before_items", false);
                boolean countItem = section.getBoolean(fullPath + ".count_as_item", false);
                KitItem displayItem = KitItemParser.loadKitItem(section, fullPath + ".display_item");
                if (!actionText.isEmpty()) {
                    actions.add(new KitAction(actionText, displayItem, execBefore, countItem));
                }
            }
        }
        return actions;
    }

    public CompletableFuture<Void> saveKitsAsync() {
        Map<String, Kit> snapshot = new HashMap<>(kitMap);
        synchronized (kitSaveQueueLock) {
            kitSaveTail = kitSaveTail.handle((ignored, failure) -> {
                if (failure != null) {
                    plugin.getLogger().log(Level.WARNING, "Previous kit definition save failed", failure);
                }
                return null;
            }).thenRunAsync(() -> {
                synchronized (plugin.getConfigService()) {
                    FileConfiguration config = plugin.getConfigService().getKitsConfig();
                    config.set("kits", null);

                    for (Map.Entry<String, Kit> entry : snapshot.entrySet()) {
                        String key = entry.getKey();
                        Kit kit = entry.getValue();
                        saveKitToSection(config, "kits." + key, kit);
                    }

                    plugin.getConfigService().save("kits.yml");
                }
            }, plugin.getScheduler().getAsyncExecutor());
            return kitSaveTail;
        }
    }

    public void saveKitToSection(ConfigurationSection config, String path, Kit kit) {
        config.set(path + ".name", kit.getName());
        config.set(path + ".cooldown", kit.getCooldownSeconds());
        config.set(path + ".permission_required", kit.isPermissionRequired());
        config.set(path + ".custom_permission", kit.getCustomPermission());
        config.set(path + ".one_time", kit.isOneTime());
        config.set(path + ".auto_armor", kit.isAutoArmor());
        config.set(path + ".clear_inventory", kit.isClearInventory());
        config.set(path + ".save_original_items", kit.isSaveOriginalItems());
        config.set(path + ".allow_placeholders_on_original_items", kit.isAllowPlaceholdersOnOriginalItems());

        int idx = 1;
        config.set(path + ".items", null);
        for (KitItem item : kit.getKitItems()) {
            KitItemParser.saveKitItem(item, config, path + ".items." + idx);
            idx++;
        }

        saveActions(config, path + ".actions.claim", kit.getClaimActions());
        saveActions(config, path + ".actions.error", kit.getErrorActions());

        if (kit.getDisplayItemDefault() != null) KitItemParser.saveKitItem(kit.getDisplayItemDefault(), config, path + ".display.default");
        if (kit.getDisplayItemNoPermission() != null) KitItemParser.saveKitItem(kit.getDisplayItemNoPermission(), config, path + ".display.no_permission");
        if (kit.getDisplayItemCooldown() != null) KitItemParser.saveKitItem(kit.getDisplayItemCooldown(), config, path + ".display.cooldown");
        if (kit.getDisplayItemOneTime() != null) KitItemParser.saveKitItem(kit.getDisplayItemOneTime(), config, path + ".display.one_time");
        if (kit.getDisplayItemOneTimeRequirements() != null) KitItemParser.saveKitItem(kit.getDisplayItemOneTimeRequirements(), config, path + ".display.one_time_requirements");

        KitRequirements req = kit.getRequirements();
        if (req != null) {
            config.set(path + ".requirements.one_time_requirements", req.isOneTimeRequirements());
            config.set(path + ".requirements.price", req.getPrice());
            if (!req.getExtraRequirements().isEmpty()) config.set(path + ".requirements.extra_requirements", req.getExtraRequirements());
            if (!req.getMessage().isEmpty()) config.set(path + ".requirements.message", req.getMessage());
            if (!req.getActionsOnBuy().isEmpty()) config.set(path + ".requirements.actions_on_buy", req.getActionsOnBuy());
        }
    }

    private void saveActions(ConfigurationSection config, String path, List<KitAction> actions) {
        config.set(path, null);
        if (actions == null || actions.isEmpty()) return;
        int idx = 1;
        for (KitAction action : actions) {
            config.set(path + "." + idx + ".action", action.getAction());
            if (action.isExecuteBeforeItems()) config.set(path + "." + idx + ".execute_before_items", true);
            if (action.isCountAsItem()) config.set(path + "." + idx + ".count_as_item", true);
            if (action.getDisplayItem() != null) KitItemParser.saveKitItem(action.getDisplayItem(), config, path + "." + idx + ".display_item");
            idx++;
        }
    }

    public Kit getKit(String name) {
        if (name == null) return null;
        return kitMap.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Kit> getAllKits() {
        return Collections.unmodifiableCollection(kitMap.values());
    }

    public boolean createKit(String name, Player player, boolean saveOriginalItems) {
        if (name == null || player == null) return false;
        Kit kit = buildKitDefinition(name, player.getInventory().getContents(), saveOriginalItems);
        if (kit == null) return false;
        kitMap.put(name.toLowerCase(Locale.ROOT), kit);
        saveKitsAsync();
        return true;
    }

    static Kit buildKitDefinition(String name, ItemStack[] contents, boolean saveOriginalItems) {
        if (name == null || contents == null) return null;

        List<KitItem> items = new ArrayList<>();
        boolean hasArmor = false;

        for (int i = 0; i < contents.length; i++) {
            ItemStack is = contents[i];
            if (is == null || is.getType().isAir()) continue;

            KitItem kitItem = KitItemParser.fromItemStack(is, saveOriginalItems);
            if (kitItem == null) continue;

            hasArmor |= applyInventorySlotMetadata(kitItem, i);

            items.add(kitItem);
        }

        if (items.isEmpty()) return null;

        Kit kit = new Kit(name);
        kit.setKitItems(items);
        kit.setAutoArmor(hasArmor);
        kit.setSaveOriginalItems(saveOriginalItems);
        kit.setCooldownSeconds(3600);

        KitItem displayDefault = new KitItem(items.get(0).getId());
        displayDefault.setName("<gradient:#7BD88F:#B9FBC0><bold>" + name + " Kit</bold></gradient>");
        displayDefault.setLore(List.of("<color:#D8D0C3>ɪᴛᴇᴍѕ <dark_gray>» <color:#6EDBFF>" + items.size() + " ɪᴛᴇᴍѕ</color></color>", "", "<color:#8DE969>Left-Click to claim</color>", "<color:#6EDBFF>Right-Click to preview</color>"));
        kit.setDisplayItemDefault(displayDefault);

        return kit;
    }

    static boolean applyInventorySlotMetadata(KitItem kitItem, int slot) {
        if (kitItem == null) return false;
        switch (slot) {
            case 36 -> kitItem.setEquipmentSlot(KitItem.EquipmentSlot.FEET);
            case 37 -> kitItem.setEquipmentSlot(KitItem.EquipmentSlot.LEGS);
            case 38 -> kitItem.setEquipmentSlot(KitItem.EquipmentSlot.CHEST);
            case 39 -> kitItem.setEquipmentSlot(KitItem.EquipmentSlot.HEAD);
            case 40 -> kitItem.setEquipmentSlot(KitItem.EquipmentSlot.OFF_HAND);
            default -> {
                return false;
            }
        }
        return slot >= 36 && slot <= 39;
    }

    public boolean createKit(String name, List<ItemStack> items) {
        if (name == null || items == null || items.isEmpty()) return false;
        String key = name.toLowerCase(Locale.ROOT);
        Kit kit = new Kit(name, items);
        kitMap.put(key, kit);
        saveKitsAsync();
        return true;
    }

    public boolean updateKit(Kit kit) {
        if (kit == null || kit.getName() == null) return false;
        kitMap.put(kit.getName().toLowerCase(Locale.ROOT), kit);
        saveKitsAsync();
        if (crossServerService != null) {
            crossServerService.broadcastReload();
        }
        return true;
    }

    public dev.lyy.friendlycore.modules.kits.gui.editor.KitEditorManager getEditorManager() {
        return editorManager;
    }

    public static long parseDurationSeconds(String input) {
        if (input == null || input.trim().isEmpty() || input.equalsIgnoreCase("none") || input.equalsIgnoreCase("0") || input.equalsIgnoreCase("free")) {
            return 0L;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        try {
            return Math.max(0L, Long.parseLong(s));
        } catch (NumberFormatException ignored) {}

        long totalSeconds = 0L;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)");
        java.util.regex.Matcher matcher = pattern.matcher(s);
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            if (unit.startsWith("d")) {
                totalSeconds += (long) (value * 86400.0);
            } else if (unit.startsWith("h")) {
                totalSeconds += (long) (value * 3600.0);
            } else if (unit.startsWith("m")) {
                totalSeconds += (long) (value * 60.0);
            } else if (unit.startsWith("s")) {
                totalSeconds += (long) value;
            }
        }
        return matched ? Math.max(0L, totalSeconds) : 0L;
    }

    public static String formatDurationDetailed(long seconds) {
        if (seconds <= 0) return "None (Instant)";
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0) sb.append(secs).append("s");
        if (sb.isEmpty()) return "0s";
        return sb.toString().trim();
    }

    public boolean deleteKit(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        Kit removed = kitMap.remove(key);
        if (removed != null) {
            saveKitsAsync();
            if (crossServerService != null) {
                crossServerService.broadcastReload();
            }
            return true;
        }
        return false;
    }

    public PlayerKitData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    public CompletableFuture<PlayerKitData> loadPlayer(UUID uuid, String username) {
        Object loadToken = new Object();
        loadTokens.put(uuid, loadToken);
        return DatabaseReadRetry.load(plugin, () -> plugin.getDatabaseManager().loadPlayerKitData(uuid, username),
                () -> loadTokens.get(uuid) == loadToken).thenApply(data -> {
            if (loadTokens.get(uuid) != loadToken) {
                return null;
            }
            playerDataCache.compute(uuid, (k, existing) -> {
                if (existing == null) return data;
                synchronized (existing) {
                    if (username != null && !username.equalsIgnoreCase(existing.getUsername())) {
                        existing.setUsername(username);
                    }
                    for (PlayerKitRecord r : data.getRecords().values()) {
                        existing.addRecord(r);
                    }
                    existing.markSaved(existing.getCurrentVersion());
                    return existing;
                }
            });
            return playerDataCache.get(uuid);
        });
    }

    public void uncachePlayer(UUID uuid) {
        loadTokens.remove(uuid);
        Set<PendingClaim> pending = inFlightClaims.get(uuid);
        if (pending != null && !pending.isEmpty()) {
            CompletableFuture<?>[] completions = pending.stream()
                    .map(claim -> {
                        failPendingClaim(claim, null, "<red>Kit delivery was cancelled because you disconnected.", null);
                        return claim.result;
                    })
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(completions).whenComplete((ignored, failure) -> uncachePlayerNow(uuid));
            return;
        }
        uncachePlayerNow(uuid);
    }

    private void uncachePlayerNow(UUID uuid) {
        PlayerKitData data = playerDataCache.remove(uuid);
        if (data != null && data.isDirty()) {
            CompletableFuture<Void> save = plugin.getDatabaseManager().savePlayerKitData(data);
            if (save != null) {
                inFlightSaves.add(save);
                save.whenComplete((r, e) -> inFlightSaves.remove(save));
            }
        }
    }

    public CompletableFuture<Void> flushDirtyPlayerKitData() {
        List<PlayerKitData> dirty = new ArrayList<>();
        for (PlayerKitData data : playerDataCache.values()) {
            if (data.isDirty()) dirty.add(data);
        }
        if (!dirty.isEmpty()) {
            return plugin.getDatabaseManager().savePlayerKitDataBatch(dirty);
        }
        return CompletableFuture.completedFuture(null);
    }

    public void resetKit(String kitName, UUID uuid) {
        if (kitName == null) return;
        resetLocalCache(kitName, uuid);
        plugin.getDatabaseManager().resetPlayerKitData(kitName, uuid);
        if (crossServerService != null) {
            crossServerService.broadcastReset(kitName, uuid, uuid != null ? null : "*");
        }
    }

    public void resetLocalCache(String kitName, UUID uuid) {
        if (kitName == null) return;
        if (uuid != null) {
            PlayerKitData data = getPlayerData(uuid);
            if (data != null) {
                synchronized (data) {
                    data.resetKit(kitName);
                    data.markSaved(data.getCurrentVersion());
                }
            }
        } else {
            resetLocalCacheForAll(kitName);
        }
    }

    public void resetLocalCacheForAll(String kitName) {
        if (kitName == null) return;
        for (PlayerKitData data : playerDataCache.values()) {
            synchronized (data) {
                data.resetKit(kitName);
                data.markSaved(data.getCurrentVersion());
            }
        }
    }

    public void updateLocalRecord(UUID uuid, String kitName, long cooldownUntil, boolean oneTimeClaimed, boolean bought) {
        if (uuid == null || kitName == null) return;
        PlayerKitData data = playerDataCache.computeIfAbsent(uuid, k -> new PlayerKitData(uuid, "Player"));
        synchronized (data) {
            PlayerKitRecord incoming = new PlayerKitRecord(kitName, cooldownUntil, oneTimeClaimed, bought, System.currentTimeMillis());
            data.mergeRecord(incoming);
            data.markSaved(data.getCurrentVersion());
        }
    }

    public void updateLocalCooldown(UUID uuid, String kitName, long cooldownUntil) {
        if (uuid == null || kitName == null) return;
        PlayerKitData data = playerDataCache.computeIfAbsent(uuid, k -> new PlayerKitData(uuid, "Player"));
        synchronized (data) {
            data.setCooldownUntil(kitName, cooldownUntil);
            data.markSaved(data.getCurrentVersion());
        }
    }

    public void cleanupOfflineData() {
        playerDataCache.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            PlayerKitData data = entry.getValue();
            if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.getPlayer(uuid) != null) return false;
            if (data.isDirty()) return false;
            return true;
        });
    }

    public void resetKitForAll(String kitName) {
        resetKit(kitName, null);
    }

    public dev.lyy.friendlycore.modules.kits.cross.KitCrossServerService getCrossServerService() {
        return crossServerService;
    }

    /**
     * Backward-compatible giveKit method.
     */
    public ClaimResult giveKit(Player player, Kit kit, boolean checkCooldown) {
        return claimKit(player, kit, !checkCooldown, false);
    }

    /**
     * Backward-compatible synchronous facade. Delivery is asynchronous even on
     * standard Paper, so pending delivery is deliberately not reported as a
     * completed success. New callers should use {@link #claimKitAsync}.
     */
    public ClaimResult claimKit(Player player, Kit kit, boolean ignoreRestrictions, boolean requireConfirmation) {
        CompletableFuture<ClaimResult> future = claimKitAsync(player, kit, ignoreRestrictions, requireConfirmation);
        return future.getNow(ClaimResult.error(ClaimResult.Status.LOCKED,
                "<yellow>Kit delivery is in progress.", kit));
    }

    /**
     * Claims a kit and completes only after inventory delivery succeeds or the
     * reservation has been compensated.
     */
    public CompletableFuture<ClaimResult> claimKitAsync(Player player, Kit kit,
                                                         boolean ignoreRestrictions,
                                                         boolean requireConfirmation) {
        if (player == null || kit == null) {
            return CompletableFuture.completedFuture(ClaimResult.error(
                    ClaimResult.Status.ERROR, "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɪɴᴠᴀʟɪᴅ ᴘʟᴀʏᴇʀ ᴏʀ ᴋɪᴛ.", kit));
        }

        if (plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            return CompletableFuture.completedFuture(ClaimResult.error(ClaimResult.Status.LOCKED,
                    "<gradient:#FFD166:#FFB347><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜʀ ᴀᴄᴄᴏᴜɴᴛ ᴅᴀᴛᴀ ɪѕ ѕʏɴᴄʜʀᴏɴɪᴢɪɴɢ. ᴘʟᴇᴀѕᴇ ᴡᴀɪᴛ ᴀ ᴍᴏᴍᴇɴᴛ.", kit));
        }

        PlayerKitData data = playerDataCache.get(player.getUniqueId());
        if (data == null) {
            return CompletableFuture.completedFuture(ClaimResult.error(ClaimResult.Status.LOCKED,
                    "<gradient:#FFD166:#FFB347><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜʀ ᴋɪᴛ ᴅᴀᴛᴀ ɪѕ ѕᴛɪʟʟ ʟᴏᴀᴅɪɴɢ. ᴘʟᴇᴀѕᴇ ᴛʀʏ ᴀɢᴀɪɴ ɪɴ ᴀ ᴍᴏᴍᴇɴᴛ.", kit));
        }

        ClaimPurchase purchase = ClaimPurchase.none();
        boolean reserveOneTime = false;
        long proposedCooldownUntil = 0L;

        if (!ignoreRestrictions) {
            synchronized (data) {
                if (!kit.hasPermission(player)) {
                    executeErrorActions(player, kit);
                    return CompletableFuture.completedFuture(ClaimResult.error(ClaimResult.Status.NO_PERMISSION,
                            plugin.getConfigService().getMessages().getString("kits.no-permission",
                                    "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ғᴏʀ ᴋɪᴛ <color:#E7B85C>%kit%</color>."), kit));
                }

                reserveOneTime = kit.isOneTime() && !hasOneTimeBypass(player, kit);
                if (reserveOneTime && data.isOneTimeClaimed(kit.getName())) {
                    executeErrorActions(player, kit);
                    return CompletableFuture.completedFuture(ClaimResult.error(
                            ClaimResult.Status.ONE_TIME_ALREADY_CLAIMED,
                            plugin.getConfigService().getMessages().getString("kits.one-time-error",
                                    "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴄᴀɴ ᴏɴʟʏ ᴄʟᴀɪᴍ ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴏɴᴄᴇ!"), kit));
                }

                if (kit.getCooldownSeconds() > 0 && !hasCooldownBypass(player, kit)) {
                    long currentCooldown = data.getCooldownUntil(kit.getName());
                    if (currentCooldown > System.currentTimeMillis()) {
                        executeErrorActions(player, kit);
                        return CompletableFuture.completedFuture(cooldownResult(kit, currentCooldown));
                    }
                    proposedCooldownUntil = System.currentTimeMillis() + (kit.getCooldownSeconds() * 1000L);
                }

                KitRequirements req = kit.getRequirements();
                if (req != null && (req.getPrice() > 0 || !req.getExtraRequirements().isEmpty())) {
                    boolean alreadyBought = req.isOneTimeRequirements() && data.isBought(kit.getName());
                    if (!alreadyBought) {
                        boolean bypassReq = hasRequirementsBypass(player, kit);
                        boolean bypassMoney = hasMoneyBypass(player, kit);
                        if (!bypassReq && !requirementChecker.meetsRequirements(player, req)) {
                            executeErrorActions(player, kit);
                            return CompletableFuture.completedFuture(ClaimResult.error(
                                    ClaimResult.Status.REQUIREMENTS_NOT_MET,
                                    plugin.getConfigService().getMessages().getString("kits.requirements-error",
                                            "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ᴍᴇᴇᴛ ᴛʜᴇ ʀᴇǫᴜɪʀᴇᴍᴇɴᴛѕ ᴛᴏ ᴄʟᴀɪᴍ ᴋɪᴛ <color:#E7B85C>%kit%</color>."), kit));
                        }
                        if (requireConfirmation && req.getPrice() > 0 && !bypassMoney) {
                            return CompletableFuture.completedFuture(ClaimResult.proceedToBuy(kit));
                        }
                        double price = bypassMoney ? 0.0 : Math.max(0.0, req.getPrice());
                        purchase = new ClaimPurchase(price, req.isOneTimeRequirements(),
                                req.getActionsOnBuy() != null ? List.copyOf(req.getActionsOnBuy()) : List.of());
                    }
                }
            }
        }

        final ClaimPurchase finalPurchase = purchase;
        final boolean finalReserveOneTime = reserveOneTime;
        final long finalCooldownUntil = proposedCooldownUntil;
        final String inFlightKey = player.getUniqueId() + "\u0000" + kit.getName().toLowerCase(Locale.ROOT);
        if (inFlightClaimKeys.putIfAbsent(inFlightKey, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(ClaimResult.error(ClaimResult.Status.LOCKED,
                    "<gradient:#FFD166:#FFB347><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜɪѕ ᴋɪᴛ ᴄʟᴀɪᴍ ɪѕ ᴀʟʀᴇᴀᴅʏ ʙᴇɪɴɢ ᴅᴇʟɪᴠᴇʀᴇᴅ.", kit));
        }

        if (ignoreRestrictions || plugin.getDatabaseManager() == null
                || (finalCooldownUntil <= 0 && !finalReserveOneTime)) {
            return scheduleReservedDelivery(player, kit, data, ignoreRestrictions, finalPurchase,
                    null, finalCooldownUntil, finalReserveOneTime, inFlightKey);
        }

        return plugin.getDatabaseManager().reserveKitClaim(player.getUniqueId(), player.getName(),
                        kit.getName(), finalCooldownUntil, finalReserveOneTime)
                .handle((reservation, failure) -> {
                    if (failure != null || reservation == null) {
                        inFlightClaimKeys.remove(inFlightKey);
                        plugin.getLogger().log(Level.SEVERE, "Failed to reserve kit claim for " + player.getName(), failure);
                        return CompletableFuture.completedFuture(ClaimResult.error(ClaimResult.Status.ERROR,
                                "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴄʟᴀɪᴍ ᴄᴏᴜʟᴅ ɴᴏᴛ ʙᴇ ʀᴇѕᴇʀᴠᴇᴅ. ᴘʟᴇᴀѕᴇ ᴛʀʏ ᴀɢᴀɪɴ.", kit));
                    }
                    if (!reservation.reserved()) {
                        inFlightClaimKeys.remove(inFlightKey);
                        return CompletableFuture.completedFuture(mapReservationFailure(kit, reservation));
                    }
                    return scheduleReservedDelivery(player, kit, data, false, finalPurchase,
                            reservation.token(), reservation.reservedCooldownUntil(),
                            reservation.reservedOneTimeClaimed(), inFlightKey);
                }).thenCompose(future -> future);
    }

    private CompletableFuture<ClaimResult> scheduleReservedDelivery(Player player, Kit kit,
                                                                      PlayerKitData data,
                                                                      boolean ignoreRestrictions,
                                                                      ClaimPurchase purchase,
                                                                      String reservationToken,
                                                                      long cooldownUntil,
                                                                      boolean oneTimeClaimed,
                                                                      String inFlightKey) {
        PlayerKitRecord previousRecord;
        synchronized (data) {
            PlayerKitRecord current = data.getRecord(kit.getName());
            previousRecord = current != null ? current.clone() : null;
            if (!ignoreRestrictions) {
                if (oneTimeClaimed) data.setOneTimeClaimed(kit.getName(), true);
                if (cooldownUntil > 0) data.setCooldownUntil(kit.getName(), cooldownUntil);
            }
        }

        PendingClaim pending = new PendingClaim(player, kit, data, previousRecord,
                ignoreRestrictions, reservationToken, purchase, inFlightKey);
        inFlightClaims.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(pending);

        Object task = plugin.getScheduler().runEntity(player,
                () -> deliverReservedClaim(pending),
                () -> failPendingClaim(pending, null,
                        "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴅᴇʟɪᴠᴇʀʏ ᴡᴀѕ ᴄᴀɴᴄᴇʟʟᴇᴅ ʙᴇᴄᴀᴜѕᴇ ʏᴏᴜ ᴅɪѕᴄᴏɴɴᴇᴄᴛᴇᴅ.", null));
        if (task == null) {
            failPendingClaim(pending, null, "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴅᴇʟɪᴠᴇʀʏ ᴄᴏᴜʟᴅ ɴᴏᴛ ʙᴇ ѕᴄʜᴇᴅᴜʟᴇᴅ.", null);
        }
        return pending.result;
    }

    private void deliverReservedClaim(PendingClaim pending) {
        Player player = pending.player;
        Kit kit = pending.kit;
        if (!player.isOnline()) {
            failPendingClaim(pending, null,
                    "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴅᴇʟɪᴠᴇʀʏ ᴡᴀѕ ᴄᴀɴᴄᴇʟʟᴇᴅ ʙᴇᴄᴀᴜѕᴇ ʏᴏᴜ ᴅɪѕᴄᴏɴɴᴇᴄᴛᴇᴅ.", null);
            return;
        }

        InventorySnapshot snapshot = null;
        try {
            List<PreparedKitItem> prepared = prepareKitItems(player, kit);
            if (prepared.isEmpty() && !kit.getKitItems().isEmpty()) {
                throw new IllegalArgumentException("Kit contains no deliverable items");
            }

            PlayerInventory inventory = player.getInventory();
            snapshot = InventorySnapshot.capture(inventory);
            if (kit.isClearInventory()) inventory.clear();

            DeliveryOutcome delivery = applyPreparedItems(inventory, prepared, kit);
            if (delivery.hadOverflow() && !dropItemsIfFull) {
                throw new NoInventorySpaceException();
            }

            if (pending.purchase.price() > 0) {
                if (!requirementChecker.chargePrice(player, pending.purchase.price())) {
                    throw new PriceChargeException();
                }
                pending.charged.set(true);
            }

            if (!pending.ignoreRestrictions && pending.purchase.markBought()) {
                synchronized (pending.data) {
                    pending.data.setBought(kit.getName(), true);
                }
            }

            Location location = player.getLocation();
            if (!commitPendingClaim(pending)) {
                restoreInventory(snapshot, player);
                return;
            }
            snapshot = null;

            // No post-commit side effect may roll the inventory transaction back.
            try {
                if (delivery.hadOverflow()) {
                    for (ItemStack drop : delivery.overflow()) {
                        try {
                            player.getWorld().dropItemNaturally(location, drop);
                        } catch (Throwable dropFailure) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Failed to drop overflow from kit '" + kit.getName() + "'", dropFailure);
                        }
                    }
                }
                actionExecutor.executeActions(player, kit.getClaimActions(), true, kit);
                for (String action : pending.purchase.actionsOnBuy()) {
                    actionExecutor.executeAction(player, action, kit);
                }
                actionExecutor.executeActions(player, kit.getClaimActions(), false, kit);

                player.playSound(location, Sound.ITEM_ARMOR_EQUIP_DIAMOND, 1.0f, 1.0f);
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("kits.kit-received",
                                "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ʀᴇᴄᴇɪᴠᴇᴅ ᴋɪᴛ <color:#E7B85C>%kit%</color>!"), Map.of("%kit%", kit.getName()));
                if (delivery.hadOverflow()) {
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("kits.inventory-full-dropped",
                                    "<gradient:#FFD166:#FFB347><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜʀ ɪɴᴠᴇɴᴛᴏʀʏ ᴡᴀѕ ғᴜʟʟ. ѕᴏᴍᴇ ɪᴛᴇᴍѕ ᴅʀᴏᴘᴘᴇᴅ ᴀᴛ ʏᴏᴜʀ ғᴇᴇᴛ."), null);
                }
                if (closeInventoryOnClaim) player.closeInventory();
            } catch (Throwable postCommitFailure) {
                plugin.getLogger().log(Level.SEVERE, "Post-commit actions failed for kit '"
                        + kit.getName() + "' and player " + player.getName(), postCommitFailure);
            }
            pending.result.complete(ClaimResult.success(kit));
        } catch (PriceChargeException failure) {
            restoreInventory(snapshot, player);
            failPendingClaim(pending, null,
                    plugin.getConfigService().getMessages().getString("kits.not-enough-money",
                            "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɴᴏᴛ ᴇɴᴏᴜɢʜ ᴍᴏɴᴇʏ ᴛᴏ ᴘᴜʀᴄʜᴀѕᴇ ᴋɪᴛ <color:#E7B85C>%kit%</color> (<color:#8DE969>$%price%</color>).")
                            .replace("%price%", String.valueOf(pending.purchase.price())), failure);
        } catch (NoInventorySpaceException failure) {
            restoreInventory(snapshot, player);
            failPendingClaim(pending, null,
                    plugin.getConfigService().getMessages().getString("kits.inventory-full-lost",
                            "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜʀ ɪɴᴠᴇɴᴛᴏʀʏ ᴡᴀѕ ғᴜʟʟ. ᴛʜᴇ ᴋɪᴛ ᴡᴀѕ ɴᴏᴛ ᴅᴇʟɪᴠᴇʀᴇᴅ."), failure);
        } catch (Throwable failure) {
            restoreInventory(snapshot, player);
            failPendingClaim(pending, null, "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴅᴇʟɪᴠᴇʀʏ ғᴀɪʟᴇᴅ. ᴘʟᴇᴀѕᴇ ᴛʀʏ ᴀɢᴀɪɴ.", failure);
        }
    }

    private List<PreparedKitItem> prepareKitItems(Player player, Kit kit) {
        List<PreparedKitItem> prepared = new ArrayList<>();
        for (KitItem kitItem : kit.getKitItems()) {
            if (kitItem == null) continue;
            if (kitItem.getOriginalItem() == null) {
                String id = kitItem.getId();
                if (id == null || Material.matchMaterial(id) == null) {
                    throw new IllegalArgumentException("Unknown kit material: " + id);
                }
            }

            ItemStack prototype = KitItemParser.toItemStack(kitItem, player, kit);
            if (prototype == null || prototype.getType().isAir()) continue;
            prototype.setAmount(1);

            int requested = Math.max(1, kitItem.getAmount());
            if (requested > MAX_AMOUNT_PER_KIT_ITEM) {
                plugin.getLogger().warning("Capped kit item '" + kitItem.getId() + "' in kit '"
                        + kit.getName() + "' from " + requested + " to " + MAX_AMOUNT_PER_KIT_ITEM);
                requested = MAX_AMOUNT_PER_KIT_ITEM;
            }

            KitItem.EquipmentSlot slot = kitItem.getEquipmentSlot();
            if (slot == null && kitItem.isOffhand()) slot = KitItem.EquipmentSlot.OFF_HAND;
            prepared.add(new PreparedKitItem(prototype, requested, slot));
        }
        return prepared;
    }

    private DeliveryOutcome applyPreparedItems(PlayerInventory inventory,
                                                List<PreparedKitItem> prepared,
                                                Kit kit) {
        List<ItemStack> overflow = new ArrayList<>();
        EnumSet<KitItem.EquipmentSlot> assignedSlots = EnumSet.noneOf(KitItem.EquipmentSlot.class);

        for (PreparedKitItem preparedItem : prepared) {
            int remaining = preparedItem.amount();
            KitItem.EquipmentSlot requestedSlot = preparedItem.equipmentSlot();
            if (requestedSlot == null && kit.isAutoArmor()) {
                requestedSlot = inferLegacyEquipmentSlot(preparedItem.prototype().getType());
            }

            if (requestedSlot != null && !assignedSlots.contains(requestedSlot)
                    && isEquipmentSlotEmpty(inventory, requestedSlot)) {
                ItemStack equipped = preparedItem.prototype().clone();
                equipped.setAmount(1);
                setEquipment(inventory, requestedSlot, equipped);
                assignedSlots.add(requestedSlot);
                remaining--;
            }

            int maxStack = Math.max(1, preparedItem.prototype().getMaxStackSize());
            while (remaining > 0) {
                ItemStack stack = preparedItem.prototype().clone();
                int stackAmount = Math.min(remaining, maxStack);
                stack.setAmount(stackAmount);
                remaining -= stackAmount;
                overflow.addAll(inventory.addItem(stack).values());
            }
        }
        return new DeliveryOutcome(!overflow.isEmpty(), overflow);
    }

    public static KitItem.EquipmentSlot inferLegacyEquipmentSlot(Material material) {
        if (material == null) return null;
        String id = material.name();
        if (id.endsWith("_HELMET") || material == Material.PLAYER_HEAD
                || material == Material.CARVED_PUMPKIN
                || id.endsWith("_SKULL") || id.endsWith("_HEAD")) {
            return KitItem.EquipmentSlot.HEAD;
        }
        if (id.endsWith("_CHESTPLATE") || material == Material.ELYTRA) {
            return KitItem.EquipmentSlot.CHEST;
        }
        if (id.endsWith("_LEGGINGS")) return KitItem.EquipmentSlot.LEGS;
        if (id.endsWith("_BOOTS")) return KitItem.EquipmentSlot.FEET;
        return null;
    }

    private static boolean isEquipmentSlotEmpty(PlayerInventory inventory, KitItem.EquipmentSlot slot) {
        ItemStack current = switch (slot) {
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            case OFF_HAND -> inventory.getItemInOffHand();
        };
        return current == null || current.getType().isAir();
    }

    private static void setEquipment(PlayerInventory inventory, KitItem.EquipmentSlot slot, ItemStack item) {
        switch (slot) {
            case HEAD -> inventory.setHelmet(item);
            case CHEST -> inventory.setChestplate(item);
            case LEGS -> inventory.setLeggings(item);
            case FEET -> inventory.setBoots(item);
            case OFF_HAND -> inventory.setItemInOffHand(item);
        }
    }

    private boolean commitPendingClaim(PendingClaim pending) {
        if (!pending.terminal.compareAndSet(false, true)) return false;
        removeInFlight(pending);

        if (!pending.ignoreRestrictions) {
            persistCompletedClaim(pending.player, pending.kit, pending.data);
            if (pending.reservationToken != null && plugin.getDatabaseManager() != null) {
                plugin.getDatabaseManager().completeKitClaimReservation(pending.player.getUniqueId(),
                                pending.kit.getName(), pending.reservationToken)
                        .whenComplete((completed, failure) -> {
                            if (failure != null || !Boolean.TRUE.equals(completed)) {
                                plugin.getLogger().log(Level.SEVERE,
                                        "Failed to complete kit reservation for " + pending.player.getUniqueId(), failure);
                            }
                        });
            }
        }
        return true;
    }

    private void failPendingClaim(PendingClaim pending, InventorySnapshot snapshot,
                                  String message, Throwable failure) {
        if (!pending.terminal.compareAndSet(false, true)) return;
        if (snapshot != null) restoreInventory(snapshot, pending.player);

        if (pending.charged.getAndSet(false) && !refundPrice(pending.player, pending.purchase.price())) {
            plugin.getLogger().severe("Failed to refund " + pending.purchase.price() + " for aborted kit '"
                    + pending.kit.getName() + "' to " + pending.player.getUniqueId());
        }

        CompletableFuture<Boolean> rollback;
        if (!pending.ignoreRestrictions && pending.reservationToken != null
                && plugin.getDatabaseManager() != null) {
            rollback = plugin.getDatabaseManager().rollbackKitClaimReservation(
                    pending.player.getUniqueId(), pending.kit.getName(), pending.reservationToken);
        } else {
            rollback = CompletableFuture.completedFuture(true);
        }

        rollback.handle((rolledBack, rollbackFailure) -> {
            if (rollbackFailure != null || !Boolean.TRUE.equals(rolledBack)) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to rollback kit reservation for " + pending.player.getUniqueId(), rollbackFailure);
            }
            rollbackClaimReservation(pending.data, pending.kit.getName(),
                    pending.previousRecord, pending.ignoreRestrictions);
            removeInFlight(pending);
            if (failure != null && !(failure instanceof NoInventorySpaceException)
                    && !(failure instanceof PriceChargeException)) {
                plugin.getLogger().log(Level.SEVERE, "Failed to deliver kit '" + pending.kit.getName()
                        + "' to " + pending.player.getName(), failure);
            }
            ClaimResult.Status status = failure instanceof NoInventorySpaceException
                    ? ClaimResult.Status.NO_SPACE
                    : failure instanceof PriceChargeException
                    ? ClaimResult.Status.REQUIREMENTS_NOT_MET : ClaimResult.Status.ERROR;
            pending.result.complete(ClaimResult.error(status, message, pending.kit));
            return null;
        });
    }

    private void removeInFlight(PendingClaim pending) {
        Set<PendingClaim> claims = inFlightClaims.get(pending.player.getUniqueId());
        if (claims != null) {
            claims.remove(pending);
            if (claims.isEmpty()) inFlightClaims.remove(pending.player.getUniqueId(), claims);
        }
        inFlightClaimKeys.remove(pending.inFlightKey);
    }

    private boolean refundPrice(Player player, double amount) {
        if (amount <= 0 || player == null) return true;
        if (plugin.getHookManager().isVaultEconomyAvailable()) {
            net.milkbowl.vault.economy.Economy economy = plugin.getHookManager().getVaultEconomy();
            if (economy == null) return false;
            net.milkbowl.vault.economy.EconomyResponse response = economy.depositPlayer(player, amount);
            return response != null && response.transactionSuccess();
        }
        return plugin.getEconomyManager() != null
                && plugin.getEconomyManager().deposit(player.getUniqueId(), amount, true);
    }

    private ClaimResult mapReservationFailure(Kit kit, KitClaimReservation reservation) {
        return switch (reservation.status()) {
            case COOLDOWN_ACTIVE -> cooldownResult(kit, reservation.reservedCooldownUntil());
            case ONE_TIME_CLAIMED -> ClaimResult.error(ClaimResult.Status.ONE_TIME_ALREADY_CLAIMED,
                    plugin.getConfigService().getMessages().getString("kits.one-time-error",
                            "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴄᴀɴ ᴏɴʟʏ ᴄʟᴀɪᴍ ᴋɪᴛ <color:#E7B85C>%kit%</color> ᴏɴᴄᴇ!"), kit);
            case IN_FLIGHT -> ClaimResult.error(ClaimResult.Status.LOCKED,
                    "<gradient:#FFD166:#FFB347><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜɪѕ ᴋɪᴛ ᴄʟᴀɪᴍ ɪѕ ᴀʟʀᴇᴀᴅʏ ʙᴇɪɴɢ ᴅᴇʟɪᴠᴇʀᴇᴅ.", kit);
            default -> ClaimResult.error(ClaimResult.Status.ERROR,
                    "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴄʟᴀɪᴍ ᴄᴏᴜʟᴅ ɴᴏᴛ ʙᴇ ʀᴇѕᴇʀᴠᴇᴅ. ᴘʟᴇᴀѕᴇ ᴛʀʏ ᴀɢᴀɪɴ.", kit);
        };
    }

    private ClaimResult cooldownResult(Kit kit, long cooldownUntil) {
        long remainingSeconds = Math.max(0L, (cooldownUntil - System.currentTimeMillis() + 999L) / 1000L);
        String message = plugin.getConfigService().getMessages().getString("kits.kit-cooldown",
                "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴋɪᴛ ᴏɴ ᴄᴏᴏʟᴅᴏᴡɴ! ᴡᴀɪᴛ <color:#E7B85C>%time%</color>.").replace("%time%", formatCooldown(remainingSeconds));
        return ClaimResult.error(ClaimResult.Status.COOLDOWN, message, kit);
    }

    private void executeErrorActions(Player player, Kit kit) {
        actionExecutor.executeActions(player, kit.getErrorActions(), false, kit);
    }

    private static void restoreInventory(InventorySnapshot snapshot, Player player) {
        if (snapshot == null || player == null || !player.isOnline()) return;
        snapshot.restore(player.getInventory());
    }

    private void persistCompletedClaim(Player player, Kit kit, PlayerKitData data) {
        if (crossServerService != null) {
            long cdUntil = data.getCooldownUntil(kit.getName());
            boolean oneTime = data.isOneTimeClaimed(kit.getName());
            boolean bought = data.isBought(kit.getName());
            crossServerService.broadcastClaim(player.getUniqueId(), player.getName(), kit.getName(), cdUntil, oneTime, bought);
        }

        if (plugin.getDatabaseManager() != null) {
            plugin.getDatabaseManager().savePlayerKitData(data);
        }
    }

    private void rollbackClaimReservation(PlayerKitData data, String kitName,
                                          PlayerKitRecord previousRecord, boolean ignoreRestrictions) {
        if (ignoreRestrictions) return;
        synchronized (data) {
            data.resetKit(kitName);
            if (previousRecord != null) {
                data.addRecord(previousRecord);
            }
        }
    }

    public void giveFirstJoinKit(Player player) {
        if (player == null || !player.isOnline()) return;
        if (firstJoinKit == null || firstJoinKit.equalsIgnoreCase("none") || firstJoinKit.isEmpty()) return;

        // Persistent check to ensure first join kit is only given once across proxy network
        if (firstJoinKitKey != null) {
            org.bukkit.persistence.PersistentDataContainer pdc = player.getPersistentDataContainer();
            if (pdc.has(firstJoinKitKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                return;
            }
        }

        PlayerKitData data = getPlayerData(player.getUniqueId());
        if (data != null && (data.isOneTimeClaimed(firstJoinKit) || data.getCooldownUntil(firstJoinKit) > 0)) {
            if (firstJoinKitKey != null) {
                player.getPersistentDataContainer().set(firstJoinKitKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            }
            return;
        }

        Kit kit = getKit(firstJoinKit);
        if (kit != null) {
            claimKitAsync(player, kit, true, false).thenAccept(result -> {
                if (!result.isSuccess() || firstJoinKitKey == null) return;
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        player.getPersistentDataContainer().set(firstJoinKitKey,
                                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                    }
                });
            });
        }
    }

    public String formatCooldown(long seconds) {
        if (seconds <= 0) return plugin.getConfigService().getMessages().getString("kits.ready", "Ready");
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(secs).append("s");
        return sb.toString().trim();
    }

    public KitActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    public KitRequirementChecker getRequirementChecker() {
        return requirementChecker;
    }

    public boolean isKitPreviewEnabled() {
        return kitPreviewEnabled;
    }

    public boolean isKitPreviewRequiresPermission() {
        return kitPreviewRequiresPermission;
    }

    public boolean hasCooldownBypass(Player player, Kit kit) {
        if (player == null) return true;
        String name = kit != null ? kit.getName().toLowerCase(Locale.ROOT) : "";
        return player.hasPermission("friendlycore.admin")
                || player.hasPermission("friendlycore.kits.admin")
                || player.hasPermission("playerkits.admin")
                || player.hasPermission("friendlycore.kits.bypass.cooldown")
                || player.hasPermission("playerkits.bypass.cooldown")
                || player.hasPermission("friendlycore.kits.bypass.cooldown." + name)
                || player.hasPermission("playerkits.bypass.cooldown." + name);
    }

    public boolean hasOneTimeBypass(Player player, Kit kit) {
        if (player == null) return true;
        String name = kit != null ? kit.getName().toLowerCase(Locale.ROOT) : "";
        return player.hasPermission("friendlycore.admin")
                || player.hasPermission("friendlycore.kits.admin")
                || player.hasPermission("playerkits.admin")
                || player.hasPermission("friendlycore.kits.bypass.onetime")
                || player.hasPermission("playerkits.bypass.onetime")
                || player.hasPermission("friendlycore.kits.bypass.onetime." + name)
                || player.hasPermission("playerkits.bypass.onetime." + name);
    }

    public boolean hasMoneyBypass(Player player, Kit kit) {
        if (player == null) return true;
        String name = kit != null ? kit.getName().toLowerCase(Locale.ROOT) : "";
        return player.hasPermission("friendlycore.admin")
                || player.hasPermission("friendlycore.kits.admin")
                || player.hasPermission("playerkits.admin")
                || player.hasPermission("friendlycore.kits.bypass.money")
                || player.hasPermission("playerkits.bypass.money")
                || player.hasPermission("friendlycore.kits.bypass.money." + name)
                || player.hasPermission("playerkits.bypass.money." + name);
    }

    public boolean hasRequirementsBypass(Player player, Kit kit) {
        if (player == null) return true;
        String name = kit != null ? kit.getName().toLowerCase(Locale.ROOT) : "";
        return player.hasPermission("friendlycore.admin")
                || player.hasPermission("friendlycore.kits.admin")
                || player.hasPermission("playerkits.admin")
                || player.hasPermission("friendlycore.kits.bypass.requirements")
                || player.hasPermission("playerkits.bypass.requirements")
                || player.hasPermission("friendlycore.kits.bypass.requirements." + name)
                || player.hasPermission("playerkits.bypass.requirements." + name);
    }

    private record ClaimPurchase(double price, boolean markBought, List<String> actionsOnBuy) {
        private static ClaimPurchase none() {
            return new ClaimPurchase(0.0, false, List.of());
        }
    }

    private record PreparedKitItem(ItemStack prototype, int amount,
                                   KitItem.EquipmentSlot equipmentSlot) {
    }

    private record DeliveryOutcome(boolean hadOverflow, List<ItemStack> overflow) {
    }

    private static final class PendingClaim {
        private final Player player;
        private final Kit kit;
        private final PlayerKitData data;
        private final PlayerKitRecord previousRecord;
        private final boolean ignoreRestrictions;
        private final String reservationToken;
        private final ClaimPurchase purchase;
        private final String inFlightKey;
        private final CompletableFuture<ClaimResult> result = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean charged = new AtomicBoolean(false);

        private PendingClaim(Player player, Kit kit, PlayerKitData data,
                             PlayerKitRecord previousRecord, boolean ignoreRestrictions,
                             String reservationToken, ClaimPurchase purchase, String inFlightKey) {
            this.player = player;
            this.kit = kit;
            this.data = data;
            this.previousRecord = previousRecord;
            this.ignoreRestrictions = ignoreRestrictions;
            this.reservationToken = reservationToken;
            this.purchase = purchase;
            this.inFlightKey = inFlightKey;
        }
    }

    private record InventorySnapshot(ItemStack[] contents) {
        private static InventorySnapshot capture(PlayerInventory inventory) {
            return new InventorySnapshot(cloneContents(inventory.getContents()));
        }

        private void restore(PlayerInventory inventory) {
            inventory.clear();
            inventory.setContents(cloneContents(contents));
        }

        private static ItemStack[] cloneContents(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = source[i] != null ? source[i].clone() : null;
            }
            return copy;
        }
    }

    private static final class NoInventorySpaceException extends RuntimeException {
    }

    private static final class PriceChargeException extends RuntimeException {
    }
}
