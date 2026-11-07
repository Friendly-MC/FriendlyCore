package dev.lyy.friendlycore.modules.chat.render;

import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Dedicated InventoryHolder for Chat snapshot preview GUIs.
 */
public class ChatPreviewHolder implements InventoryHolder {

    private final RenderSnapshot snapshot;
    private Inventory inventory;

    public ChatPreviewHolder(RenderSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public RenderSnapshot getSnapshot() {
        return snapshot;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
