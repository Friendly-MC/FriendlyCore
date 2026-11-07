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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class OrderDeliveryConfirmGui extends OrderMenuBase {

    private final BuyOrder order;

    public static void openMenu(FriendlyCore plugin, Player player, BuyOrder order, OrderMenuBase previousMenu) {
        OrderDeliveryConfirmGui gui = new OrderDeliveryConfirmGui(plugin, player, order, previousMenu);
        gui.open();
    }

    public OrderDeliveryConfirmGui(FriendlyCore plugin, Player viewer, BuyOrder order, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/order_delivery_confirm.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/order_delivery_confirm.yml", "ᴏʀᴅᴇʀs » ᴄᴏɴғɪʀᴍ ᴅᴇʟɪᴠᴇʀʏ"), 3, previousMenu);
        this.order = order;
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        FileConfiguration mainOrdersConfig = getPlugin().getConfigService().get("modules/orders/config.yml");
        boolean requireFullDurability = mainOrdersConfig != null && mainOrdersConfig.getBoolean("require-full-durability", false);

        long availableInInventory = orderManager.getSafetyValidator().countMatchingItems(getViewer(), order.getItem(), requireFullDurability);
        long maxNeeded = order.getAvailableAmount();
        long deliverAmount = Math.min(availableInInventory, maxNeeded);

        double payout = BigDecimal.valueOf(deliverAmount)
                .multiply(BigDecimal.valueOf(order.getPricePerItem()))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        String itemTypeName = orderManager.formatItemName(order.getItem());

        ConfigurationSection delivSec = config.getConfigurationSection("items.delivery_order");
        int delivSlot = delivSec != null ? delivSec.getInt("slot", 13) : 13;
        String delivName = delivSec != null ? delivSec.getString("name", "#E63A35{itemAmount} &f{itemType}") : "#E63A35{itemAmount} &f{itemType}";
        delivName = delivName
                .replace("{playerName}", order.getPlayerName())
                .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()))
                .replace("{itemType}", itemTypeName);

        String article = " a";
        if (!itemTypeName.isEmpty()) {
            char first = Character.toUpperCase(itemTypeName.charAt(0));
            if (first == 'A' || first == 'E' || first == 'I' || first == 'O' || first == 'U') {
                article = " an";
            }
        }

        List<String> delivLore = delivSec != null ? delivSec.getStringList("lore") : List.of();
        List<String> formattedDelivLore = new ArrayList<>();
        for (String s : delivLore) {
            String f = s.replace("{playerName}", order.getPlayerName())
                    .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()))
                    .replace("{itemType}", itemTypeName)
                    .replace("{itemPrice}", orderManager.formatPrice(order.getPricePerItem()))
                    .replace("{itemPrice_short}", orderManager.formatPriceShort(order.getPricePerItem()))
                    .replace("{deliveryAmount}", orderManager.formatAmount(deliverAmount))
                    .replace("{totalPrice}", orderManager.formatPrice(order.getTotalCost()))
                    .replace("{totalPrice_short}", orderManager.formatPriceShort(order.getTotalCost()))
                    .replace("{moneyForDelivery}", orderManager.formatPrice(payout))
                    .replace("{shouldUsea}", article);
            formattedDelivLore.add(f);
        }

        ItemStack baseItem = order.getItem() != null ? order.getItem().clone() : new ItemStack(Material.STONE);
        ItemBuilder delivBuilder = new ItemBuilder(baseItem).name(delivName).loreStrings(formattedDelivLore);
        delivBuilder.flags(ItemFlag.HIDE_ATTRIBUTES);
        setItem(delivSlot, new GuiItem(delivBuilder.build(), e -> {}));

        ConfigurationSection cancelSec = config.getConfigurationSection("items.cancel");
        int cancelSlot = cancelSec != null ? cancelSec.getInt("slot", 10) : 10;
        String cancelName = cancelSec != null ? cancelSec.getString("name", "#FC0000ᴄᴀɴᴄᴇʟ") : "#FC0000ᴄᴀɴᴄᴇʟ";
        List<String> cancelLore = cancelSec != null ? cancelSec.getStringList("lore") : List.of("&fClick to go back");

        ItemBuilder cancelBuilder = new ItemBuilder(Material.RED_STAINED_GLASS_PANE).name(cancelName).loreStrings(cancelLore);
        setItem(cancelSlot, new GuiItem(cancelBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            if (previousMenu != null) {
                previousMenu.open();
            } else {
                close();
            }
        }));

        ConfigurationSection confirmSec = config.getConfigurationSection("items.confirm");
        int confirmSlot = confirmSec != null ? confirmSec.getInt("slot", 16) : 16;
        String confirmName = confirmSec != null ? confirmSec.getString("name", "#00FC00ᴄᴏɴғɪʀᴍ") : "#00FC00ᴄᴏɴғɪʀᴍ";
        List<String> confirmLore = confirmSec != null ? confirmSec.getStringList("lore") : List.of("&fClick to deliver items", "&7(${moneyForDelivery})");
        List<String> formattedConfirmLore = new ArrayList<>();
        for (String s : confirmLore) {
            formattedConfirmLore.add(s.replace("{moneyForDelivery}", orderManager.formatPrice(payout))
                    .replace("{deliveryAmount}", orderManager.formatAmount(deliverAmount))
                    .replace("{itemType}", itemTypeName)
                    .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount())));
        }

        ItemBuilder confirmBuilder = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name(confirmName).loreStrings(formattedConfirmLore);
        setItem(confirmSlot, new GuiItem(confirmBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            if (deliverAmount <= 0) {
                playConfigSound("error");
                getPlugin().getTextService().send(getViewer(), "no_matching_items");
                return;
            }
            playConfigSound("click");
            close();
            orderManager.deliverOrder(getViewer(), order.getOrderId(), deliverAmount).thenAccept(success -> {
                if (success) {
                    playConfigSound("success");
                } else {
                    playConfigSound("error");
                }
            });
        }));
    }
}
