package dev.lyy.friendlycore.nms.common.container;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * High-performance container/shulker contents inspector leveraging Mojang DataComponents.
 */
public interface ContainerInspector {

    /**
     * Extracts items contained inside a Shulker Box, Bundle, or Container Item
     * directly from DataComponents without instantiating BlockStateMeta.
     */
    List<ItemStack> getContainerContents(ItemStack containerItem);

    /**
     * Quickly checks if item has container contents without deserialization.
     */
    boolean hasContainerContents(ItemStack item);
}
