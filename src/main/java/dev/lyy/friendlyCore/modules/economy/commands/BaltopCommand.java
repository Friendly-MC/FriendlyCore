package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.gui.BaltopGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Command executor for viewing top balance leaderboards via GUI or chat.
 */
public class BaltopCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public BaltopCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        int page = 1;
        boolean textMode = false;

        if (args.length > 0) {
            try {
                page = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException e) {
                if (args[0].equalsIgnoreCase("text") || args[0].equalsIgnoreCase("chat")) {
                    textMode = true;
                }
            }
        }

        int finalPage = page;
        boolean finalTextMode = textMode;

        plugin.getEconomyManager().getBaltop(100).thenAccept(list -> {
            if (sender instanceof Player player && !finalTextMode) {
                plugin.getScheduler().runEntity(player, () -> new BaltopGui(plugin, player, list, finalPage).open());
            } else {
                plugin.getScheduler().runGlobal(() -> {
                int pageSize = 10;
                int maxPages = Math.max(1, (int) Math.ceil((double) list.size() / pageSize));
                int safePage = Math.min(finalPage, maxPages);

                Messages.send(plugin, sender, "economy", "baltop-header",
                        "%page%", String.valueOf(safePage), "%total%", String.valueOf(maxPages));

                int start = (safePage - 1) * pageSize;
                int end = Math.min(start + pageSize, list.size());

                for (int i = start; i < end; i++) {
                    AccountData acc = list.get(i);
                    int rank = i + 1;
                    String balFormatted = plugin.getNumberFormatter().format(acc.getBalance());
                    Messages.send(plugin, sender, "economy", "baltop-line", "%rank_value%", String.valueOf(rank), "%player%", acc.getUsername(), "%bal_formatted%", String.valueOf(balFormatted));
                }
                });
            }
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("1", "2", "3", "text");
        }
        return List.of();
    }
}
