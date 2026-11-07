package dev.lyy.friendlycore.modules.tpa.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command handler for declining incoming TPA requests.
 */
public class TpDenyCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final TpaManager tpaManager;

    public TpDenyCommand(FriendlyCore plugin, TpaManager tpaManager) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        if (!player.hasPermission("donuttpa.deny") && !player.hasPermission("friendlycore.tpa.deny")
                && !player.hasPermission("friendlycore.tpa") && !player.hasPermission("friendlycore.tpa.use")) {
            tpaManager.sendConfigMessage(player, "no-permission", Map.of());
            return true;
        }

        String targetName = args.length > 0 ? args[0] : null;
        tpaManager.denyRequest(player, targetName);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (sender instanceof Player player && args.length == 1) {
            TpaProfile profile = tpaManager.getProfile(player);
            if (profile == null) return List.of();

            String search = args[0].toLowerCase(Locale.ROOT);
            List<String> list = new ArrayList<>();
            for (TpaRequest req : profile.getIncomingRequests().values()) {
                if (req.getStatus() == TpaRequest.Status.PENDING && req.getSenderName().toLowerCase(Locale.ROOT).startsWith(search)) {
                    list.add(req.getSenderName());
                }
            }
            return list;
        }
        return List.of();
    }
}
