package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
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
 * High-performance, modern /vanish command for FriendlyCore.
 */
public class VanishCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;
    private final VanishManager vanishManager;

    public VanishCommand(FriendlyCore plugin, EssentialsManager essentialsManager, VanishManager vanishManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
        this.vanishManager = vanishManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "only-players-can-toggle-vanish-on", "%label%", String.valueOf(label));
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.use")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.toggleVanish(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("on") || sub.equals("enable")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "console-usage-on", "%label%", String.valueOf(label));
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.use")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.setVanished(player, true, false);
            return true;
        }

        if (sub.equals("off") || sub.equals("disable")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "console-usage-off", "%label%", String.valueOf(label));
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.use")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.setVanished(player, false, false);
            return true;
        }

        if (sub.equals("toggle")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "console-usage", "%label%", String.valueOf(label));
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.use")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.toggleVanish(player);
            return true;
        }

        if (sub.equals("pickup")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "only-players-can-toggle-pickup-mode");
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.use") || !sender.hasPermission("friendlycore.vanish.pickup")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            boolean enabled = vanishManager.toggleItemPickup(player);
            String key = enabled ? "vanish.pickup-enabled" : "vanish.pickup-disabled";
            String def = enabled
                    ? "<gradient:#7BD88F:#B9FBC0><bold>✦ ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɪᴛᴇᴍ ᴘɪᴄᴋᴜᴘ <color:#7BD88F>ᴇɴᴀʙʟᴇᴅ</color>."
                    : "<gradient:#FF4D5A:#FF9A9A><bold>✦ ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ɪᴛᴇᴍ ᴘɪᴄᴋᴜᴘ <color:#FF4D5A>ᴅɪѕᴀʙʟᴇᴅ</color>.";
            String msg = plugin.getConfigService().getMessages().getString("essentials." + key, def);
            plugin.getTextService().sendMessage(player, msg);
            return true;
        }

        if (sub.equals("fakejoin")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "only-players-can-trigger-fake-join");
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.fakejoin")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.broadcastFakeJoin(player);
            plugin.getTextService().sendMessage(player, "<gradient:#7BD88F:#B9FBC0><bold>✦ ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʙʀᴏᴀᴅᴄᴀѕᴛᴇᴅ ғᴀᴋᴇ ᴊᴏɪɴ ᴍᴇѕѕᴀɢᴇ.</color>");
            return true;
        }

        if (sub.equals("fakequit")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "only-players-can-trigger-fake-quit");
                return true;
            }
            if (!sender.hasPermission("friendlycore.vanish.fakequit")) {
                plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
                return true;
            }
            vanishManager.broadcastFakeQuit(player);
            plugin.getTextService().sendMessage(player, "<gradient:#FF4D5A:#FF9A9A><bold>✦ ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʙʀᴏᴀᴅᴄᴀѕᴛᴇᴅ ғᴀᴋᴇ ǫᴜɪᴛ ᴍᴇѕѕᴀɢᴇ.</color>");
            return true;
        }

        if (sub.equals("status")) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "total-vanished-players", "%vanished_count%", String.valueOf(vanishManager.getVanishedCount()));
                return true;
            }
            boolean vanished = vanishManager.isVanished(player.getUniqueId());
            boolean pickup = vanishManager.canPickupItems(player);
            int tier = vanishManager.getVanishTier(player);
            long duration = vanishManager.getVanishDuration(player.getUniqueId()) / 1000L;

            String statusTemplate = "<newline><gradient:#6EDBFF:#A7F3FF><bold>👻 ᴠᴀɴɪѕʜ ѕᴛᴀᴛᴜѕ</bold></gradient><newline>"
                    + "<dark_gray>•</dark_gray> <gray>Status:</gray> " + (vanished ? "<color:#7BD88F>Vanished</color>" : "<color:#FF4D5A>Visible</color>") + "<newline>"
                    + "<dark_gray>•</dark_gray> <gray>Tier:</gray> <color:#E7B85C>" + tier + "</color><newline>"
                    + "<dark_gray>•</dark_gray> <gray>Pickup Items:</gray> " + (pickup ? "<color:#7BD88F>Yes</color>" : "<color:#FF4D5A>No</color>") + "<newline>"
                    + (vanished ? "<dark_gray>•</dark_gray> <gray>Time Elapsed:</gray> <color:#00d2ff>" + formatDuration(duration) + "</color><newline>" : "")
                    + "<newline>";
            plugin.getTextService().sendMessage(player, statusTemplate);
            return true;
        }

        if (!sender.hasPermission("friendlycore.vanish.others")) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.player-not-found", "<red>Player not found."));
            return true;
        }

        Boolean explicitState = null;
        if (args.length >= 2) {
            String stateArg = args[1].toLowerCase(Locale.ROOT);
            if (stateArg.equals("on") || stateArg.equals("enable") || stateArg.equals("true")) {
                explicitState = true;
            } else if (stateArg.equals("off") || stateArg.equals("disable") || stateArg.equals("false")) {
                explicitState = false;
            }
        }

        boolean finalState = explicitState != null ? explicitState : !vanishManager.isVanished(target.getUniqueId());
        vanishManager.setVanished(target, finalState, false);

        String key = finalState ? "vanish.enabled-other" : "vanish.disabled-other";
        String def = finalState
                ? "<gradient:#6EDBFF:#A7F3FF><bold>👻 ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴇɴᴀʙʟᴇᴅ ᴠᴀɴɪѕʜ ғᴏʀ <color:#E7B85C>%player%</color>."
                : "<gradient:#6EDBFF:#A7F3FF><bold>👻 ᴠᴀɴɪѕʜ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴅɪѕᴀʙʟᴇᴅ ᴠᴀɴɪѕʜ ғᴏʀ <color:#E7B85C>%player%</color>.";
        String msg = plugin.getConfigService().getMessages().getString("essentials." + key, def);
        plugin.getTextService().sendMessage(sender, msg, Map.of("player", target.getName()));

        return true;
    }

    private String formatDuration(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        long h = m / 60;
        m = m % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> subcommands = List.of("on", "off", "toggle", "pickup", "fakejoin", "fakequit", "status");
            for (String s : subcommands) {
                if (s.startsWith(prefix)) completions.add(s);
            }

            if (sender.hasPermission("friendlycore.vanish.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        completions.add(p.getName());
                    }
                }
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String s : List.of("on", "off", "toggle")) {
                if (s.startsWith(prefix)) completions.add(s);
            }
        }

        return completions;
    }
}
