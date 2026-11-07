package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Confirmation GUI presented to buyers before completing an auction purchase,
 * matching AxAuctions confirm-purchase-gui layout, items, and actions.
 */
public class AuctionConfirmPurchaseGui extends AuctionMenuBase {

    private final AuctionItem item;

    public AuctionConfirmPurchaseGui(FriendlyCore plugin, Player viewer, AuctionItem item, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/confirm-purchase-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/confirm-purchase-gui.yml", viewer, 1, 1, getItemDisplayName(item.getItemStack())),
                plugin.getConfigService().get("modules/auctions/guis/confirm-purchase-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/confirm-purchase-gui.yml").getInt("rows", 5) : 5,
                previousMenu);
        this.item = item;
        refresh();
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        loadTemplateButtons();
        renderConfirmationButtons(() -> auctionManager.purchaseAuction(getViewer(), item),
                item.getPrice(), item.getItemStack(), item.getSellerName());

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection itemSec = null;
        if (config != null) {
            itemSec = config.getConfigurationSection("items.item");
            if (itemSec == null) itemSec = config.getConfigurationSection("item");
        }

        int itemSlot = itemSec != null && itemSec.isInt("slot") ? itemSec.getInt("slot") : 22;
        ItemStack displayStack = formatAuctionItemDisplay(item, "item-lore");
        setItem(itemSlot, new GuiItem(displayStack, event -> {
            event.setCancelled(true);
            if (event.isRightClick() && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
            }
        }));
    }
}
