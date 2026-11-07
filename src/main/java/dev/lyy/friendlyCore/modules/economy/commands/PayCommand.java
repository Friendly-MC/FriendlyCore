package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.NumberFormatter;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.economy.AccountData;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Command executor for player-to-player monetary transfers.
 */
public class PayCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public PayCommand(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getTextService().sendMessage(sender, plugin.getConfigService().getMessages().getString("player-only", "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>This command can only be executed by players.</gray>"), null);
            return true;
        }

        if (args.length < 2) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("economy.usage-pay",
                            plugin.getConfigService().getMessages().getString("usage-pay", "<gradient:#ff5252:#ff1744><bold>USAGE</bold></gradient> <dark_gray>»</dark_gray> <gray>Use <#00d2ff>/pay <player> <amount></#00d2ff></gray>")),
                    null);
            return true;
        }

        if (plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            plugin.getTextService().sendMessage(player, "<gradient:#ffa726:#fb8c00><bold>SYNC</bold></gradient> <dark_gray>»</dark_gray> <gray>Your data is currently synchronizing across the network. Please wait a moment.</gray>", null);
            return true;
        }

        boolean hasCooldownBypass = player.hasPermission("friendlycore.bypass.cooldown") || player.hasPermission("swiftpay.bypass.cooldown");

        int cdSeconds = plugin.getConfigService().getEconomyConfig().getInt("payment.cooldown-seconds", 1);
        if (!hasCooldownBypass && plugin.getCooldownManager().isOnCooldown("pay", player.getUniqueId(), cdSeconds * 1000L)) {
            long remaining = plugin.getCooldownManager().getRemainingSeconds("pay", player.getUniqueId(), cdSeconds * 1000L);
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("economy.cooldown", "<red>Wait %seconds%s before paying again."),
                    Map.of("%seconds%", String.valueOf(remaining)));
            return true;
        }

        String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("economy.cant-pay-yourself", "<red>You cannot pay yourself!"),
                    null);
            return true;
        }

        double amount;
        try {
            amount = NumberFormatter.parseAmount(args[1]);
        } catch (NumberFormatException e) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("invalid-number", "<red>Invalid number amount: %amount%"),
                    Map.of("%amount%", args[1]));
            return true;
        }

        double minAmount = plugin.getConfigService().getEconomyConfig().getDouble("payment.minimum-amount", 1.0);
        if (amount < minAmount) {
            String formattedMin = plugin.getNumberFormatter().format(minAmount);
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("economy.minimum-pay-amount", "<red>Minimum payment amount is $%amount%."),
                    Map.of("%min%", formattedMin, "%amount%", formattedMin));
            return true;
        }

        AccountData senderAccount = plugin.getEconomyManager().getCachedAccount(player.getUniqueId());
        if (senderAccount == null) {
            plugin.getTextService().sendMessage(player,
                    "<gradient:#ffa726:#fb8c00><bold>LOADING</bold></gradient> <dark_gray>»</dark_gray> <gray>Your economy data is still loading. Please try again in a moment.</gray>", null);
            return true;
        }
        double currentBal = senderAccount.getBalance();
        if (currentBal < amount) {
            plugin.getTextService().sendMessage(player,
                    plugin.getConfigService().getMessages().getString("economy.insufficient-funds", "<red>You do not have enough money! Current: $%balance%"),
                    Map.of("%balance%", plugin.getNumberFormatter().format(currentBal)));
            return true;
        }

        // Apply cooldown early to mitigate rapid command spamming
        if (!hasCooldownBypass) {
            plugin.getCooldownManager().setCooldown("pay", player.getUniqueId());
        }

        // Resolve target account asynchronously before executing transfer
        plugin.getEconomyManager().getUuidByNameAsync(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return plugin.getEconomyManager().getAccountAsync(targetUuid, targetName);
        }).thenAccept(targetAcc -> {
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;

                if (targetAcc == null) {
                    plugin.getCooldownManager().resetCooldown("pay", player.getUniqueId());
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player %player% not found."),
                            Map.of("%player%", targetName));
                    return;
                }

                UUID targetUuid = targetAcc.getUuid();
                Player targetOnline = Bukkit.getPlayer(targetUuid);
                if (targetOnline != null && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(targetOnline)) {
                    plugin.getCooldownManager().resetCooldown("pay", player.getUniqueId());
                    plugin.getTextService().sendMessage(player, "<gradient:#ffa726:#fb8c00><bold>SYNC</bold></gradient> <dark_gray>»</dark_gray> <gray>Target player is currently synchronizing across the network. Please wait a moment.</gray>", null);
                    return;
                }

                boolean canBypassToggle = player.hasPermission("friendlycore.economy.paytoggle.bypass") || player.hasPermission("friendlycore.admin") || player.hasPermission("swiftpay.admin");
                if (!targetAcc.isPayEnabled() && !canBypassToggle) {
                    plugin.getCooldownManager().resetCooldown("pay", player.getUniqueId());
                    plugin.getTextService().sendMessage(player,
                            plugin.getConfigService().getMessages().getString("economy.player-disabled-pay", "<red>%player% has disabled payments."),
                            Map.of("%player%", targetName));
                    return;
                }

                plugin.getEconomyManager().transfer(player.getUniqueId(), player.getName(), targetUuid, targetName, amount).thenAccept(success -> {
                if (success) {
                    String formattedAmount = plugin.getNumberFormatter().format(amount);
                    boolean sendActionbar = plugin.getConfigService().getEconomyConfig().getBoolean("payment.actionbar.enabled", true);

                    plugin.getScheduler().runEntity(player, () -> {
                        plugin.getTextService().sendMessage(player,
                                plugin.getConfigService().getMessages().getString("economy.payment-sent", "<green>Sent $%amount% to %target%."),
                                Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));

                        if (sendActionbar) {
                            String sentBar = plugin.getConfigService().getMessages().getString("economy.actionbar-sent",
                                    "&#63ed2dYou paid %target% $%amount%!");
                            plugin.getTextService().sendActionBar(player, sentBar,
                                    Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));
                        }

                        String sendSoundStr = plugin.getConfigService().getEconomyConfig().getString("payment.sound.send", "ENTITY_PLAYER_LEVELUP");
                        TextService.playSound(player, sendSoundStr, 1.0f, 1.0f);
                    });

                    // Notify receiver if currently connected to this server instance
                    String senderDisplayName = player.getName();
                    plugin.getScheduler().runGlobal(() -> {
                        Player onlineReceiver = Bukkit.getPlayer(targetUuid);
                        if (onlineReceiver != null) {
                            plugin.getScheduler().runEntity(onlineReceiver, () -> {
                                if (!onlineReceiver.isOnline()) return;

                                boolean alertsEnabled = plugin.getSettingsManager() == null ||
                                        plugin.getSettingsManager().getSetting(onlineReceiver.getUniqueId(), dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS);

                                if (alertsEnabled) {
                                    plugin.getTextService().sendMessage(onlineReceiver,
                                            plugin.getConfigService().getMessages().getString("economy.payment-received", "&#63ed2dYou received $%amount% from %sender%!"),
                                            Map.of("%amount%", formattedAmount, "%sender%", senderDisplayName, "%player%", senderDisplayName));

                                    if (sendActionbar) {
                                        String recvBar = plugin.getConfigService().getMessages().getString("economy.actionbar-received",
                                                "&#63ed2dYou received $%amount% from %sender%!");
                                        plugin.getTextService().sendActionBar(onlineReceiver, recvBar,
                                                Map.of("%amount%", formattedAmount, "%sender%", senderDisplayName, "%player%", senderDisplayName));
                                    }

                                    String recvSoundStr = plugin.getConfigService().getEconomyConfig().getString("payment.sound.receive", "ENTITY_PLAYER_LEVELUP");
                                    TextService.playSound(onlineReceiver, recvSoundStr, 1.0f, 1.0f);
                                }
                            });
                        }
                    });
                } else {
                    plugin.getScheduler().runEntity(player, () -> {
                        plugin.getTextService().sendMessage(player,
                                plugin.getConfigService().getMessages().getString("economy.insufficient-funds", "<red>Transaction failed."),
                                Map.of("%balance%", plugin.getNumberFormatter().format(plugin.getEconomyManager().getBalance(player.getUniqueId()))));
                    });
                }
            });
            });
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String search = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            for (String name : plugin.getRedisService().getOnlinePlayerNames()) {
                if (!name.equalsIgnoreCase(sender.getName()) && name.toLowerCase().startsWith(search)) {
                    list.add(name);
                }
            }
            return list;
        } else if (args.length == 2) {
            return List.of("10", "100", "1k", "10k", "1m");
        }
        return List.of();
    }
}
