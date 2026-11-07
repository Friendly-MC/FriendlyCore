package dev.lyy.friendlycore.modules.tpa.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Command handler for toggling auto-accept of incoming teleport requests.
 */
public class TpAutoCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpAutoCommand(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        if (!player.hasPermission("donuttpa.auto")) {
            tpaManager.sendConfigMessage(player, "no-permission", Map.of());
            return true;
        }

        TpaProfile profile = tpaManager.getProfile(player);
        if (profile == null) return true;

        boolean newState = !profile.isTpAutoEnabled();
        profile.setTpAutoEnabled(newState);
        plugin.getDatabaseManager().saveTpaProfile(profile);

        String msgKey = newState ? "tpa.tp-auto-on" : "tpa.tp-auto-off";
        String barKey = newState ? "tpa.tp-auto-on-action-bar" : "tpa.tp-auto-off-action-bar";

        tpaManager.sendConfigMessage(player, msgKey, Map.of());
        tpaManager.sendConfigMessage(player, barKey, Map.of());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return List.of();
    }
}
