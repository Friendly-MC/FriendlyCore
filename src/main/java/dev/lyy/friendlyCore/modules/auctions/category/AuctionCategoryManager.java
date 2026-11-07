package dev.lyy.friendlycore.modules.auctions.category;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages category definitions loaded from configuration, provides cycling navigation,
 * and matches items against active category rules.
 */
public class AuctionCategoryManager {

    private final FriendlyCore plugin;
    private final Map<String, AuctionCategory> categories = new LinkedHashMap<>();
    private final List<String> categoryOrder = new ArrayList<>();
    private boolean enabled;

    public AuctionCategoryManager(FriendlyCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        categoryOrder.clear();

        FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/categories.yml");
        if (cfg == null) {
            registerDefaultCategories();
            return;
        }

        this.enabled = cfg.getBoolean("enabled", false);
        ConfigurationSection sec = cfg.getConfigurationSection("categories");
        // AxAuctions 2.7.8 stores category sections at the root. Keep accepting the
        // earlier FriendlyCore wrapper so existing installations migrate cleanly.
        if (sec == null) sec = cfg;

        for (String key : sec.getKeys(false)) {
            if ("enabled".equalsIgnoreCase(key) || "version".equalsIgnoreCase(key)) continue;
            ConfigurationSection catSec = sec.getConfigurationSection(key);
            if (catSec == null) continue;

            String name = catSec.getString("name", key);
            String iconName = catSec.getString("icon", "CHEST");
            Material icon = Material.matchMaterial(iconName);
            if (icon == null) icon = Material.CHEST;
            int cmd = catSec.getInt("custom-model-data", -1);

            List<Map<String, Object>> matchers = new ArrayList<>();
            List<Map<?, ?>> rawMatchers = catSec.getMapList("items");
            for (Map<?, ?> raw : rawMatchers) {
                Map<String, Object> rule = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    rule.put(String.valueOf(e.getKey()), e.getValue());
                }
                matchers.add(rule);
            }

            AuctionCategory category = new AuctionCategory(key, name, icon, cmd, matchers);
            categories.put(key.toLowerCase(Locale.ROOT), category);
            categoryOrder.add(key.toLowerCase(Locale.ROOT));
        }

        if (!categories.containsKey("all")) {
            AuctionCategory all = new AuctionCategory("all", "All Items", Material.NETHER_STAR, -1, List.of());
            categories.put("all", all);
            categoryOrder.add(0, "all");
        }
    }

    private void registerDefaultCategories() {
        this.enabled = false;
        AuctionCategory all = new AuctionCategory("all", "All Items", Material.NETHER_STAR, -1, List.of());
        categories.put("all", all);
        categoryOrder.add("all");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AuctionCategory getCategory(String id) {
        if (id == null) return categories.get("all");
        return categories.getOrDefault(id.toLowerCase(Locale.ROOT), categories.get("all"));
    }

    public List<AuctionCategory> getAllCategories() {
        return new ArrayList<>(categories.values());
    }

    public String getNextCategory(String currentId) {
        if (categoryOrder.isEmpty()) return "all";
        int index = categoryOrder.indexOf(currentId != null ? currentId.toLowerCase(Locale.ROOT) : "all");
        if (index == -1 || index >= categoryOrder.size() - 1) {
            return categoryOrder.get(0);
        }
        return categoryOrder.get(index + 1);
    }

    public String getPreviousCategory(String currentId) {
        if (categoryOrder.isEmpty()) return "all";
        int index = categoryOrder.indexOf(currentId != null ? currentId.toLowerCase(Locale.ROOT) : "all");
        if (index <= 0) {
            return categoryOrder.get(categoryOrder.size() - 1);
        }
        return categoryOrder.get(index - 1);
    }

    public boolean matches(String categoryId, ItemStack item) {
        AuctionCategory cat = getCategory(categoryId);
        return cat == null || cat.matches(item);
    }
}
