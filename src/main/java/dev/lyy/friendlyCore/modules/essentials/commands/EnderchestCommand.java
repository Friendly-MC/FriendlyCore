package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.model.EnderchestSession;
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
 * Command executor for opening personal or target player Ender Chest inventories (/ec, /enderchest).
 * Supports local inspection and cross-server viewing over Redis.
 */
public class EnderchestCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public EnderchestCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        if (args.length == 0) {
            if (!player.hasPermission("friendlycore.enderchest")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            plugin.getScheduler().runEntity(player, () -> {
                player.openInventory(player.getEnderChest());
                TextService.playSound(player, "block.ender_chest.open", 1.0f, 1.0f);
            });
            return true;
        }

        if (!player.hasPermission("friendlycore.enderchest.others")) {
            essentialsManager.sendMessage(player, "general.no-permission", Map.of());
            return true;
        }

        String targetName = args[0];
        boolean editable = player.hasPermission("friendlycore.enderchest.modify");

        Player target = Bukkit.getPlayer(targetName);
        if (target != null && target.isOnline()) {
            if (target.hasPermission("friendlycore.enderchest.prevent") && !player.hasPermission("friendlycore.enderchest.bypass")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }

            plugin.getScheduler().runEntity(target, () -> {
                if (!target.isOnline()) return;
                ItemStack[] contents = target.getEnderChest().getContents().clone();
                String tName = target.getName();
                UUID tUuid = target.getUniqueId();
                Inventory liveEc = target.getEnderChest();

                plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline()) return;
                    String titleStr = editable
                            ? "<dark_gray><light_purple>" + tName + "'s Ender Chest"
                            : "<dark_gray><light_purple>" + tName + "'s Ender Chest <red>(Read-Only)";
                    Inventory clone = Bukkit.createInventory(null, 27, plugin.getTextService().parse(titleStr));
                    clone.setContents(contents);
                    EnderchestSession session = new EnderchestSession(
                            player.getUniqueId(),
                            tUuid,
                            tName,
                            EnderchestSession.SessionType.LOCAL_ONLINE,
                            essentialsManager.getCrossServerService().getLocalServerId(),
                            editable,
                            clone
                    );
                    // Open first: opening closes the previous inventory, and that close event drops the viewer's session.
                    if (player.openInventory(clone) != null) {
                        essentialsManager.registerEnderchestSession(player.getUniqueId(), session);
                    }
                    TextService.playSound(player, "block.ender_chest.open", 1.0f, 1.0f);
                });
            });
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.sendMessage(player, "enderchest.requesting-remote", Map.of("%player%", networkName));
            essentialsManager.getCrossServerService().dispatchEnderchestRequest(player, networkName, targetUuid, editable);
            return true;
        }

        essentialsManager.sendMessage(player, "general.player-not-found", Map.of("%player%", targetName));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("friendlycore.enderchest.others")) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String name : plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : getLocalPlayerNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
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
