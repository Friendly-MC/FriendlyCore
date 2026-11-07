package dev.lyy.friendlycore.common.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * Represents a GUI slot item paired with an optional click event callback.
 */
public class GuiItem {

    private final ItemStack itemStack;
    private final Consumer<InventoryClickEvent> clickHandler;

    public GuiItem(ItemStack itemStack) {
        this(itemStack, null);
    }

    public GuiItem(ItemStack itemStack, Consumer<InventoryClickEvent> clickHandler) {
        this.itemStack = itemStack;
        this.clickHandler = clickHandler;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public void handleClick(InventoryClickEvent event) {
        if (clickHandler != null) {
            clickHandler.accept(event);
        }
    }
}
