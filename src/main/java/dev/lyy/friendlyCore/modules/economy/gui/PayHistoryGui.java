package dev.lyy.friendlycore.modules.economy.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.economy.Transaction;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paginated GUI displaying personal or inspected transaction history.
 */
public class PayHistoryGui extends PaginatedGui {

    private final UUID targetUuid;
    private final String targetName;
    private final List<Transaction> transactions;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private final int pageSize = 45; // 5 full rows of 9 slots (0..44)

    public PayHistoryGui(FriendlyCore plugin, Player viewer, UUID targetUuid, String targetName, List<Transaction> transactions, int page) {
        this(plugin, viewer, targetUuid, targetName, transactions,
                Math.max(1, (int) Math.ceil((double) transactions.size() / 45)),
                page);
    }

    private PayHistoryGui(FriendlyCore plugin, Player viewer, UUID targetUuid, String targetName, List<Transaction> transactions, int totalPages, int page) {
        super(plugin, viewer,
                plugin.getTextService().parse(
                        plugin.getConfigService().getEconomyConfig().getString("payhistory.inventory-title", "Pay History %page%/%max_page%"),
                        Map.of(
                                "%page%", String.valueOf(Math.max(1, Math.min(page, totalPages))),
                                "%max_page%", String.valueOf(totalPages)
                        )),
                6);
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.transactions = transactions;
        setMaxPages(totalPages);
        setCurrentPage(Math.max(1, Math.min(page, totalPages)));
        render();
    }

    public void render() {
        FriendlyCore plugin = getPlugin();
        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, transactions.size());

        List<String> configuredLore = plugin.getConfigService().getEconomyConfig().getStringList("payhistory.transaction-lore");
        if (configuredLore.isEmpty()) {
            configuredLore = List.of(
                    "",
                    "&fStatus: %type%",
                    "&fAmount: %amount_formatted%",
                    "&fDate: &7%date%",
                    "&fFrom: &7%from%",
                    "&fTo: &7%to%",
                    "&fTransaction ID: &b#&b%transaction_id%"
            );
        }

        for (int i = startIndex; i < endIndex; i++) {
            Transaction tx = transactions.get(i);
            int slot = i - startIndex;

            boolean isOutgoing = tx.getSenderUuid() != null && tx.getSenderUuid().equals(targetUuid);
            String status = isOutgoing ? "&cSent" : "&aReceived";
            String rawAmount = plugin.getNumberFormatter().format(tx.getAmount());
            String formattedAmount = isOutgoing ? "&c-$" + rawAmount : "&a+$" + rawAmount;
            String dateFormatted = DATE_FORMATTER.format(Instant.ofEpochMilli(tx.getTimestamp()));

            String fromName = tx.getSenderName() != null ? tx.getSenderName() : "System";
            String toName = tx.getReceiverName() != null ? tx.getReceiverName() : "System";
            UUID skullUuid = isOutgoing ? tx.getReceiverUuid() : tx.getSenderUuid();
            String skullHead = isOutgoing ? toName : fromName;

            Map<String, String> lorePlaceholders = Map.of(
                    "%type%", status,
                    "%amount_formatted%", formattedAmount,
                    "%date%", dateFormatted,
                    "%from%", fromName,
                    "%to%", toName,
                    "%transaction_id%", String.valueOf(tx.getId())
            );

            ItemBuilder builder = new ItemBuilder(Material.PLAYER_HEAD)
                    .name(plugin.getTextService().parse(formattedAmount))
                    .lore(plugin.getTextService().parseList(configuredLore, lorePlaceholders));

            if (skullUuid != null) {
                builder.skullOwner(skullUuid, skullHead);
            } else if (skullHead != null && !skullHead.equalsIgnoreCase("System") && !skullHead.equalsIgnoreCase("Console")) {
                builder.skullOwner(skullHead);
            }

            ItemStack skull = builder.build();
            setItem(slot, new GuiItem(skull, e -> e.setCancelled(true)));
        }

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
        for (int s = 45; s < 54; s++) {
            setItem(s, new GuiItem(filler, e -> e.setCancelled(true)));
        }

        int prevPage = getCurrentPage() - 1;
        int nextPage = getCurrentPage() + 1;

        if (getCurrentPage() > 1) {
            ItemStack prevItem = new ItemBuilder(Material.ARROW)
                    .name(Messages.component(plugin, "economy", "previous-page"))
                    .lore(Messages.component(plugin, "economy", "click-to-navigate-to-page", "%prev_page%", String.valueOf(prevPage)))
                    .build();
            setItem(48, new GuiItem(prevItem, e -> {
                e.setCancelled(true);
                getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                new PayHistoryGui(plugin, getViewer(), targetUuid, targetName, transactions, prevPage).open();
            }));
        }

        ItemStack actualItem = new ItemBuilder(Material.SPECTRAL_ARROW)
                .name(Messages.component(plugin, "economy", "actual-page"))
                .lore(Messages.component(plugin, "economy", "this-is-page", "%current_page%", String.valueOf(getCurrentPage())))
                .build();
        setItem(49, new GuiItem(actualItem, e -> e.setCancelled(true)));

        if (getCurrentPage() < getMaxPages()) {
            ItemStack nextItem = new ItemBuilder(Material.ARROW)
                    .name(Messages.component(plugin, "economy", "next-page"))
                    .lore(Messages.component(plugin, "economy", "click-to-navigate-to-page-2", "%next_page%", String.valueOf(nextPage)))
                    .build();
            setItem(50, new GuiItem(nextItem, e -> {
                e.setCancelled(true);
                getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                new PayHistoryGui(plugin, getViewer(), targetUuid, targetName, transactions, nextPage).open();
            }));
        }
    }
}
