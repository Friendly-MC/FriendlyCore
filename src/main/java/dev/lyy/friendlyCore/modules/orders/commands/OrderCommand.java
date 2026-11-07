package dev.lyy.friendlycore.modules.orders.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.orders.OrderManager;
import dev.lyy.friendlycore.modules.orders.gui.CollectItemsGui;
import dev.lyy.friendlycore.modules.orders.gui.OrderMarketGui;
import dev.lyy.friendlycore.modules.orders.gui.SelectItemGui;
import dev.lyy.friendlycore.modules.orders.gui.YourOrdersGui;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OrderCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final OrderManager orderManager;

    public OrderCommand(FriendlyCore plugin, OrderManager orderManager) {
        this.plugin = plugin;
        this.orderManager = orderManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "orders", "this-command-can-only-be-executed");
            return true;
        }

        if (args.length == 0) {
            OrderMarketGui.openMenu(plugin, player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "new" -> {
                if (!player.hasPermission("friendlycore.orders.create")) {
                    plugin.getTextService().send(player, "no_permission");
                    return true;
                }
                SelectItemGui.openMenu(plugin, player, null);
            }
            case "myorders", "yourorders", "orders" -> YourOrdersGui.openMenu(plugin, player, null);
            case "collect", "claims" -> CollectItemsGui.openMenu(plugin, player, null);
            case "togglenotifications", "toggle" -> {
                boolean enabled = plugin.getOrdersModule().getPlayerListener().toggleNotifications(player.getUniqueId());
                if (enabled) {
                    plugin.getTextService().send(player, "notifications.toggle.on");
                } else {
                    plugin.getTextService().send(player, "notifications.toggle.off");
                }
            }
            case "search" -> {
                if (args.length > 1) {
                    String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    OrderMarketGui gui = new OrderMarketGui(plugin, player, null);
                    gui.setSearchQuery(query);
                    gui.open();
                } else {
                    OrderMarketGui.openMenu(plugin, player);
                }
            }
            default -> {
                FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
                boolean quickSearch = config != null && config.getBoolean("quick-search", true);
                if (quickSearch) {
                    String query = String.join(" ", args);
                    OrderMarketGui gui = new OrderMarketGui(plugin, player, null);
                    gui.setSearchQuery(query);
                    gui.open();
                } else {
                    OrderMarketGui.openMenu(plugin, player);
                }
            }
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = List.of("create", "myorders", "collect", "togglenotifications", "search");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : subs) {
                if (s.startsWith(prefix)) completions.add(s);
            }
            for (Material mat : Material.values()) {
                if (mat.isItem() && mat.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(mat.name().toLowerCase(Locale.ROOT));
                    if (completions.size() > 30) break;
                }
            }
        }
        return completions;
    }
}
