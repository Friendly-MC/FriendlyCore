package dev.lyy.friendlycore.modules.essentials.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.essentials.EssentialsManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.*;

/**
 * Command executor for restoring player health, hunger, saturation, and clearing status effects (/heal).
 * Supports local execution and cross-server dispatch.
 */
public class HealCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final EssentialsManager essentialsManager;

    public HealCommand(FriendlyCore plugin, EssentialsManager essentialsManager) {
        this.plugin = plugin;
        this.essentialsManager = essentialsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Messages.send(plugin, sender, "essentials", "usage", "%label%", String.valueOf(label));
                return true;
            }
            if (!player.hasPermission("friendlycore.heal")) {
                essentialsManager.sendMessage(player, "general.no-permission", Map.of());
                return true;
            }
            healPlayer(player, player);
            return true;
        }

        if (!sender.hasPermission("friendlycore.heal.others")) {
            essentialsManager.sendMessage(sender, "general.no-permission", Map.of());
            return true;
        }

        String targetName = args[0];
        Player localTarget = Bukkit.getPlayer(targetName);
        if (localTarget != null && localTarget.isOnline()) {
            healPlayer(sender, localTarget);
            return true;
        }

        String networkName = essentialsManager.getCrossServerService().findNetworkPlayer(targetName);
        if (networkName != null) {
            UUID targetUuid = plugin.getRedisService().getNetworkPlayerUuid(networkName);
            essentialsManager.getCrossServerService().dispatchHeal(targetUuid, networkName);
            essentialsManager.sendMessage(sender, "heal.healed-other-network", Map.of("%player%", networkName));
            return true;
        }

        essentialsManager.sendMessage(sender, "general.player-not-found", Map.of("%player%", targetName));
        return true;
    }

    private void healPlayer(CommandSender sender, Player target) {
        plugin.getScheduler().runEntity(target, () -> {
            target.setHealth(target.getMaxHealth());
            target.setFoodLevel(20);
            target.setSaturation(20.0f);
            target.setFireTicks(0);
            target.setRemainingAir(target.getMaximumAir());
            for (PotionEffect effect : target.getActivePotionEffects()) {
                target.removePotionEffect(effect.getType());
            }

            TextService.playSound(target, "entity.player.levelup", 1.0f, 1.5f);
            essentialsManager.sendMessage(target, "heal.healed-self", Map.of());

            if (!sender.equals(target)) {
                essentialsManager.sendMessage(sender, "heal.healed-other", Map.of("%player%", target.getName()));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("friendlycore.heal.others")) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            for (String name : plugin.getRedisService() != null ? plugin.getRedisService().getOnlinePlayerNames() : getLocalPlayerNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    result.add(name);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

    private Set<String> getLocalPlayerNames() {
        Set<String> set = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            set.add(p.getName());
        }
        return set;
    }
}
