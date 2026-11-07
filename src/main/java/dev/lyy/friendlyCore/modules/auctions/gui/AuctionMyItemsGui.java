package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for players to inspect and cancel their own active auction house listings.
 */
public class AuctionMyItemsGui extends AuctionMenuBase {

    public AuctionMyItemsGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/my-items-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/my-items-gui.yml", viewer, page,
                        computeInitialMaxPages(plugin, viewer), null),
                plugin.getConfigService().get("modules/auctions/guis/my-items-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/my-items-gui.yml").getInt("rows", 6) : 6,
                previousMenu);
        setCurrentPage(Math.max(1, page));
        loadItemSlots();
        refresh();
    }

    private static int computeInitialMaxPages(FriendlyCore plugin, Player viewer) {
        if (plugin == null || plugin.getAuctionsModule() == null || viewer == null) return 1;
        var manager = plugin.getAuctionsModule().getAuctionManager();
        if (manager == null) return 1;
        List<AuctionItem> myItems = new ArrayList<>(manager.getPlayerActiveListings(viewer.getUniqueId()));
        var cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        if (cfg != null && !cfg.getBoolean("separate-expired-items", false)) {
            myItems.addAll(manager.getPlayerExpiredListings(viewer.getUniqueId()));
        }
        var user = manager.getUser(viewer.getUniqueId());
        if (user != null) {
            manager.filterAndSort(myItems, user.getSelectedCategory(), user.getSelectedSorting(), user.getSearchFilter());
        }
        var guiCfg = plugin.getConfigService().get("modules/auctions/guis/my-items-gui.yml");
        int pageSize = guiCfg != null ? guiCfg.getInt("page-size", 45) : 45;
        return Math.max(1, (int) Math.ceil((double) myItems.size() / pageSize));
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }


        List<AuctionItem> myItems = auctionManager.getPlayerActiveListings(getViewer().getUniqueId());
        if (!getPlugin().getConfigService().get("modules/auctions/config.yml").getBoolean("separate-expired-items", false)) {
            myItems.addAll(auctionManager.getPlayerExpiredListings(getViewer().getUniqueId()));
        }
        auctionManager.filterAndSort(myItems, auctionUser.getSelectedCategory(),
                auctionUser.getSelectedSorting(), auctionUser.getSearchFilter());
        int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
        int totalItems = myItems.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        setMaxPages(maxPages);

        if (getCurrentPage() > maxPages) {
            setCurrentPage(maxPages);
        }
        loadTemplateButtons();

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= itemSlots.size()) break;
            int slot = itemSlots.get(slotIndex);

            AuctionItem item = myItems.get(i);
            String loreKey = item.getItemState() == dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState.EXPIRED ? "item-lore-expired" : "item-lore-own";
            ItemStack displayStack = formatAuctionItemDisplay(item, loreKey);

            setItem(slot, new GuiItem(displayStack, event -> {
                event.setCancelled(true);

                if (event.isRightClick() && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                    new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
                    return;
                }

                auctionManager.cancelAuction(getViewer(), item);
                getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                refresh();
            }));
        }
    }
}
