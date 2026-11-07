package dev.lyy.friendlycore.modules.spawn.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Location;
import org.bukkit.Sound;
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
 * Command executor and tab completer for setting the server or network spawn location (/setspawn).
 */
public class SetSpawnCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public SetSpawnCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.spawn.set") && !sender.hasPermission("friendlycore.admin")) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission."), null);
            return true;
        }

        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "spawn", "this-command-can-only-be-executed");
            return true;
        }

        String targetServer = (args.length > 0) ? args[0] : plugin.getSpawnManager().resolveLocalServerId();
        Location loc = player.getLocation();
        plugin.getSpawnManager().setSpawnLocation(loc, targetServer);

        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "unknown";
        String x = String.format("%.2f", loc.getX());
        String y = String.format("%.2f", loc.getY());
        String z = String.format("%.2f", loc.getZ());

        plugin.getTextService().sendMessage(player,
                plugin.getConfigService().getMessages().getString("spawn.spawn-set",
                        "<gradient:#00e676:#00b0ff><bold>SPAWN</bold></gradient> <dark_gray>»</dark_gray> <gray>Spawn location set on server <#00e676>%server%</#00e676> in world <#00e676>%world%</#00e676> <dark_gray>(%x%, %y%, %z%)</dark_gray>!</gray>"),
                Map.of(
                        "%server%", targetServer,
                        "%world%", worldName,
                        "%x%", x,
                        "%y%", y,
                        "%z%", z
                ));

        try {
            player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        } catch (Throwable ignored) {}

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String current = plugin.getSpawnManager().resolveLocalServerId();
            return List.of(current);
        }
        return List.of();
    }
}
