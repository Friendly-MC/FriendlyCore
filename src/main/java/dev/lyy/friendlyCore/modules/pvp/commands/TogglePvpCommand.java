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

/**
 * Shortcut command for toggling personal PvP state.
 */
public class TogglePvpCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public TogglePvpCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "pvp", "this-command-can-only-be-executed");
            return true;
        }

        plugin.getPvpManager().togglePvp(player);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
