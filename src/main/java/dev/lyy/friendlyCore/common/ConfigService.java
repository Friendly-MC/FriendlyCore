package dev.lyy.friendlycore.common;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central configuration manager supporting modular, per-module YAML files.
 * Handles path aliasing, automatic defaults copying, per-module containers,
 * and unified message aggregation across all modules.
 */
public class ConfigService {

    private final Plugin plugin;
    private final ConcurrentHashMap<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File> files = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModuleConfigContainer> moduleContainers = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    /** Files whose YAML failed to parse at first load; never saved so the user's edit isn't overwritten. */
    private final java.util.Set<String> invalidFiles = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> loadErrors = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private volatile MergedMessageConfiguration compositeMessages;

    public ConfigService(Plugin plugin) {
        this.plugin = plugin;
        registerAliases();
        new ConfigMigrator(plugin).migrate();
        loadAll();
    }

    private void registerAliases() {
        aliases.put("messages/general.yml", "messages.yml");
        aliases.put("messages.yml", "messages.yml");

        aliases.put("economy.yml", "modules/economy/config.yml");
        aliases.put("modules/economy.yml", "modules/economy/config.yml");
        aliases.put("messages/economy.yml", "modules/economy/messages.yml");

        aliases.put("kits.yml", "modules/kits/config.yml");
        aliases.put("modules/kits.yml", "modules/kits/config.yml");
        aliases.put("messages/kits.yml", "modules/kits/messages.yml");
        aliases.put("inventories/kits.yml", "modules/kits/inventories/kits.yml");

        aliases.put("nightvision.yml", "modules/nightvision/config.yml");
        aliases.put("modules/nightvision.yml", "modules/nightvision/config.yml");
        aliases.put("messages/nightvision.yml", "modules/nightvision/messages.yml");

        aliases.put("pvp.yml", "modules/pvp/config.yml");
        aliases.put("modules/pvp.yml", "modules/pvp/config.yml");
        aliases.put("messages/pvp.yml", "modules/pvp/messages.yml");

        aliases.put("rtp.yml", "modules/rtp/config.yml");
        aliases.put("modules/rtp.yml", "modules/rtp/config.yml");
        aliases.put("messages/rtp.yml", "modules/rtp/messages.yml");

        aliases.put("spawn.yml", "modules/spawn/config.yml");
        aliases.put("modules/spawn.yml", "modules/spawn/config.yml");
        aliases.put("messages/spawn.yml", "modules/spawn/messages.yml");

        aliases.put("teams.yml", "modules/teams/config.yml");
        aliases.put("modules/teams.yml", "modules/teams/config.yml");
        aliases.put("settings.yml", "modules/teams/config.yml");
        aliases.put("language.yml", "modules/teams/messages.yml");
        aliases.put("messages/teams.yml", "modules/teams/messages.yml");

        aliases.put("tools.yml", "modules/tools/config.yml");
        aliases.put("modules/tools.yml", "modules/tools/config.yml");
        aliases.put("messages/tools.yml", "modules/tools/messages.yml");

        aliases.put("homes.yml", "modules/homes/config.yml");
        aliases.put("modules/homes.yml", "modules/homes/config.yml");
        aliases.put("messages/homes.yml", "modules/homes/messages.yml");
        aliases.put("sounds/homes.yml", "modules/homes/sounds.yml");
        aliases.put("inventories/homes/home.yml", "modules/homes/inventories/home.yml");
        aliases.put("inventories/homes/confirm.yml", "modules/homes/inventories/confirm.yml");

        aliases.put("tpa.yml", "modules/tpa/config.yml");
        aliases.put("modules/tpa.yml", "modules/tpa/config.yml");
        aliases.put("messages/tpa.yml", "modules/tpa/messages.yml");

        aliases.put("joinquit.yml", "modules/joinquit/config.yml");
        aliases.put("modules/joinquit.yml", "modules/joinquit/config.yml");
        aliases.put("messages/joinquit.yml", "modules/joinquit/messages.yml");

        aliases.put("essentials.yml", "modules/essentials/config.yml");
        aliases.put("modules/essentials.yml", "modules/essentials/config.yml");
        aliases.put("admin.yml", "modules/essentials/config.yml");
        aliases.put("messages/essentials.yml", "modules/essentials/messages.yml");
        aliases.put("messages/admin.yml", "modules/essentials/messages.yml");

        aliases.put("inventories/tpa-admin.yml", "modules/tpa/inventories/tpa-admin.yml");
        aliases.put("inventories/tpa-here-inventory.yml", "modules/tpa/inventories/tpa-here-inventory.yml");
        aliases.put("inventories/tpa-inventory.yml", "modules/tpa/inventories/tpa-inventory.yml");
        aliases.put("inventories/tpa-safe-teleport-settings.yml", "modules/tpa/inventories/tpa-safe-teleport-settings.yml");
        aliases.put("inventories/tpa-settings.yml", "modules/tpa/inventories/tpa-settings.yml");

        // Redis is now part of master config.yml
        aliases.put("redis.yml", "config.yml");
        aliases.put("modules/redis.yml", "config.yml");

        aliases.put("pets.yml", "modules/pets/config.yml");
        aliases.put("modules/pets.yml", "modules/pets/config.yml");
        aliases.put("ecopets.yml", "modules/pets/config.yml");
        aliases.put("messages/pets.yml", "modules/pets/messages.yml");
        aliases.put("messages/ecopets.yml", "modules/pets/messages.yml");
        aliases.put("inventories/pets.yml", "modules/pets/inventories/pets.yml");
        aliases.put("inventories/confirm.yml", "modules/pets/inventories/confirm.yml");

        aliases.put("chat.yml", "modules/chat/config.yml");
        aliases.put("modules/chat.yml", "modules/chat/config.yml");
        aliases.put("zelchat.yml", "modules/chat/config.yml");
        aliases.put("messages/chat.yml", "modules/chat/messages.yml");
        aliases.put("messages/zelchat.yml", "modules/chat/messages.yml");

        aliases.put("settings.yml", "modules/settings/config.yml");
        aliases.put("modules/settings.yml", "modules/settings/config.yml");
        aliases.put("playersettings.yml", "modules/settings/config.yml");
        aliases.put("messages/settings.yml", "modules/settings/messages.yml");

        aliases.put("chat-filter.yml", "modules/chat/chat-filter.yml");
        aliases.put("filter-rules.yml", "modules/chat/filter-rules.yml");
        aliases.put("filter-whitelist.yml", "modules/chat/filter-whitelist.yml");
        aliases.put("leetmap.yml", "modules/chat/leetmap.yml");
        aliases.put("placeholders.yml", "modules/chat/placeholders.yml");
        aliases.put("renders-gui.yml", "modules/chat/renders-gui.yml");

        String[] teamGuis = {
                "allies-gui.yml", "bank-gui.yml", "color-gui.yml", "confirm-gui.yml",
                "enderchest-gui.yml", "home-gui.yml", "leaderboard-gui.yml", "mailbox-gui.yml",
                "members-gui.yml", "playerstats-gui.yml", "settings-gui.yml", "stats-gui.yml", "team-gui.yml"
        };
        for (String gui : teamGuis) {
            aliases.put("inventories/teams/" + gui, "modules/teams/inventories/" + gui);
            aliases.put("menu/" + gui, "modules/teams/inventories/" + gui);
        }

        aliases.put("auctions.yml", "modules/auctions/config.yml");
        aliases.put("modules/auctions.yml", "modules/auctions/config.yml");
        aliases.put("messages/auctions.yml", "modules/auctions/messages.yml");
        aliases.put("categories.yml", "modules/auctions/categories.yml");
        aliases.put("discord.yml", "modules/auctions/discord.yml");
        String[] auctionGuis = {
                "main-gui.yml", "my-items-gui.yml", "expired-items-gui.yml", "history-gui.yml",
                "logs-gui.yml", "deleted-gui.yml", "category-gui.yml", "shulker-preview-gui.yml",
                "confirm-purchase-gui.yml", "confirm-listing-gui.yml", "admin-removal-gui.yml"
        };
        for (String gui : auctionGuis) {
            aliases.put("guis/" + gui, "modules/auctions/guis/" + gui);
            aliases.put("modules/auctions/guis/" + gui, "modules/auctions/guis/" + gui);
        }

        aliases.put("orders.yml", "modules/orders/config.yml");
        aliases.put("modules/orders.yml", "modules/orders/config.yml");
        aliases.put("donutorder.yml", "modules/orders/config.yml");
        aliases.put("messages/orders.yml", "modules/orders/messages.yml");
        aliases.put("messages/donutorder.yml", "modules/orders/messages.yml");
        aliases.put("blacklist.yml", "modules/orders/blacklist.yml");
        aliases.put("hooks.yml", "modules/orders/hooks.yml");
        aliases.put("items.yml", "modules/orders/items.yml");
        aliases.put("prices.yml", "modules/orders/prices.yml");
        aliases.put("pricesv2.yml", "modules/orders/pricesv2.yml");
        aliases.put("sounds.yml", "modules/orders/sounds.yml");
        String[] orderGuis = {
                "collect_items.yml", "edit_order.yml", "edit_order_confirm.yml", "new_order.yml",
                "order_delivery.yml", "order_delivery_confirm.yml", "orders.yml", "pick_enchantments.yml",
                "select_item.yml", "your_orders.yml"
        };
        for (String gui : orderGuis) {
            aliases.put("gui/" + gui, "modules/orders/guis/" + gui);
            aliases.put("guis/" + gui, "modules/orders/guis/" + gui);
            aliases.put("modules/orders/guis/" + gui, "modules/orders/guis/" + gui);
        }
    }

