package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.category.AuctionCategory;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI for browsing and selecting market item categories to filter active auction listings,
 * matching AxAuctions category-gui layout.
 */
public class AuctionCategoryGui extends AuctionMenuBase {

    private final List<Integer> categorySlots = new ArrayList<>();

    public AuctionCategoryGui(FriendlyCore plugin, Player viewer, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/category-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/category-gui.yml", viewer, 1, 1, null),
                plugin.getConfigService().get("modules/auctions/guis/category-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/category-gui.yml").getInt("rows", 5) : 5,
                previousMenu);
        loadCategorySlots();
        refresh();
    }

    private void loadCategorySlots() {
        categorySlots.clear();
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config != null) {
            ConfigurationSection sec = config.getConfigurationSection("items.categories");
            if (sec == null) sec = config.getConfigurationSection("categories");
            if (sec != null) {
                categorySlots.addAll(parseSlots(sec, getSize()));
            }
        }
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        loadTemplateButtons();

        // If categories slot list was explicitly defined, populate dynamic categories
        if (!categorySlots.isEmpty()) {
            List<AuctionCategory> allCategories = auctionManager.getCategoryManager().getAllCategories();
            for (int i = 0; i < allCategories.size() && i < categorySlots.size(); i++) {
                int slot = categorySlots.get(i);
                AuctionCategory cat = allCategories.get(i);
                boolean isSelected = cat.getId().equalsIgnoreCase(auctionUser.getSelectedCategory());

                List<Component> lore = new ArrayList<>();
                lore.add(Messages.component(getPlugin(), "auctions", "category-filter"));
                lore.add(getPlugin().getTextService().parse(""));
                lore.add(Messages.component(getPlugin(), "auctions", "click-to-select"));
                if (isSelected) {
                    lore.add(Messages.component(getPlugin(), "auctions", "category-selected"));
                }

                ItemBuilder builder = new ItemBuilder(cat.getIcon())
                        .name(Messages.component(getPlugin(), "auctions", "category-name", "%cat_name%", cat.getName()))
                        .lore(lore)
                        .glow(isSelected);

                if (cat.getCustomModelData() > 0) {
                    builder.customModelData(cat.getCustomModelData());
                }

                ItemStack iconStack = builder.build();
                setItem(slot, new GuiItem(iconStack, event -> {
                    event.setCancelled(true);
                    auctionUser.setSelectedCategory(cat.getId());
                    getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    if (previousMenu != null) {
                        previousMenu.refresh();
                        previousMenu.open();
                    } else {
                        new AuctionMainGui(getPlugin(), getViewer(), 1, null).open();
                    }
                }));
            }
        }
    }
}
