package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.gui.PayHistoryGui;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command executor for opening the transaction history GUI.
 */
public class PayHistoryCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public PayHistoryCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "economy", "this-command-can-only-be-executed");
            return true;
        }

        String targetName = player.getName();
        if (args.length > 0) {
            boolean isAdmin = player.hasPermission("swiftpay.admin") || player.hasPermission("friendlycore.economy.admin") || player.hasPermission("friendlycore.admin");
            if (!isAdmin) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("error-no-permission",
                                plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission.")), null);
                return true;
            }
            if (args[0].equalsIgnoreCase("check") && args.length > 1) {
                targetName = args[1];
            } else {
                targetName = args[0];
            }
        }

        final String target = targetName;
        plugin.getEconomyManager().getUuidByNameAsync(target).thenAccept(targetUuid -> {
            if (targetUuid == null) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                        Map.of("%player%", target));
                return;
            }

            plugin.getDatabaseManager().getRecentTransactions(targetUuid, 100).thenAccept(txList -> {
                plugin.getScheduler().runEntity(player, () -> new PayHistoryGui(plugin, player, targetUuid, target, txList, 1).open());
            });
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String search = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            if ("check".startsWith(search)) {
                list.add("check");
            }
            if (sender.hasPermission("swiftpay.admin") || sender.hasPermission("friendlycore.admin") || sender.hasPermission("friendlycore.economy.admin")) {
                for (String name : plugin.getRedisService().getOnlinePlayerNames()) {
                    if (name.toLowerCase().startsWith(search)) {
                        list.add(name);
                    }
                }
            }
            return list;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            String search = args[1].toLowerCase();
            List<String> list = new ArrayList<>();
            for (String name : plugin.getRedisService().getOnlinePlayerNames()) {
                if (name.toLowerCase().startsWith(search)) {
                    list.add(name);
                }
            }
            return list;
        }
        return List.of();
    }
}
