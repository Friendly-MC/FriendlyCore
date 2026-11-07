package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.orders.enums.ItemSelectSort;
import dev.lyy.friendlycore.modules.orders.enums.OrderFilter;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class SelectItemGui extends OrderMenuBase {

    private OrderFilter currentFilter = OrderFilter.ALL;
    private ItemSelectSort currentSort = ItemSelectSort.A_Z;
    private String searchQuery = "";
    private List<Material> catalogMaterials = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player, OrderMenuBase previousMenu) {
        SelectItemGui gui = new SelectItemGui(plugin, player, previousMenu);
        gui.open();
    }

    public SelectItemGui(FriendlyCore plugin, Player viewer, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/select_item.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/select_item.yml", "ᴏʀᴅᴇʀs » sᴇʟᴇᴄᴛ ɪᴛᴇᴍ"), 6, previousMenu);
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();
        filterAndSortCatalog();

        int pageSize = 45;
        int maxPages = Math.max(1, (int) Math.ceil((double) catalogMaterials.size() / pageSize));
        setMaxPages(maxPages);

        if (getCurrentPage() > maxPages) {
            setCurrentPage(maxPages);
        }

        renderSortButton();
        renderFilterButton();
        renderSearchButton();

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, catalogMaterials.size());

        for (int i = startIndex; i < endIndex; i++) {
            Material mat = catalogMaterials.get(i);
            int slot = i - startIndex;
            if (slot >= 45) break;

            double stdPrice = orderManager.getStandardPrice(mat);
            List<String> lore = new ArrayList<>();
            lore.add("&8▪ &7Estimated Value: &a$" + orderManager.formatPrice(stdPrice));
            lore.add("");
            lore.add("&aClick to create buy order");

            ItemStack icon = new ItemBuilder(mat)
                    .flags(ItemFlag.HIDE_ATTRIBUTES)
                    .loreStrings(lore)
                    .build();
            setItem(slot, new GuiItem(icon, e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                onItemSelected(mat);
            }));
        }
    }

    private void onItemSelected(Material mat) {
        ItemStack template = new ItemStack(mat);
        FileConfiguration config = getPlugin().getConfigService().get("modules/orders/config.yml");
        boolean skipEnchants = config != null && config.getBoolean("skipEnchants", false);

        boolean isEnchantable = mat.getMaxDurability() > 0
                || mat == Material.BOOK
                || mat == Material.ENCHANTED_BOOK
                || mat == Material.BOW
                || mat == Material.CROSSBOW
                || mat == Material.TRIDENT;

        if (isEnchantable && !skipEnchants) {
            PickEnchantmentsGui.openMenu(getPlugin(), getViewer(), template, this);
        } else {
            CreateOrderGui.openMenu(getPlugin(), getViewer(), template, this);
        }
    }

    private void filterAndSortCatalog() {
        catalogMaterials.clear();
        Set<String> blacklisted = orderManager.getBlacklistedMaterials();
        String query = searchQuery.toLowerCase(Locale.ROOT).trim();

        for (Material mat : Material.values()) {
            if (!mat.isItem() || mat.isAir()) continue;
            if (blacklisted.contains(mat.name())) continue;
            if (!currentFilter.matches(mat)) continue;

            if (!query.isEmpty() && !mat.name().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            catalogMaterials.add(mat);
        }

        switch (currentSort) {
            case A_Z -> catalogMaterials.sort(Comparator.comparing(Enum::name));
            case Z_A -> catalogMaterials.sort((a, b) -> b.name().compareTo(a.name()));
            case HIGHEST_PRICE -> catalogMaterials.sort((a, b) -> Double.compare(orderManager.getStandardPrice(b), orderManager.getStandardPrice(a)));
            case LOWEST_PRICE -> catalogMaterials.sort((a, b) -> Double.compare(orderManager.getStandardPrice(a), orderManager.getStandardPrice(b)));
        }
    }

    private void renderSortButton() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;
        ConfigurationSection sortSec = config.getConfigurationSection("items.sort_select");
        if (sortSec == null) return;

        int slot = sortSec.getInt("slot", 49);
        String name = sortSec.getString("name", "<gradient:#E63A35:#FF6B6B><b>sᴏʀᴛ</b></gradient>");
        String selColor = sortSec.getString("selected_color", "#E63A35");
        String unselColor = sortSec.getString("unselected_color", "&7");
        String bullet = sortSec.getString("bullet_icon", "▪ ");

        List<String> lore = new ArrayList<>();
        for (ItemSelectSort s : ItemSelectSort.values()) {
            String color = s == currentSort ? selColor : unselColor;
            lore.add(color + bullet + s.getDisplayName());
        }

        ItemBuilder builder = new ItemBuilder(Material.CAULDRON).name(name).loreStrings(lore);
        setItem(slot, new GuiItem(builder.build(), e -> {
            if (!checkClickCooldown()) return;
            playConfigSound("click");
            int nextIndex = (currentSort.ordinal() + 1) % ItemSelectSort.values().length;
            currentSort = ItemSelectSort.values()[nextIndex];
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
                searchQuery = "";
                refresh();
                return;
            }
            getPlugin().getOrdersModule().getInputManager().openSearchInput(getViewer(), this.searchQuery, query -> {
                this.searchQuery = query;
                open();
            }, this::open);
        }));
    }

    @Override
    protected void handleButtonClick(String buttonKey, InventoryClickEvent e) {
        super.handleButtonClick(buttonKey, e);
        if (buttonKey.equalsIgnoreCase("search")) {
            getPlugin().getOrdersModule().getInputManager().openSearchInput(getViewer(), this.searchQuery, query -> {
                this.searchQuery = query;
                open();
            }, this::open);
        }
    }
}
