package dev.lyy.friendlycore.modules.homes.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.gui.HomeGui;
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
 * Command executor and tab completer for opening the home menu and teleporting to homes (/home, /homes).
 */
public class HomeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;

    public HomeCommand(FriendlyCore plugin, HomesManager homesManager) {
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

        if (homesManager.isLoading(player.getUniqueId())) {
            Messages.send(plugin, player, "homes", "your-homes-are-still-loading-please");
            homesManager.playSound(player, "error");
            return true;
        }

        PlayerHomes profile = homesManager.getPlayerHomes(player.getUniqueId());
        if (profile == null) {
            profile = new PlayerHomes(player.getUniqueId(), player.getName());
        }

        // /home -> Open Home GUI
        if (args.length == 0) {
            homesManager.playSound(player, "ui-open");
            new HomeGui(plugin, homesManager, player).open();
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("help") && !homesManager.isDisableSubcommands()) {
            List<String> helpList = plugin.getConfigService().get("modules/homes/messages.yml").getStringList("help-message");
            for (String line : helpList) {
                player.sendMessage(plugin.getTextService().parse(line));
            }
            return true;
        }

        if (sub.equals("rename") && !homesManager.isDisableSubcommands() && !homesManager.isDisableHomeRenaming()) {
            if (args.length < 2) {
                Messages.send(plugin, player, "homes", "usage-home-rename-name");
                return true;
            }
            try {
                int slot = Integer.parseInt(args[1]);
                HomeData home = profile.getHome(slot);
                if (home == null) {
                    homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                    homesManager.playSound(player, "error");
                    return true;
                }
                if (args.length >= 3) {
                    String newName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                    if (newName.length() > 32) {
                        homesManager.sendHomeMessage(player, "name-too-long", Map.of());
                        homesManager.playSound(player, "error");
                        return true;
                    }
                    home.setName(newName);
                    homesManager.savePlayerHomesAsync(profile);
                    homesManager.sendHomeMessage(player, "home-renamed", Map.of("%home%", String.valueOf(slot), "%name%", newName));
                    homesManager.playSound(player, "ui-click");
                } else {
                    homesManager.startChatRename(player, player.getUniqueId(), slot);
                }
            } catch (NumberFormatException e) {
                homesManager.sendHomeMessage(player, "invalid-number", Map.of());
                homesManager.playSound(player, "error");
            }
            return true;
        }

        if (sub.equals("delete") && !homesManager.isDisableSubcommands()) {
            if (args.length < 2) {
                Messages.send(plugin, player, "homes", "usage-home-delete");
                return true;
            }
            try {
                int slot = Integer.parseInt(args[1]);
                HomeData removed = profile.removeHome(slot);
                if (removed != null) {
                    homesManager.savePlayerHomesAsync(profile);
                    homesManager.sendHomeMessage(player, "home-deleted-chat", Map.of("%home%", String.valueOf(slot)));
                    homesManager.playSound(player, "ui-click");
                } else {
                    homesManager.sendHomeMessage(player, "home-not-found", Map.of());
                    homesManager.playSound(player, "error");
                }
            } catch (NumberFormatException e) {
                homesManager.sendHomeMessage(player, "invalid-number", Map.of());
                homesManager.playSound(player, "error");
            }
            return true;
        }

        // /home <name|slot> -> Direct Teleport
        HomeData home = profile.getHomeByNameOrSlot(args[0]);
        if (home != null) {
            homesManager.teleportPlayerToHome(player, home);
        } else {
            homesManager.sendHomeMessage(player, "home-not-found", Map.of());
            homesManager.playSound(player, "error");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        List<String> completions = new ArrayList<>();
        PlayerHomes profile = homesManager.getPlayerHomes(player.getUniqueId());

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            if (!homesManager.isDisableSubcommands()) {
                if ("help".startsWith(input)) completions.add("help");
                if ("rename".startsWith(input)) completions.add("rename");
                if ("delete".startsWith(input)) completions.add("delete");
            }
            if (profile != null) {
                for (HomeData h : profile.getHomes().values()) {
                    if (String.valueOf(h.getSlot()).startsWith(input)) completions.add(String.valueOf(h.getSlot()));
                    if (h.getName() != null && !h.getName().isEmpty() && h.getName().toLowerCase().startsWith(input)) {
                        completions.add(h.getName());
                    }
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("rename") || args[0].equalsIgnoreCase("delete"))) {
            String input = args[1].toLowerCase();
            if (profile != null) {
                for (Integer slot : profile.getHomes().keySet()) {
                    if (String.valueOf(slot).startsWith(input)) completions.add(String.valueOf(slot));
                }
            }
        }

        return completions;
    }
}