    public void loadAll() {
        loadConfig("config.yml");
        loadConfig("messages.yml");

        String[] moduleNames = {
                "economy", "homes", "joinquit", "kits", "nightvision", "pvp", "rtp", "spawn", "teams", "tools", "tpa", "essentials", "pets", "chat", "settings", "auctions", "orders"
        };

        for (String mod : moduleNames) {
            loadConfig("modules/" + mod + "/config.yml");
            loadConfig("modules/" + mod + "/messages.yml");
        }

        loadConfig("modules/chat/chat-filter.yml");
        loadConfig("modules/chat/filter-rules.yml");
        loadConfig("modules/chat/filter-whitelist.yml");
        loadConfig("modules/chat/leetmap.yml");
        loadConfig("modules/chat/placeholders.yml");
        loadConfig("modules/chat/renders-gui.yml");

        // Auction categories and GUIs (only load when module is enabled)
        if (getMainConfig().getBoolean("modules.auctions", false)) {
            loadConfig("modules/auctions/categories.yml");
            loadConfig("modules/auctions/discord.yml");
            String[] auctionGuis = {
                    "main-gui.yml", "my-items-gui.yml", "expired-items-gui.yml", "history-gui.yml",
                    "logs-gui.yml", "deleted-gui.yml", "category-gui.yml", "shulker-preview-gui.yml",
                    "confirm-purchase-gui.yml", "confirm-listing-gui.yml", "admin-removal-gui.yml"
            };
            for (String gui : auctionGuis) {
                loadConfig("modules/auctions/guis/" + gui);
            }
        }

        // Orders configs and GUIs (only load when module is enabled)
        if (getMainConfig().getBoolean("modules.orders", false)) {
            loadConfig("modules/orders/blacklist.yml");
            loadConfig("modules/orders/hooks.yml");
            loadConfig("modules/orders/items.yml");
            loadConfig("modules/orders/prices.yml");
            loadConfig("modules/orders/pricesv2.yml");
            loadConfig("modules/orders/sounds.yml");
            String[] orderGuis = {
                    "collect_items.yml", "edit_order.yml", "edit_order_confirm.yml", "new_order.yml",
                    "order_delivery.yml", "order_delivery_confirm.yml", "orders.yml", "pick_enchantments.yml",
                    "select_item.yml", "your_orders.yml"
            };
            for (String gui : orderGuis) {
                loadConfig("modules/orders/guis/" + gui);
            }
        }

        loadConfig("modules/pets/inventories/pets.yml");
        loadConfig("modules/pets/inventories/confirm.yml");

        loadConfig("modules/homes/sounds.yml");
        loadConfig("modules/homes/inventories/home.yml");
        loadConfig("modules/homes/inventories/confirm.yml");

        loadConfig("modules/kits/inventories/kits.yml");

        loadConfig("modules/tpa/inventories/tpa-admin.yml");
        loadConfig("modules/tpa/inventories/tpa-here-inventory.yml");
        loadConfig("modules/tpa/inventories/tpa-inventory.yml");
        loadConfig("modules/tpa/inventories/tpa-safe-teleport-settings.yml");
        loadConfig("modules/tpa/inventories/tpa-settings.yml");

        String[] teamGuis = {
                "allies-gui.yml", "bank-gui.yml", "color-gui.yml", "confirm-gui.yml",
                "enderchest-gui.yml", "home-gui.yml", "leaderboard-gui.yml", "mailbox-gui.yml",
                "members-gui.yml", "playerstats-gui.yml", "settings-gui.yml", "stats-gui.yml", "team-gui.yml"
        };
        for (String gui : teamGuis) {
            loadConfig("modules/teams/inventories/" + gui);
        }

        rebuildCompositeMessages();
    }

