package dev.lyy.friendlycore.modules.auctions.category;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Represents a configured auction category with pattern-matching rules
 * for materials, display names, and CustomModelData.
 */
public class AuctionCategory {

    private final String id;
    private final String name;
    private final Material icon;
    private final int customModelData;
    private final List<Map<String, Object>> matchers;

    public AuctionCategory(String id, String name, Material icon, int customModelData, List<Map<String, Object>> matchers) {
        this.id = id.toLowerCase(Locale.ROOT);
        this.name = name;
        this.icon = icon != null ? icon : Material.CHEST;
        this.customModelData = customModelData;
        this.matchers = matchers != null ? matchers : List.of();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Material getIcon() {
        return icon;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public boolean matches(ItemStack item) {
        if ("all".equalsIgnoreCase(id)) return true;
        if (item == null || item.getType() == Material.AIR) return false;

        if (matchers.isEmpty()) return false;

        for (Map<String, Object> rule : matchers) {
            if (matchesRule(item, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesRule(ItemStack item, Map<String, Object> rule) {
        Object materialRule = rule.containsKey("material") ? rule.get("material") : rule.get("type");
        if (materialRule != null) {
            String matPattern = String.valueOf(materialRule).toUpperCase(Locale.ROOT);
            String itemMat = item.getType().name();
            if (!dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard(itemMat, matPattern)) {
                return false;
            }
        }

        if (rule.containsKey("name")) {
            if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
            String namePattern = String.valueOf(rule.get("name"));
            String itemName = item.getItemMeta().displayName() != null
                    ? PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                    : item.getItemMeta().getDisplayName();
            if (!dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard(itemName, namePattern)) {
                return false;
            }
        }

        if (rule.containsKey("custom-model-data")) {
            int cmd;
            try {
                Object configured = rule.get("custom-model-data");
                cmd = configured instanceof Number number
                        ? number.intValue() : Integer.parseInt(String.valueOf(configured));
            } catch (NumberFormatException invalid) {
                return false;
            }
            if (!item.hasItemMeta() || !item.getItemMeta().hasCustomModelData() || item.getItemMeta().getCustomModelData() != cmd) {
                return false;
            }
        }

        return true;
    }
}
