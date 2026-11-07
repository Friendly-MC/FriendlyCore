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

public class YourOrdersGui extends OrderMenuBase {

    private List<BuyOrder> userOrders = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player, OrderMenuBase previousMenu) {
        YourOrdersGui gui = new YourOrdersGui(plugin, player, previousMenu);
        gui.open();
    }

    public YourOrdersGui(FriendlyCore plugin, Player viewer, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/your_orders.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/your_orders.yml", "ᴏʀᴅᴇʀs » ʏᴏᴜʀ ᴏʀᴅᴇʀs"), 6, previousMenu);
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        getPlugin().getDatabaseManager().loadOrdersByCreator(getViewer().getUniqueId()).thenAccept(orders -> {
            userOrders = orders != null ? orders : new ArrayList<>();
            getPlugin().getScheduler().runEntity(getViewer(), this::renderOrders);
        });
    }

    private void renderOrders() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection actualOrderSec = config != null ? config.getConfigurationSection("items.actual_order") : null;
        ConfigurationSection expiredOrderSec = config != null ? config.getConfigurationSection("items.expired_order") : null;
        ConfigurationSection yourOrderSec = config != null ? config.getConfigurationSection("items.your_order") : null;
        ConfigurationSection maxOrdersSec = config != null ? config.getConfigurationSection("items.max_orders") : null;

        int maxLimit = orderManager.getPlayerMaxOrders(getViewer());
        long activeCount = orderManager.getPlayerActiveOrderCount(getViewer().getUniqueId());

        int slot = 0;
        for (BuyOrder order : userOrders) {
            if (slot >= 44) break;
            boolean isExpired = order.isExpired() || order.isCancelled();
            ConfigurationSection sec = isExpired ? expiredOrderSec : actualOrderSec;

            ItemStack icon = buildUserOrderIcon(order, sec, isExpired);
            setItem(slot, new GuiItem(icon, e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                EditOrderGui.openMenu(getPlugin(), getViewer(), order, this);
            }));
            slot++;
        }

        if (slot <= 44) {
            if (activeCount < maxLimit) {
                String name = yourOrderSec != null ? yourOrderSec.getString("name", "<gradient:#E63A35:#FF6B6B><b>ɴᴇᴡ ᴏʀᴅᴇʀ</b></gradient>") : "<gradient:#E63A35:#FF6B6B><b>ɴᴇᴡ ᴏʀᴅᴇʀ</b></gradient>";
                List<String> rawLore = yourOrderSec != null ? yourOrderSec.getStringList("lore") : List.of("&8▪ &7Click to create a new buy order");
                List<String> lore = new ArrayList<>();
                for (String l : rawLore) {
                    lore.add(l.replace("{currentOrders}", String.valueOf(activeCount)).replace("{maxOrders}", String.valueOf(maxLimit)));
                }
                ItemBuilder builder = new ItemBuilder(Material.MAP).name(name).loreStrings(lore);
                builder.flags(ItemFlag.HIDE_ATTRIBUTES);
                setItem(slot, new GuiItem(builder.build(), e -> {
                    if (!checkClickCooldown()) return;
                    playConfigSound("click");
                    SelectItemGui.openMenu(getPlugin(), getViewer(), this);
                }));
            } else {
                String name = maxOrdersSec != null ? maxOrdersSec.getString("name", "&c<b>ᴍᴀx ᴏʀᴅᴇʀs</b>") : "&c<b>ᴍᴀx ᴏʀᴅᴇʀs</b>";
                List<String> rawLore = maxOrdersSec != null ? maxOrdersSec.getStringList("lore") : List.of("&7You have reached your active order limit");
                List<String> lore = new ArrayList<>();
                for (String l : rawLore) {
                    lore.add(l.replace("{currentOrders}", String.valueOf(activeCount)).replace("{maxOrders}", String.valueOf(maxLimit)));
                }
                ItemBuilder builder = new ItemBuilder(Material.BARRIER).name(name).loreStrings(lore);
                builder.flags(ItemFlag.HIDE_ATTRIBUTES);
                setItem(slot, new GuiItem(builder.build(), e -> {
                    if (!checkClickCooldown()) return;
                    playConfigSound("error");
                }));
            }
        }
    }

    private ItemStack buildUserOrderIcon(BuyOrder order, ConfigurationSection section, boolean isExpired) {
        ItemStack baseItem = order.getItem() != null ? order.getItem().clone() : new ItemStack(Material.STONE);
        ItemBuilder builder = new ItemBuilder(baseItem);
        builder.flags(ItemFlag.HIDE_ATTRIBUTES);
        String itemTypeName = orderManager.formatItemName(order.getItem());

        String rawName = section != null ? section.getString("name", "#E63A35{itemAmount} &f{itemType}") : "#E63A35{itemAmount} &f{itemType}";
        rawName = rawName
                .replace("{playerName}", order.getPlayerName())
                .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()))
                .replace("{itemType}", itemTypeName);
        builder.name(rawName);

        List<String> rawLore = section != null ? section.getStringList("lore") : List.of();
        List<String> formattedLore = new ArrayList<>();

        String formattedExpirationTime = orderManager.formatTimeRemaining(order.getRemainingTime());
        long expirationDays = orderManager.getExpirationDays();
        long deletionRemaining = Math.max(0, (order.getExpirationTime() + expirationDays * 86400000L) - System.currentTimeMillis());
        String formattedDeletionTime = orderManager.formatTimeRemaining(deletionRemaining);

        String article = " a";
        if (!itemTypeName.isEmpty()) {
            char first = Character.toUpperCase(itemTypeName.charAt(0));
            if (first == 'A' || first == 'E' || first == 'I' || first == 'O' || first == 'U') {
                article = " an";
            }
        }
        String articleWithoutSpace = article.trim();
        String articleWithSpace = article;

        for (String line : rawLore) {
            String formatted = line
                    .replace("{playerName}", order.getPlayerName())
                    .replace("{itemAmount}", orderManager.formatAmount(order.getTotalAmount()))
                    .replace("{itemType}", itemTypeName)
                    .replace("{itemPrice}", orderManager.formatPrice(order.getPricePerItem()))
                    .replace("{itemPrice_short}", orderManager.formatPriceShort(order.getPricePerItem()))
                    .replace("{currentlyDelivered}", orderManager.formatAmount(order.getDeliveredAmount()))
                    .replace("{totalDelivery}", orderManager.formatAmount(order.getTotalAmount()))
                    .replace("{totalPaid}", orderManager.formatPrice(order.getTotalPaid()))
                    .replace("{totalPaid_short}", orderManager.formatPriceShort(order.getTotalPaid()))
                    .replace("{totalPrice}", orderManager.formatPrice(order.getTotalCost()))
                    .replace("{totalPrice_short}", orderManager.formatPriceShort(order.getTotalCost()))
                    .replace("{formattedExpirationTime}", formattedExpirationTime)
                    .replace("{formattedDeletionTime}", formattedDeletionTime)
                    .replace(" {shouldUsea}", " " + articleWithoutSpace)
                    .replace("{shouldUsea}", articleWithSpace);
            formattedLore.add(formatted);
        }

        builder.loreStrings(formattedLore);
        return builder.build();
    }
}
