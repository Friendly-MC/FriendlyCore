package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
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

public class CreateOrderGui extends OrderMenuBase {

    private final ItemStack itemTemplate;
    private long currentAmount = 1;
    private double currentPrice = 10.0;

    public static void openMenu(FriendlyCore plugin, Player player, ItemStack itemTemplate, OrderMenuBase previousMenu) {
        CreateOrderGui gui = new CreateOrderGui(plugin, player, itemTemplate, previousMenu);
        gui.open();
    }

    public CreateOrderGui(FriendlyCore plugin, Player viewer, ItemStack itemTemplate, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/new_order.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/new_order.yml", "ᴏʀᴅᴇʀs » ɴᴇᴡ ᴏʀᴅᴇʀ"), 3, previousMenu);
        this.itemTemplate = itemTemplate != null ? itemTemplate.clone() : new ItemStack(Material.STONE);
        this.currentPrice = plugin.getOrdersModule().getOrderManager().getStandardPrice(this.itemTemplate.getType());
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        double totalPrice = BigDecimal.valueOf(currentAmount)
                .multiply(BigDecimal.valueOf(currentPrice))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        String itemTypeName = orderManager.formatItemName(itemTemplate);

        ConfigurationSection itemSec = config.getConfigurationSection("items.current_item");
        int itemSlot = itemSec != null ? itemSec.getInt("slot", 12) : 12;
        if (itemSlot == -999) itemSlot = 12;

        ItemBuilder itemBuilder = new ItemBuilder(itemTemplate);
        itemBuilder.flags(ItemFlag.HIDE_ATTRIBUTES);
        String currentItemName = itemSec != null ? itemSec.getString("name", "#E63A35{currentAmount} &f{currentItem}") : "#E63A35{currentAmount} &f{currentItem}";
        currentItemName = currentItemName
                .replace("{currentItem}", itemTypeName)
                .replace("{currentAmount}", orderManager.formatAmount(currentAmount));
        itemBuilder.name(currentItemName);

        List<String> rawItemLore = itemSec != null ? itemSec.getStringList("lore") : List.of("&7(" + itemTypeName + ")");
        List<String> formattedItemLore = new ArrayList<>();
        for (String line : rawItemLore) {
            formattedItemLore.add(line
                    .replace("{currentItem}", itemTypeName)
                    .replace("{currentAmount}", orderManager.formatAmount(currentAmount))
                    .replace("{currentPrice}", orderManager.formatPrice(currentPrice))
                    .replace("{currentPrice_short}", orderManager.formatPriceShort(currentPrice))
                    .replace("{totalPrice}", orderManager.formatPrice(totalPrice))
                    .replace("{totalPrice_short}", orderManager.formatPriceShort(totalPrice)));
        }
        itemBuilder.loreStrings(formattedItemLore);
        setItem(itemSlot, new GuiItem(itemBuilder.build(), e -> {}));

        ConfigurationSection amountSec = config.getConfigurationSection("items.amount");
        int amountSlot = amountSec != null ? amountSec.getInt("slot", 13) : 13;
        String amountName = amountSec != null ? amountSec.getString("name", "#E63A35ᴀᴍᴏᴜɴᴛ") : "#E63A35ᴀᴍᴏᴜɴᴛ";
        List<String> amountLore = amountSec != null ? amountSec.getStringList("lore") : List.of("&fClick to type number of items", "&7({currentAmount})");
        List<String> formattedAmountLore = new ArrayList<>();
        for (String s : amountLore) formattedAmountLore.add(s.replace("{currentAmount}", orderManager.formatAmount(currentAmount)));

        ItemBuilder amountBuilder = new ItemBuilder(Material.CHEST).name(amountName).loreStrings(formattedAmountLore);
        setItem(amountSlot, new GuiItem(amountBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            getPlugin().getOrdersModule().getInputManager().openAmountInput(getViewer(), currentAmount, amt -> {
                this.currentAmount = amt;
                open();
            }, this::open);
        }));

        ConfigurationSection priceSec = config.getConfigurationSection("items.price");
        int priceSlot = priceSec != null ? priceSec.getInt("slot", 14) : 14;
        String priceName = priceSec != null ? priceSec.getString("name", "#E63A35ᴘʀɪᴄᴇ") : "#E63A35ᴘʀɪᴄᴇ";
        List<String> priceLore = priceSec != null ? priceSec.getStringList("lore") : List.of("&fClick to type the price per item", "&7(${currentPrice})");
        List<String> formattedPriceLore = new ArrayList<>();
        for (String s : priceLore) formattedPriceLore.add(s.replace("{currentPrice}", orderManager.formatPrice(currentPrice)));

        ItemBuilder priceBuilder = new ItemBuilder(Material.EMERALD).name(priceName).loreStrings(formattedPriceLore);
        setItem(priceSlot, new GuiItem(priceBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            getPlugin().getOrdersModule().getInputManager().openPriceInput(getViewer(), currentPrice, prc -> {
                this.currentPrice = prc;
                open();
            }, this::open);
        }));

        ConfigurationSection confirmSec = config.getConfigurationSection("items.confirm");
        int confirmSlot = confirmSec != null ? confirmSec.getInt("slot", 16) : 16;
        String confirmName = confirmSec != null ? confirmSec.getString("name", "#00FC00ᴄᴏɴғɪʀᴍ") : "#00FC00ᴄᴏɴғɪʀᴍ";
        List<String> confirmLore = confirmSec != null ? confirmSec.getStringList("lore") : List.of("&fClick to confirm order", "&7(Total: ${totalPrice})");
        List<String> formattedConfirmLore = new ArrayList<>();
        for (String s : confirmLore) {
            formattedConfirmLore.add(s.replace("{totalPrice}", orderManager.formatPrice(totalPrice))
                    .replace("{totalPrice_short}", orderManager.formatPriceShort(totalPrice))
                    .replace("{currentAmount}", orderManager.formatAmount(currentAmount))
                    .replace("{currentItem}", itemTypeName));
        }

        ItemBuilder confirmBuilder = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name(confirmName).loreStrings(formattedConfirmLore);
        setItem(confirmSlot, new GuiItem(confirmBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            close();
            orderManager.createOrder(getViewer(), itemTemplate, currentAmount, currentPrice).thenAccept(success -> {
                if (success) {
                    playConfigSound("success");
                } else {
                    playConfigSound("error");
                }
            });
        }));
    }
}
