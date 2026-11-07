package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for modifying world weather state (/weather, /sun, /rain, /storm).
 * Supports specific world targets, network-wide broadcasts, and Folia global scheduling.
 */
public class WeatherCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private static final List<String> TYPES = List.of("sun", "clear", "rain", "storm", "thunder");
    private static final List<String> DURATIONS = List.of("60", "300", "600", "1200", "3600");

    public WeatherCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("friendlycore.weather")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String cmd = label.toLowerCase(Locale.ROOT);
        boolean isStorm;
        int durationSeconds = 600; // default 10 minutes
        String worldArg = null;

        if (cmd.endsWith("sun")) {
            isStorm = false;
            if (args.length > 0) {
                Integer dur = parseDuration(args[0]);
                if (dur != null) {
                    durationSeconds = dur;
                    if (args.length > 1) worldArg = args[1];
                } else {
                    worldArg = args[0];
                }
            }
        } else if (cmd.endsWith("rain") || cmd.endsWith("storm") || cmd.endsWith("thunder")) {
            isStorm = true;
            if (args.length > 0) {
                Integer dur = parseDuration(args[0]);
                if (dur != null) {
                    durationSeconds = dur;
                    if (args.length > 1) worldArg = args[1];
                } else {
                    worldArg = args[0];
                }
            }
        } else {
            if (args.length == 0) {
                essentialsManager.sendMessage(sender, "weather.usage", Map.of("%label%", label));
                return true;
            }

            String type = args[0].toLowerCase(Locale.ROOT);
            if (type.equals("sun") || type.equals("clear")) {
                isStorm = false;
            } else if (type.equals("rain") || type.equals("storm") || type.equals("thunder")) {
                isStorm = true;
            } else {
                essentialsManager.sendMessage(sender, "weather.invalid-type", Map.of("%type%", args[0]));
                return true;
            }

            if (args.length > 1) {
                Integer dur = parseDuration(args[1]);
                if (dur != null) {
                    durationSeconds = dur;
                    if (args.length > 2) worldArg = args[2];
                } else {
                    worldArg = args[1];
                }
            }
        }

        setWeather(sender, isStorm, durationSeconds, worldArg);
        return true;
    }

    private void setWeather(CommandSender sender, boolean isStorm, int durationSeconds, String worldArg) {
        int durationTicks = durationSeconds * 20;
        boolean networkSync = "network".equalsIgnoreCase(worldArg) || "all".equalsIgnoreCase(worldArg);

        if (networkSync && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            essentialsManager.getCrossServerService().broadcastWeatherSync(isStorm, durationTicks);
        }

        plugin.getScheduler().runGlobal(() -> {
            List<World> targetWorlds = new ArrayList<>();
            if (worldArg == null || networkSync) {
                if (networkSync || !(sender instanceof Player player)) {
                    targetWorlds.addAll(Bukkit.getWorlds());
                } else {
                    targetWorlds.add(player.getWorld());
                }
            } else {
                World w = Bukkit.getWorld(worldArg);
                if (w != null) {
                    targetWorlds.add(w);
                } else {
                    essentialsManager.sendMessage(sender, "general.world-not-found", Map.of("%world%", worldArg));
                    return;
                }
            }

            for (World world : targetWorlds) {
                world.setStorm(isStorm);
                world.setWeatherDuration(durationTicks);
            }

            String targetDesc = networkSync ? "all worlds" : (targetWorlds.size() == 1 ? targetWorlds.get(0).getName() : "all worlds");
            essentialsManager.sendMessage(sender, isStorm ? "weather.storm" : "weather.sun",
                    Map.of("%world%", targetDesc, "%duration%", String.valueOf(durationSeconds)));
        });
    }

    private Integer parseDuration(String str) {
        try {
            int d = Integer.parseInt(str);
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase(Locale.ROOT);
        boolean direct = cmd.endsWith("sun") || cmd.endsWith("rain") || cmd.endsWith("storm");

        if (direct) {
            if (args.length == 1) {
                List<String> list = new ArrayList<>(DURATIONS);
                list.addAll(getWorldNames());
                return filterList(list, args[0]);
            } else if (args.length == 2) {
                return filterList(getWorldNames(), args[1]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterList(TYPES, args[0]);
        } else if (args.length == 2) {
            List<String> list = new ArrayList<>(DURATIONS);
            list.addAll(getWorldNames());
            return filterList(list, args[1]);
        } else if (args.length == 3) {
            return filterList(getWorldNames(), args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> getWorldNames() {
        List<String> list = new ArrayList<>();
        list.add("network");
        list.add("all");
        for (World w : Bukkit.getWorlds()) {
            list.add(w.getName());
        }
        return list;
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
