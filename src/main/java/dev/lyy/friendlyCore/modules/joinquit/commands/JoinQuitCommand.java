package dev.lyy.friendlycore.modules.joinquit.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.joinquit.JoinQuitManager;
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
 * Command executor and tab completer for join/quit settings and preferences (/joinquit, /togglejoinquit).
 */
public class JoinQuitCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final JoinQuitManager manager;

    public JoinQuitCommand(FriendlyCore plugin, JoinQuitManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("friendlycore.admin") || sender.hasPermission("friendlycore.joinquit.admin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (label.equalsIgnoreCase("togglejoinquit") || label.equalsIgnoreCase("togglemessages") || label.equalsIgnoreCase("togglejq")) {
            return handleToggle(sender);
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "toggle" -> {
                return handleToggle(sender);
            }
            case "reload" -> {
                if (!isAdmin(sender)) {
                    plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission.</red>"), null);
                    return true;
                }
                long start = System.currentTimeMillis();
                manager.reload();
                long elapsed = System.currentTimeMillis() - start;
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("joinquit.reloaded", "<green>Configurations reloaded in %time%ms!</green>"),
                        Map.of("%time%", String.valueOf(elapsed)));
                return true;
            }
            case "stats" -> {
                sendStats(sender);
                return true;
            }
            default -> {
                sendHelp(sender);
                return true;
            }
        }
    }

    private boolean handleToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        boolean enabled = manager.togglePlayerMessages(player.getUniqueId());
        if (enabled) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("joinquit.toggled-on", "<green>Join & Quit messages enabled.</green>"), null);
        } else {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("joinquit.toggled-off", "<yellow>Join & Quit messages disabled.</yellow>"), null);
        }
        return true;
    }

    private void sendStats(CommandSender sender) {
        int unique = manager.getUniqueJoinsCount();
        int local = Bukkit.getOnlinePlayers().size();
        int network = (plugin.getRedisService() != null && plugin.getRedisService().isEnabled())
                ? plugin.getRedisService().getOnlinePlayerNames().size()
                : local;

        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.stats-header", "<gradient:#00d2ff:#3a7bd5><bold>=== Join & Quit Network Stats ===</bold></gradient>"), null);
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.stats-unique", "<gray>Total Unique Player Joins: <gold>%unique_joins%</gold></gray>"),
                Map.of("%unique_joins%", String.valueOf(unique)));
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.stats-local-online", "<gray>Local Server Online: <aqua>%local_online%</aqua></gray>"),
                Map.of("%local_online%", String.valueOf(local)));
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.stats-network-online", "<gray>Network Online Players: <green>%network_online%</green></gray>"),
                Map.of("%network_online%", String.valueOf(network)));
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.stats-status", "<gray>Module Status: %status%</gray>"),
                Map.of("%status%", manager.isEnabled() ? "<green>ACTIVE</green>" : "<red>DISABLED</red>"));
    }

    private void sendHelp(CommandSender sender) {
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.help-header", "<gradient:#00d2ff:#3a7bd5><bold>=== Join & Quit Commands ===</bold></gradient>"), null);
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.help-toggle", " <yellow>/joinquit toggle</yellow> <dark_gray>-</dark_gray> <gray>Toggle join/quit messages</gray>"), null);
        plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.help-stats", " <yellow>/joinquit stats</yellow> <dark_gray>-</dark_gray> <gray>View unique joins stats</gray>"), null);
        if (isAdmin(sender)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("joinquit.help-reload", " <yellow>/joinquit reload</yellow> <dark_gray>-</dark_gray> <gray>Reload join/quit module</gray>"), null);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("toggle");
            subs.add("stats");
            if (isAdmin(sender)) {
                subs.add("reload");
            }
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
