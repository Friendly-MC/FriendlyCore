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
 * Command executor and tab completer for creating and saving home points (/sethome).
 */
public class SetHomeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final HomesManager homesManager;

    public SetHomeCommand(FriendlyCore plugin, HomesManager homesManager) {
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
            homesManager.sendHomeMessage(player, "not-allowed-set-home", Map.of());
            homesManager.playSound(player, "error");
            return true;
        }

        if (homesManager.isLoading(player.getUniqueId())) {
            Messages.send(plugin, player, "homes", "your-homes-are-still-loading-please");
            homesManager.playSound(player, "error");
            return true;
        }

        int maxHomes = homesManager.getMaxHomes(player.getUniqueId());
        PlayerHomes profile = homesManager.getPlayerHomes(player.getUniqueId());
        if (profile == null) {
            profile = new PlayerHomes(player.getUniqueId(), player.getName());
        }

        int slot = -1;
        String name = "";

        if (args.length == 0) {
            // /sethome -> Next available slot
            slot = profile.getNextAvailableSlot(maxHomes);
            if (slot <= 0) {
                homesManager.sendHomeMessage(player, "reached_max_number_of_homes", Map.of("%max_homes%", String.valueOf(maxHomes)));
                homesManager.playSound(player, "error");
                return true;
            }
        } else if (args.length == 1) {
            try {
                slot = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                name = args[0];
                HomeData existing = profile.getHomeByNameOrSlot(name);
                if (existing != null) {
                    slot = existing.getSlot();
                } else {
                    slot = profile.getNextAvailableSlot(maxHomes);
                    if (slot <= 0) {
                        homesManager.sendHomeMessage(player, "reached_max_number_of_homes", Map.of("%max_homes%", String.valueOf(maxHomes)));
                        homesManager.playSound(player, "error");
                        return true;
                    }
                }
            }
        } else {
            try {
                slot = Integer.parseInt(args[0]);
                name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            } catch (NumberFormatException e) {
                homesManager.sendHomeMessage(player, "invalid-number", Map.of());
                homesManager.playSound(player, "error");
                return true;
            }
        }

        if (name.length() > 32) {
            homesManager.sendHomeMessage(player, "name-too-long", Map.of());
            homesManager.playSound(player, "error");
            return true;
        }

        homesManager.setHomeAtLocation(player, slot, name, player.getLocation());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            int max = homesManager.getMaxHomes(player.getUniqueId());
            for (int i = 1; i <= max; i++) {
                if (String.valueOf(i).startsWith(args[0])) {
                    completions.add(String.valueOf(i));
                }
            }
        }
        return completions;
    }
}
