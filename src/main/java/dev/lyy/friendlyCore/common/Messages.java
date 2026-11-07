package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends messages from a module's messages.yml ({@code "core"} is the root messages.yml).
 * An entry is a plain string, or a section with {@code text}, {@code display-type}
 * (CHAT, ACTION_BAR or BOTH) and {@code enabled}. Placeholder values are inserted escaped, except
 * names ending in {@code _formatted} (and TextService's prefix/suffix/display-name keys), which are parsed.
 */
public final class Messages {

    private Messages() {
    }

    /** {@code placeholders} are pairs: "%player%", name, "%kit%", kit, ... */
    public static void send(FriendlyCore plugin, CommandSender to, String module, String key, String... placeholders) {
        if (to == null) return;
        FileConfiguration cfg = plugin.getConfigService().get(file(module));
        String type = "CHAT";
        String text;
        if (cfg.isConfigurationSection(key)) {
            if (!cfg.getBoolean(key + ".enabled", true)) return;
            text = cfg.getString(key + ".text", "");
            type = cfg.getString(key + ".display-type", "CHAT");
        } else {
            text = cfg.getString(key);
        }
        if (text == null) {
            plugin.getLogger().warning("Missing message '" + key + "' in " + file(module));
            return;
        }
        if (text.isEmpty() || text.equalsIgnoreCase("DISABLED")) return;
        Map<String, String> map = pairs(placeholders);
        if (to instanceof Player player) {
            boolean both = "BOTH".equalsIgnoreCase(type);
            if (both || "ACTION_BAR".equalsIgnoreCase(type)) {
                plugin.getTextService().sendActionBar(player, text, map);
            }
            if (both || !"ACTION_BAR".equalsIgnoreCase(type)) {
                plugin.getTextService().sendMessage(player, text, map);
            }
        } else {
            // Not TextService.sendMessage: its console defaults would turn usage hints like <player> into "CONSOLE".
            to.sendMessage(plugin.getTextService().parse(text, map));
        }
    }

    /** Raw template, for formats and prompts parsed elsewhere. */
    public static String raw(FriendlyCore plugin, String module, String key) {
        FileConfiguration cfg = plugin.getConfigService().get(file(module));
        String text = cfg.isConfigurationSection(key) ? cfg.getString(key + ".text") : cfg.getString(key);
        return text != null ? text : "";
    }

    /** Parsed component, for GUI item names and lore. */
    public static Component component(FriendlyCore plugin, String module, String key, String... placeholders) {
        return plugin.getTextService().parse(raw(plugin, module, key), pairs(placeholders));
    }

    private static String file(String module) {
        return "core".equals(module) ? "messages.yml" : "modules/" + module + "/messages.yml";
    }

    private static Map<String, String> pairs(String... placeholders) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            map.put(placeholders[i], placeholders[i + 1] != null ? placeholders[i + 1] : "");
        }
        return map;
    }
}