    public synchronized FileConfiguration loadConfig(String fileName) {
        String resolvedPath = resolvePath(fileName);
        File file = new File(plugin.getDataFolder(), resolvedPath);

        if (!file.exists()) {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            try {
                plugin.saveResource(resolvedPath, false);
            } catch (IllegalArgumentException e) {
                // Resource not bundled in JAR, create an empty file
                try {
                    file.createNewFile();
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Could not create " + resolvedPath, ex);
                }
            }
        }

        YamlConfiguration config;
        try {
            config = loadStrict(file);
            invalidFiles.remove(resolvedPath);
        } catch (IOException | InvalidConfigurationException e) {
            String reason = firstLine(e.getMessage());
            loadErrors.add(resolvedPath + ": " + reason);
            FileConfiguration previous = configs.get(resolvedPath);
            if (previous != null) {
                plugin.getLogger().severe("Invalid YAML in " + resolvedPath + "; keeping the last loaded version. " + reason);
                return previous;
            }
            plugin.getLogger().severe("Invalid YAML in " + resolvedPath + "; using bundled defaults until it is fixed. " + reason);
            invalidFiles.add(resolvedPath);
            config = new YamlConfiguration();
        }

        YamlConfiguration defConfig = null;
        InputStream stream = plugin.getResource(resolvedPath);
        if (stream != null) {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                defConfig = YamlConfiguration.loadConfiguration(reader);
                config.setDefaults(defConfig);
            } catch (IOException ignored) {
            }
        }
        if (!invalidFiles.contains(resolvedPath)) {
            boolean removed = removeObsoleteKeys(resolvedPath, config);
            boolean added = defConfig != null && resolvedPath.endsWith("messages.yml") && addMissingMessages(config, defConfig);
            if (removed || added) writeUpdate(resolvedPath, file, config);
        }

