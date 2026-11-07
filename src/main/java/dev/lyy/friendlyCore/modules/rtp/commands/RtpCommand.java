package dev.lyy.friendlycore.modules.rtp.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.rtp.RtpManager;
import dev.lyy.friendlycore.modules.rtp.RtpSpreadingManager;
import dev.lyy.friendlycore.modules.rtp.RtpWorldConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command handling for /rtp, /wild, and /randomtp with support for world targeting,
 * cross-server dispatch, administrative force-teleportation, and mass player spreading.
 */
public class RtpCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final RtpManager rtpManager;

    public RtpCommand(FriendlyCore plugin, RtpManager rtpManager) {
        this.plugin = plugin;
        this.rtpManager = rtpManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "rtp", "usage-rtp");
                return true;
            }
            if (!player.hasPermission("friendlycore.rtp.use")) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission to use RTP."), null);
                return true;
            }
            rtpManager.startRtp(player, null, false);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("friendlycore.rtp.admin")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission."), null);
                return true;
            }
            plugin.getConfigService().loadConfig("rtp.yml");
            plugin.getConfigService().loadConfig("messages/rtp.yml");
            rtpManager.reload();
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("rtp.reload-success",
                                "&#00e676RTP configuration, spatial spreading engine, and cache reloaded successfully!"), null);
            return true;
        }

        // View cache stats and active dispersal settings
        if (sub.equals("status") || sub.equals("info")) {
            if (!sender.hasPermission("friendlycore.rtp.admin")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission."), null);
                return true;
            }
            Messages.send(plugin, sender, "rtp", "friendlycore-rtp-spreading-status");
            Messages.send(plugin, sender, "rtp", "location-cache-enabled", "%enabled%", String.valueOf(rtpManager.getLocationCache().isEnabled()));
            Messages.send(plugin, sender, "rtp", "total-cached-safe-spots", "%total_cached%", String.valueOf(rtpManager.getLocationCache().getTotalCached()));
            if (rtpManager.getSpreadingManager() != null) {
                RtpSpreadingManager sm = rtpManager.getSpreadingManager();
                Messages.send(plugin, sender, "rtp", sm.isEnabled() ? "spreading-engine-enabled" : "spreading-engine-disabled");
                Messages.send(plugin, sender, "rtp", "default-distribution-algorithm-sectors", "%default_algorithm%", String.valueOf(sm.getDefaultAlgorithm()), "%sector_count%", String.valueOf(sm.getSectorCount()));
                Messages.send(plugin, sender, "rtp", "min-distance-from-players-blocks", "%min_player_distance%", String.valueOf(sm.getMinPlayerDistance()));
                Messages.send(plugin, sender, "rtp", "min-distance-between-recent-blocks", "%min_recent_distance%", String.valueOf(sm.getMinRecentDistance()));
                Messages.send(plugin, sender, "rtp", "active-tracked-recent-landings", "%total_recent_count%", String.valueOf(sm.getTotalRecentCount()));
            }
            for (World w : Bukkit.getWorlds()) {
                RtpWorldConfig cfg = rtpManager.getWorldConfig(w.getName());
                int cached = rtpManager.getLocationCache().getCachedCount(w.getName());
                int recent = rtpManager.getSpreadingManager() != null ? rtpManager.getSpreadingManager().getRecentCount(w.getName()) : 0;
                if (cfg != null) {
                    Messages.send(plugin, sender, "rtp", "cached-recent-r", "%w_name%", w.getName(), "%world_type%", String.valueOf(cfg.getWorldType()), "%shape%", String.valueOf(cfg.getShape()), "%distribution_algorithm%", String.valueOf(cfg.getDistributionAlgorithm()), "%cached%", String.valueOf(cached), "%recent%", String.valueOf(recent), "%min_radius%", String.valueOf(cfg.getMinRadius()), "%max_radius%", String.valueOf(cfg.getMaxRadius()));
                    if (cfg.isLandingPool()) {
                        int pool = rtpManager.getLocationCache().getPoolSize(w.getName());
                        Messages.send(plugin, sender, "rtp", "landing-pool-size", "%size%", pool < 0 ? "building..." : pool + " spots");
                    }
                }
            }
            return true;
        }

        if (sub.equals("spread")) {
            if (!sender.hasPermission("friendlycore.rtp.spread") && !sender.hasPermission("friendlycore.rtp.admin")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission to spread players."), null);
                return true;
            }

            int radius = -1;
            List<Player> targetPlayers = new ArrayList<>();
            int playerArgIndex = 1;

            if (args.length >= 2) {
                try {
                    radius = Integer.parseInt(args[1]);
                    playerArgIndex = 2;
                } catch (NumberFormatException ignored) {
                    playerArgIndex = 1;
                }
            }

            if (args.length > playerArgIndex) {
                String targetArg = args[playerArgIndex];
                if ("all".equalsIgnoreCase(targetArg) || "*".equals(targetArg)) {
                    targetPlayers.addAll(Bukkit.getOnlinePlayers());
                } else {
                    for (int i = playerArgIndex; i < args.length; i++) {
                        String[] split = args[i].split(",");
                        for (String pName : split) {
                            Player p = Bukkit.getPlayer(pName.trim());
                            if (p != null && p.isOnline() && !targetPlayers.contains(p)) {
                                targetPlayers.add(p);
                            }
                        }
                    }
                }
            } else if (sender instanceof Player p) {
                targetPlayers.add(p);
            } else {
                Messages.send(plugin, sender, "rtp", "usage-rtp-spread-radius");
                return true;
            }

            if (targetPlayers.isEmpty()) {
                Messages.send(plugin, sender, "rtp", "no-online-players-found-to-spread");
                return true;
            }

            World world = (sender instanceof Player p) ? p.getWorld() : targetPlayers.get(0).getWorld();
            rtpManager.spreadPlayers(sender, targetPlayers, world.getName(), radius);
            return true;
        }

        if (sub.equals("world") && args.length >= 2) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "rtp", "only-players-can-rtp-to-a");
                return true;
            }
            if (!player.hasPermission("friendlycore.rtp.world")) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission."), null);
                return true;
            }
            rtpManager.startRtp(player, args[1], false);
            return true;
        }

        if (sub.equals("server") && args.length >= 2) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "rtp", "only-players-can-cross-server-rtp");
                return true;
            }
            if (!player.hasPermission("friendlycore.rtp.server")) {
                plugin.getTextService().sendMessage(player,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission."), null);
                return true;
            }
            String targetServer = args[1];
            String targetWorld = args.length >= 3 ? args[2] : null;
            rtpManager.getCrossServerService().requestCrossServerRtp(player, targetServer, targetWorld);
            return true;
        }

        if (sub.equals("player") && args.length >= 2) {
            if (!sender.hasPermission("friendlycore.rtp.others")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                "&#ff3845You do not have permission."), null);
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                Messages.send(plugin, sender, "rtp", "player-is-not-online", "%input%", args[1]);
                return true;
            }
            String targetWorld = args.length >= 3 ? args[2] : null;
            rtpManager.startRtp(target, targetWorld, true);
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("rtp.admin-teleported-other",
                            "&#00d2ffTeleported %player% to random location in %world%."),
                    Map.of("%player%", target.getName(),
                            "%world%", targetWorld != null ? targetWorld : target.getWorld().getName()));
            return true;
        }

        if (sender instanceof Player player) {
            World directWorld = Bukkit.getWorld(args[0]);
            if (directWorld != null) {
                if (!player.hasPermission("friendlycore.rtp.world")) {
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                    "&#ff3845You do not have permission."), null);
                    return true;
                }
                rtpManager.startRtp(player, args[0], false);
                return true;
            }

            if (rtpManager.getCrossServerService() != null && rtpManager.getCrossServerService().isEnabled()) {
                for (String srv : rtpManager.getCrossServerService().getTargetServers()) {
                    if (srv.equalsIgnoreCase(args[0])) {
                        if (!player.hasPermission("friendlycore.rtp.server")) {
                            plugin.getTextService().sendMessage(player,
                                    plugin.getConfigService().getMessages().getString("rtp.no-permission",
                                            "&#ff3845You do not have permission."), null);
                            return true;
                        }
                        String targetWorld = args.length >= 2 ? args[1] : null;
                        rtpManager.getCrossServerService().requestCrossServerRtp(player, srv, targetWorld);
                        return true;
                    }
                }
            }
        }

        Messages.send(plugin, sender, "rtp", "usage-rtp-world-server-player-spread");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            String input = args[0].toLowerCase(Locale.ROOT);
            if ("world".startsWith(input) && sender.hasPermission("friendlycore.rtp.world")) list.add("world");
            if ("server".startsWith(input) && sender.hasPermission("friendlycore.rtp.server")) list.add("server");
            if ("player".startsWith(input) && sender.hasPermission("friendlycore.rtp.others")) list.add("player");
            if ("spread".startsWith(input) && (sender.hasPermission("friendlycore.rtp.spread") || sender.hasPermission("friendlycore.rtp.admin"))) list.add("spread");
            if ("reload".startsWith(input) && sender.hasPermission("friendlycore.rtp.admin")) list.add("reload");
            if ("status".startsWith(input) && sender.hasPermission("friendlycore.rtp.admin")) list.add("status");

            if (sender.hasPermission("friendlycore.rtp.world")) {
                for (World w : Bukkit.getWorlds()) {
                    if (w.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        list.add(w.getName());
                    }
                }
            }

            if (sender.hasPermission("friendlycore.rtp.server") && rtpManager.getCrossServerService() != null) {
                for (String srv : rtpManager.getCrossServerService().getTargetServers()) {
                    if (srv.toLowerCase(Locale.ROOT).startsWith(input)) {
                        list.add(srv);
                    }
                }
            }
            return list;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String input = args[1].toLowerCase(Locale.ROOT);

            if (sub.equals("world") && sender.hasPermission("friendlycore.rtp.world")) {
                List<String> worlds = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) {
                    if (w.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        worlds.add(w.getName());
                    }
                }
                return worlds;
            }

            if (sub.equals("server") && sender.hasPermission("friendlycore.rtp.server")) {
                List<String> servers = new ArrayList<>();
                for (String s : rtpManager.getCrossServerService().getTargetServers()) {
                    if (s.toLowerCase(Locale.ROOT).startsWith(input)) {
                        servers.add(s);
                    }
                }
                return servers;
            }

            if (sub.equals("player") && sender.hasPermission("friendlycore.rtp.others")) {
                List<String> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        players.add(p.getName());
                    }
                }
                return players;
            }

            if (sub.equals("spread") && (sender.hasPermission("friendlycore.rtp.spread") || sender.hasPermission("friendlycore.rtp.admin"))) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("1000");
                suggestions.add("2500");
                suggestions.add("5000");
                suggestions.add("all");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        suggestions.add(p.getName());
                    }
                }
                return suggestions;
            }
        }

        if (args.length >= 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String input = args[args.length - 1].toLowerCase(Locale.ROOT);

            if (sub.equals("spread") && (sender.hasPermission("friendlycore.rtp.spread") || sender.hasPermission("friendlycore.rtp.admin"))) {
                List<String> players = new ArrayList<>();
                if ("all".startsWith(input)) players.add("all");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        players.add(p.getName());
                    }
                }
                return players;
            }

            if (args.length == 3 && (sub.equals("server") || sub.equals("player"))) {
                List<String> worlds = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) {
                    if (w.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                        worlds.add(w.getName());
                    }
                }
                return worlds;
            }
        }

        return Collections.emptyList();
    }
}
