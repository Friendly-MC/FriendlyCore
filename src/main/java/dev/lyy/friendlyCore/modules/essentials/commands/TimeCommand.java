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
 * Command executor for world time adjustment (/time, /day, /night).
 * Supports per-world adjustment, global network synchronization, and Folia global scheduling.
 */
public class TimeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private static final List<String> SUBCOMMANDS = List.of("set", "add");
    private static final List<String> PRESETS = List.of("day", "night", "noon", "midnight", "morning", "sunset", "sunrise", "0", "1000", "6000", "13000", "18000");

    public TimeCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("friendlycore.time")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String cmd = label.toLowerCase(Locale.ROOT);

        if (cmd.endsWith("day")) {
            setTime(sender, 1000L, false, args.length > 0 ? args[0] : null, "day");
            return true;
        } else if (cmd.endsWith("night")) {
            setTime(sender, 13000L, false, args.length > 0 ? args[0] : null, "night");
            return true;
        }

        // Handle general time modification (/time <set|add> <time> [world])
        if (args.length == 0) {
            World world = getWorld(sender, null);
            if (world != null) {
                long time = world.getTime();
                essentialsManager.sendMessage(sender, "time.current",
                        Map.of("%world%", world.getName(), "%time%", String.valueOf(time)));
            }
            return true;
        }

        boolean isAdd = false;
        String timeStr;
        String worldName = null;

        if (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add")) {
            isAdd = args[0].equalsIgnoreCase("add");
            if (args.length < 2) {
                essentialsManager.sendMessage(sender, "time.usage", Map.of("%label%", label));
                return true;
            }
            timeStr = args[1];
            if (args.length > 2) worldName = args[2];
        } else {
            timeStr = args[0];
            if (args.length > 1) worldName = args[1];
        }

        Long ticks = parseTimeTicks(timeStr);
        if (ticks == null) {
            essentialsManager.sendMessage(sender, "time.invalid-time", Map.of("%time%", timeStr));
            return true;
        }

        setTime(sender, ticks, isAdd, worldName, timeStr);
        return true;
    }

    private void setTime(CommandSender sender, long ticks, boolean isAdd, String worldArg, String displayTime) {
        boolean networkSync = "network".equalsIgnoreCase(worldArg) || "all".equalsIgnoreCase(worldArg);

        if (networkSync && plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            essentialsManager.getCrossServerService().broadcastTimeSync(ticks, isAdd);
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
                long current = world.getTime();
                if (!isAdd) {
                    current -= current % 24000L;
                }
                world.setTime(current + (isAdd ? 0L : 24000L) + ticks);
            }

            String targetDesc = networkSync ? "all worlds" : (targetWorlds.size() == 1 ? targetWorlds.get(0).getName() : "all worlds");
            essentialsManager.sendMessage(sender, isAdd ? "time.added" : "time.set",
                    Map.of("%time%", displayTime, "%world%", targetDesc));
        });
    }

    private World getWorld(CommandSender sender, String worldArg) {
        if (worldArg != null) {
            return Bukkit.getWorld(worldArg);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    public static Long parseTimeTicks(String str) {
        if (str == null) return null;
        String s = str.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "day" -> 1000L;
            case "morning" -> 2000L;
            case "noon" -> 6000L;
            case "afternoon" -> 9000L;
            case "sunset" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            case "sunrise" -> 23000L;
            default -> {
                try {
                    if (s.endsWith("t") || s.endsWith("d")) {
                        s = s.substring(0, s.length() - 1);
                    }
                    yield Long.parseLong(s);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase(Locale.ROOT);
        if (cmd.endsWith("day") || cmd.endsWith("night")) {
            if (args.length == 1) {
                return getWorldSuggestions(args[0]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> list = new ArrayList<>(SUBCOMMANDS);
            list.addAll(PRESETS);
            return filterList(list, args[0]);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add")) {
                return filterList(PRESETS, args[1]);
            } else {
                return getWorldSuggestions(args[1]);
            }
        } else if (args.length == 3) {
            return getWorldSuggestions(args[2]);
        }

        return Collections.emptyList();
    }

    private List<String> getWorldSuggestions(String prefix) {
        List<String> worlds = new ArrayList<>();
        worlds.add("network");
        worlds.add("all");
        for (World w : Bukkit.getWorlds()) {
            worlds.add(w.getName());
        }
        return filterList(worlds, prefix);
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
