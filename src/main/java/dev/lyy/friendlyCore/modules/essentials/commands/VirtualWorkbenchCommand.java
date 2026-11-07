package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.Locale;
import java.util.Map;

/**
 * Command executor for opening virtual utility stations and workbench interfaces
 * (/craft, /anvil, /grindstone, /stonecutter, /loom, /cartographytable, /disposal).
 */
public class VirtualWorkbenchCommand implements CommandExecutor {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public VirtualWorkbenchCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        String cmd = label.toLowerCase(Locale.ROOT);
        plugin.getScheduler().runEntity(player, () -> {
            switch (cmd) {
                case "craft", "workbench", "wb" -> {
                    if (!player.hasPermission("friendlycore.workbench")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openWorkbench(null, true);
                }
                case "anvil" -> {
                    if (!player.hasPermission("friendlycore.anvil")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openAnvil(null, true);
                }
                case "grindstone" -> {
                    if (!player.hasPermission("friendlycore.grindstone")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openGrindstone(null, true);
                }
                case "stonecutter" -> {
                    if (!player.hasPermission("friendlycore.stonecutter")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openStonecutter(null, true);
                }
                case "smithingtable", "smith" -> {
                    if (!player.hasPermission("friendlycore.smithingtable")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openSmithingTable(null, true);
                }
                case "loom" -> {
                    if (!player.hasPermission("friendlycore.loom")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openLoom(null, true);
                }
                case "cartographytable", "cartography" -> {
                    if (!player.hasPermission("friendlycore.cartographytable")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    player.openCartographyTable(null, true);
                }
                case "disposal", "trash" -> {
                    if (!player.hasPermission("friendlycore.disposal")) {
                        essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                        return;
                    }
                    Inventory trash = Bukkit.createInventory(null, 36,
                            Messages.component(plugin, "essentials", "disposal-items-will-be-destroyed"));
                    player.openInventory(trash);
                }
                default -> essentialsManager.sendMessage(player, "general.no-permission", Map.of());
            }
        });

        return true;
    }
}
