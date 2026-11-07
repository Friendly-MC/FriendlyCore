package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for players to view and reclaim their expired market listings.
 */
public class AuctionExpiredGui extends AuctionMenuBase {

    public AuctionExpiredGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/expired-items-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/expired-items-gui.yml", viewer, page,
                        computeInitialMaxPages(plugin, viewer), null),
                plugin.getConfigService().get("modules/auctions/guis/expired-items-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/expired-items-gui.yml").getInt("rows", 6) : 6,
                previousMenu);
        setCurrentPage(Math.max(1, page));
        loadItemSlots();
        refresh();
    }

    private static int computeInitialMaxPages(FriendlyCore plugin, Player viewer) {
        if (plugin == null || plugin.getAuctionsModule() == null || viewer == null) return 1;
        var manager = plugin.getAuctionsModule().getAuctionManager();
        if (manager == null) return 1;
        List<AuctionItem> expiredItems = new ArrayList<>(manager.getPlayerExpiredListings(viewer.getUniqueId()));
        var user = manager.getUser(viewer.getUniqueId());
        if (user != null) {
            manager.filterAndSort(expiredItems, user.getSelectedCategory(), user.getSelectedSorting(), user.getSearchFilter());
        }
        var guiCfg = plugin.getConfigService().get("modules/auctions/guis/expired-items-gui.yml");
        int pageSize = guiCfg != null ? guiCfg.getInt("page-size", 45) : 45;
        return Math.max(1, (int) Math.ceil((double) expiredItems.size() / pageSize));
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }


        List<AuctionItem> expiredItems = auctionManager.getPlayerExpiredListings(getViewer().getUniqueId());
        auctionManager.filterAndSort(expiredItems, auctionUser.getSelectedCategory(),
                auctionUser.getSelectedSorting(), auctionUser.getSearchFilter());
        int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
        int totalItems = expiredItems.size();
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

            AuctionItem item = expiredItems.get(i);
            ItemStack displayStack = formatAuctionItemDisplay(item, "item-lore-expired");

            setItem(slot, new GuiItem(displayStack, event -> {
                event.setCancelled(true);

                if (event.isRightClick() && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                    new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
                    return;
                }

                auctionManager.claimExpiredAuction(getViewer(), item);
                getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                refresh();
            }));
        }
    }
}
