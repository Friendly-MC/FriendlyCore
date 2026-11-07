package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for toggling god mode invulnerability (/god).
 * Provides damage immunity, hunger locking, and void rescue with network support.
 */
public class GodCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public GodCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage-on-off", "%label%", String.valueOf(label));
                return true;
            }
            if (!player.hasPermission("friendlycore.god")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }
            toggleSelfGod(player, null);
            return true;
        }

        Boolean state = parseState(args[0]);
        if (state != null && args.length == 1) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage-on-off", "%label%", String.valueOf(label));
                return true;
            }
            if (!player.hasPermission("friendlycore.god")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }
            toggleSelfGod(player, state);
            return true;
        }

        if (!sender.hasPermission("friendlycore.god.others")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String targetName = args[0];
        Boolean explicitState = args.length > 1 ? parseState(args[1]) : null;

        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            plugin.getScheduler().runEntity(localTarget, () -> {
                boolean newState = explicitState != null ? explicitState : !essentialsManager.isGodMode(localTarget.getUniqueId());
                essentialsManager.setGodMode(localTarget.getUniqueId(), newState);
                if (newState) {
                    localTarget.setHealth(localTarget.getMaxHealth());
                    localTarget.setFoodLevel(20);
                    localTarget.setFireTicks(0);
                }

                essentialsManager.sendMessage(localTarget, newState ? "god.enabled-self" : "god.disabled-self", Map.of());
                essentialsManager.sendMessage(sender, newState ? "god.enabled-other" : "god.disabled-other",
                        Map.of("%player%", localTarget.getName()));
            });
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.getCrossServerService().dispatchGod(targetUuid, networkName, explicitState);
            String key = explicitState == null ? "god.toggled-other-network"
                    : explicitState ? "god.enabled-other-network" : "god.disabled-other-network";
            essentialsManager.sendMessage(sender, key,
                    Map.of("%player%", networkName));
            return true;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
        return true;
    }

    private void toggleSelfGod(Player player, Boolean explicitState) {
        plugin.getScheduler().runEntity(player, () -> {
            boolean newState = explicitState != null ? explicitState : !essentialsManager.isGodMode(player.getUniqueId());
            essentialsManager.setGodMode(player.getUniqueId(), newState);
            if (newState) {
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setFireTicks(0);
            }
            essentialsManager.sendMessage(player, newState ? "god.enabled-self" : "god.disabled-self", Map.of());
        });
    }

    private Boolean parseState(String str) {
        if (str.equalsIgnoreCase("on") || str.equalsIgnoreCase("enable") || str.equalsIgnoreCase("true") || str.equalsIgnoreCase("1")) {
            return true;
        }
        if (str.equalsIgnoreCase("off") || str.equalsIgnoreCase("disable") || str.equalsIgnoreCase("false") || str.equalsIgnoreCase("0")) {
            return false;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("on", "off"));
            if (sender.hasPermission("friendlycore.god.others")) {
                list.addAll(getPlayerSuggestions(args[0]));
            }
            return filterList(list, args[0]);
        } else if (args.length == 2 && sender.hasPermission("friendlycore.god.others")) {
            return filterList(List.of("on", "off"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> getPlayerSuggestions(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String name : plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : getLocalPlayerNames()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(name);
            }
        }
        return result;
    }

    private Set<String> getLocalPlayerNames() {
        Set<String> set = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            set.add(p.getName());
        }
        return set;
    }

    private List<String> filterList(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> res = new ArrayList<>();
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                res.add(s);
            }
        }
        return res;
    }
}
