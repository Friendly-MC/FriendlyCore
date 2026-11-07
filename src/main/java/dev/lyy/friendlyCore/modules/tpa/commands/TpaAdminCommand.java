package dev.lyy.friendlycore.modules.tpa.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
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
 * Admin management command for opening the configuration GUI or reloading TPA files.
 */
public class TpaAdminCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpaAdminCommand(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("donuttpa.admin") && !sender.hasPermission("friendlycore.admin")) {
            if (sender instanceof Player p) {
                tpaManager.sendConfigMessage(p, "no-permission", Map.of());
            } else {
                plugin.getTextService().sendMessage(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>You do not have permission.</gray>", null);
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            long start = System.currentTimeMillis();
            plugin.getConfigService().loadConfig("modules/tpa/config.yml");
            plugin.getConfigService().loadConfig("modules/tpa/messages.yml");
            plugin.getConfigService().loadConfig("modules/tpa/inventories/tpa-inventory.yml");
            plugin.getConfigService().loadConfig("modules/tpa/inventories/tpa-here-inventory.yml");
            plugin.getConfigService().loadConfig("modules/tpa/inventories/tpa-settings.yml");
            plugin.getConfigService().loadConfig("modules/tpa/inventories/tpa-admin.yml");
            plugin.getConfigService().loadConfig("modules/tpa/inventories/tpa-safe-teleport-settings.yml");
            tpaManager.reload();

            long elapsed = System.currentTimeMillis() - start;
            if (sender instanceof Player p) {
                tpaManager.sendConfigMessage(p, "tpa.config-reload", Map.of("%ms%", String.valueOf(elapsed)));
            } else {
                plugin.getTextService().sendMessage(sender, "<gradient:#00e676:#00b0ff><bold>TPA ADMIN</bold></gradient> <dark_gray>»</dark_gray> <gray>Reloaded TPA configs in <#00e676>" + elapsed + "ms</#00e676>!</gray>", null);
            }
            return true;
        }

        if (sender instanceof Player player) {
            tpaManager.openAdminGui(player);
        } else {
            plugin.getTextService().sendMessage(sender, "<gradient:#ff5252:#ff1744><bold>USAGE</bold></gradient> <dark_gray>»</dark_gray> <gray>Use <#00d2ff>/" + label + " reload</#00d2ff></gray>", null);
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && (sender.hasPermission("donuttpa.admin") || sender.hasPermission("friendlycore.admin"))) {
            List<String> list = new ArrayList<>();
            if ("reload".startsWith(args[0].toLowerCase())) {
                list.add("reload");
            }
            return list;
        }
        return List.of();
    }
}
