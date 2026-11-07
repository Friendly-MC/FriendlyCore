package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;

/**
 * Command executor for line-of-sight and vertical teleportation utilities (/top, /bottom, /jump, /suicide).
 */
public class JumpTopBottomCommand implements CommandExecutor {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public JumpTopBottomCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(plugin, sender, "essentials", "this-command-can-only-be-executed");
            return true;
        }

        String cmd = label.toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "top" -> {
                if (!player.hasPermission("friendlycore.top")) {
                    essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                    return true;
                }
                teleportTop(player);
            }
            case "bottom" -> {
                if (!player.hasPermission("friendlycore.bottom")) {
                    essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                    return true;
                }
                teleportBottom(player);
            }
            case "jump", "j" -> {
                if (!player.hasPermission("friendlycore.jump")) {
                    essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                    return true;
                }
                teleportJump(player);
            }
            case "suicide" -> {
                if (!player.hasPermission("friendlycore.suicide")) {
                    essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                    return true;
                }
                plugin.getScheduler().runEntity(player, () -> {
                    player.setHealth(0.0);
                    essentialsManager.sendMessage(player, "suicide.died", Map.of());
                });
            }
            default -> essentialsManager.sendMessage(player, "general.no-permission", Map.of());
        }

        return true;
    }

    private void teleportTop(Player player) {
        plugin.getScheduler().runEntity(player, () -> {
            Location loc = player.getLocation();
            Location dest = dev.lyy.friendlycore.common.SafeTeleportUtil.getHighestSafeLocation(loc);
            if (dest == null) {
                essentialsManager.sendMessage(player, "teleport.no-safe-location", Map.of());
                return;
            }

            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
                    essentialsManager.sendMessage(player, "teleport.teleported-top", Map.of());
                }
            });
        });
    }

    private void teleportBottom(Player player) {
        plugin.getScheduler().runEntity(player, () -> {
            Location loc = player.getLocation();
            Location dest = dev.lyy.friendlycore.common.SafeTeleportUtil.getLowestSafeLocation(loc);
            if (dest == null) {
                essentialsManager.sendMessage(player, "teleport.no-safe-location", Map.of());
                return;
            }

            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest).thenAccept(success -> {
                if (Boolean.TRUE.equals(success)) {
                    TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
                    essentialsManager.sendMessage(player, "teleport.teleported-bottom", Map.of());
                }
            });
        });
    }

    private void teleportJump(Player player) {
        plugin.getScheduler().runEntity(player, () -> {
            Block targetBlock = player.getTargetBlockExact(60);
            if (targetBlock == null || targetBlock.getType().isAir()) {
                essentialsManager.sendMessage(player, "teleport.no-target-block", Map.of());
                return;
            }

            Location rawDest = targetBlock.getLocation().add(0.5, 1.0, 0.5);
            rawDest.setYaw(player.getLocation().getYaw());
            rawDest.setPitch(player.getLocation().getPitch());

            plugin.getScheduler().runLocation(rawDest, () -> {
                Location dest = dev.lyy.friendlycore.common.SafeTeleportUtil.getSafeDestination(rawDest);
                if (dest == null) {
                    dest = rawDest;
                }

                dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, dest).thenAccept(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        plugin.getScheduler().runEntity(player, () -> {
                            TextService.playSound(player, "entity.experience_orb.pickup", 1.0f, 1.2f);
                            essentialsManager.sendMessage(player, "teleport.teleported-jump", Map.of());
                        });
                    }
                });
            });
        });
    }
}
