package dev.lyy.friendlycore.modules.orders.safety;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class OrderSafetyValidator {

    private final FriendlyCore plugin;

    public OrderSafetyValidator(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isBlacklisted(Material material) {
        if (material == null || material.isAir()) return true;
        Set<String> blacklisted = plugin.getOrdersModule().getOrderManager().getBlacklistedMaterials();
        return blacklisted.contains(material.name().toUpperCase());
    }

    public boolean matches(ItemStack targetTemplate, ItemStack inventoryItem, boolean requireFullDurability) {
        if (targetTemplate == null || inventoryItem == null) return false;
        if (targetTemplate.getType() != inventoryItem.getType()) return false;

        // Container protection: reject shulker boxes / bundles with nested contents
        if (inventoryItem.getItemMeta() instanceof BlockStateMeta bsm) {
            if (bsm.hasBlockState() && bsm.getBlockState() instanceof ShulkerBox box) {
                if (box.getInventory().getContents() != null) {
                    for (ItemStack nested : box.getInventory().getContents()) {
                        if (nested != null && !nested.getType().isAir()) {
                            return false; // Prevent destroying or duping filled containers
                        }
                    }
                }
            }
        }
        if (inventoryItem.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta bm) {
            if (bm.hasItems() && !bm.getItems().isEmpty()) {
                return false;
            }
        }

        if (requireFullDurability && inventoryItem.getItemMeta() instanceof Damageable dmg) {
            if (dmg.hasDamage() && dmg.getDamage() > 0) {
                return false;
            }
        }

        ItemMeta targetMeta = targetTemplate.getItemMeta();
        ItemMeta invMeta = inventoryItem.getItemMeta();

        if (targetMeta == null && invMeta == null) return true;
        if (targetMeta == null || invMeta == null) return false;

        if (targetMeta.hasCustomModelData()) {
            if (!invMeta.hasCustomModelData() || targetMeta.getCustomModelData() != invMeta.getCustomModelData()) {
                return false;
            }
        }

        // Display Name check if target has one
        if (targetMeta.hasDisplayName()) {
            if (!invMeta.hasDisplayName() || !Objects.equals(targetMeta.getDisplayName(), invMeta.getDisplayName())) {
                return false;
            }
        }

        Map<Enchantment, Integer> targetEnchants = getEnchantments(targetTemplate);
        if (!targetEnchants.isEmpty()) {
            Map<Enchantment, Integer> invEnchants = getEnchantments(inventoryItem);
            for (Map.Entry<Enchantment, Integer> entry : targetEnchants.entrySet()) {
                int invLevel = invEnchants.getOrDefault(entry.getKey(), 0);
                if (invLevel < entry.getValue()) {
                    return false;
                }
            }
        }

        return true;
    }

    public Map<Enchantment, Integer> getEnchantments(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Map.of();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta bookMeta) {
            return bookMeta.getStoredEnchants();
        }
        return meta.getEnchants();
    }

    public long countMatchingItems(Player player, ItemStack targetTemplate, boolean requireFullDurability) {
        if (player == null || targetTemplate == null) return 0;
        long count = 0;
        // Use storage contents only to avoid equipped armor and offhand stripping
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack item : contents) {
            if (item != null && matches(targetTemplate, item, requireFullDurability)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public long removeMatchingItems(Player player, ItemStack targetTemplate, long amountToRemove, boolean requireFullDurability) {
        if (player == null || targetTemplate == null || amountToRemove <= 0) return 0;
        long removed = 0;
        // Use storage contents only to avoid equipped armor and offhand stripping
        ItemStack[] contents = player.getInventory().getStorageContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && matches(targetTemplate, item, requireFullDurability)) {
                long needed = amountToRemove - removed;
                if (item.getAmount() <= needed) {
                    removed += item.getAmount();
                    contents[i] = null;
                } else {
                    item.setAmount((int) (item.getAmount() - needed));
                    removed += needed;
                }
                if (removed >= amountToRemove) break;
            }
        }

        player.getInventory().setStorageContents(contents);
        player.updateInventory();
        return removed;
    }
}
