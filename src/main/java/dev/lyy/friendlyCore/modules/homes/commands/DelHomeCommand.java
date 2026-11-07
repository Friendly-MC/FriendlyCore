package dev.lyy.friendlycore.modules.homes.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
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

/**
 * Command executor and tab completer for deleting home locations (/delhome).
 */
public class DelHomeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;

    public DelHomeCommand(FriendlyCore plugin, HomesManager homesManager) {
        this.plugin = plugin;
        this.homesManager = homesManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        if (!homesManager.hasHomeUsePermission(player)) {
            homesManager.sendHomeMessage(player, "not-allowed-home", Map.of());
            homesManager.playSound(player, "error");
            return true;
        }

        if (args.length < 1) {
            Messages.send(plugin, player, "homes", "use-delhome");
            return true;
        }

        PlayerHomes profile = homesManager.getPlayerHomes(player.getUniqueId());
        if (profile == null) {
            homesManager.sendHomeMessage(player, "home-not-found", Map.of());
            homesManager.playSound(player, "error");
            return true;
        }

        HomeData targetHome = profile.getHomeByNameOrSlot(args[0]);
        if (targetHome == null) {
            homesManager.sendHomeMessage(player, "home-not-found", Map.of());
            homesManager.playSound(player, "error");
            return true;
        }

        profile.removeHome(targetHome.getSlot());
        homesManager.savePlayerHomesAsync(profile);
        homesManager.sendHomeMessage(player, "home-deleted-chat", Map.of("%home%", targetHome.getDisplayName()));
        homesManager.playSound(player, "ui-click");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            PlayerHomes profile = homesManager.getPlayerHomes(player.getUniqueId());
            if (profile != null) {
                String input = args[0].toLowerCase();
                for (HomeData h : profile.getHomes().values()) {
                    if (String.valueOf(h.getSlot()).startsWith(input)) completions.add(String.valueOf(h.getSlot()));
                    if (h.getName() != null && !h.getName().isEmpty() && h.getName().toLowerCase().startsWith(input)) {
                        completions.add(h.getName());
                    }
                }
            }
        }
        return completions;
    }
}
