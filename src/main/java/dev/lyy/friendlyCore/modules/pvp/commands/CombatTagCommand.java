package dev.lyy.friendlycore.modules.pvp.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
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

/**
 * Command handling for /combattag, /ct, and /tag shortcuts.
 */
public class CombatTagCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public CombatTagCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("untag")) {
            if (!sender.hasPermission("friendlycore.admin") && !sender.hasPermission("friendlycore.pvp.admin")) {
                plugin.getTextService().sendMessage(sender, "commands.no-permission");
                return true;
            }
            Player target = sender instanceof Player p ? p : null;
            if (args.length > 1) {
                target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                    return true;
                }
            }
            if (target == null) {
                Messages.send(plugin, sender, "pvp", "usage-combattag-untag");
                return true;
            }
            if (plugin.getPvpManager().getCombatTagManager().isTagged(target.getUniqueId())) {
                plugin.getPvpManager().getCombatTagManager().untagPlayer(target, dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.COMMAND);
                plugin.getTextService().sendMessage(sender, "pvp.untagged-other", Map.of("%target%", target.getName()));
            } else {
                plugin.getTextService().sendMessage(sender, "combat.status-safe");
            }
            return true;
        }

        Player target = sender instanceof Player p ? p : null;
        if (args.length > 0) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                return true;
            }
        }

        if (target == null) {
            Messages.send(plugin, sender, "pvp", "usage-combattag-player-untag");
            return true;
        }

        if (plugin.getPvpManager().getCombatTagManager().isTagged(target.getUniqueId())) {
            int remaining = plugin.getPvpManager().getCombatTagManager().getRemainingSeconds(target.getUniqueId());
            plugin.getTextService().sendMessage(sender, "combat.status-in-combat", Map.of("%time%", String.valueOf(remaining)));
        } else {
            plugin.getTextService().sendMessage(sender, "combat.status-safe");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            if (sender.hasPermission("friendlycore.admin") || sender.hasPermission("friendlycore.pvp.admin")) {
                list.add("untag");
            }
            String search = args[0].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(search)) {
                    list.add(p.getName());
                }
            }
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("untag")) {
            List<String> list = new ArrayList<>();
            String search = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(search)) {
                    list.add(p.getName());
                }
            }
            return list;
        }
        return List.of();
    }
}
