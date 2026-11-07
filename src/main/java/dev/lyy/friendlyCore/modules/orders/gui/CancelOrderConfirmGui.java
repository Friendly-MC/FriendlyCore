package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class CancelOrderConfirmGui extends OrderMenuBase {

    private final BuyOrder order;

    public static void openMenu(FriendlyCore plugin, Player player, BuyOrder order, OrderMenuBase previousMenu) {
        CancelOrderConfirmGui gui = new CancelOrderConfirmGui(plugin, player, order, previousMenu);
        gui.open();
    }

    public CancelOrderConfirmGui(FriendlyCore plugin, Player viewer, BuyOrder order, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/edit_order_confirm.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/edit_order_confirm.yml", "ᴏʀᴅᴇʀs » ᴄᴀɴᴄᴇʟ ᴏʀᴅᴇʀ"), 3, previousMenu);
        this.order = order;
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        double refundAmount = order.getRemainingCost();

        ConfigurationSection cancelSec = config.getConfigurationSection("items.cancel");
        int cancelSlot = cancelSec != null ? cancelSec.getInt("slot", 10) : 10;
        String cancelName = cancelSec != null ? cancelSec.getString("name", "#FC0000ᴄᴀɴᴄᴇʟ") : "#FC0000ᴄᴀɴᴄᴇʟ";
        List<String> cancelLore = cancelSec != null ? cancelSec.getStringList("lore") : List.of("&fClick to return");

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
        List<String> confirmLore = confirmSec != null ? confirmSec.getStringList("lore") : List.of("&fClick to cancel order", "&7(Refund: ${refundAmount})");
        List<String> formattedConfirmLore = new ArrayList<>();
        for (String s : confirmLore) formattedConfirmLore.add(s.replace("{refundAmount}", orderManager.formatPrice(refundAmount)));

        ItemBuilder confirmBuilder = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name(confirmName).loreStrings(formattedConfirmLore);
        setItem(confirmSlot, new GuiItem(confirmBuilder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            close();
            orderManager.cancelOrder(getViewer(), order.getOrderId()).thenAccept(success -> {
                if (success) {
                    playConfigSound("success");
                } else {
                    playConfigSound("error");
                }
            });
        }));
    }
}
