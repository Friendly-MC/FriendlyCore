package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Preview GUI allowing players to safely inspect the contents of shulker boxes and bundles
 * listed on the auction house without extracting or modifying them, matching AxAuctions.
 */
public class AuctionShulkerPreviewGui extends AuctionMenuBase {

    private final ItemStack containerItem;

    public AuctionShulkerPreviewGui(FriendlyCore plugin, Player viewer, ItemStack containerItem, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/shulker-preview-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/shulker-preview-gui.yml", viewer, 1, 1, getItemDisplayName(containerItem)),
                plugin.getConfigService().get("modules/auctions/guis/shulker-preview-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/shulker-preview-gui.yml").getInt("rows", 5) : 5,
                previousMenu);
        this.containerItem = containerItem;
        refresh();
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        int itemSlot = 4;
        if (config != null) {
            ConfigurationSection sec = config.getConfigurationSection("items.item");
            if (sec == null) sec = config.getConfigurationSection("item");
            if (sec != null && sec.isInt("slot")) {
                itemSlot = sec.getInt("slot");
            }
        }

        // Show the container item header at configured slot (default 4)
        if (containerItem != null) {
            setItem(itemSlot, new GuiItem(containerItem.clone(), event -> event.setCancelled(true)));
        }

        // Extract container contents and render in the 27 grid slots (slots 9 to 35)
        ItemStack[] contents = AuctionSafetyValidator.getContainerContents(containerItem);
        for (int i = 0; i < contents.length && i < 27; i++) {
            ItemStack content = contents[i];
            int slot = 9 + i;
            if (content != null && content.getType() != Material.AIR && slot < getSize()) {
                setItem(slot, new GuiItem(content.clone(), event -> event.setCancelled(true)));
            }
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        super.handleClose(event);
        reopenPreviousMenu(event);
    }
}
