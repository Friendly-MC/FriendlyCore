package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for changing game modes (/gamemode, /gm, /gmc, /gms, /gma, /gmsp).
 * Supports Folia entity scheduling and cross-server execution.
 */
public class GamemodeCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private static final List<String> MODES = List.of("creative", "survival", "adventure", "spectator", "toggle");

    public GamemodeCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase(Locale.ROOT);
        GameMode defaultMode = getDirectMode(cmd);

        // Handle shorthand aliases (/gmc, /gms, /gma, /gmsp)
        if (defaultMode != null) {
            if (args.length == 0) {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "essentials", "usage", "%label%", String.valueOf(label));
                    return true;
                }
                setSelfGamemode(player, defaultMode);
                return true;
            } else {
                if (!sender.hasPermission("friendlycore.gamemode.others")) {
                    essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                    return true;
                }
                setOtherGamemode(sender, args[0], defaultMode);
                return true;
            }
        }

        if (args.length == 0) {
            if (sender instanceof Player player) {
                GameMode next = getNextMode(player.getGameMode());
                setSelfGamemode(player, next);
            } else {
                Messages.send(plugin, sender, "essentials", "usage-player", "%label%", String.valueOf(label));
            }
            return true;
        }

        GameMode mode = parseGameMode(args[0]);
        if (mode == null && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("cycle") || args[0].equalsIgnoreCase("t"))) {
            if (args.length == 1) {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "essentials", "console-cannot-toggle-gamemode-for-itself");
                    return true;
                }
                setSelfGamemode(player, getNextMode(player.getGameMode()));
                return true;
            } else {
                if (!sender.hasPermission("friendlycore.gamemode.others")) {
                    essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null && target.isOnline()) {
                    setOtherGamemode(sender, target.getName(), getNextMode(target.getGameMode()));
                } else {
                    essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", args[1]));
                }
                return true;
            }
        }

        if (mode == null) {
            essentialsManager.sendMessage(sender, "gamemode.invalid-mode", Map.of("%mode%", args[0]));
            return true;
        }

        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage-2", "%label%", String.valueOf(label));
                return true;
            }
            setSelfGamemode(player, mode);
        } else {
            if (!sender.hasPermission("friendlycore.gamemode.others")) {
                essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                return true;
            }
            setOtherGamemode(sender, args[1], mode);
        }

        return true;
    }

    private void setSelfGamemode(Player player, GameMode mode) {
        String permSuffix = mode.name().toLowerCase(Locale.ROOT);
        if (!player.hasPermission("friendlycore.gamemode." + permSuffix) &&
            !player.hasPermission("friendlycore.gamemode.all") &&
            !player.hasPermission("friendlycore.gamemode")) {
            essentialsManager.sendMessage(player, "general.no-permission", Map.of());
            return;
        }

        plugin.getScheduler().runEntity(player, () -> {
            player.setGameMode(mode);
            essentialsManager.sendMessage(player, "gamemode.changed-self",
                    Map.of("%gamemode%", mode.name().toLowerCase(Locale.ROOT)));
            TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
        });
    }

    private void setOtherGamemode(CommandSender sender, String targetName, GameMode mode) {
        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            plugin.getScheduler().runEntity(localTarget, () -> {
                localTarget.setGameMode(mode);
                essentialsManager.sendMessage(localTarget, "gamemode.changed-self",
                        Map.of("%gamemode%", mode.name().toLowerCase(Locale.ROOT)));
                TextService.playSound(localTarget, "entity.experience_orb.pickup", 1.0f, 1.2f);

                essentialsManager.sendMessage(sender, "gamemode.changed-other",
                        Map.of("%player%", localTarget.getName(), "%gamemode%", mode.name().toLowerCase(Locale.ROOT)));
            });
            return;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.getCrossServerService().dispatchGamemode(targetUuid, networkName, mode);
            essentialsManager.sendMessage(sender, "gamemode.changed-other-network",
                    Map.of("%player%", networkName, "%gamemode%", mode.name().toLowerCase(Locale.ROOT)));
            return;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
    }

    public static GameMode getDirectMode(String label) {
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "gmc", "egmc" -> GameMode.CREATIVE;
            case "gms", "egms" -> GameMode.SURVIVAL;
            case "gma", "egma" -> GameMode.ADVENTURE;
            case "gmsp", "egmsp", "gmspec" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    public static GameMode parseGameMode(String input) {
        if (input == null) return null;
        String s = input.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "c", "1", "creative" -> GameMode.CREATIVE;
            case "s", "0", "survival" -> GameMode.SURVIVAL;
            case "a", "2", "adventure" -> GameMode.ADVENTURE;
            case "sp", "3", "spectator", "spec" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private GameMode getNextMode(GameMode current) {
        return switch (current) {
            case SURVIVAL -> GameMode.CREATIVE;
            case CREATIVE -> GameMode.ADVENTURE;
            case ADVENTURE -> GameMode.SPECTATOR;
            case SPECTATOR -> GameMode.SURVIVAL;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        GameMode direct = getDirectMode(label);
        if (direct != null) {
            if (args.length == 1 && sender.hasPermission("friendlycore.gamemode.others")) {
                return getPlayerSuggestions(args[0]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            for (String mode : MODES) {
                if (mode.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    list.add(mode);
                }
            }
            return list;
        } else if (args.length == 2 && sender.hasPermission("friendlycore.gamemode.others")) {
            return getPlayerSuggestions(args[1]);
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
}
