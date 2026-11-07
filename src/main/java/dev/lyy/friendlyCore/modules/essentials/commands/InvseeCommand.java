package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.model.InvseeSession;
import dev.lyy.friendlycore.modules.essentials.util.InvseeLayout;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Command executor for inspecting and modifying player inventories (/invsee).
 * Supports both local view sessions and real-time cross-server synchronization over Redis.
 */
public class InvseeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public InvseeCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        if (!player.hasPermission("friendlycore.invsee")) {
            essentialsManager.sendMessage(player, "general.no-permission", Map.of());
            return true;
        }

        if (args.length < 1) {
            essentialsManager.sendMessage(player, "invsee.usage", Map.of("%label%", label));
            return true;
        }

        String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            essentialsManager.sendMessage(player, "invsee.no-self", Map.of());
            return true;
        }

        boolean editable = player.hasPermission("friendlycore.invsee.modify");

        Player target = Bukkit.getPlayer(targetName);
        if (target != null && target.isOnline()) {
            if (target.hasPermission("friendlycore.invsee.prevent") && !player.hasPermission("friendlycore.invsee.bypass")) {
                essentialsManager.sendMessage(player, "invsee.exempt", Map.of("%player%", target.getName()));
                return true;
            }

            openLocalInvsee(player, target, editable);
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.sendMessage(player, "invsee.requesting-remote", Map.of("%player%", networkName));
            essentialsManager.getCrossServerService().dispatchInvseeRequest(player, networkName, targetUuid, editable);
            return true;
        }

        essentialsManager.sendMessage(player, "general.player-not-found", Map.of("%player%", targetName));
        return true;
    }

    private void openLocalInvsee(Player viewer, Player target, boolean editable) {
        plugin.getScheduler().runEntity(target, () -> {
            if (!target.isOnline()) return;
            ItemStack[] items = InvseeLayout.fromPlayer(target.getInventory());
            String targetName = target.getName();
            UUID targetUuid = target.getUniqueId();

            plugin.getScheduler().runEntity(viewer, () -> {
                if (!viewer.isOnline()) return;
                Inventory inv = InvseeLayout.createGui(
                        Messages.component(plugin, "essentials", editable ? "invsee-title-editable" : "invsee-title-read-only", "%player%", targetName),
                        Messages.component(plugin, "essentials", "armor-equipment"),
                        Messages.component(plugin, "essentials", "local", "%player%", targetName),
                        items);

                InvseeSession session = new InvseeSession(
                        viewer.getUniqueId(),
                        targetUuid,
                        targetName,
                        InvseeSession.SessionType.LOCAL_ONLINE,
                        essentialsManager.getCrossServerService().getLocalServerId(),
                        editable,
                        inv
                );
                // Open first: opening closes the previous inventory, and that close event drops the viewer's session.
                if (viewer.openInventory(inv) != null) {
                    essentialsManager.registerInvseeSession(viewer.getUniqueId(), session);
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String name : plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : getLocalPlayerNames()) {
                if (!name.equalsIgnoreCase(sender.getName()) && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(name);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private Set<String> getLocalPlayerNames() {
        Set<String> set = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            set.add(p.getName());
        }
        return set;
    }
}
