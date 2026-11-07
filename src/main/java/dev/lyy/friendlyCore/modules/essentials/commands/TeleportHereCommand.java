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
 * Command executor for teleporting a player directly to the admin's location (/tphere).
 * Supports local server players as well as cross-server summons over Redis.
 */
public class TeleportHereCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public TeleportHereCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player admin)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        if (!admin.hasPermission("friendlycore.teleport.here")) {
            essentialsManager.sendMessage(admin, "general.no-permission", Map.of());
            return true;
        }

        if (args.length < 1) {
            essentialsManager.sendMessage(admin, "teleport.here-usage", Map.of("%label%", label));
            return true;
        }

        String targetName = args[0];
        if (targetName.equalsIgnoreCase(admin.getName())) {
            essentialsManager.sendMessage(admin, "teleport.self-teleport", Map.of());
            return true;
        }

        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            Location dest = admin.getLocation();
            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(localTarget, dest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    TextService.playSound(localTarget, "entity.experience_orb.pickup", 1.0f, 1.2f);
                    essentialsManager.sendMessage(localTarget, "teleport.teleported-to-admin",
                            Map.of("%admin%", admin.getName()));
                    essentialsManager.sendMessage(admin, "teleport.teleported-player-here",
                            Map.of("%player%", localTarget.getName()));
                }
            });
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.sendMessage(admin, "teleport.pulling-remote", Map.of("%player%", networkName));
            essentialsManager.getCrossServerService().dispatchTeleportPlayerHere(admin, networkName, targetUuid);
            return true;
        }

        essentialsManager.sendMessage(admin, "general.player-not-found", Map.of("%player%", targetName));
        return true;
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
