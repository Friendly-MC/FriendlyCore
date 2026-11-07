package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Command executor for player and coordinate teleportation (/tp, /tppos).
 * Supports direct coordinates, relative offsets, player-to-player, and cross-server transfers.
 */
public class TeleportCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public TeleportCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("friendlycore.teleport")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        switch (args.length) {
            case 0 -> {
                essentialsManager.sendMessage(sender, "teleport.usage", Map.of("%label%", label));
                return true;
            }
            case 1 -> {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "essentials", "usage-3", "%label%", String.valueOf(label));
                    return true;
                }
                teleportPlayerToTarget(player, args[0]);
                return true;
            }
            case 2 -> {
                if (!sender.hasPermission("friendlycore.teleport.others")) {
                    essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                    return true;
                }
                teleportOtherToTarget(sender, args[0], args[1]);
                return true;
            }
            case 3 -> {
                if (!(sender instanceof Player player)) {
                    Messages.send(plugin, sender, "essentials", "usage-world", "%label%", String.valueOf(label));
                    return true;
                }
                if (!player.hasPermission("friendlycore.teleport.position") && !player.hasPermission("friendlycore.teleport")) {
                    essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                    return true;
                }
                teleportToCoords(player, player, args[0], args[1], args[2], null);
                return true;
            }
            case 4 -> {
                // Could be /tp <player> <x> <y> <z> OR /tp <x> <y> <z> <world>
                if (isCoordinate(args[0])) {
                    if (!(sender instanceof Player player)) {
                        Messages.send(plugin, sender, "essentials", "usage-world", "%label%", String.valueOf(label));
                        return true;
                    }
                    teleportToCoords(player, player, args[0], args[1], args[2], args[3]);
                } else {
                    if (!sender.hasPermission("friendlycore.teleport.others")) {
                        essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[0]);
                    if (target == null || !target.isOnline()) {
                        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", args[0]));
                        return true;
                    }
                    teleportToCoords(sender, target, args[1], args[2], args[3], null);
                }
                return true;
            }
            case 5 -> {
                if (!sender.hasPermission("friendlycore.teleport.others")) {
                    essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null || !target.isOnline()) {
                    essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", args[0]));
                    return true;
                }
                teleportToCoords(sender, target, args[1], args[2], args[3], args[4]);
                return true;
            }
            default -> {
                essentialsManager.sendMessage(sender, "teleport.usage", Map.of("%label%", label));
                return true;
            }
        }
    }

    private void teleportPlayerToTarget(Player player, String targetName) {
        if (targetName.equalsIgnoreCase(player.getName())) {
            essentialsManager.sendMessage(player, "teleport.self-teleport", Map.of());
            return;
        }

        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            plugin.getScheduler().runEntity(localTarget, () -> {
                if (!localTarget.isOnline() || !player.isOnline()) {
                    essentialsManager.sendMessage(player, "general.player-not-found", Map.of("%player%", targetName));
                    return;
                }
                Location dest = localTarget.getLocation().clone();
                dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
                        essentialsManager.sendMessage(player, "teleport.teleported-to-player",
                                Map.of("%player%", localTarget.getName()));
                    }
                });
            });
            return;
        }

        // Request cross-server teleport ticket for remote player
        String matchedName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (matchedName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(matchedName);
            essentialsManager.sendMessage(player, "teleport.locating-remote", Map.of("%player%", matchedName));
            essentialsManager.getCrossServerService().dispatchTeleportToPlayer(player, matchedName, targetUuid);
            return;
        }

        essentialsManager.sendMessage(player, "general.player-not-found", Map.of("%player%", targetName));
    }

    private void teleportOtherToTarget(CommandSender sender, String playerName, String destName) {
        Player playerToMove = Bukkit.getPlayer(playerName);
        Player destPlayer = Bukkit.getPlayer(destName);

        if (playerToMove != null && playerToMove.isOnline() && destPlayer != null && destPlayer.isOnline()) {
            plugin.getScheduler().runEntity(destPlayer, () -> {
                if (!destPlayer.isOnline() || !playerToMove.isOnline()) {
                    essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", !destPlayer.isOnline() ? destName : playerName));
                    return;
                }
                Location dest = destPlayer.getLocation().clone();
                dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(playerToMove, dest).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        TextService.playSound(playerToMove, "entity.experience_orb.pickup", 1.0f, 1.2f);
                        essentialsManager.sendMessage(playerToMove, "teleport.teleported-to-player",
                                Map.of("%player%", destPlayer.getName()));
                        essentialsManager.sendMessage(sender, "teleport.teleported-other-to-player",
                                Map.of("%player%", playerToMove.getName(), "%target%", destPlayer.getName()));
                    }
                });
            });
            return;
        }

        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            if (playerToMove != null && playerToMove.isOnline()) {
                String matchDest = essentialsManager.getCrossServerService().findNetworkPlayer(destName);
                if (matchDest != null) {
                    UUID destUuid = plugin.getRedisService().getNetworkPlayerUuid(matchDest);
                    essentialsManager.sendMessage(sender, "teleport.locating-remote", Map.of("%player%", matchDest));
                    essentialsManager.getCrossServerService().dispatchTeleportToPlayer(playerToMove, matchDest, destUuid);
                    return;
                }
            }
            if (destPlayer != null && destPlayer.isOnline()) {
                String matchMove = essentialsManager.getCrossServerService().findNetworkPlayer(playerName);
                if (matchMove != null) {
                    UUID moveUuid = plugin.getRedisService().getNetworkPlayerUuid(matchMove);
                    essentialsManager.sendMessage(sender, "teleport.locating-remote", Map.of("%player%", matchMove));
                    essentialsManager.getCrossServerService().dispatchTeleportPlayerHere(destPlayer, matchMove, moveUuid);
                    return;
                }
            }
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", playerToMove == null ? playerName : destName));
    }

    private void teleportToCoords(CommandSender sender, Player target, String xStr, String yStr, String zStr, String worldArg) {
        World world = target.getWorld();
        if (worldArg != null) {
            world = Bukkit.getWorld(worldArg);
            if (world == null) {
                essentialsManager.sendMessage(sender, "general.world-not-found", Map.of("%world%", worldArg));
                return;
            }
        }

        try {
            double x = parseCoord(xStr, target.getLocation().getX());
            double y = parseCoord(yStr, target.getLocation().getY());
            double z = parseCoord(zStr, target.getLocation().getZ());

            double maxCoord = essentialsManager.getConfig().getDouble("teleport.max-coord", 30000000);
            if (Math.abs(x) > maxCoord || Math.abs(y) > maxCoord || Math.abs(z) > maxCoord) {
                essentialsManager.sendMessage(sender, "teleport.invalid-coords", Map.of());
                return;
            }

            Location dest = new Location(world, x, y, z, target.getLocation().getYaw(), target.getLocation().getPitch());
            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(target, dest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    TextService.playSound(target, "entity.experience_orb.pickup", 1.0f, 1.2f);
                    essentialsManager.sendMessage(target, "teleport.teleported-to-coords",
                            Map.of("%world%", dest.getWorld().getName(),
                                    "%x%", String.format(Locale.ROOT, "%.1f", x),
                                    "%y%", String.format(Locale.ROOT, "%.1f", y),
                                    "%z%", String.format(Locale.ROOT, "%.1f", z)));
                    if (!sender.equals(target)) {
                        essentialsManager.sendMessage(sender, "teleport.teleported-other-to-coords",
                                Map.of("%player%", target.getName(),
                                        "%world%", dest.getWorld().getName(),
                                        "%x%", String.format(Locale.ROOT, "%.1f", x),
                                        "%y%", String.format(Locale.ROOT, "%.1f", y),
                                        "%z%", String.format(Locale.ROOT, "%.1f", z)));
                    }
                }
            });
        } catch (NumberFormatException e) {
            essentialsManager.sendMessage(sender, "teleport.invalid-coords", Map.of());
        }
    }

    private double parseCoord(String arg, double current) throws NumberFormatException {
        if (arg.startsWith("~")) {
            if (arg.length() == 1) return current;
            return current + Double.parseDouble(arg.substring(1));
        }
        return Double.parseDouble(arg);
    }

    private boolean isCoordinate(String arg) {
        if (arg.startsWith("~")) return true;
        try {
            Double.parseDouble(arg);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        } else if (args.length == 2 && !isCoordinate(args[0])) {
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
