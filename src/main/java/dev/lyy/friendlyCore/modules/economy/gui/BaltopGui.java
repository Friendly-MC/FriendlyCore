package dev.lyy.friendlycore.modules.economy.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.economy.AccountData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Paginated GUI displaying top balance leaderboards.
 */
public class BaltopGui extends PaginatedGui {

    private final List<AccountData> accounts;
    private final int pageSize = 45; // 5 full rows of 9 slots (0..44)

    public BaltopGui(FriendlyCore plugin, Player viewer, List<AccountData> accounts, int page) {
        this(plugin, viewer, accounts,
                Math.max(1, (int) Math.ceil((double) accounts.size() / 45)),
                page);
    }

    private BaltopGui(FriendlyCore plugin, Player viewer, List<AccountData> accounts, int totalPages, int page) {
        super(plugin, viewer,
                plugin.getTextService().parse(
                        plugin.getConfigService().getEconomyConfig().getString("baltop.inventory-title", "Leaderboard %page%/%max_page%"),
                        Map.of(
                                "%page%", String.valueOf(Math.max(1, Math.min(page, totalPages))),
                                "%max_page%", String.valueOf(totalPages)
                        )
                ),
                6);
        this.accounts = accounts;
        setMaxPages(totalPages);
        setCurrentPage(Math.max(1, Math.min(page, totalPages)));
        render();
    }

    public void render() {
        FriendlyCore plugin = getPlugin();
        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, accounts.size());

        String namePattern = plugin.getConfigService().getEconomyConfig().getString("baltop.player-display-name", "%player% &7(#%rank%)");
        List<String> loreConfig = plugin.getConfigService().getEconomyConfig().getStringList("baltop.player-lore");
        if (loreConfig.isEmpty()) {
            loreConfig = List.of("&fMoney: &7$%money%");
        }

        for (int i = startIndex; i < endIndex; i++) {
            AccountData acc = accounts.get(i);
            int rank = i + 1;
            int slot = i - startIndex;

            String balanceFormatted = plugin.getNumberFormatter().format(acc.getBalance());
            Map<String, String> placeholders = Map.of(
                    "%player%", acc.getUsername(),
                    "%rank%", String.valueOf(rank),
                    "%money%", balanceFormatted,
                    "%money_raw%", String.valueOf(acc.getBalance())
            );

            ItemStack skull = new ItemBuilder(Material.PLAYER_HEAD)
                    .skullOwner(acc.getUuid(), acc.getUsername())
                    .name(plugin.getTextService().parse(namePattern, placeholders))
                    .lore(plugin.getTextService().parseList(loreConfig, placeholders))
                    .hideFlags()
                    .build();

            setItem(slot, new GuiItem(skull, e -> e.setCancelled(true)));
        }

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).hideFlags().build();
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
                new BaltopGui(plugin, getViewer(), accounts, prevPage).open();
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
                new BaltopGui(plugin, getViewer(), accounts, nextPage).open();
            }));
        }
    }
}
