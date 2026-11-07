package dev.lyy.friendlycore.modules.auctions.search;

import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates full-text search queries across seller usernames, item types, display names,
 * lores, standard enchantments, stored book enchantments, and nested container contents.
 */
public class AuctionSearchEngine {

    public static boolean matches(AuctionItem auctionItem, String query) {
        if (query == null || query.isBlank()) return true;
        if (auctionItem == null) return false;

        String lowerQuery = query.trim().toLowerCase(Locale.ROOT);
        if (auctionItem.getSearchKeywords().contains(lowerQuery)) {
            return true;
        }

        ItemStack item = auctionItem.getItemStack();
        if (item == null) return false;

        // Scan nested container contents without re-scanning root item metadata
        if (AuctionSafetyValidator.isContainer(item)) {
            for (ItemStack content : AuctionSafetyValidator.getContainerContents(item)) {
                if (content != null && matchesItemStack(content, lowerQuery)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean matchesItemStack(ItemStack item, String lowerQuery) {
        return matchesItemStack(item, lowerQuery, 0);
    }

    private static boolean matchesItemStack(ItemStack item, String lowerQuery, int depth) {
        if (item == null || depth > 5) return false;

        String matName = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (matName.contains(lowerQuery) || item.getType().name().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            if (meta.hasDisplayName()) {
                if (meta.getDisplayName().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    return true;
                }
                if (meta.displayName() != null && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(meta.displayName()).toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    return true;
                }
            }

            if (meta.hasLore()) {
                List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        if (line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                            return true;
                        }
                    }
                }
            }

            if (meta.hasEnchants()) {
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    String enchKey = entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT).replace('_', ' ');
                    String enchFull = enchKey + " " + entry.getValue();
                    if (enchKey.contains(lowerQuery) || enchFull.contains(lowerQuery)) {
                        return true;
                    }
                }
            }

            if (meta instanceof EnchantmentStorageMeta esm && esm.hasStoredEnchants()) {
                for (Map.Entry<Enchantment, Integer> entry : esm.getStoredEnchants().entrySet()) {
                    String enchKey = entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT).replace('_', ' ');
                    String enchFull = enchKey + " " + entry.getValue();
                    if (enchKey.contains(lowerQuery) || enchFull.contains(lowerQuery)) {
                        return true;
                    }
                }
            }
        }

        // Recursive container search (Shulker Box / Bundle) with depth limit
        if (AuctionSafetyValidator.isContainer(item)) {
            ItemStack[] contents = AuctionSafetyValidator.getContainerContents(item);
            for (ItemStack content : contents) {
                if (content != null && matchesItemStack(content, lowerQuery, depth + 1)) {
                    return true;
                }
            }
        }

        return false;
    }
}
