package dev.lyy.friendlycore.modules.tpa.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Command handler for requesting teleportation to another player.
 */
public class TpaCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpaCommand(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        if (!player.hasPermission("donuttpa.tpa") && !player.hasPermission("friendlycore.tpa.use")
                && !player.hasPermission("friendlycore.tpa")) {
            tpaManager.sendConfigMessage(player, "no-permission", Map.of());
            return true;
        }

        if (args.length < 1) {
            plugin.getTextService().sendMessage(player, "<gradient:#ff5252:#ff1744><bold>USAGE</bold></gradient> <dark_gray>»</dark_gray> <gray>Use <#00d2ff>/tpa <player></#00d2ff></gray>", null);
            return true;
        }

        tpaManager.sendRequest(player, args[0], TpaRequest.Type.TPA);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String search = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            Set<String> playerNames = plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : null;
            if (playerNames != null) {
                for (String name : playerNames) {
                    if (!name.equalsIgnoreCase(sender.getName()) && name.toLowerCase(Locale.ROOT).startsWith(search)) {
                        list.add(name);
                    }
                }
            }
            return list;
        }
        return List.of();
    }
}
