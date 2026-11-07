package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.enums.OrderFilter;
import dev.lyy.friendlycore.modules.orders.enums.OrderSort;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class OrderMarketGui extends OrderMenuBase {

    private OrderFilter currentFilter = OrderFilter.ALL;
    private OrderSort currentSort = OrderSort.MOST_PAID;
    private String searchQuery = "";
    private List<BuyOrder> displayedOrders = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player) {
        OrderMarketGui gui = new OrderMarketGui(plugin, player, null);
        gui.open();
    }

    public OrderMarketGui(FriendlyCore plugin, Player viewer, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/orders.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/orders.yml", "ᴏʀᴅᴇʀs"), 6, previousMenu);
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        displayedOrders = orderManager.getFilteredAndSortedOrders(currentFilter, currentSort, searchQuery);
        int pageSize = 45;
        int maxPages = Math.max(1, (int) Math.ceil((double) displayedOrders.size() / pageSize));
        setMaxPages(maxPages);

        if (getCurrentPage() > maxPages) {
            setCurrentPage(maxPages);
        }

        renderSortButton();
        renderFilterButton();
        renderSearchButton();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection actualOrderSec = config != null ? config.getConfigurationSection("items.actual_order") : null;

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, displayedOrders.size());

        for (int i = startIndex; i < endIndex; i++) {
            BuyOrder order = displayedOrders.get(i);
            int slot = i - startIndex;
            if (slot >= 45) break;

            ItemStack displayItem = buildOrderIcon(order, actualOrderSec);
            setItem(slot, new GuiItem(displayItem, e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                OrderDeliveryConfirmGui.openMenu(getPlugin(), getViewer(), order, this);
            }));
        }
    }

    private ItemStack buildOrderIcon(BuyOrder order, ConfigurationSection section) {
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

        String article = " a";
        if (!itemTypeName.isEmpty()) {
            char first = Character.toUpperCase(itemTypeName.charAt(0));
            if (first == 'A' || first == 'E' || first == 'I' || first == 'O' || first == 'U') {
                article = " an";
            }
        }
        String articleWithoutSpace = article.trim();
        String articleWithSpace = article;

        String formattedExpirationTime = orderManager.formatTimeRemaining(order.getRemainingTime());

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
                    .replace(" {shouldUsea}", " " + articleWithoutSpace)
                    .replace("{shouldUsea}", articleWithSpace);
            formattedLore.add(formatted);
        }

        builder.loreStrings(formattedLore);
        return builder.build();
    }

    private void renderSearchButton() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;
        ConfigurationSection searchSec = config.getConfigurationSection("items.search");
        if (searchSec == null) return;

        int slot = searchSec.getInt("slot", 50);
        String name = searchSec.getString("name", "<gradient:#E63A35:#FF6B6B><b>sᴇᴀʀᴄʜ</b></gradient>");
        List<String> lore = new ArrayList<>();
        if (!searchQuery.isEmpty()) {
            lore.add("&8▪ &7Filter: &f" + searchQuery);
            lore.add("");
        }
        lore.add("&eClick to search");
        if (!searchQuery.isEmpty()) {
            lore.add("&cRight-click to clear filter");
        }

        ItemBuilder builder = new ItemBuilder(Material.OAK_SIGN).name(name).loreStrings(lore);
        setItem(slot, new GuiItem(builder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            if (e.isRightClick() && !searchQuery.isEmpty()) {
                this.searchQuery = "";
                refresh();
                return;
            }
            getPlugin().getOrdersModule().getInputManager().openSearchInput(getViewer(), this.searchQuery, query -> {
                this.searchQuery = query;
                open();
            }, this::open);
        }));
    }

    private void renderSortButton() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;
        ConfigurationSection sortSec = config.getConfigurationSection("items.sort");
        if (sortSec == null) return;

        int slot = sortSec.getInt("slot", 47);
        String name = sortSec.getString("name", "<gradient:#E63A35:#FF6B6B><b>sᴏʀᴛ</b></gradient>");
        String selColor = sortSec.getString("selected_color", "#E63A35");
        String unselColor = sortSec.getString("unselected_color", "&7");
        String bullet = sortSec.getString("bullet_icon", "▪ ");

        List<String> lore = new ArrayList<>();
        for (OrderSort s : OrderSort.values()) {
            String color = s == currentSort ? selColor : unselColor;
            lore.add(color + bullet + s.getDisplayName());
        }

        ItemBuilder builder = new ItemBuilder(Material.CAULDRON).name(name).loreStrings(lore);
        setItem(slot, new GuiItem(builder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            int nextIndex = (currentSort.ordinal() + 1) % OrderSort.values().length;
            currentSort = OrderSort.values()[nextIndex];
            refresh();
        }));
    }

    private void renderFilterButton() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;
        ConfigurationSection filterSec = config.getConfigurationSection("items.filter");
        if (filterSec == null) return;

        int slot = filterSec.getInt("slot", 48);
        String name = filterSec.getString("name", "<gradient:#E63A35:#FF6B6B><b>ғɪʟᴛᴇʀ</b></gradient>");
        String selColor = filterSec.getString("selected_color", "#E63A35");
        String unselColor = filterSec.getString("unselected_color", "&7");
        String bullet = filterSec.getString("bullet_icon", "▪ ");

        List<String> lore = new ArrayList<>();
        for (OrderFilter f : OrderFilter.values()) {
            String color = f == currentFilter ? selColor : unselColor;
            lore.add(color + bullet + f.getDisplayName());
        }

        ItemBuilder builder = new ItemBuilder(Material.HOPPER).name(name).loreStrings(lore);
        setItem(slot, new GuiItem(builder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            int nextIndex = (currentFilter.ordinal() + 1) % OrderFilter.values().length;
            currentFilter = OrderFilter.values()[nextIndex];
            refresh();
        }));
    }

    @Override
    protected void handleButtonClick(String buttonKey, InventoryClickEvent e) {
        super.handleButtonClick(buttonKey, e);
        switch (buttonKey.toLowerCase()) {
            case "your_orders" -> YourOrdersGui.openMenu(getPlugin(), getViewer(), this);
            case "orders" -> refresh();
            case "search" -> {
                getPlugin().getOrdersModule().getInputManager().openSearchInput(getViewer(), this.searchQuery, query -> {
                    this.searchQuery = query;
                    open();
                }, this::open);
            }
        }
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query != null ? query : "";
    }
}
