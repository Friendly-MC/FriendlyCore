package dev.lyy.friendlycore.modules.pvp.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
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
 * Command handling for checking or manually forfeiting newbie protection.
 */
public class NewbieCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public NewbieCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "pvp", "this-command-can-only-be-executed");
            return true;
        }

        if (args.length > 0 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable"))) {
            if (!plugin.getPvpManager().getNewbieProtectionManager().hasProtection(player.getUniqueId())) {
                plugin.getTextService().sendMessage(player, "newbie.expired");
                return true;
            }
            plugin.getPvpManager().getNewbieProtectionManager().removeProtection(player.getUniqueId(), true);
            return true;
        }

        if (plugin.getPvpManager().getNewbieProtectionManager().hasProtection(player.getUniqueId())) {
            int remaining = plugin.getPvpManager().getNewbieProtectionManager().getRemainingSeconds(player.getUniqueId());
            plugin.getTextService().sendMessage(player, "newbie.granted", Map.of("%time%", String.valueOf(remaining)));
        } else {
            plugin.getTextService().sendMessage(player, "newbie.expired");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("off", "disable", "status");
        }
        return List.of();
    }
}
