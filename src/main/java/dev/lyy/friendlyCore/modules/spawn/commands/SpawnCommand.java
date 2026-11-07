package dev.lyy.friendlycore.modules.spawn.commands;

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
 * Command executor and tab completer for teleporting players to the spawn location (/spawn).
 */
public class SpawnCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public SpawnCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.spawn.use") && !sender.hasPermission("friendlycore.admin")) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission."), null);
            return true;
        }

        if (args.length > 0) {
            if (!sender.hasPermission("friendlycore.spawn.others") && !sender.hasPermission("friendlycore.admin")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission."), null);
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || !target.isOnline()) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                        Map.of("%player%", args[0]));
                return true;
            }

            plugin.getSpawnManager().teleportToSpawn(target, true);
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("spawn.teleport-other",
                            "<gradient:#00e676:#00b0ff><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Teleported <white>%player%</white> to spawn!</gray>"),
                    Map.of("%player%", target.getName()));
            return true;
        }

        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "spawn", "usage-spawn");
            return true;
        }

        boolean bypass = player.hasPermission("friendlycore.bypass.cooldown") || player.hasPermission("friendlycore.bypass.warmup");
        plugin.getSpawnManager().teleportToSpawn(player, bypass);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && (sender.hasPermission("friendlycore.spawn.others") || sender.hasPermission("friendlycore.admin"))) {
            String search = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
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
