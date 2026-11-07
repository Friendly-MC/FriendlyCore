package dev.lyy.friendlycore.common.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

/**
 * Global inventory event listener dispatching click, drag, and close events
 * to active {@link PaginatedGui} instances.
 */
public class GuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory clickedInv = event.getClickedInventory();
        Inventory topInv = event.getView().getTopInventory();

        if (topInv.getHolder() instanceof PaginatedGui gui) {
            // Prevent collect-to-cursor from pulling protected GUI items into the player's cursor
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }

            if (event.getWhoClicked() instanceof org.bukkit.entity.Player player &&
                    gui.getPlugin().getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
                event.setCancelled(true);
                player.closeInventory();
                dev.lyy.friendlycore.common.Messages.send(gui.getPlugin(), player, "core", "gui-data-synchronizing");
                return;
            }

            if (clickedInv != null && clickedInv.equals(topInv)) {
                gui.handleClick(event);
            } else {
                // Prevent shift-clicking items into the GUI unless explicitly permitted by the interface
                if (event.isShiftClick() && !gui.isItemMovementAllowed(-1)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        Inventory topInv = event.getView().getTopInventory();
        if (topInv.getHolder() instanceof PaginatedGui gui) {
            gui.handleDrag(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof PaginatedGui gui) {
            gui.handleClose(event);
        }
    }
}
