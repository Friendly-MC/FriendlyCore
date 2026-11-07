package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Administrative GUI for inspecting and recovering permanently deleted or timed-out auction listings.
 */
public class AuctionDeletedGui extends AuctionMenuBase {

    private final UUID targetUser;

    public AuctionDeletedGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        this(plugin, viewer, viewer.getUniqueId(), page, previousMenu);
    }

    public AuctionDeletedGui(FriendlyCore plugin, Player viewer, UUID targetUser, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/deleted-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/deleted-gui.yml", viewer, page, 1, null),
                plugin.getConfigService().get("modules/auctions/guis/deleted-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/deleted-gui.yml").getInt("rows", 6) : 6,
                previousMenu);
        this.targetUser = targetUser;
        setCurrentPage(Math.max(1, page));
        loadItemSlots();
        refresh();
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }


        getPlugin().getDatabaseManager().loadAuctionDeleted(targetUser, Integer.MAX_VALUE).thenAccept(deletedItems -> {
            getPlugin().getScheduler().runEntity(getViewer(), () -> {
                int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
                int totalItems = deletedItems.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
                setMaxPages(maxPages);

                if (getCurrentPage() > maxPages) {
                    setCurrentPage(maxPages);
                }
                loadTemplateButtons();

                int startIndex = (getCurrentPage() - 1) * pageSize;
                int endIndex = Math.min(startIndex + pageSize, totalItems);

                boolean canAdminTake = getViewer().hasPermission("friendlycore.auctions.deleted.admin.take") || getViewer().hasPermission("axauctions.deleted.admin.take");

                for (int i = startIndex; i < endIndex; i++) {
                    int slotIndex = i - startIndex;
                    if (slotIndex >= itemSlots.size()) break;
                    int slot = itemSlots.get(slotIndex);

                    AuctionItem item = deletedItems.get(i);
                    ItemStack displayStack = formatDeletedItemDisplay(item, canAdminTake);

                    setItem(slot, new GuiItem(displayStack, event -> {
                        event.setCancelled(true);

                        if (event.isRightClick() && AuctionSafetyValidator.isContainer(item.getItemStack())) {
                            new AuctionShulkerPreviewGui(getPlugin(), getViewer(), item.getItemStack(), this).open();
                            return;
                        }

                        if (canAdminTake) {
                            if (getPlugin().getHookManager() != null && getPlugin().getHookManager().getMcDataBridgeHook() != null
                                    && getPlugin().getHookManager().getMcDataBridgeHook().isPlayerLocked(getViewer())) {
                                getPlugin().getTextService().send(getViewer(), "auctions.action-cooldown");
                                return;
                            }

                            // AxAuctions treats this as an administrative clone; the archive remains as audit evidence.
                            auctionManager.getSafetyValidator().giveOrDropItem(getViewer(), item.getItemStack());
                            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                            auctionManager.sendLang(getViewer(), "admin-action.take-item");
                            auctionManager.logAction(getViewer(), AuctionActionType.TAKE_ITEM,
                                    getViewer().getName() + "|deleted-gui|" + auctionManager.getItemDisplayName(item.getItemStack()));
                        }
                    }));
                }
            });
        });
    }

    private ItemStack formatDeletedItemDisplay(AuctionItem item, boolean isStaff) {
        ItemStack base = item.getItemStack();
        if (base == null) return new ItemStack(org.bukkit.Material.STONE);

        ItemStack display = base.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        List<Component> originalLore = meta.hasLore() && meta.lore() != null ? meta.lore() : new ArrayList<>();
        FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
        String loreKey = isStaff ? "deleted-gui-admin-item-lore" : "deleted-gui-item-lore";
        List<String> templateLore = msgs != null ? msgs.getStringList(loreKey) : List.of();
        if (templateLore.isEmpty() && msgs != null) {
            templateLore = msgs.getStringList("deleted-gui-item-lore");
        }

        String formattedDate = formatDate(item.getStartTime());
        String priceStr = auctionManager.formatPrice(item.getPrice());
        String priceShortStr = auctionManager.formatPriceShort(item.getPrice());
        String containerMsg = msgs != null ? msgs.getString("container", "&7Right-click to &fview contents") : "&7Right-click to &fview contents";

        List<Component> formattedLore = new ArrayList<>();
        boolean skipNextEmpty = false;
        for (String line : templateLore) {
            if (line.contains("%lore%")) {
                if (!originalLore.isEmpty()) {
                    formattedLore.addAll(originalLore);
                } else {
                    skipNextEmpty = true;
                }
                continue;
            }

            if (skipNextEmpty && line.trim().isEmpty()) {
                skipNextEmpty = false;
                continue;
            }
            skipNextEmpty = false;

            if (line.contains("%container%")) {
                if (AuctionSafetyValidator.isContainer(base)) {
                    Component containerComp = getPlugin().getTextService().parse(containerMsg);
                    if (containerComp.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                        containerComp = containerComp.decoration(TextDecoration.ITALIC, false);
                    }
                    formattedLore.add(containerComp);
                }
                continue;
            }

            String parsedLine = line
                    .replace("%seller%", item.getSellerName())
                    .replace("{seller}", item.getSellerName())
                    .replace("%price_short%", priceShortStr)
                    .replace("{price_short}", priceShortStr)
                    .replace("%price%", priceStr)
                    .replace("{price}", priceStr)
                    .replace("%time%", formattedDate)
                    .replace("{time}", formattedDate)
                    .replace("%date%", formattedDate)
                    .replace("{date}", formattedDate)
                    .replace("%item%", auctionManager.getItemDisplayName(base))
                    .replace("{item}", auctionManager.getItemDisplayName(base))
                    .replace("%amount%", String.valueOf(base.getAmount()))
                    .replace("{amount}", String.valueOf(base.getAmount()));

            Component comp = getPlugin().getTextService().parse(parsedLine);
            if (comp.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                comp = comp.decoration(TextDecoration.ITALIC, false);
            }
            formattedLore.add(comp);
        }

        meta.lore(formattedLore);
        display.setItemMeta(meta);
        fireItemRenderEvent(display);
        return display;
    }
}
