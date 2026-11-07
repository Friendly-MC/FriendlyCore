package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Administrative GUI for auditing marketplace actions including creations, purchases, cancellations, and staff actions.
 */
public class AuctionLogsGui extends AuctionMenuBase {

    private final UUID targetUser;

    public AuctionLogsGui(FriendlyCore plugin, Player viewer, int page, AuctionMenuBase previousMenu) {
        this(plugin, viewer, null, page, previousMenu);
    }

    public AuctionLogsGui(FriendlyCore plugin, Player viewer, UUID targetUser, int page, AuctionMenuBase previousMenu) {
        super(plugin, viewer, "modules/auctions/guis/logs-gui.yml",
                resolveConfigTitle(plugin, "modules/auctions/guis/logs-gui.yml", viewer, page, 1, null),
                plugin.getConfigService().get("modules/auctions/guis/logs-gui.yml") != null ?
                        plugin.getConfigService().get("modules/auctions/guis/logs-gui.yml").getInt("rows", 6) : 6,
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


        getPlugin().getDatabaseManager().loadAuctionLogs(targetUser, 200).thenAccept(logs -> {
            getPlugin().getScheduler().runEntity(getViewer(), () -> {
                int pageSize = itemSlots.isEmpty() ? 36 : itemSlots.size();
                int totalItems = logs.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
                setMaxPages(maxPages);

                if (getCurrentPage() > maxPages) {
                    setCurrentPage(maxPages);
                }
                loadTemplateButtons();

                int startIndex = (getCurrentPage() - 1) * pageSize;
                int endIndex = Math.min(startIndex + pageSize, totalItems);

                for (int i = startIndex; i < endIndex; i++) {
                    int slotIndex = i - startIndex;
                    if (slotIndex >= itemSlots.size()) break;
                    int slot = itemSlots.get(slotIndex);

                    AuctionLogEntry log = logs.get(i);
                    ItemStack logItem = formatLogItem(log);

                    setItem(slot, new GuiItem(logItem, event -> event.setCancelled(true)));
                }
            });
        });
    }

    private ItemStack formatLogItem(AuctionLogEntry log) {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        String logTypeName = log.getActionType().name();
        ConfigurationSection typeSec = config != null ? config.getConfigurationSection(logTypeName) : null;

        Material mat = switch (log.getActionType()) {
            case CREATE_AUCTION -> Material.YELLOW_BANNER;
            case PURCHASE -> Material.GREEN_BANNER;
            case CANCEL_SELF -> Material.ORANGE_BANNER;
            case CANCEL_OTHER -> Material.RED_BANNER;
            case TAKE_ITEM -> Material.WHITE_BANNER;
            case PAYOUT -> Material.LIME_BANNER;
        };

        if (typeSec != null) {
            String matName = typeSec.getString("material", typeSec.getString("type", mat.name()));
            Material parsedMat = Material.matchMaterial(matName);
            if (parsedMat != null) mat = parsedMat;
        }

        String dateStr = formatDate(log.getTimestamp());
        String[] parts = log.getExtra() != null ? log.getExtra().split("\\|") : new String[0];
        String actor = parts.length > 0 ? parts[0] : log.getActorName();

        String itemName = log.getActionType().getDisplayName();
        if (typeSec != null && typeSec.contains("name")) {
            itemName = typeSec.getString("name", "");
        }

        List<String> rawLore = typeSec != null ? typeSec.getStringList("lore") : List.of();
        List<Component> lore = new ArrayList<>();

        if (!rawLore.isEmpty()) {
            for (String line : rawLore) {
                String parsed = line
                        .replace("%date%", dateStr)
                        .replace("%player%", actor);

                switch (log.getActionType()) {
                    case PURCHASE -> {
                        String rawPrice = parts.length > 3 ? parts[3] : "0.00";
                        parsed = parsed
                                .replace("%seller%", parts.length > 1 ? parts[1] : "Unknown")
                                .replace("%item%", parts.length > 2 ? AuctionMenuBase.formatEnumName(parts[2]) : "Unknown")
                                .replace("%price_short%", formatLogPriceShort(rawPrice))
                                .replace("{price_short}", formatLogPriceShort(rawPrice))
                                .replace("%price%", formatLogPrice(rawPrice))
                                .replace("{price}", formatLogPrice(rawPrice));
                    }
                    case CREATE_AUCTION, CANCEL_SELF -> {
                        String rawPrice = parts.length > 2 ? parts[2] : "0.00";
                        parsed = parsed
                                .replace("%item%", parts.length > 1 ? AuctionMenuBase.formatEnumName(parts[1]) : "Unknown")
                                .replace("%price_short%", formatLogPriceShort(rawPrice))
                                .replace("{price_short}", formatLogPriceShort(rawPrice))
                                .replace("%price%", formatLogPrice(rawPrice))
                                .replace("{price}", formatLogPrice(rawPrice));
                    }
                    case CANCEL_OTHER -> {
                        String rawPrice = parts.length > 5 ? parts[5] : "0.00";
                        parsed = parsed
                                .replace("%notify-seller%", parts.length > 1 ? parts[1] : "false")
                                .replace("%give-back%", parts.length > 2 ? parts[2] : "false")
                                .replace("%seller%", parts.length > 3 ? parts[3] : "Unknown")
                                .replace("%item%", parts.length > 4 ? AuctionMenuBase.formatEnumName(parts[4]) : "Unknown")
                                .replace("%price_short%", formatLogPriceShort(rawPrice))
                                .replace("{price_short}", formatLogPriceShort(rawPrice))
                                .replace("%price%", formatLogPrice(rawPrice))
                                .replace("{price}", formatLogPrice(rawPrice));
                    }
                    case TAKE_ITEM -> {
                        parsed = parsed
                                .replace("%menu%", parts.length > 1 ? parts[1] : "Unknown")
                                .replace("%item%", parts.length > 2 ? AuctionMenuBase.formatEnumName(parts[2]) : "Unknown");
                    }
                }
                lore.add(getPlugin().getTextService().parse(parsed));
            }
        } else {
            lore.add(Messages.component(getPlugin(), "auctions", "log-action", "%display_name_value%", log.getActionType().getDisplayName()));
            lore.add(Messages.component(getPlugin(), "auctions", "actor", "%actor%", String.valueOf(actor)));
            lore.add(Messages.component(getPlugin(), "auctions", "date", "%date_str%", String.valueOf(dateStr)));
            if (log.getExtra() != null && !log.getExtra().isBlank()) {
                lore.add(Messages.component(getPlugin(), "auctions", "details", "%extra%", String.valueOf(log.getExtra())));
            }
        }

        return new ItemBuilder(mat)
                .name(getPlugin().getTextService().parse(itemName.replace("%player%", actor).replace("%date%", dateStr)))
                .lore(lore)
                .build();
    }

    private String formatLogPrice(String stored) {
        try {
            return auctionManager.formatPrice(Double.parseDouble(stored));
        } catch (NumberFormatException ignored) {
            return stored;
        }
    }

    private String formatLogPriceShort(String stored) {
        try {
            return auctionManager.formatPriceShort(Double.parseDouble(stored));
        } catch (NumberFormatException ignored) {
            return stored;
        }
    }
}
