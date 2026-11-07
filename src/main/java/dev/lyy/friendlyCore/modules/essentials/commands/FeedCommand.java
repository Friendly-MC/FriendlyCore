package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for restoring player hunger and saturation (/feed).
 * Supports targeting remote players across network instances.
 */
public class FeedCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public FeedCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage", "%label%", String.valueOf(label));
                return true;
            }
            if (!player.hasPermission("friendlycore.feed")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }
            feedPlayer(player, player);
            return true;
        }

        if (!sender.hasPermission("friendlycore.feed.others")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String targetName = args[0];
        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            feedPlayer(sender, localTarget);
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.getCrossServerService().dispatchFeed(targetUuid, networkName);
            essentialsManager.sendMessage(sender, "feed.fed-other-network", Map.of("%player%", networkName));
            return true;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
        return true;
    }

    private void feedPlayer(CommandSender sender, Player target) {
        plugin.getScheduler().runEntity(target, () -> {
            target.setFoodLevel(20);
            target.setSaturation(20.0f);

            TextService.playSound(target, "entity.generic.eat", 1.0f, 1.0f);
            essentialsManager.sendMessage(target, "feed.fed-self", Map.of());

            if (!sender.equals(target)) {
                essentialsManager.sendMessage(sender, "feed.fed-other", Map.of("%player%", target.getName()));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("friendlycore.feed.others")) {
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
