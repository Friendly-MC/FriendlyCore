package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
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
 * High-performance, aesthetic /online (/list) command for FriendlyCore.
 * Features LuckPerms weight hierarchy sorting, Adventure hover tooltips, click actions,
 * network-wide Redis aggregation, and vanish awareness.
 */
public class OnlineCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;
    private final VanishManager vanishManager;

    public record GroupInfo(String name, String displayName, int weight, String prefix) {}

    public record PlayerListing(Player player, GroupInfo groupInfo, boolean isVanished) {}

    public OnlineCommand(FriendlyCore plugin, EssentialsManager essentialsManager, VanishManager vanishManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
        this.vanishManager = vanishManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("friendlycore.online.use") && !sender.hasPermission("friendlycore.list.use")) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("general.no-permission", "<red>No permission."));
            return true;
        }

        Player viewer = sender instanceof Player p ? p : null;
        String filterGroup = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;

        List<PlayerListing> visibleListings = new ArrayList<>();
        int totalOnline = 0;
        int vanishedVisibleToViewer = 0;

        for (Player target : Bukkit.getOnlinePlayers()) {
            boolean isVanished = vanishManager.isVanished(target.getUniqueId());
            if (isVanished) {
                if (viewer != null && !vanishManager.canSee(viewer, target)) {
                    // Hide vanished player completely from regular player
                    continue;
                }
                vanishedVisibleToViewer++;
            }
            totalOnline++;
            GroupInfo group = resolvePlayerGroup(target);
            visibleListings.add(new PlayerListing(target, group, isVanished));
        }

        // 2. Network counts if Redis is enabled
        boolean showNetwork = plugin.getConfigService().get("essentials.yml").getBoolean("online.show-network-total", true);
        int networkTotal = totalOnline;
        if (showNetwork && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            Set<String> networkNames = plugin.getRedisService().getOnlinePlayerNames();
            if (networkNames != null && !networkNames.isEmpty()) {
                // The network set includes every local player; swap them for the visible local count
                // so "Online: 10 · Network: 11" can't reveal a vanished player on this server.
                networkTotal = Math.max(totalOnline, networkNames.size() - Bukkit.getOnlinePlayers().size() + totalOnline);
            }
        }

        int maxPlayers = Bukkit.getMaxPlayers();

        String headerFormat = plugin.getConfigService().getMessages().getString("essentials.online.header",
                "<newline><gradient:#00d2ff:#3a7bd5><bold>FriendlyCore Online</bold></gradient> <dark_gray>(<color:#7BD88F>%online%</color><dark_gray>/</dark_gray><color:#D8D0C3>%max%</color>)</dark_gray>");
        
        if (showNetwork && networkTotal > totalOnline) {
            headerFormat += " <dark_gray>[<gray>Network: <color:#00d2ff>" + networkTotal + "</color></dark_gray>]";
        }
        if (viewer != null && viewer.hasPermission("friendlycore.vanish.see") && vanishedVisibleToViewer > 0) {
            headerFormat += " <dark_gray>(<color:#6EDBFF>" + vanishedVisibleToViewer + " vanished</color>)</dark_gray>";
        }

        Map<String, String> headerPlaceholders = Map.of(
                "online", String.valueOf(totalOnline),
                "max", String.valueOf(maxPlayers),
                "network", String.valueOf(networkTotal)
        );

        plugin.getTextService().sendMessage(sender, headerFormat, headerPlaceholders);

        Map<String, List<PlayerListing>> groupedPlayers = new LinkedHashMap<>();
        Map<String, GroupInfo> groupInfoMap = new HashMap<>();

        for (PlayerListing listing : visibleListings) {
            String groupKey = listing.groupInfo().name().toLowerCase(Locale.ROOT);
            if (filterGroup != null && !groupKey.contains(filterGroup) && !listing.groupInfo().displayName().toLowerCase(Locale.ROOT).contains(filterGroup)) {
                continue;
            }
            groupedPlayers.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(listing);
            groupInfoMap.putIfAbsent(groupKey, listing.groupInfo());
        }

        if (groupedPlayers.isEmpty()) {
            if (filterGroup != null) {
                plugin.getTextService().sendMessage(sender, "<gray>No online players found in group '<color:#00d2ff>" + filterGroup + "</color>'.");
            } else {
                plugin.getTextService().sendMessage(sender, "<gray>No players currently online.");
            }
            return true;
        }

        List<String> sortedGroupKeys = new ArrayList<>(groupedPlayers.keySet());
        sortedGroupKeys.sort((g1, g2) -> {
            int w1 = groupInfoMap.get(g1).weight();
            int w2 = groupInfoMap.get(g2).weight();
            if (w1 != w2) return Integer.compare(w2, w1);
            return g1.compareToIgnoreCase(g2);
        });

        for (String groupKey : sortedGroupKeys) {
            List<PlayerListing> playersInGroup = groupedPlayers.get(groupKey);
            GroupInfo gInfo = groupInfoMap.get(groupKey);

            playersInGroup.sort(Comparator.comparing(p -> p.player().getName(), String.CASE_INSENSITIVE_ORDER));

            Component groupRow = buildGroupComponent(sender, gInfo, playersInGroup);
            sender.sendMessage(groupRow);
        }

        String footer = plugin.getConfigService().getMessages().getString("essentials.online.footer", "<newline>");
        if (footer != null && !footer.isBlank()) {
            plugin.getTextService().sendMessage(sender, footer);
        }

        return true;
    }

    private Component buildGroupComponent(CommandSender viewer, GroupInfo group, List<PlayerListing> listings) {
        String groupLayout = plugin.getConfigService().getMessages().getString("essentials.online.group-layout",
                "<color:#00d2ff><bold>%group_display%</bold></color> <dark_gray>(<color:#7BD88F>%count%</color>):</dark_gray> ");
        
        groupLayout = groupLayout
                .replace("%group%", group.name())
                .replace("%group_display%", group.displayName())
                .replace("%count%", String.valueOf(listings.size()));

        Component groupPrefixComp = plugin.getTextService().parse(groupLayout);

        Component playerListComp = Component.empty();
        String separator = plugin.getConfigService().getMessages().getString("essentials.online.separator", "<dark_gray>, </dark_gray>");
        Component separatorComp = plugin.getTextService().parse(separator);

        boolean isFirst = true;
        for (PlayerListing listing : listings) {
            if (!isFirst) {
                playerListComp = playerListComp.append(separatorComp);
            }
            isFirst = false;

            Component singlePlayer = formatPlayerComponent(viewer, listing);
            playerListComp = playerListComp.append(singlePlayer);
        }

        return groupPrefixComp.append(playerListComp);
    }

    private Component formatPlayerComponent(CommandSender viewer, PlayerListing listing) {
        Player player = listing.player();
        int ping = player.getPing();
        String pingColor = ping < 60 ? "#7BD88F" : (ping < 150 ? "#FFD166" : "#FF4D5A");
        int health = (int) Math.ceil(player.getHealth());
        String world = player.getWorld().getName();
        String prefix = listing.groupInfo().prefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "";
        }

        String vanishTag = listing.isVanished() ? "<color:#6EDBFF>[V]</color> " : "";

        String hoverText = "<gradient:#00d2ff:#3a7bd5><bold>" + player.getName() + "</bold></gradient><newline>"
                + (prefix.isEmpty() ? "" : "<gray>Rank Prefix: " + prefix + "<newline>")
                + "<gray>Rank:</gray> <color:#00d2ff>" + listing.groupInfo().displayName() + "</color><newline>"
                + "<gray>Ping:</gray> <color:" + pingColor + ">" + ping + "ms</color><newline>"
                + "<gray>Health:</gray> <color:#FF5252>" + health + "/20 ❤</color><newline>"
                + "<gray>World:</gray> <color:#E7B85C>" + world + "</color><newline>"
                + (listing.isVanished() ? "<color:#6EDBFF>👻 Currently Vanished</color><newline>" : "")
                + "<newline><dark_gray>▶ Click to message</dark_gray>";

        String clickCommand = "/msg " + player.getName() + " ";
        if (viewer instanceof Player p && p.hasPermission("friendlycore.teleport")) {
            hoverText += "<newline><dark_gray>▶ Staff: /tp " + player.getName() + "</dark_gray>";
        }

        String playerTemplate = "<hover:show_text:'" + hoverText.replace("'", "\\'") + "'>"
                + "<click:suggest_command:'" + clickCommand + "'>"
                + vanishTag + "<color:#D8D0C3>" + player.getName() + "</color>"
                + "</click></hover>";

        return plugin.getTextService().parse(playerTemplate);
    }

    public GroupInfo resolvePlayerGroup(Player player) {
        if (player == null) return new GroupInfo("default", "Player", 0, "");

        try {
            if (plugin.getConfigService().get("essentials.yml").getBoolean("online.group-by-luckperms", true)
                    && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                LuckPerms lp = LuckPermsProvider.get();
                User lpUser = lp.getUserManager().getUser(player.getUniqueId());
                if (lpUser != null) {
                    Group highestGroup = lpUser.getInheritedGroups(lpUser.getQueryOptions()).stream()
                            .max(Comparator.comparingInt(g -> g.getWeight().orElse(0)))
                            .orElse(null);

                    if (highestGroup == null) {
                        highestGroup = lp.getGroupManager().getGroup(lpUser.getPrimaryGroup());
                    }

                    if (highestGroup != null) {
                        String name = highestGroup.getName();
                        String displayName = highestGroup.getDisplayName() != null ? highestGroup.getDisplayName() : capitalize(name);
                        int weight = highestGroup.getWeight().orElse(0);
                        String prefix = lpUser.getCachedData().getMetaData().getPrefix();
                        if (prefix == null) prefix = highestGroup.getCachedData().getMetaData().getPrefix();
                        return new GroupInfo(name, displayName, weight, prefix != null ? prefix : "");
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (player.isOp()) {
            return new GroupInfo("admin", "Admin", 100, "<red>[Admin]</red> ");
        }

        return new GroupInfo("default", "Player", 0, "");
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            Set<String> groups = new HashSet<>();

            try {
                if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
                    for (Group g : LuckPermsProvider.get().getGroupManager().getLoadedGroups()) {
                        groups.add(g.getName().toLowerCase(Locale.ROOT));
                    }
                }
            } catch (Throwable ignored) {}

            groups.add("admin");
            groups.add("default");

            return groups.stream()
                    .filter(g -> g.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
