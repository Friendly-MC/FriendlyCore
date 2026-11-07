package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Administrative GUI allowing staff to delete or cancel an auction listing,
 * matching AxAuctions admin-removal-gui layout, items, and actions.
 */
public class AuctionAdminRemovalGui extends AuctionMenuBase {

    private final AuctionItem item;

    public AuctionAdminRemovalGui(FriendlyCore plugin, Player viewer, AuctionItem item, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/admin-removal-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/admin-removal-gui.yml", viewer, 1, 1, getItemDisplayName(item.getItemStack())),
                plugin.getConfigService().get("modules/auctions/guis/admin-removal-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/admin-removal-gui.yml").getInt("rows", 5) : 5,
                previousMenu);
        this.item = item;
        refresh();
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        loadTemplateButtons();

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        int itemSlot = 22;
        if (config != null) {
            ConfigurationSection sec = config.getConfigurationSection("items.item");
            if (sec == null) sec = config.getConfigurationSection("item");
            if (sec != null && sec.isInt("slot")) {
                itemSlot = sec.getInt("slot");
            }
        }

        ItemStack displayItem = formatAuctionItemDisplay(item, "item-lore");
        setItem(itemSlot, new GuiItem(displayItem, event -> {
            event.setCancelled(true);
            if (event.isRightClick() && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
            }
        }));

        // Option 1: Remove Silently (Give item to staff, delete listing, no notification)
        bindActionButtons("remove-1", List.of(10, 19, 28), () -> handleRemoveMode(AdminRemoveMode.REMOVE_SILENTLY));

        // Option 2: Remove & Notify (Give item to staff, delete listing, notify seller)
        bindActionButtons("remove-2", List.of(11, 20, 29), () -> handleRemoveMode(AdminRemoveMode.REMOVE_AND_NOTIFY));

        // Option 3: Cancel Silently (Mark expired for seller reclaim, no notification)
        bindActionButtons("remove-3", List.of(15, 24, 33), () -> handleRemoveMode(AdminRemoveMode.CANCEL_SILENTLY));

        // Option 4: Cancel & Notify (Mark expired for seller reclaim, notify seller)
        bindActionButtons("remove-4", List.of(16, 25, 34), () -> handleRemoveMode(AdminRemoveMode.CANCEL_AND_NOTIFY));
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        super.handleClose(event);
        reopenPreviousMenu(event);
    }

    private void bindActionButtons(String key, List<Integer> fallbackSlots, Runnable action) {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection sec = null;
        if (config != null) {
            sec = config.getConfigurationSection("items." + key);
            if (sec == null) sec = config.getConfigurationSection(key);
        }

        List<Integer> slots = sec != null ? parseSlots(sec, getSize()) : fallbackSlots;
        String matName = sec != null ? sec.getString("material", "STONE") : "STONE";
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.STONE;

        String name = sec != null ? sec.getString("name", "") : "";
        List<String> lore = sec != null ? sec.getStringList("lore") : List.of();
        List<String> actions = sec != null ? sec.getStringList("actions") : List.of("[SOUND] ui.button.click|1|1");

        ItemBuilder builder = new ItemBuilder(mat);
        if (!name.isEmpty()) builder.name(getPlugin().getTextService().parse(applyPlaceholders(name)));
        if (!lore.isEmpty()) {
            builder.lore(lore.stream().map(l -> getPlugin().getTextService().parse(applyPlaceholders(l))).toList());
        }

        ItemStack stack = builder.build();
        for (int slot : slots) {
            if (slot >= 0 && slot < getSize()) {
                setItem(slot, new GuiItem(stack, event -> {
                    event.setCancelled(true);
                    for (String act : actions) {
                        executeAction(act, event);
                    }
                    action.run();
                }));
            }
        }
    }

    private void handleRemoveMode(AdminRemoveMode mode) {
        if (!getViewer().hasPermission("friendlycore.auctions.admin") && !getViewer().hasPermission("axauctions.admin")) return;
        if (previousMenu != null) previousMenu.open();
        else getViewer().closeInventory();
        boolean cancel = mode == AdminRemoveMode.CANCEL_SILENTLY || mode == AdminRemoveMode.CANCEL_AND_NOTIFY;
        boolean notify = mode == AdminRemoveMode.REMOVE_AND_NOTIFY || mode == AdminRemoveMode.CANCEL_AND_NOTIFY;
        auctionManager.adminRemoveAuction(item, getViewer(), cancel, notify);
    }

    public enum AdminRemoveMode {
        REMOVE_SILENTLY,
        REMOVE_AND_NOTIFY,
        CANCEL_SILENTLY,
        CANCEL_AND_NOTIFY
    }
}
