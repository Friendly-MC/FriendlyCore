package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Command executor for reloading economy configuration and cache states.
 */
public class PayReloadCommand implements CommandExecutor {

    private final FriendlyCore plugin;

    public PayReloadCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("swiftpay.admin") && !sender.hasPermission("friendlycore.economy.admin") && !sender.hasPermission("friendlycore.admin")) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("error-no-permission",
                            plugin.getConfigService().getMessages().getString("no-permission", "<red>No permission.")),
                    null);
            return true;
        }

        long start = System.currentTimeMillis();
        java.util.List<String> problems = plugin.reloadModule("economy");
        plugin.sendReloadResult(sender, "economy", problems, System.currentTimeMillis() - start);
        return true;
    }
}
