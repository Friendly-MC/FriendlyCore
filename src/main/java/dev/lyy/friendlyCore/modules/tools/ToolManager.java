package dev.lyy.friendlycore.modules.tools;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Factory and metadata manager for custom tools.
 * Handles PDC tag serialization, lore placeholders, and backward compatibility
 * with legacy UnicornTools data.
 */
public class ToolManager {

    private final FriendlyCore plugin;
    public final NamespacedKey KEY_TOOL_TYPE;
    public final NamespacedKey KEY_LAST_USED_BY;

    // Legacy UnicornTools keys for backward compatibility
    public final NamespacedKey LEGACY_DRILL_KEY;
    public final NamespacedKey LEGACY_CHOPPER_KEY;

    public ToolManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.KEY_TOOL_TYPE = new NamespacedKey(plugin, "tool_type");
        this.KEY_LAST_USED_BY = new NamespacedKey(plugin, "last_used_by");
        this.LEGACY_DRILL_KEY = new NamespacedKey("unicorntools", "drill");
        this.LEGACY_CHOPPER_KEY = new NamespacedKey("unicorntools", "chopper");
    }

    public CustomToolType getToolType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String typeStr = pdc.get(KEY_TOOL_TYPE, PersistentDataType.STRING);
        if (typeStr != null) {
            try {
                return CustomToolType.valueOf(typeStr);
            } catch (IllegalArgumentException ignored) {}
        }

        // Check legacy tags if modern PDC key is absent
        if (pdc.has(LEGACY_DRILL_KEY, PersistentDataType.BYTE)) {
            return item.getType().name().endsWith("_SHOVEL") ? CustomToolType.DRILL_SHOVEL : CustomToolType.DRILL_PICKAXE;
        }
        if (pdc.has(LEGACY_CHOPPER_KEY, PersistentDataType.BYTE)) {
            return CustomToolType.TREE_CHOPPER;
        }

        return null;
    }

    public ItemStack createDrill(Material material, String lastUsedBy) {
        FileConfiguration config = plugin.getConfigService().getToolsConfig();
        boolean isShovel = material.name().endsWith("_SHOVEL");
        String path = isShovel ? "drill.items.shovel" : "drill.items.pickaxe";

        String nameFormat = config.getString(path + ".name", isShovel ? "#a503fcғʀɪᴇɴᴅʟʏ sʜᴏᴠᴇʟ" : "#a503fcғʀɪᴇɴᴅʟʏ ᴅʀɪʟʟ");
        List<String> loreLines = config.getStringList(path + ".lore");
        boolean glow = config.getBoolean(path + ".glow", true);
        List<String> enchants = config.getStringList(path + ".enchantments");

        String resolvedUser = (lastUsedBy != null && !lastUsedBy.isEmpty()) ? lastUsedBy : "Nobody";
        int maxDurability = material.getMaxDurability();
        String uses = maxDurability > 0 ? String.valueOf(maxDurability) : "N/A";

        Map<String, String> placeholders = Map.of(
                "%uses%", uses,
                "%lastusedby%", resolvedUser
        );

        ItemBuilder builder = new ItemBuilder(material)
                .name(plugin.getTextService().parse(nameFormat, placeholders))
                .lore(plugin.getTextService().parseList(loreLines, placeholders))
                .glow(glow)
                .pdcString(KEY_TOOL_TYPE, isShovel ? CustomToolType.DRILL_SHOVEL.name() : CustomToolType.DRILL_PICKAXE.name())
                .pdcString(KEY_LAST_USED_BY, resolvedUser)
                .pdcByte(LEGACY_DRILL_KEY, (byte) 1);

        for (String enchEntry : enchants) {
            String[] parts = enchEntry.split(":");
            if (parts.length == 2) {
                Enchantment ench = parseEnchantment(parts[0]);
                if (ench != null) {
                    try {
                        int lvl = Integer.parseInt(parts[1]);
                        builder.enchant(ench, lvl);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return builder.build();
    }

    public ItemStack createChopper(Material material) {
        FileConfiguration config = plugin.getConfigService().getToolsConfig();
        String path = "chopper.items.axe";

        String nameFormat = config.getString(path + ".name", "<#58c8ff><bold>Tree Chopper</bold></#58c8ff>");
        List<String> loreLines = config.getStringList(path + ".lore");
        boolean glow = config.getBoolean(path + ".glow", true);
        List<String> enchants = config.getStringList(path + ".enchantments");

        ItemBuilder builder = new ItemBuilder(material)
                .name(plugin.getTextService().parse(nameFormat))
                .lore(plugin.getTextService().parseList(loreLines, null))
                .glow(glow)
                .pdcString(KEY_TOOL_TYPE, CustomToolType.TREE_CHOPPER.name())
                .pdcByte(LEGACY_CHOPPER_KEY, (byte) 1);

        for (String enchEntry : enchants) {
            String[] parts = enchEntry.split(":");
            if (parts.length == 2) {
                Enchantment ench = parseEnchantment(parts[0]);
                if (ench != null) {
                    try {
                        int lvl = Integer.parseInt(parts[1]);
                        builder.enchant(ench, lvl);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return builder.build();
    }

    public void updateDrillMeta(ItemStack item, String lastUsedBy) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        FileConfiguration config = plugin.getConfigService().getToolsConfig();
        boolean isShovel = item.getType().name().endsWith("_SHOVEL");
        String path = isShovel ? "drill.items.shovel" : "drill.items.pickaxe";

        List<String> loreLines = config.getStringList(path + ".lore");
        int remaining = getRemainingUses(item);
        String usesStr = (remaining >= 0) ? String.valueOf(remaining) : "N/A";
        String resolvedUser = (lastUsedBy != null && !lastUsedBy.isEmpty()) ? lastUsedBy : "Nobody";

        Map<String, String> placeholders = Map.of(
                "%uses%", usesStr,
                "%lastusedby%", resolvedUser
        );

        List<Component> newLore = plugin.getTextService().parseList(loreLines, placeholders);
        meta.lore(newLore);
        meta.getPersistentDataContainer().set(KEY_LAST_USED_BY, PersistentDataType.STRING, resolvedUser);
        item.setItemMeta(meta);
    }

    public int getRemainingUses(ItemStack item) {
        if (item == null || item.getType().getMaxDurability() <= 0) return -1;
        if (item.getItemMeta() instanceof Damageable damageable) {
            return Math.max(0, item.getType().getMaxDurability() - damageable.getDamage());
        }
        return item.getType().getMaxDurability();
    }

    private Enchantment parseEnchantment(String name) {
        String clean = name.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(clean.contains(":") ? clean : "minecraft:" + clean);
        if (key == null) return null;
        Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(key);
        if (ench != null) return ench;
        return org.bukkit.enchantments.Enchantment.getByKey(key);
    }
}
