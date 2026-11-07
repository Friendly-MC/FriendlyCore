package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.FriendlyCore;
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
 * Command executor for checking personal or target player balance.
 */
public class BalCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public BalCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("economy.usage-balance",
                                plugin.getConfigService().getMessages().getString("usage-balance", "<red>Usage: /bal <player></red>")),
                        null);
                return true;
            }

            plugin.getEconomyManager().getAccountAsync(player.getUniqueId(), player.getName()).thenAccept(account ->
                    plugin.getScheduler().runEntity(player, () -> {
                        if (!player.isOnline() || account == null) return;
                        String formatted = plugin.getNumberFormatter().format(account.getBalance());
                        plugin.getTextService().sendMessage(player,
                                plugin.getConfigService().getMessages().getString("economy.balance-self",
                                        "&#63ed2dYour balance: $%balance%"),
                                Map.of("%balance%", formatted));
                        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    }));
            return true;
        }

        if (!sender.hasPermission("swiftpay.bal.other") && !sender.hasPermission("friendlycore.economy.bal.other") && !sender.hasPermission("swiftpay.admin") && !sender.hasPermission("friendlycore.admin")) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("error-no-permission",
                            plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission.")),
                    null);
            return true;
        }

        String targetName = args[0];
        plugin.getEconomyManager().getUuidByNameAsync(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return plugin.getEconomyManager().getAccountAsync(uuid, targetName);
        }).thenAccept(account -> {
            if (account == null) {
                Runnable notFound = () -> plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                        Map.of("%player%", targetName));
                if (sender instanceof Player p) plugin.getScheduler().runEntity(p, notFound);
                else plugin.getScheduler().runGlobal(notFound);
                return;
            }
            String formatted = plugin.getNumberFormatter().format(account.getBalance());

            if (sender instanceof Player p) {
                plugin.getScheduler().runEntity(p, () -> {
                    plugin.getTextService().sendMessage(p,
                            plugin.getConfigService().getMessages().getString("economy.balance-other",
                                    "&#63ed2d%player%'s balance: $%balance%"),
                            Map.of("%player%", targetName, "%balance%", formatted));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                });
            } else {
                plugin.getScheduler().runGlobal(() ->
                        plugin.getTextService().sendMessage(sender,
                                plugin.getConfigService().getMessages().getString("economy.balance-other",
                                        "&#63ed2d%player%'s balance: $%balance%"),
                                Map.of("%player%", targetName, "%balance%", formatted)));
            }
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String search = args[0].toLowerCase();
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
