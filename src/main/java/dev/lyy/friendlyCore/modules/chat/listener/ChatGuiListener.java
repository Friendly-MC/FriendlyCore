package dev.lyy.friendlycore.modules.chat.listener;

import dev.lyy.friendlycore.modules.chat.render.ChatPreviewHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Prevents item interaction, removal, or modification inside Chat preview GUIs.
 */
public class ChatGuiListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof ChatPreviewHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChatPreviewHolder) {
            event.setCancelled(true);
        }
    }
}
