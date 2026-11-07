package dev.lyy.friendlycore.common.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base GUI container supporting pagination controls, slot click handlers,
 * border filling, and Folia-safe inventory opening.
 */
public class PaginatedGui implements InventoryHolder {

    private final FriendlyCore plugin;
    private final Player viewer;
    private final Component title;
    private final int rows;
    private final int size;
    private final Map<Integer, GuiItem> items = new HashMap<>();
    private final Inventory inventory;

    private int currentPage = 1;
    private int maxPages = 1;

    public PaginatedGui(FriendlyCore plugin, Player viewer, Component title, int rows) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.title = title;
        this.rows = Math.min(6, Math.max(1, rows));
        this.size = this.rows * 9;
        this.inventory = Bukkit.createInventory(this, this.size, title);
    }

    public FriendlyCore getPlugin() {
        return plugin;
    }

    public Player getViewer() {
        return viewer;
    }

    public int getRows() {
        return rows;
    }

    public int getSize() {
        return size;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = currentPage;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = Math.max(1, maxPages);
    }

    public void setItem(int slot, GuiItem item) {
        if (slot >= 0 && slot < size) {
            if (item != null) {
                items.put(slot, item);
                inventory.setItem(slot, item.getItemStack());
            } else {
                items.remove(slot);
                inventory.setItem(slot, null);
            }
        }
    }

    public void setItem(int slot, ItemStack stack) {
        setItem(slot, new GuiItem(stack, null));
    }

    public void fillBorder(ItemStack borderStack) {
        GuiItem borderItem = new GuiItem(borderStack, e -> e.setCancelled(true));
        for (int i = 0; i < 9; i++) {
            setItem(i, borderItem);
            setItem(size - 9 + i, borderItem);
        }
        for (int i = 0; i < rows; i++) {
            setItem(i * 9, borderItem);
            setItem(i * 9 + 8, borderItem);
        }
    }

    public void fillEmpty(ItemStack filler) {
        GuiItem fillerItem = new GuiItem(filler, e -> e.setCancelled(true));
        for (int i = 0; i < size; i++) {
            if (!items.containsKey(i)) {
                setItem(i, fillerItem);
            }
        }
    }

    public void addNavigationControls(Runnable onPrev, Runnable onNext) {
        int prevSlot = size - 9 + 3;
        int nextSlot = size - 9 + 5;
        int pageIndicatorSlot = size - 9 + 4;

        if (currentPage > 1) {
            ItemStack prevItem = new ItemBuilder(Material.ARROW)
                    .name(Messages.component(plugin, "core", "gui-previous-page", "%page%", String.valueOf(currentPage - 1)))
                    .build();
            setItem(prevSlot, new GuiItem(prevItem, e -> {
                e.setCancelled(true);
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                if (onPrev != null) onPrev.run();
            }));
        }

        ItemStack pageItem = new ItemBuilder(Material.PAPER)
                .name(Messages.component(plugin, "core", "gui-page-indicator",
                        "%page%", String.valueOf(currentPage), "%total%", String.valueOf(maxPages)))
                .build();
        setItem(pageIndicatorSlot, new GuiItem(pageItem, e -> e.setCancelled(true)));

        if (currentPage < maxPages) {
            ItemStack nextItem = new ItemBuilder(Material.ARROW)
                    .name(Messages.component(plugin, "core", "gui-next-page", "%page%", String.valueOf(currentPage + 1)))
                    .build();
            setItem(nextSlot, new GuiItem(nextItem, e -> {
                e.setCancelled(true);
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                if (onNext != null) onNext.run();
            }));
        }
    }

    public void open() {
        plugin.getScheduler().runEntity(viewer, () -> viewer.openInventory(inventory));
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        GuiItem item = items.get(slot);
        if (item != null) {
            item.handleClick(event);
        }
    }

    public boolean isItemMovementAllowed(int slot) {
        return false;
    }

    public void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!isItemMovementAllowed(-1)) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < inventory.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    public void handleClose(InventoryCloseEvent event) {
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
