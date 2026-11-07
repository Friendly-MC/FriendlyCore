package dev.lyy.friendlycore.modules.orders.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.orders.OrderManager;
import dev.lyy.friendlycore.modules.orders.gui.AdminOrdersGui;
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
import java.util.UUID;

public class OrderAdminCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final OrderManager orderManager;

    public OrderAdminCommand(FriendlyCore plugin, OrderManager orderManager) {
        this.plugin = plugin;
        this.orderManager = orderManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.orders.admin") && !sender.isOp()) {
            plugin.getTextService().send(sender, "&cYou do not have permission to execute this command.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                AdminOrdersGui.openMenu(plugin, player);
            } else {
                Messages.send(plugin, sender, "orders", "usage-delete-convert", "%label%", String.valueOf(label));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui", "menu" -> {
                if (sender instanceof Player player) {
                    AdminOrdersGui.openMenu(plugin, player);
                } else {
                    Messages.send(plugin, sender, "orders", "gui-can-only-be-opened-by");
                }
            }
            case "reload" -> {
                plugin.getOrdersModule().reload();
                plugin.getTextService().send(sender, "&aOrders module configuration reloaded successfully.");
            }
            case "cancel" -> {
                if (args.length < 2) {
                    Messages.send(plugin, sender, "orders", "usage-cancel", "%label%", String.valueOf(label));
                    return true;
                }
                try {
                    UUID orderId = UUID.fromString(args[1]);
                    Player player = sender instanceof Player p ? p : null;
                    orderManager.cancelOrder(player, orderId).thenAccept(success -> {
                        if (success) {
                            Messages.send(plugin, sender, "orders", "order-cancelled-and-refunded-to-creator");
                        } else {
                            Messages.send(plugin, sender, "orders", "failed-to-cancel-order");
                        }
                    });
                } catch (IllegalArgumentException e) {
                    Messages.send(plugin, sender, "orders", "invalid-uuid-format", "%input%", args[1]);
                }
            }
            case "delete" -> {
                if (args.length < 2) {
                    Messages.send(plugin, sender, "orders", "usage-delete", "%label%", String.valueOf(label));
                    return true;
                }
                try {
                    UUID orderId = UUID.fromString(args[1]);
                    plugin.getDatabaseManager().deleteOrder(orderId).thenRun(() -> {
                        orderManager.removeCachedOrder(orderId);
                        Messages.send(plugin, sender, "orders", "order-permanently-deleted");
                    });
                } catch (IllegalArgumentException e) {
                    Messages.send(plugin, sender, "orders", "invalid-uuid-format", "%input%", args[1]);
                }
            }
            case "convert" -> {
                Messages.send(plugin, sender, "orders", "starting-donutorder-conversion-in-background");
                plugin.getOrdersModule().getConverter().convertAll(sender);
            }
            default -> Messages.send(plugin, sender, "orders", "usage-delete-convert", "%label%", String.valueOf(label));
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = List.of("gui", "reload", "cancel", "delete", "convert");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : subs) {
                if (s.startsWith(prefix)) completions.add(s);
            }
        }
        return completions;
    }
}
