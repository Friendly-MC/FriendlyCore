package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class EditOrderGui extends OrderMenuBase {

    private final BuyOrder order;

    public static void openMenu(FriendlyCore plugin, Player player, BuyOrder order, OrderMenuBase previousMenu) {
        EditOrderGui gui = new EditOrderGui(plugin, player, order, previousMenu);
        gui.open();
    }

    public EditOrderGui(FriendlyCore plugin, Player viewer, BuyOrder order, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/edit_order.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/edit_order.yml", "ᴏʀᴅᴇʀs » ᴇᴅɪᴛ ᴏʀᴅᴇʀ"), 3, previousMenu);
        this.order = order;
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        // Slot 11 (or configured): Order Preview Info
        ConfigurationSection infoSec = config.getConfigurationSection("items.order_info");
        if (infoSec != null) {
            int infoSlot = infoSec.getInt("slot", 11);
            String infoName = infoSec.getString("name", "#E63A35{itemAmount} &f{itemType}");
            infoName = infoName
                    .replace("{itemType}", orderManager.formatItemName(order.getItem()))
                    .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()));

            List<String> rawInfoLore = infoSec.getStringList("lore");
            List<String> formattedInfoLore = new ArrayList<>();
            for (String line : rawInfoLore) {
                formattedInfoLore.add(line
                        .replace("{itemType}", orderManager.formatItemName(order.getItem()))
                        .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()))
                        .replace("{currentlyDelivered}", orderManager.formatAmount(order.getDeliveredAmount()))
                        .replace("{totalDelivery}", orderManager.formatAmount(order.getTotalAmount()))
                        .replace("{itemPrice}", orderManager.formatPrice(order.getPricePerItem()))
                        .replace("{itemPrice_short}", orderManager.formatPriceShort(order.getPricePerItem()))
                        .replace("{totalPrice}", orderManager.formatPrice(order.getTotalCost()))
                        .replace("{totalPrice_short}", orderManager.formatPriceShort(order.getTotalCost()))
                        .replace("{totalPaid}", orderManager.formatPrice(order.getTotalPaid()))
                        .replace("{totalPaid_short}", orderManager.formatPriceShort(order.getTotalPaid()))
                        .replace("{uncollected}", orderManager.formatAmount(order.getUnclaimedAmount())));
            }

            ItemStack baseItem = order.getItem() != null ? order.getItem().clone() : new ItemStack(Material.STONE);
            ItemBuilder infoBuilder = new ItemBuilder(baseItem).name(infoName).loreStrings(formattedInfoLore);
            infoBuilder.flags(ItemFlag.HIDE_ATTRIBUTES);
            setItem(infoSlot, new GuiItem(infoBuilder.build(), e -> {}));
        }

        ConfigurationSection cancelSec = config.getConfigurationSection("items.cancel");
        int cancelSlot = cancelSec != null ? cancelSec.getInt("slot", 13) : 13;
        String cancelName = cancelSec != null ? cancelSec.getString("name", "#E63A35ᴄᴀɴᴄᴇʟ") : "#E63A35ᴄᴀɴᴄᴇʟ";
        List<String> cancelLore = cancelSec != null ? cancelSec.getStringList("lore") : List.of("&fClick to cancel this order");

        ItemBuilder cancelBuilder = new ItemBuilder(Material.RED_TERRACOTTA).name(cancelName).loreStrings(cancelLore);
        setItem(cancelSlot, new GuiItem(cancelBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            CancelOrderConfirmGui.openMenu(getPlugin(), getViewer(), order, this);
        }));

        ConfigurationSection collectSec = config.getConfigurationSection("items.collect");
        int collectSlot = collectSec != null ? collectSec.getInt("slot", 15) : 15;
        String collectName = collectSec != null ? collectSec.getString("name", "#E63A35ᴄᴏʟʟᴇᴄᴛ") : "#E63A35ᴄᴏʟʟᴇᴄᴛ";

        long uncollected = order.getUnclaimedAmount();
        List<String> rawCollectLore = collectSec != null ? collectSec.getStringList("lore") : List.of();
        List<String> formattedCollectLore = new ArrayList<>();
        if (rawCollectLore.isEmpty()) {
            formattedCollectLore.add("&fClick to collect items");
            formattedCollectLore.add("&7" + orderManager.formatAmount(uncollected) + "x " + orderManager.formatItemName(order.getItem()) + " available");
        } else {
            for (String line : rawCollectLore) {
                formattedCollectLore.add(line
                        .replace("{uncollected}", orderManager.formatAmount(uncollected))
                        .replace("{itemList}", orderManager.formatAmount(uncollected) + "x " + orderManager.formatItemName(order.getItem())));
            }
        }

        ItemBuilder collectBuilder = new ItemBuilder(Material.CHEST).name(collectName).loreStrings(formattedCollectLore);
        setItem(collectSlot, new GuiItem(collectBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            CollectItemsGui.openMenu(getPlugin(), getViewer(), this);
        }));
    }
}
