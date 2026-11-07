package dev.lyy.friendlycore.modules.tools.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tools.gui.ToolsGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command handler for opening the custom tools GUI and granting tools to players.
 */
public class ToolsCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public ToolsCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "tools", "usage-tools-give");
                return true;
            }
            if (!player.hasPermission("unicorntools.command.tools") && !player.hasPermission("unicorntools.admin") && !player.hasPermission("friendlycore.tools.use") && !player.hasPermission("friendlycore.admin")) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("tools.no-permission",
                                plugin.getConfigService().getMessages().getString("no-permission", "&cYou do not have permission to use this.")), null);
                return true;
            }
            new ToolsGui(plugin, player).open();
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("unicorntools.command.give") && !sender.hasPermission("unicorntools.admin") && !sender.hasPermission("friendlycore.tools.admin") && !sender.hasPermission("friendlycore.admin")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("tools.no-permission",
                                plugin.getConfigService().getMessages().getString("no-permission", "&cYou do not have permission to use this.")), null);
                return true;
            }

            if (args.length < 4) {
                Messages.send(plugin, sender, "tools", "usage-tools-give-3");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("player-not-found", "&#ff3845Player not found."),
                        Map.of("%player%", args[1]));
                return true;
            }

            String toolType = args[2].toLowerCase();
            Material material = Material.matchMaterial(args[3].toUpperCase());
            if (material == null) {
                Messages.send(plugin, sender, "tools", "invalid-material", "%input%", args[3]);
                return true;
            }

            ItemStack toolItem;
            if (toolType.equals("drill")) {
                toolItem = plugin.getToolManager().createDrill(material, target.getName());
                plugin.getScheduler().runEntity(target, () -> target.getInventory().addItem(toolItem));
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("tools.given-drill", "&aGiven %type% 3x3 Drill to %player%."),
                        Map.of("%player%", target.getName(), "%type%", material.name()));
            } else if (toolType.equals("chopper")) {
                toolItem = plugin.getToolManager().createChopper(material);
                plugin.getScheduler().runEntity(target, () -> target.getInventory().addItem(toolItem));
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("tools.given-chopper", "&aGiven %type% Tree Chopper to %player%."),
                        Map.of("%player%", target.getName(), "%type%", material.name()));
            } else {
                Messages.send(plugin, sender, "tools", "tool-type-must-be-drill-or");
            }
            return true;
        }

        Messages.send(plugin, sender, "tools", "usage-tools-give-2");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        boolean hasAdmin = sender.hasPermission("unicorntools.command.give") || sender.hasPermission("unicorntools.admin") || sender.hasPermission("friendlycore.tools.admin") || sender.hasPermission("friendlycore.admin");
        if (args.length == 1) {
            return hasAdmin ? List.of("give") : List.of();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && hasAdmin) {
            String search = args[1].toLowerCase();
            List<String> list = new ArrayList<>();
            for (String name : plugin.getRedisService().getOnlinePlayerNames()) {
                if (name.toLowerCase().startsWith(search)) {
                    list.add(name);
                }
            }
            return list;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && hasAdmin) {
            return List.of("drill", "chopper");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give") && hasAdmin) {
            if (args[2].equalsIgnoreCase("drill")) {
                return List.of("NETHERITE_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_SHOVEL", "DIAMOND_SHOVEL");
            } else {
                return List.of("NETHERITE_AXE", "DIAMOND_AXE", "IRON_AXE");
            }
        }
        return List.of();
    }
}