        configs.put(resolvedPath, config);
        files.put(resolvedPath, file);
        return config;
    }

    /** Parses a YAML file, throwing instead of silently returning an empty configuration. */
    public static YamlConfiguration loadStrict(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return config;
    }

    /** Keys of removed features, per file; nothing reads them anymore. A "*" segment matches every entry. */
    private static final Map<String, List<String>> OBSOLETE_KEYS = Map.of(
            "modules/rtp/config.yml", List.of("cross-server.end-portal-target", "cross-server.end-portal-world",
                    "worlds.*.portal-arrivals"),
            "modules/rtp/messages.yml", List.of("no-end-permission"));

    private static boolean removeObsoleteKeys(String path, YamlConfiguration config) {
        boolean changed = false;
        for (String key : OBSOLETE_KEYS.getOrDefault(path, List.of())) {
            int star = key.indexOf(".*.");
            List<String> matches = new java.util.ArrayList<>();
            if (star < 0) {
                matches.add(key);
            } else if (config.isConfigurationSection(key.substring(0, star))) {
                for (String entry : config.getConfigurationSection(key.substring(0, star)).getKeys(false)) {
                    matches.add(key.substring(0, star) + "." + entry + key.substring(star + 2));
                }
            }
            for (String match : matches) {
                if (config.contains(match, true)) {
                    config.set(match, null);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Rewrites an updated file: copies the original to a timestamped .bak first, then swaps the new
     * content in atomically, so a crash mid-write never leaves a truncated config.
     */
    private void writeUpdate(String path, File file, YamlConfiguration config) {
        try {
            java.nio.file.Path target = file.toPath();
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            java.nio.file.Path backup = target.resolveSibling(target.getFileName() + ".bak-" + stamp);
            java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            java.nio.file.Files.writeString(temp, config.saveToString(), StandardCharsets.UTF_8);
            try {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.getLogger().info("Updated " + path + " (backup: " + backup.getFileName() + ")");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not update " + path + "; it stays as it was", e);
        }
    }

    /** Adds message keys introduced in newer versions so they can be edited. */
    private static boolean addMissingMessages(YamlConfiguration config, YamlConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.isSet(key) || hasScalarAncestor(config, key)) continue;
            config.set(key, defaults.get(key));
            List<String> comments = defaults.getComments(key);
            if (!comments.isEmpty()) config.setComments(key, comments);
            changed = true;
        }
        return changed;
    }

    /** True when the user replaced a parent section with a plain value; adding children would overwrite it. */
    private static boolean hasScalarAncestor(YamlConfiguration config, String key) {
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (config.isSet(parent) && !config.isConfigurationSection(parent)) return true;
            dot = parent.lastIndexOf('.');
        }
        return false;
    }

    private static String firstLine(String message) {
        if (message == null) return "unknown error";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** Errors from the most recent load or reload, for reporting to the command sender. */
    public List<String> getLoadErrors() {
        synchronized (loadErrors) {
            return List.copyOf(loadErrors);
        }
    }

    public String resolvePath(String path) {
        if (path == null) return "config.yml";
        String normalized = path.replace("\\", "/");
        return aliases.getOrDefault(normalized, normalized);
    }

    public FileConfiguration get(String fileName) {
        String resolved = resolvePath(fileName);
        FileConfiguration config = configs.get(resolved);
        if (config == null) {
            return loadConfig(resolved);
        }
        return config;
    }

    public FileConfiguration getConfig(String fileName) {
        return get(fileName);
    }

    public String getMessage(String fileName, String key, String def) {
        FileConfiguration cfg = get(fileName);
        if (cfg != null) {
            return cfg.getString(key, def);
        }
        return def;
    }

    public ModuleConfigContainer getModuleContainer(String moduleId) {
        return moduleContainers.computeIfAbsent(moduleId.toLowerCase(),
                id -> new ModuleConfigContainer(plugin, this, id));
    }

    public FileConfiguration getMainConfig() {
        return get("config.yml");
    }

    public MergedMessageConfiguration getCompositeMessages() {
        if (compositeMessages == null) {
            rebuildCompositeMessages();
        }
        return compositeMessages;
    }

    public FileConfiguration getMessages() {
        if (compositeMessages == null) {
            rebuildCompositeMessages();
        }
        return compositeMessages;
    }

    public FileConfiguration getMessageConfig(String module) {
        return get("modules/" + module + "/messages.yml");
    }

    public FileConfiguration getEconomyConfig() {
        return get("modules/economy/config.yml");
    }

    public FileConfiguration getKitsConfig() {
        return get("modules/kits/config.yml");
    }

    public FileConfiguration getToolsConfig() {
        return get("modules/tools/config.yml");
    }

    public FileConfiguration getPvpConfig() {
        return get("modules/pvp/config.yml");
    }

    public FileConfiguration getPvpMessages() {
        return get("modules/pvp/messages.yml");
    }

    public FileConfiguration getSpawnConfig() {
        return get("modules/spawn/config.yml");
    }

    public FileConfiguration getRedisConfig() {
        return get("config.yml");
    }

    public FileConfiguration getRtpConfig() {
        return get("modules/rtp/config.yml");
    }

    public FileConfiguration getNightVisionConfig() {
        return get("modules/nightvision/config.yml");
    }

    public FileConfiguration getTpaConfig() {
        return get("modules/tpa/config.yml");
    }

    public FileConfiguration getTeamsConfig() {
        return get("modules/teams/config.yml");
    }

    public FileConfiguration getHomesConfig() {
        return get("modules/homes/config.yml");
    }

    public FileConfiguration getJoinQuitConfig() {
        return get("modules/joinquit/config.yml");
    }

    public FileConfiguration getChatConfig() {
        return get("modules/chat/config.yml");
    }

    public FileConfiguration getChatMessages() {
        return get("modules/chat/messages.yml");
    }

    public FileConfiguration getChatFilterConfig() {
        return get("modules/chat/chat-filter.yml");
    }

    public FileConfiguration getSettingsConfig() {
        return get("modules/settings/config.yml");
    }

    public FileConfiguration getSettingsMessages() {
        return get("modules/settings/messages.yml");
    }

    public synchronized void save(String fileName) {
        String resolved = resolvePath(fileName);
        FileConfiguration config = configs.get(resolved);
        File file = files.get(resolved);
        if (invalidFiles.contains(resolved)) {
            plugin.getLogger().severe("Refusing to save " + resolved + " until its YAML error is fixed.");
            return;
        }
        if (config != null && file != null) {
            try {
                config.save(file);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + resolved, e);
            }
        }
    }

    public synchronized void reload(String fileName) {
        String resolved = resolvePath(fileName);
        loadConfig(resolved);
        if (resolved.contains("messages.yml")) {
            rebuildCompositeMessages();
        }
    }

    /** Reloads every file in place; a file with broken YAML keeps its last good version. */
    public void reloadAll() {
        loadErrors.clear();
        moduleContainers.clear();
        Map<String, FileConfiguration> before = new HashMap<>(configs);
        loadAll();
        // Files loaded on demand (outside loadAll) must also pick up edits.
        boolean reloadedExtra = false;
        for (Map.Entry<String, FileConfiguration> entry : before.entrySet()) {
            if (configs.get(entry.getKey()) == entry.getValue()) {
                loadConfig(entry.getKey());
                reloadedExtra = true;
            }
        }
        if (reloadedExtra) {
            rebuildCompositeMessages();
        }
    }

    /** Reloads config.yml and the root messages.yml. */
    public void reloadCore() {
        loadErrors.clear();
        loadConfig("config.yml");
        loadConfig("messages.yml");
        rebuildCompositeMessages();
    }

    /** Reloads every loaded file under {@code modules/<module>/}. */
    public void reloadModule(String module) {
        loadErrors.clear();
        String prefix = "modules/" + module.toLowerCase(Locale.ROOT) + "/";
        loadConfig(prefix + "config.yml");
        loadConfig(prefix + "messages.yml");
        for (String path : List.copyOf(configs.keySet())) {
            if (path.startsWith(prefix)) {
                loadConfig(path);
            }
        }
        rebuildCompositeMessages();
    }

    private synchronized void rebuildCompositeMessages() {
        Map<String, FileConfiguration> moduleMessageMap = new LinkedHashMap<>();
        moduleMessageMap.put("", get("messages.yml"));
        moduleMessageMap.put("general", get("messages.yml"));
        moduleMessageMap.put("economy", get("modules/economy/messages.yml"));
        moduleMessageMap.put("homes", get("modules/homes/messages.yml"));
        moduleMessageMap.put("joinquit", get("modules/joinquit/messages.yml"));
        moduleMessageMap.put("kits", get("modules/kits/messages.yml"));
        moduleMessageMap.put("nightvision", get("modules/nightvision/messages.yml"));
        moduleMessageMap.put("pvp", get("modules/pvp/messages.yml"));
        moduleMessageMap.put("rtp", get("modules/rtp/messages.yml"));
        moduleMessageMap.put("spawn", get("modules/spawn/messages.yml"));
        moduleMessageMap.put("teams", get("modules/teams/messages.yml"));
        moduleMessageMap.put("tools", get("modules/tools/messages.yml"));
        moduleMessageMap.put("tpa", get("modules/tpa/messages.yml"));
        moduleMessageMap.put("essentials", get("modules/essentials/messages.yml"));
        moduleMessageMap.put("pets", get("modules/pets/messages.yml"));
        moduleMessageMap.put("ecopets", get("modules/pets/messages.yml"));
        moduleMessageMap.put("chat", get("modules/chat/messages.yml"));
        moduleMessageMap.put("zelchat", get("modules/chat/messages.yml"));
        moduleMessageMap.put("settings", get("modules/settings/messages.yml"));
        moduleMessageMap.put("auctions", get("modules/auctions/messages.yml"));
        moduleMessageMap.put("axauctions", get("modules/auctions/messages.yml"));
        moduleMessageMap.put("ah", get("modules/auctions/messages.yml"));
        moduleMessageMap.put("orders", get("modules/orders/messages.yml"));
        moduleMessageMap.put("donutorder", get("modules/orders/messages.yml"));

        String[] messageFiles = {
                "messages.yml",
                "modules/economy/messages.yml",
                "modules/homes/messages.yml",
                "modules/joinquit/messages.yml",
                "modules/kits/messages.yml",
                "modules/nightvision/messages.yml",
                "modules/pvp/messages.yml",
                "modules/rtp/messages.yml",
                "modules/spawn/messages.yml",
                "modules/teams/messages.yml",
                "modules/tools/messages.yml",
                "modules/tpa/messages.yml",
                "modules/essentials/messages.yml",
                "modules/pets/messages.yml",
                "modules/chat/messages.yml",
                "modules/settings/messages.yml",
                "modules/auctions/messages.yml",
                "modules/orders/messages.yml"
        };

        FileConfiguration[] loadedMessageConfigs = new FileConfiguration[messageFiles.length];
        for (int i = 0; i < messageFiles.length; i++) {
            loadedMessageConfigs[i] = get(messageFiles[i]);
        }

        this.compositeMessages = new MergedMessageConfiguration(moduleMessageMap, loadedMessageConfigs);
    }

    /**
     * Composite configuration wrapper that routes string lookups across root messages
     * and module-specific files, supporting prefixed keys like "spawn.teleporting".
     */
    public static class MergedMessageConfiguration extends YamlConfiguration {
        private final Map<String, FileConfiguration> moduleMap;
        private final FileConfiguration[] sources;

        public MergedMessageConfiguration(FileConfiguration... sources) {
            this(null, sources);
        }

        public MergedMessageConfiguration(Map<String, FileConfiguration> moduleMap, FileConfiguration... sources) {
            this.sources = sources != null ? sources : new FileConfiguration[0];
            this.moduleMap = new LinkedHashMap<>();
            if (moduleMap != null) {
                this.moduleMap.putAll(moduleMap);
            }
            if (this.moduleMap.isEmpty() && this.sources.length > 0) {
                String[] standardKeys = {
                        "", "economy", "homes", "joinquit", "kits", "nightvision",
                        "pvp", "rtp", "spawn", "teams", "tools", "tpa"
                };
                for (int i = 0; i < standardKeys.length && i < this.sources.length; i++) {
                    if (this.sources[i] != null) {
                        this.moduleMap.put(standardKeys[i], this.sources[i]);
                    }
                }
            }
        }

        @Override
        public Object get(String path) {
            return get(path, null);
        }

        @Override
        public Object get(String path, Object def) {
            if (path == null) return def;

            // Route prefixed queries (e.g. "spawn.teleporting") to the matching module config
            int dotIndex = path.indexOf('.');
            if (dotIndex != -1) {
                String prefix = path.substring(0, dotIndex).toLowerCase(Locale.ROOT);
                String subPath = path.substring(dotIndex + 1);

                FileConfiguration moduleConfig = moduleMap.get(prefix);
                if (moduleConfig != null) {
                    if (moduleConfig.contains(subPath)) {
                        return moduleConfig.get(subPath);
                    }
                    if (moduleConfig.contains(path)) {
                        return moduleConfig.get(path);
                    }
                }
            }

            // Look for an exact key match across all sources (e.g. root messages like "no-permission")
            for (FileConfiguration source : sources) {
                if (source != null && source.contains(path)) {
                    return source.get(path);
                }
            }

            // Fallback: search by stripped subkey if the prefix was not a known module
            if (dotIndex != -1) {
                String subPath = path.substring(dotIndex + 1);
                for (FileConfiguration source : sources) {
                    if (source != null && source.contains(subPath)) {
                        return source.get(subPath);
                    }
                }
            }

            return def;
        }

        @Override
        public String getString(String path) {
            return getString(path, null);
        }

        @Override
        public String getString(String path, String def) {
            Object val = get(path, def);
            return val != null ? val.toString() : def;
        }

        @Override
        public List<String> getStringList(String path) {
            if (path == null) return List.of();

            int dotIndex = path.indexOf('.');
            if (dotIndex != -1) {
                String prefix = path.substring(0, dotIndex).toLowerCase(Locale.ROOT);
                String subPath = path.substring(dotIndex + 1);

                FileConfiguration moduleConfig = moduleMap.get(prefix);
                if (moduleConfig != null) {
                    if (moduleConfig.isList(subPath)) {
                        return moduleConfig.getStringList(subPath);
                    }
                    if (moduleConfig.isList(path)) {
                        return moduleConfig.getStringList(path);
                    }
                }
            }

            for (FileConfiguration source : sources) {
                if (source != null && source.isList(path)) {
                    return source.getStringList(path);
                }
            }

            if (dotIndex != -1) {
                String subPath = path.substring(dotIndex + 1);
                for (FileConfiguration source : sources) {
                    if (source != null && source.isList(subPath)) {
                        return source.getStringList(subPath);
                    }
                }
            }

            return List.of();
        }

        @Override
        public boolean contains(String path) {
            return get(path) != null;
        }

        @Override
        public boolean isList(String path) {
            if (path == null) return false;

            int dotIndex = path.indexOf('.');
            if (dotIndex != -1) {
                String prefix = path.substring(0, dotIndex).toLowerCase(Locale.ROOT);
                String subPath = path.substring(dotIndex + 1);

                FileConfiguration moduleConfig = moduleMap.get(prefix);
                if (moduleConfig != null) {
                    if (moduleConfig.isList(subPath) || moduleConfig.isList(path)) {
                        return true;
                    }
                }
            }

            for (FileConfiguration source : sources) {
                if (source != null && source.isList(path)) {
                    return true;
                }
            }

            if (dotIndex != -1) {
                String subPath = path.substring(dotIndex + 1);
                for (FileConfiguration source : sources) {
                    if (source != null && source.isList(subPath)) {
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public boolean isConfigurationSection(String path) {
            return getConfigurationSection(path) != null;
        }

        @Override
        public ConfigurationSection getConfigurationSection(String path) {
            if (path == null) return null;

            int dotIndex = path.indexOf('.');
            if (dotIndex != -1) {
                String prefix = path.substring(0, dotIndex).toLowerCase(Locale.ROOT);
                String subPath = path.substring(dotIndex + 1);

                FileConfiguration moduleConfig = moduleMap.get(prefix);
                if (moduleConfig != null) {
                    if (moduleConfig.isConfigurationSection(subPath)) {
                        return moduleConfig.getConfigurationSection(subPath);
                    }
                    if (moduleConfig.isConfigurationSection(path)) {
                        return moduleConfig.getConfigurationSection(path);
                    }
                }
            }

            for (FileConfiguration source : sources) {
                if (source != null && source.isConfigurationSection(path)) {
                    return source.getConfigurationSection(path);
                }
            }

            if (dotIndex != -1) {
                String subPath = path.substring(dotIndex + 1);
                for (FileConfiguration source : sources) {
                    if (source != null && source.isConfigurationSection(subPath)) {
                        return source.getConfigurationSection(subPath);
                    }
                }
            }

            return null;
        }
    }
}
