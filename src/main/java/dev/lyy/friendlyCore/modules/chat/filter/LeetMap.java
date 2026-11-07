package dev.lyy.friendlycore.modules.chat.filter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Character mapping table to identify leet / obfuscated text substitutions.
 */
public class LeetMap {

    private final Map<Character, List<String>> map = new ConcurrentHashMap<>();

    public void load(FileConfiguration config) {
        map.clear();
        if (config == null) return;
        ConfigurationSection section = config.getConfigurationSection("map");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            if (key.isEmpty()) continue;
            char c = Character.toLowerCase(key.charAt(0));
            List<String> replacements = new java.util.ArrayList<>();
            if (section.isList(key)) {
                for (String s : section.getStringList(key)) {
                    if (s != null && !s.isEmpty()) replacements.add(s);
                }
            } else {
                String str = section.getString(key, String.valueOf(c));
                if (str != null && !str.isEmpty()) {
                    for (int i = 0; i < str.length(); i++) {
                        replacements.add(String.valueOf(str.charAt(i)));
                    }
                }
            }
            if (replacements.isEmpty()) {
                replacements.add(String.valueOf(c));
            }
            map.put(c, replacements);
        }
    }

    public List<String> getReplacements(char c) {
        return map.getOrDefault(Character.toLowerCase(c), List.of(String.valueOf(c)));
    }

    public String getReplacement(char c) {
        List<String> list = getReplacements(c);
        return String.join("", list);
    }

    public Map<Character, List<String>> getMap() {
        return map;
    }
}
