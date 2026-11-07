package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Confirmation GUI presented to players before publishing a new market listing,
 * matching AxAuctions confirm-listing-gui layout, items, and actions.
 */
public class AuctionConfirmListingGui extends AuctionMenuBase {

    private final ItemStack listingItem;
    private final double price;

    public AuctionConfirmListingGui(FriendlyCore plugin, Player viewer, ItemStack listingItem, double price, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/confirm-listing-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/confirm-listing-gui.yml", viewer, 1, 1, getItemDisplayName(listingItem)),
                plugin.getConfigService().get("modules/auctions/guis/confirm-listing-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/confirm-listing-gui.yml").getInt("rows", 5) : 5,
                previousMenu);
        this.listingItem = listingItem;
        this.price = price;
        refresh();
    }

    @Override
    public void refresh() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        loadTemplateButtons();

        renderConfirmationButtons(() -> auctionManager.createAuction(getViewer(), listingItem, price),
                price, listingItem, getViewer().getName());

        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection itemSec = null;
        if (config != null) {
            itemSec = config.getConfigurationSection("items.item");
            if (itemSec == null) itemSec = config.getConfigurationSection("item");
        }

        // 3. Item preview with formatted listing confirmation lore
        int itemSlot = itemSec != null && itemSec.isInt("slot") ? itemSec.getInt("slot") : 22;
        List<String> itemLoreTemplate = itemSec != null ? itemSec.getStringList("lore") : List.of();

        ItemStack displayStack = formatListingPreview(listingItem, price, itemLoreTemplate);
        setItem(itemSlot, new GuiItem(displayStack, event -> {
            event.setCancelled(true);
            if (event.isRightClick() && AuctionSafetyValidator.isContainer(listingItem)) {
                new AuctionShulkerPreviewGui(getPlugin(), getViewer(), listingItem, this).open();
            }
        }));
    }

    private ItemStack formatListingPreview(ItemStack base, double price, List<String> templateLore) {
        if (base == null) return new ItemStack(Material.STONE);
        ItemStack display = base.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        if (templateLore == null || templateLore.isEmpty()) {
            return display;
        }

        List<Component> originalLore = meta.hasLore() && meta.lore() != null ? meta.lore() : new ArrayList<>();
        long expireDuration = auctionManager.getExpireDurationMillis();
        long deletionDuration = auctionManager.getDeletionDurationMillis();
        String expiresStr = formatTimeRemaining(expireDuration);
        String deletionStr = formatTimeRemaining(expireDuration + deletionDuration);
        String priceStr = auctionManager.formatPrice(price);
        String priceShortStr = auctionManager.formatPriceShort(price);

        String containerText = AuctionSafetyValidator.isContainer(base) ?
                getPlugin().getConfigService().get("modules/auctions/messages.yml").getString("container", "&7Right-click to &fview contents") : "";

        List<Component> finalLore = new ArrayList<>();
        boolean skipNextEmpty = false;
        for (String line : templateLore) {
            if (line.contains("%lore%")) {
                if (!originalLore.isEmpty()) {
                    finalLore.addAll(originalLore);
                } else {
                    skipNextEmpty = true;
                }
            } else if (skipNextEmpty && line.trim().isEmpty()) {
                skipNextEmpty = false;
                continue;
            } else if (line.contains("%container%")) {
                skipNextEmpty = false;
                if (!containerText.isEmpty()) {
                    Component c = getPlugin().getTextService().parse(applyPlaceholders(containerText));
                    if (c.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                        c = c.decoration(TextDecoration.ITALIC, false);
                    }
                    finalLore.add(c);
                }
            } else {
                skipNextEmpty = false;
                String parsed = applyPlaceholders(line)
                        .replace("%price_short%", priceShortStr)
                        .replace("{price_short}", priceShortStr)
                        .replace("%price%", priceStr)
                        .replace("{price}", priceStr)
                        .replace("%seller%", getViewer().getName())
                        .replace("{seller}", getViewer().getName())
                        .replace("%expires%", expiresStr)
                        .replace("{expires}", expiresStr)
                        .replace("%deletion%", deletionStr)
                        .replace("{deletion}", deletionStr)
                        .replace("%item%", getItemDisplayName(base))
                        .replace("{item}", getItemDisplayName(base))
                        .replace("%amount%", String.valueOf(base.getAmount()))
                        .replace("{amount}", String.valueOf(base.getAmount()));
                Component c = getPlugin().getTextService().parse(parsed);
                if (c.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                    c = c.decoration(TextDecoration.ITALIC, false);
                }
                finalLore.add(c);
            }
        }

        meta.lore(finalLore);
        display.setItemMeta(meta);
        return display;
    }
}
