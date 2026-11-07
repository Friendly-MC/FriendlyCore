package dev.lyy.friendlycore.modules.pvp.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Root command for PvP module functionality: toggle, newbie status, stats, and admin utilities.
 */
public class PvpCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public PvpCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "toggle" -> {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "pvp", "this-command-can-only-be-executed");
                    return true;
                }
                plugin.getPvpManager().togglePvp(player);
                return true;
            }
            case "newbie" -> {
                if (args.length > 1 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("disable"))) {
                    if (!(sender instanceof Player player)) {
                        Messages.send(plugin, sender, "pvp", "this-command-can-only-be-executed");
                        return true;
                    }
                    if (!plugin.getPvpManager().getNewbieProtectionManager().hasProtection(player.getUniqueId())) {
                        plugin.getTextService().sendMessage(player, "newbie.expired");
                        return true;
                    }
                    plugin.getPvpManager().getNewbieProtectionManager().removeProtection(player.getUniqueId(), true);
                    return true;
                }

                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "pvp", "usage-pvp-newbie-off");
                    return true;
                }

                if (plugin.getPvpManager().getNewbieProtectionManager().hasProtection(player.getUniqueId())) {
                    int remaining = plugin.getPvpManager().getNewbieProtectionManager().getRemainingSeconds(player.getUniqueId());
                    plugin.getTextService().sendMessage(player, "newbie.granted", Map.of("%time%", String.valueOf(remaining)));
                } else {
                    plugin.getTextService().sendMessage(player, "newbie.expired");
                }
                return true;
            }
            case "stats" -> {
                Player target = sender instanceof Player p ? p : null;
                if (args.length > 1) {
                    target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                        return true;
                    }
                }
                if (target == null) {
                    Messages.send(plugin, sender, "pvp", "usage-pvp-stats");
                    return true;
                }

                PlayerStats stats = plugin.getStatsManager().getStats(target.getUniqueId(), target.getName());
                Map<String, String> placeholders = Map.of(
                        "%player%", target.getName(),
                        "%kills%", String.valueOf(stats.getKills()),
                        "%deaths%", String.valueOf(stats.getDeaths()),
                        "%kd%", String.format("%.2f", stats.getKDRatio()),
                        "%streak%", String.valueOf(stats.getCurrentStreak()),
                        "%highest_streak%", String.valueOf(stats.getHighestStreak())
                );

                plugin.getTextService().sendMessage(sender, "commands.stats-header", placeholders);
                plugin.getTextService().sendMessage(sender, "commands.stats-kills", placeholders);
                plugin.getTextService().sendMessage(sender, "commands.stats-deaths", placeholders);
                plugin.getTextService().sendMessage(sender, "commands.stats-kd", placeholders);
                plugin.getTextService().sendMessage(sender, "commands.stats-streak", placeholders);
                return true;
            }
            case "tag", "combattag", "status" -> {
                Player target = sender instanceof Player p ? p : null;
                if (args.length > 1) {
                    target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                        return true;
                    }
                }
                if (target == null) {
                    Messages.send(plugin, sender, "pvp", "usage-pvp-tag");
                    return true;
                }

                if (plugin.getPvpManager().getCombatTagManager().isTagged(target.getUniqueId())) {
                    int remaining = plugin.getPvpManager().getCombatTagManager().getRemainingSeconds(target.getUniqueId());
                    plugin.getTextService().sendMessage(sender, "combat.status-in-combat", Map.of("%time%", String.valueOf(remaining)));
                } else {
                    plugin.getTextService().sendMessage(sender, "combat.status-safe");
                }
                return true;
            }
            case "bypass" -> {
                if (!sender.hasPermission("friendlycore.pvp.admin") && !sender.hasPermission("friendlycore.admin")) {
                    plugin.getTextService().sendMessage(sender, "commands.no-permission");
                    return true;
                }
                Player target = sender instanceof Player p ? p : null;
                if (args.length > 1) {
                    if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("toggle")) {
                        if (target == null) {
                            Messages.send(plugin, sender, "pvp", "usage-pvp-bypass-on-off");
                            return true;
                        }
                        boolean newState = args[1].equalsIgnoreCase("on") || (args[1].equalsIgnoreCase("toggle") && !plugin.getPvpManager().hasAdminBypass(target.getUniqueId()));
                        plugin.getPvpManager().setAdminBypass(target.getUniqueId(), newState);
                        if (newState) {
                            plugin.getTextService().sendMessage(sender, "pvp.bypass-enabled-self");
                        } else {
                            plugin.getTextService().sendMessage(sender, "pvp.bypass-disabled-self");
                        }
                        return true;
                    } else {
                        target = Bukkit.getPlayer(args[1]);
                        if (target == null) {
                            plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                            return true;
                        }
                        boolean newState;
                        if (args.length > 2) {
                            newState = args[2].equalsIgnoreCase("on");
                        } else {
                            newState = plugin.getPvpManager().toggleAdminBypass(target.getUniqueId());
                        }
                        plugin.getPvpManager().setAdminBypass(target.getUniqueId(), newState);
                        if (newState) {
                            plugin.getTextService().sendMessage(sender, "pvp.bypass-enabled-other", Map.of("%target%", target.getName()));
                        } else {
                            plugin.getTextService().sendMessage(sender, "pvp.bypass-disabled-other", Map.of("%target%", target.getName()));
                        }
                        return true;
                    }
                }
                if (target == null) {
                    Messages.send(plugin, sender, "pvp", "usage-pvp-bypass-on-off");
                    return true;
                }
                boolean newState = plugin.getPvpManager().toggleAdminBypass(target.getUniqueId());
                if (newState) {
                    plugin.getTextService().sendMessage(sender, "pvp.bypass-enabled-self");
                } else {
                    plugin.getTextService().sendMessage(sender, "pvp.bypass-disabled-self");
                }
                return true;
            }
            case "untag" -> {
                if (!sender.hasPermission("friendlycore.admin") && !sender.hasPermission("friendlycore.pvp.admin")) {
                    plugin.getTextService().sendMessage(sender, "commands.no-permission");
                    return true;
                }
                Player target = sender instanceof Player p ? p : null;
                if (args.length > 1) {
                    target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        plugin.getTextService().sendMessage(sender, "commands.player-not-found");
                        return true;
                    }
                }
                if (target == null) {
                    Messages.send(plugin, sender, "pvp", "usage-pvp-untag");
                    return true;
                }
                if (plugin.getPvpManager().getCombatTagManager().isTagged(target.getUniqueId())) {
                    plugin.getPvpManager().getCombatTagManager().untagPlayer(target, dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.COMMAND);
                    plugin.getTextService().sendMessage(sender, "pvp.untagged-other", Map.of("%target%", target.getName()));
                } else {
                    plugin.getTextService().sendMessage(sender, "combat.status-safe");
                }
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("friendlycore.admin") && !sender.hasPermission("friendlycore.pvp.admin")) {
                    plugin.getTextService().sendMessage(sender, "commands.no-permission");
                    return true;
                }
                plugin.getConfigService().reload("modules/pvp/config.yml");
                plugin.getConfigService().reload("modules/pvp/messages.yml");
                plugin.getPvpManager().reload();
                plugin.getTextService().sendMessage(sender, "commands.reloaded");
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        Messages.send(plugin, sender, "pvp", "pvp-commands");
        Messages.send(plugin, sender, "pvp", "pvp-toggle-toggle-your-pvp-combat");
        Messages.send(plugin, sender, "pvp", "pvp-stats-player-view-pvp-statistics");
        Messages.send(plugin, sender, "pvp", "pvp-tag-player-check-combat-status");
        Messages.send(plugin, sender, "pvp", "pvp-newbie-off-check-or-disable");
        if (sender.hasPermission("friendlycore.admin") || sender.hasPermission("friendlycore.pvp.admin")) {
            Messages.send(plugin, sender, "pvp", "pvp-untag-remove-combat-tag-from");
            Messages.send(plugin, sender, "pvp", "pvp-bypass-player-on-off-toggle");
            Messages.send(plugin, sender, "pvp", "pvp-reload-reload-pvp-configuration");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("toggle", "newbie", "stats", "tag"));
            if (sender.hasPermission("friendlycore.admin") || sender.hasPermission("friendlycore.pvp.admin")) {
                subs.add("untag");
                subs.add("bypass");
                subs.add("reload");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("bypass"))) {
            List<String> players = new ArrayList<>();
            if (args[0].equalsIgnoreCase("bypass")) {
                players.addAll(List.of("on", "off", "toggle"));
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                players.add(p.getName());
            }
            return filter(players, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bypass")) {
            return filter(List.of("on", "off", "toggle"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("newbie")) {
            return filter(List.of("off", "status"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> list, String prefix) {
        String search = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String item : list) {
            if (item.toLowerCase().startsWith(search)) {
                result.add(item);
            }
        }
        return result;
    }
}
