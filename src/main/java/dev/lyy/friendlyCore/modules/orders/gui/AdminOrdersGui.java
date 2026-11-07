package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class AdminOrdersGui extends OrderMenuBase {

    private List<BuyOrder> allOrders = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player) {
        AdminOrdersGui gui = new AdminOrdersGui(plugin, player);
        gui.open();
    }

    public AdminOrdersGui(FriendlyCore plugin, Player viewer) {
        super(plugin, viewer, "modules/orders/guis/orders.yml",
                Messages.component(plugin, "orders", "admin-moderation"), 6, null);
    }

    @Override
    public void refresh() {
        clear();
        allOrders = new ArrayList<>(orderManager.getActiveOrders().values());

        int pageSize = 45;
        int maxPages = Math.max(1, (int) Math.ceil((double) allOrders.size() / pageSize));
        setMaxPages(maxPages);

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, allOrders.size());

        for (int i = startIndex; i < endIndex; i++) {
            BuyOrder order = allOrders.get(i);
            int slot = i - startIndex;
            if (slot >= 45) break;

            ItemStack baseItem = order.getItem() != null ? order.getItem().clone() : new ItemStack(Material.STONE);
            ItemBuilder builder = new ItemBuilder(baseItem);
            builder.flags(ItemFlag.HIDE_ATTRIBUTES);
            builder.name("<gradient:#E63A35:#FF6B6B><b>" + order.getPlayerName() + "'s Order</b></gradient> &8(#" + order.getOrderId().toString().substring(0, 8) + ")")
                    .loreStrings(List.of(
                            "&8▪ &7Amount: &f" + orderManager.formatAmount(order.getDeliveredAmount()) + "&8/&f" + orderManager.formatAmount(order.getTotalAmount()),
                            "&8▪ &7Price Each: &a$" + orderManager.formatPrice(order.getPricePerItem()),
                            "&8▪ &7Total Escrow: &a$" + orderManager.formatPrice(order.getTotalCost()),
                            "",
                            "&cLeft-Click: &7Force Cancel & Refund",
                            "&4Right-Click: &7Force Delete Permanently"
                    ));

            setItem(slot, new GuiItem(builder.build(), e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                if (e.isLeftClick()) {
                    orderManager.cancelOrder(getViewer(), order.getOrderId()).thenRun(() -> {
                        getPlugin().getScheduler().runEntity(getViewer(), this::refresh);
                    });
                } else if (e.isRightClick()) {
                    getPlugin().getDatabaseManager().deleteOrder(order.getOrderId()).thenRun(() -> {
                        orderManager.removeCachedOrder(order.getOrderId());
                        getPlugin().getScheduler().runEntity(getViewer(), this::refresh);
                    });
                }
            }));
        }

        addNavigationControls(this::previousPage, this::nextPage);
    }
}
