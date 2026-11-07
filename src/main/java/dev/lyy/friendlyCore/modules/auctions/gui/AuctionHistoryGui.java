package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry;
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
 * GUI for browsing historical auction purchases and sales, supporting player-specific or global views,
 * with staff item reproduction capabilities.
 */
public class AuctionHistoryGui extends AuctionMenuBase {

    private final UUID targetUser;

    public AuctionHistoryGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        this(plugin, viewer, plugin.getConfigService().get("modules/auctions/config.yml").getBoolean("show-global-history", false)
                ? null : viewer.getUniqueId(), page, previousMenu);
    }

    public AuctionHistoryGui(FriendlyCore plugin, Player viewer, UUID targetUser, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/history-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/history-gui.yml", viewer, page, 1, null),
                plugin.getConfigService().get("modules/auctions/guis/history-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/history-gui.yml").getInt("rows", 6) : 6,
                previousMenu);
        this.targetUser = targetUser;
        setCurrentPage(Math.max(1, page));
        loadItemSlots();
        refresh();
    }

    private int historyLimit() {
        int limit = getPlugin().getConfigService().get("modules/auctions/config.yml").getInt("max-history-length", -1);
        return limit < 0 ? Integer.MAX_VALUE : limit;
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }


        getPlugin().getDatabaseManager().loadAuctionHistory(targetUser, historyLimit()).thenAccept(historyEntries -> {
            getPlugin().getScheduler().runEntity(getViewer(), () -> {
                int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
                int totalItems = historyEntries.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
                setMaxPages(maxPages);

                if (getCurrentPage() > maxPages) {
                    setCurrentPage(maxPages);
                }
                loadTemplateButtons();

                int startIndex = (getCurrentPage() - 1) * pageSize;
                int endIndex = Math.min(startIndex + pageSize, totalItems);

                boolean canAdminTake = getViewer().hasPermission("friendlycore.auctions.history.admin.take") || getViewer().hasPermission("axauctions.history.admin.take");

                for (int i = startIndex; i < endIndex; i++) {
                    int slotIndex = i - startIndex;
                    if (slotIndex >= itemSlots.size()) break;
                    int slot = itemSlots.get(slotIndex);

                    AuctionHistoryEntry entry = historyEntries.get(i);
                    ItemStack displayStack = formatHistoryItemDisplay(entry, canAdminTake);

                    setItem(slot, new GuiItem(displayStack, event -> {
                        event.setCancelled(true);

                        if (event.isRightClick() && AuctionSafetyValidator.isContainer(entry.getItemStack())) {
                            new AuctionShulkerPreviewGui(getPlugin(), getViewer(), entry.getItemStack(), this).open();
                            return;
                        }

                        if (canAdminTake) {
                            auctionManager.getSafetyValidator().giveOrDropItem(getViewer(), entry.getItemStack());
                            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                            auctionManager.sendLang(getViewer(), "admin-action.take-item");
                            auctionManager.logAction(getViewer(), AuctionActionType.TAKE_ITEM, getViewer().getName() + "|history-gui|" + auctionManager.getItemDisplayName(entry.getItemStack()));
                        }
                    }));
                }
            });
        });
    }

    private ItemStack formatHistoryItemDisplay(AuctionHistoryEntry entry, boolean isStaff) {
        ItemStack base = entry.getItemStack();
        if (base == null) return new ItemStack(org.bukkit.Material.STONE);

        ItemStack display = base.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        List<Component> originalLore = meta.hasLore() && meta.lore() != null ? meta.lore() : new ArrayList<>();
        FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
        String loreKey = isStaff ? "history-admin-item-lore" : "history-item-lore";
        List<String> templateLore = msgs != null ? msgs.getStringList(loreKey) : List.of();
        if (templateLore.isEmpty() && msgs != null) {
            templateLore = msgs.getStringList("history-item-lore");
        }

        String formattedDate = formatDate(entry.getSoldTime());
        String priceStr = auctionManager.formatPrice(entry.getPrice());
        String priceShortStr = auctionManager.formatPriceShort(entry.getPrice());
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
                    .replace("%seller%", entry.getSellerName())
                    .replace("{seller}", entry.getSellerName())
                    .replace("%buyer%", entry.getBuyerName())
                    .replace("{buyer}", entry.getBuyerName())
                    .replace("%price_short%", priceShortStr)
                    .replace("{price_short}", priceShortStr)
                    .replace("%price%", priceStr)
                    .replace("{price}", priceStr)
                    .replace("%date%", formattedDate)
                    .replace("{date}", formattedDate)
                    .replace("%time%", formattedDate)
                    .replace("{time}", formattedDate)
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
