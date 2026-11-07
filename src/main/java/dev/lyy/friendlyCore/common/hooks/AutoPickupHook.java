package dev.lyy.friendlycore.common.hooks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.HashMap;

/**
 * Hook for AutoPickup plugin integration and inventory overflow handling.
 */
public class AutoPickupHook {

    private final boolean autoPickupEnabled;

    public AutoPickupHook() {
        this.autoPickupEnabled = Bukkit.getPluginManager().isPluginEnabled("AutoPickup");
    }

    public boolean isAutoPickupEnabled() {
        return autoPickupEnabled;
    }

    /**
     * Attempts to add items directly to the player's inventory, dropping any overflow at their feet.
     */
    public void giveOrDrop(Player player, Collection<ItemStack> items) {
        if (player == null || items == null || items.isEmpty()) return;

        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            if (!leftovers.isEmpty()) {
                for (ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
    }
}
