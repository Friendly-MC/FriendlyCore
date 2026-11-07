package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Command executor for mass teleporting players to the executing admin (/tpall).
 * Supports local server players as well as network-wide summons.
 */
public class TeleportAllCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public TeleportAllCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        if (!admin.hasPermission("friendlycore.teleport.all")) {
            essentialsManager.sendMessage(admin, "general.no-permission", Map.of());
            return true;
        }

        boolean network = args.length > 0 && args[0].equalsIgnoreCase("network");
        Location dest = admin.getLocation();

        int count = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(admin)) continue;
            count++;
            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(target, dest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    TextService.playSound(target, "entity.experience_orb.pickup", 1.0f, 1.2f);
                    essentialsManager.sendMessage(target, "teleport.teleported-to-admin",
                            Map.of("%admin%", admin.getName()));
                }
            });
        }

        // Summon players from other servers if network flag was specified
        if (network) {
            essentialsManager.getCrossServerService().dispatchTeleportAllHere(admin);
        }

        essentialsManager.sendMessage(admin, "teleport.tpall-success", Map.of("%count%", String.valueOf(count)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("network");
        }
        return Collections.emptyList();
    }
}
