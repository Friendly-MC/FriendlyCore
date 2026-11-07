package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Command executor allowing players to toggle receiving incoming payments.
 */
public class PayToggleCommand implements CommandExecutor {

    private final FriendlyCore plugin;

    public PayToggleCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        plugin.getEconomyManager().getAccountAsync(player.getUniqueId(), player.getName()).thenAccept(account ->
                plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline() || account == null) return;
                    boolean current = account.isPayEnabled();
                    account.setPayEnabled(!current);

                    String path = current ? "economy.pay-toggle-disabled" : "economy.pay-toggle-enabled";
                    String fallback = current ? "&#63ed2dYou disabled the payments."
                            : "&#63ed2dYou activated the payments.";
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString(path, fallback), null);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                }));

        return true;
    }
}
