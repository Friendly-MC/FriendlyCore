package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import dev.lyy.friendlycore.modules.essentials.util.SpeedUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Command executor for player flight and walk speed adjustments (/speed, /flyspeed, /walkspeed).
 * Supports EssentialsX-style scaling (0-10), automatic mode detection, and cross-server Redis dispatch.
 */
public class SpeedCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    private static final List<String> SPEED_TYPES = List.of("fly", "walk", "1", "2", "3", "5", "10", "reset");
    private static final List<String> SPEEDS = List.of("1", "1.5", "2", "3", "5", "10", "reset");

    public SpeedCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = label.toLowerCase(Locale.ROOT);
        Boolean explicitFly = isFlyAlias(cmd) ? Boolean.TRUE : isWalkAlias(cmd) ? Boolean.FALSE : null;

        if (args.length == 0) {
            essentialsManager.sendMessage(sender, "speed.usage", Map.of("%label%", label));
            return true;
        }

        boolean isFly;
        float userSpeed;
        String targetPlayerName = null;

        if (explicitFly != null) {
            // /flyspeed <speed> [player] or /walkspeed <speed> [player]
            isFly = explicitFly;
            userSpeed = parseSpeed(args[0]);
            if (userSpeed < 0) {
                essentialsManager.sendMessage(sender, "speed.invalid-speed", Map.of("%speed%", args[0]));
                return true;
            }
            if (args.length > 1) {
                targetPlayerName = args[1];
            }
        } else {
            // /speed [fly|walk] <speed> [player] OR /speed <speed> [player]
            if (isFlyMode(args[0]) != null) {
                isFly = Boolean.TRUE.equals(isFlyMode(args[0]));
                if (args.length < 2) {
                    essentialsManager.sendMessage(sender, "speed.usage", Map.of("%label%", label));
                    return true;
                }
                userSpeed = parseSpeed(args[1]);
                if (userSpeed < 0) {
                    essentialsManager.sendMessage(sender, "speed.invalid-speed", Map.of("%speed%", args[1]));
                    return true;
                }
                if (args.length > 2) {
                    targetPlayerName = args[2];
                }
            } else {
                userSpeed = parseSpeed(args[0]);
                if (userSpeed < 0) {
                    essentialsManager.sendMessage(sender, "speed.invalid-speed", Map.of("%speed%", args[0]));
                    return true;
                }
                if (args.length > 1) {
                    targetPlayerName = args[1];
                    isFly = false; // Default for other player if unspecified
                } else {
                    if (sender instanceof Player player) {
                        isFly = player.isFlying();
                    } else {
                        isFly = false;
                    }
                }
            }
        }

        if (targetPlayerName == null) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "console-must-specify-a-player-name", "%label%", String.valueOf(label));
                return true;
            }
            applySelfSpeed(player, isFly, userSpeed);
        } else {
            if (!sender.hasPermission("friendlycore.speed.others")) {
                essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
                return true;
            }
            applyOtherSpeed(sender, targetPlayerName, isFly, userSpeed);
        }

        return true;
    }

    private void applySelfSpeed(Player player, boolean isFly, float userSpeed) {
        String perm = isFly ? "friendlycore.speed.fly" : "friendlycore.speed.walk";
        if (!player.hasPermission(perm) && !player.hasPermission("friendlycore.speed")) {
            essentialsManager.sendMessage(player, "general.no-permission", Map.of());
            return;
        }

        boolean bypass = player.hasPermission("friendlycore.speed.bypass");
        float maxSpeed = bypass ? 1.0f : (isFly ? essentialsManager.getMaxFlySpeed() : essentialsManager.getMaxWalkSpeed());
        float bukkitSpeed = SpeedUtil.calculateBukkitSpeed(userSpeed, isFly, maxSpeed);

        plugin.getScheduler().runEntity(player, () -> {
            if (isFly) {
                player.setFlySpeed(bukkitSpeed);
                essentialsManager.sendMessage(player, "speed.fly-changed-self",
                        Map.of("%speed%", String.valueOf(userSpeed)));
            } else {
                player.setWalkSpeed(bukkitSpeed);
                essentialsManager.sendMessage(player, "speed.walk-changed-self",
                        Map.of("%speed%", String.valueOf(userSpeed)));
            }
        });
    }

    private void applyOtherSpeed(CommandSender sender, String targetName, boolean isFly, float userSpeed) {
        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            boolean bypass = localTarget.hasPermission("friendlycore.speed.bypass");
            float maxSpeed = bypass ? 1.0f : (isFly ? essentialsManager.getMaxFlySpeed() : essentialsManager.getMaxWalkSpeed());
            float bukkitSpeed = SpeedUtil.calculateBukkitSpeed(userSpeed, isFly, maxSpeed);

            plugin.getScheduler().runEntity(localTarget, () -> {
                if (isFly) {
                    localTarget.setFlySpeed(bukkitSpeed);
                    essentialsManager.sendMessage(localTarget, "speed.fly-changed-self",
                            Map.of("%speed%", String.valueOf(userSpeed)));
                } else {
                    localTarget.setWalkSpeed(bukkitSpeed);
                    essentialsManager.sendMessage(localTarget, "speed.walk-changed-self",
                            Map.of("%speed%", String.valueOf(userSpeed)));
                }

                essentialsManager.sendMessage(sender, "speed.changed-other",
                        Map.of("%player%", localTarget.getName(),
                                "%type%", isFly ? "flying" : "walking",
                                "%speed%", String.valueOf(userSpeed)));
            });
            return;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.getCrossServerService().dispatchSpeed(targetUuid, networkName, isFly, userSpeed);
            essentialsManager.sendMessage(sender, "speed.changed-other-network",
                    Map.of("%player%", networkName,
                            "%type%", isFly ? "flying" : "walking",
                            "%speed%", String.valueOf(userSpeed)));
            return;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
    }

    private float parseSpeed(String input) {
        if (input.equalsIgnoreCase("reset") || input.equalsIgnoreCase("default") || input.equalsIgnoreCase("0")) {
            return 1.0f; // 1 is default Essentials speed
        }
        try {
            float val = Float.parseFloat(input);
            if (val < 0.0001f) val = 0.0001f;
            if (val > 10.0f) val = 10.0f;
            return val;
        } catch (NumberFormatException e) {
            return -1.0f;
        }
    }

    private boolean isFlyAlias(String label) {
        return label.contains("fly") || label.equalsIgnoreCase("fspeed") || label.equalsIgnoreCase("efspeed");
    }

    private boolean isWalkAlias(String label) {
        return label.contains("walk") || label.equalsIgnoreCase("wspeed") || label.equalsIgnoreCase("ewspeed");
    }

    private Boolean isFlyMode(String mode) {
        if (mode.equalsIgnoreCase("fly") || mode.equalsIgnoreCase("flying") || mode.equalsIgnoreCase("f")) {
            return true;
        }
        if (mode.equalsIgnoreCase("walk") || mode.equalsIgnoreCase("walking") || mode.equalsIgnoreCase("w") || mode.equalsIgnoreCase("run")) {
            return false;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        boolean direct = isFlyAlias(label) || isWalkAlias(label);
        if (direct) {
            if (args.length == 1) {
                return SPEEDS;
            } else if (args.length == 2 && sender.hasPermission("friendlycore.speed.others")) {
                return getPlayerSuggestions(args[1]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return SPEED_TYPES;
        } else if (args.length == 2) {
            if (isFlyMode(args[0]) != null) {
                return SPEEDS;
            } else if (sender.hasPermission("friendlycore.speed.others")) {
                return getPlayerSuggestions(args[1]);
            }
        } else if (args.length == 3 && sender.hasPermission("friendlycore.speed.others")) {
            return getPlayerSuggestions(args[2]);
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
