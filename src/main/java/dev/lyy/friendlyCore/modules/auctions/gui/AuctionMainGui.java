package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
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
 * Primary auction house marketplace GUI for browsing, purchasing, previewing containers,
 * and managing active player listings.
 */
public class AuctionMainGui extends AuctionMenuBase {

    public AuctionMainGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/main-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/main-gui.yml", viewer, page,
                        computeInitialMaxPages(plugin, viewer), null),
                plugin.getConfigService().get("modules/auctions/guis/main-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/main-gui.yml").getInt("rows", 6) : 6,
                previousMenu);
        setCurrentPage(Math.max(1, page));
        loadItemSlots();
        refresh();
    }

    private static int computeInitialMaxPages(FriendlyCore plugin, Player viewer) {
        if (plugin == null || plugin.getAuctionsModule() == null || viewer == null) return 1;
        var manager = plugin.getAuctionsModule().getAuctionManager();
        if (manager == null) return 1;
        var user = manager.getUser(viewer.getUniqueId());
        List<AuctionItem> listings = manager.getFilteredActiveListings(
                user != null ? user.getSelectedCategory() : "all",
                user != null ? user.getSelectedSorting() : dev.lyy.friendlycore.modules.auctions.enums.AuctionSorting.NEWEST,
                user != null ? user.getSearchFilter() : null
        );
        var cfg = plugin.getConfigService().get("modules/auctions/guis/main-gui.yml");
        int pageSize = cfg != null ? cfg.getInt("page-size", 45) : 45;
        return Math.max(1, (int) Math.ceil((double) listings.size() / pageSize));
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }


        List<AuctionItem> listings = auctionManager.getFilteredActiveListings(
                auctionUser.getSelectedCategory(),
                auctionUser.getSelectedSorting(),
                auctionUser.getSearchFilter()
        );

        int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
        int totalItems = listings.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        setMaxPages(maxPages);

        if (getCurrentPage() > maxPages) {
            setCurrentPage(maxPages);
        }
        loadTemplateButtons();

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalItems);

        boolean isStaff = getViewer().hasPermission("friendlycore.auctions.admin") || getViewer().hasPermission("axauctions.admin");

        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= itemSlots.size()) break;
            int slot = itemSlots.get(slotIndex);

            AuctionItem item = listings.get(i);
            boolean isOwn = item.getSellerUuid().equals(getViewer().getUniqueId());

            String loreKey;
            if (isOwn) {
                loreKey = item.getItemState() == dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState.EXPIRED ? "item-lore-expired" : "item-lore-own";
            } else if (isStaff) {
                loreKey = "item-lore-staff";
            } else {
                loreKey = "item-lore";
            }

            ItemStack displayStack = formatAuctionItemDisplay(item, loreKey);

            setItem(slot, new GuiItem(displayStack, event -> {
                event.setCancelled(true);

                if (event.getClick() == org.bukkit.event.inventory.ClickType.RIGHT && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                    new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
                    return;
                }

                if (event.isShiftClick() && isStaff) {
                    new AuctionAdminRemovalGui(getPlugin(), getViewer(), item, this).open();
                    return;
                }

                if (isOwn) {
                    auctionManager.cancelAuction(getViewer(), item);
                    getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    refresh();
                    return;
                }

                boolean confirmEnabled = getPlugin().getConfigService().get("modules/auctions/config.yml").getBoolean("auction-purchase-confirmation", true);
                boolean allowSkip = getPlugin().getConfigService().get("modules/auctions/config.yml").getBoolean("allow-confirmation-skipping", false);

                if (confirmEnabled && (!allowSkip || event.getClick() != org.bukkit.event.inventory.ClickType.SHIFT_LEFT)) {
                    new AuctionConfirmPurchaseGui(getPlugin(), getViewer(), item, this).open();
                } else {
                    auctionManager.purchaseAuction(getViewer(), item);
                    refresh();
                }
            }));
        }
    }
}
