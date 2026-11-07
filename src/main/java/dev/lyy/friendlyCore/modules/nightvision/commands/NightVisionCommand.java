package dev.lyy.friendlycore.modules.nightvision.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.nightvision.NightVisionManager;
import dev.lyy.friendlycore.modules.nightvision.NightVisionProfile;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command handler for /nv and /nightvision.
 */
public class NightVisionCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final NightVisionManager manager;

    public NightVisionCommand(FriendlyCore plugin, NightVisionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendMsg(sender, "nightvision.players-only", "&cOnly players can use this command.");
                return true;
            }
            if (!hasUsePermission(sender)) {
                sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                return true;
            }
            manager.toggle(player, sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "toggle", "t" -> {
                if (args.length > 1) {
                    if (!hasOthersPermission(sender)) {
                        sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sendMsg(sender, "nightvision.player-not-found", "&cPlayer not found.", Map.of("player", args[1]));
                        return true;
                    }
                    manager.toggle(target, sender);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sendMsg(sender, "nightvision.players-only", "&cOnly players can use this command.");
                    return true;
                }
                if (!hasUsePermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                manager.toggle(player, sender);
                return true;
            }
            case "on" -> {
                if (args.length > 1) {
                    if (!hasOthersPermission(sender)) {
                        sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sendMsg(sender, "nightvision.player-not-found", "&cPlayer not found.", Map.of("player", args[1]));
                        return true;
                    }
                    manager.enable(target, sender, false);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sendMsg(sender, "nightvision.players-only", "&cOnly players can use this command.");
                    return true;
                }
                if (!hasUsePermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                manager.enable(player, sender, false);
                return true;
            }
            case "off" -> {
                if (args.length > 1) {
                    if (!hasOthersPermission(sender)) {
                        sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sendMsg(sender, "nightvision.player-not-found", "&cPlayer not found.", Map.of("player", args[1]));
                        return true;
                    }
                    manager.disable(target, sender, false);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sendMsg(sender, "nightvision.players-only", "&cOnly players can use this command.");
                    return true;
                }
                if (!hasUsePermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                manager.disable(player, sender, false);
                return true;
            }
            case "status" -> {
                if (args.length > 1) {
                    if (!hasOthersPermission(sender)) {
                        sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sendMsg(sender, "nightvision.player-not-found", "&cPlayer not found.", Map.of("player", args[1]));
                        return true;
                    }
                    NightVisionProfile prof = manager.getProfile(target.getUniqueId(), target.getName());
                    String status = prof.isEnabled() ? "&aEnabled" : "&cDisabled";
                    sendMsg(sender, "nightvision.status-other", "&7Night vision status for &f%player%&7: %status%",
                            Map.of("player", target.getName(), "status", status));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sendMsg(sender, "nightvision.players-only", "&cOnly players can use this command.");
                    return true;
                }
                if (!hasUsePermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                NightVisionProfile prof = manager.getProfile(player.getUniqueId(), player.getName());
                String status = prof.isEnabled() ? "&aEnabled" : "&cDisabled";
                sendMsg(sender, "nightvision.status-self", "&7Your night vision status: %status%", Map.of("status", status));
                return true;
            }
            case "reload" -> {
                if (!hasReloadPermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                long start = System.currentTimeMillis();
                try {
                    plugin.getConfigService().loadConfig("nightvision.yml");
                    plugin.getConfigService().loadConfig("messages.yml");
                    manager.loadSettings();
                    long time = System.currentTimeMillis() - start;
                    sendMsg(sender, "nightvision.reloaded", "&aNightVision configuration reloaded in %time%ms.", Map.of("time", String.valueOf(time)));
                    if (sender instanceof Player player) {
                        manager.playReloadSound(player);
                    }
                } catch (Exception e) {
                    sendMsg(sender, "nightvision.reload-failed", "&cFailed to reload configuration. Check console for details.");
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to reload NightVision config", e);
                }
                return true;
            }
            case "help" -> {
                if (!hasHelpPermission(sender)) {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                    return true;
                }
                sendHelp(sender);
                return true;
            }
            default -> {
                // If arg0 is a player name and sender has others permission, toggle for that player
                Player target = Bukkit.getPlayer(args[0]);
                if (target != null && hasOthersPermission(sender)) {
                    manager.toggle(target, sender);
                    return true;
                }
                // Otherwise show help
                if (hasHelpPermission(sender)) {
                    sendHelp(sender);
                } else {
                    sendMsg(sender, "nightvision.no-permission", "&cYou do not have permission to do that.");
                }
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        List<String> lines = plugin.getConfigService().getMessages().getStringList("nightvision.help-lines");
        if (lines == null || lines.isEmpty()) {
            lines = List.of(
                    "<gradient:#00d2ff:#3a7bd5><bold>NightVision Commands:</bold></gradient>",
                    " <#00d2ff>/nv</#00d2ff> <gray>- Toggle your night vision</gray>",
                    " <#00d2ff>/nv <on|off></#00d2ff> <gray>- Turn night vision on or off</gray>",
                    " <#00d2ff>/nv status</#00d2ff> <gray>- Check your night vision status</gray>",
                    " <#00d2ff>/nv toggle <player></#00d2ff> <gray>- Toggle night vision for another player</gray>",
                    " <#00d2ff>/nv reload</#00d2ff> <gray>- Reload NightVision configuration</gray>",
                    " <#00d2ff>/nv help</#00d2ff> <gray>- Display this help message</gray>"
            );
        }

        for (String line : lines) {
            plugin.getTextService().sendMessage(sender, line, null);
        }
    }

    private void sendMsg(CommandSender sender, String key, String def) {
        sendMsg(sender, key, def, null);
    }

    private void sendMsg(CommandSender sender, String key, String def, Map<String, String> placeholders) {
        String msg = plugin.getConfigService().getMessages().getString(key, def);
        plugin.getTextService().sendMessage(sender, msg, placeholders);
    }

    private boolean hasUsePermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.nightvision.use")
                || sender.hasPermission("nightvision.use")
                || sender.hasPermission("nightvision.admin")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasOthersPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.nightvision.others")
                || sender.hasPermission("nightvision.admin")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasReloadPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.nightvision.admin")
                || sender.hasPermission("nightvision.reload")
                || sender.hasPermission("nightvision.admin")
                || sender.hasPermission("friendlycore.admin");
    }

    private boolean hasHelpPermission(CommandSender sender) {
        return sender.hasPermission("friendlycore.nightvision.help")
                || sender.hasPermission("nightvision.help")
                || sender.hasPermission("nightvision.use")
                || sender.hasPermission("friendlycore.nightvision.use")
                || sender.hasPermission("friendlycore.admin");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (hasUsePermission(sender)) {
                options.add("toggle");
                options.add("on");
                options.add("off");
                options.add("status");
            }
            if (hasReloadPermission(sender)) {
                options.add("reload");
            }
            if (hasHelpPermission(sender)) {
                options.add("help");
            }
            if (hasOthersPermission(sender)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    options.add(p.getName());
                }
            }
            String current = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(current)).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("toggle") || sub.equals("on") || sub.equals("off") || sub.equals("status")) && hasOthersPermission(sender)) {
                String current = args[1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(current))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
