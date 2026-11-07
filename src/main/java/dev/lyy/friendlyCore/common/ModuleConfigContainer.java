package dev.lyy.friendlycore.common;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scoped configuration container for an individual FriendlyCore module.
 * Provides structured access to module settings, localized messages, and inventory layouts.
 */
public class ModuleConfigContainer {

    private final Plugin plugin;
    private final ConfigService configService;
    private final String moduleId;
    private final String moduleBasePath;
    private final ConcurrentHashMap<String, FileConfiguration> inventoryCache = new ConcurrentHashMap<>();

    public ModuleConfigContainer(Plugin plugin, ConfigService configService, String moduleId) {
        this.plugin = plugin;
        this.configService = configService;
        this.moduleId = moduleId.toLowerCase();
        this.moduleBasePath = "modules/" + this.moduleId + "/";
    }

    public String getModuleId() {
        return moduleId;
    }

    public FileConfiguration getConfig() {
        return configService.get(moduleBasePath + "config.yml");
    }

    public FileConfiguration getMessages() {
        return configService.get(moduleBasePath + "messages.yml");
    }

    public FileConfiguration getInventory(String name) {
        String fileName = name.endsWith(".yml") ? name : name + ".yml";
        return configService.get(moduleBasePath + "inventories/" + fileName);
    }

    public void saveConfig() {
        configService.save(moduleBasePath + "config.yml");
    }

    public void saveMessages() {
        configService.save(moduleBasePath + "messages.yml");
    }

    public void reload() {
        inventoryCache.clear();
        configService.get(moduleBasePath + "config.yml");
        configService.get(moduleBasePath + "messages.yml");
    }

    public String getMessage(String key, String def) {
        FileConfiguration msg = getMessages();
        if (msg != null && msg.contains(key)) {
            return msg.getString(key, def);
        }
        return def;
    }
}
