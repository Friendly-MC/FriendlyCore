package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.model.OrderDelivery;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class CollectItemsGui extends OrderMenuBase {

    private List<OrderDelivery> unclaimedDeliveries = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player, OrderMenuBase previousMenu) {
        CollectItemsGui gui = new CollectItemsGui(plugin, player, previousMenu);
        gui.open();
    }

    public CollectItemsGui(FriendlyCore plugin, Player viewer, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/collect_items.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/collect_items.yml", "ᴏʀᴅᴇʀs » ᴄᴏʟʟᴇᴄᴛ ɪᴛᴇᴍs"), 6, previousMenu);
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        getPlugin().getDatabaseManager().loadUnclaimedDeliveries(getViewer().getUniqueId()).thenAccept(deliveries -> {
            getPlugin().getScheduler().runEntity(getViewer(), () -> {
                unclaimedDeliveries = deliveries != null ? deliveries : new ArrayList<>();
                renderItems();
            });
        });
    }

    private void renderItems() {
        int pageSize = 45;
        int maxPages = Math.max(1, (int) Math.ceil((double) unclaimedDeliveries.size() / pageSize));
        setMaxPages(maxPages);

        if (getCurrentPage() > maxPages) {
            setCurrentPage(maxPages);
        }

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, unclaimedDeliveries.size());

        for (int i = startIndex; i < endIndex; i++) {
            OrderDelivery delivery = unclaimedDeliveries.get(i);
            int slot = i - startIndex;
            if (slot >= 45) break;

            ItemStack item = delivery.getItem() != null ? delivery.getItem().clone() : new ItemStack(Material.STONE);
            item.setAmount(Math.min(64, delivery.getQuantity()));

            ItemBuilder builder = new ItemBuilder(item);
            if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
                builder.name("#E63A35" + orderManager.formatAmount(delivery.getQuantity()) + " &f" + orderManager.formatItemName(item));
            }
            builder.flags(ItemFlag.HIDE_ATTRIBUTES);

            builder.loreStrings(List.of(
                    "&7Delivered by: #E63A35" + delivery.getSellerName(),
                    "&7Quantity: &f" + orderManager.formatAmount(delivery.getQuantity()) + "x",
                    "",
                    "&aClick to claim into inventory"
            ));

            setItem(slot, new GuiItem(builder.build(), e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                claimSingleDelivery(delivery);
            }));
        }
    }

    private void claimSingleDelivery(OrderDelivery delivery) {
        orderManager.claimDelivery(getViewer(), delivery.getId()).thenRun(() ->
                getPlugin().getScheduler().runEntity(getViewer(), this::refresh));
    }

    @Override
    protected void handleButtonClick(String buttonKey, InventoryClickEvent e) {
        super.handleButtonClick(buttonKey, e);
        if (buttonKey.equalsIgnoreCase("drop_all")) {
            close();
            orderManager.claimDeliveries(getViewer());
        } else if (buttonKey.equalsIgnoreCase("sell_all")) {
            close();
            orderManager.sellDeliveries(getViewer());
        }
    }

}
