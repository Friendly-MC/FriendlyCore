package dev.lyy.friendlycore.modules.economy.commands;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.NumberFormatter;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.Transaction;
import org.bukkit.Bukkit;
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

/**
 * Staff and admin command executor for economy management, balance modification, and data migration.
 */
public class EconomyCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;

    public EconomyCommand(FriendlyCore plugin) {
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

        if (args.length < 1) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("resetall")) {
            if (args.length < 2 || !args[1].equalsIgnoreCase("CONFIRM")) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("economy.admin-reset-confirm",
                                "&#ff3845Type CONFIRM to delete all user data."), null);
                return true;
            }
            plugin.getDatabaseManager().resetEconomyData().thenRun(() -> runForSender(sender, () -> {
                plugin.getEconomyManager().clearLocalCache();
                if (plugin.getRedisService().isEnabled()) {
                    plugin.getRedisService().broadcastGlobalReset();
                }
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("economy.admin-resetall",
                                "&#ff3845All player economy data has been reset."), null);
            }));
            return true;
        }

        if (sub.equals("migrate")) {
            java.io.File customPath = (args.length >= 2) ? new java.io.File(args[1]) : null;
            plugin.getEconomyManager().getEssentialsMigrator().migrate(sender, customPath);
            return true;
        }

        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        String targetName = args[1];

        if (sub.equals("see")) {
            plugin.getEconomyManager().getUuidByNameAsync(targetName)
                    .thenCompose(uuid -> uuid == null
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : plugin.getEconomyManager().getAccountAsync(uuid, targetName))
                    .thenAccept(account -> runForSender(sender, () -> {
                if (account == null) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                            Map.of("%player%", targetName));
                    return;
                }
                String formatted = plugin.getNumberFormatter().format(account.getBalance());
                plugin.getTextService().sendMessage(sender, "<gradient:#00d2ff:#3a7bd5><bold>BALANCE</bold></gradient> <dark_gray>»</dark_gray> <gray><white>%player%</white>'s balance: <#00e676>$%balance%</#00e676></gray>",
                        Map.of("%player%", targetName, "%balance%", formatted));
            }));
            return true;
        }

        if (sub.equals("reset")) {
            plugin.getEconomyManager().getUuidByNameAsync(targetName)
                    .thenCompose(uuid -> uuid == null
                            ? java.util.concurrent.CompletableFuture.completedFuture(null)
                            : plugin.getEconomyManager().getAccountAsync(uuid, targetName))
                    .thenAccept(account -> runForSender(sender, () -> {
                if (account == null) {
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                            Map.of("%player%", targetName));
                    return;
                }
                java.util.UUID uuid = account.getUuid();
                String senderName = (sender instanceof Player p) ? p.getName() : "Console";
                plugin.getEconomyManager().setBalance(uuid, targetName, 0.0, true);
                plugin.getDatabaseManager().logTransaction(new Transaction(0, null, senderName, uuid, targetName, 0.0, Transaction.Type.ADMIN_SET, System.currentTimeMillis()));
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("economy.admin-reset", "<green>Reset balance."),
                        Map.of("%target%", targetName));
            }));
            return true;
        }

        if (args.length < 3) {
            sendHelp(sender);
            return true;
        }

        double amount;
        try {
            amount = NumberFormatter.parseAmount(args[2]);
        } catch (NumberFormatException e) {
            plugin.getTextService().sendMessage(sender,
                    plugin.getConfigService().getMessages().getString("invalid-number", "<red>Invalid number amount."),
                    Map.of("%amount%", args[2]));
            return true;
        }

        if (amount < 0 || (amount <= 0 && !sub.equalsIgnoreCase("set"))) {
            plugin.getTextService().sendMessage(sender,
                    "<red>Amount must be greater than zero.", null);
            return true;
        }

        plugin.getEconomyManager().getUuidByNameAsync(targetName)
                .thenCompose(uuid -> uuid == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(null)
                        : plugin.getEconomyManager().getAccountAsync(uuid, targetName))
                .thenAccept(account -> runForSender(sender, () -> {
            if (account == null) {
                plugin.getTextService().sendMessage(sender,
                        plugin.getConfigService().getMessages().getString("player-not-found", "<red>Player not found."),
                        Map.of("%player%", targetName));
                return;
            }

            java.util.UUID uuid = account.getUuid();
            String senderName = (sender instanceof Player p) ? p.getName() : "Console";
            String formattedAmount = plugin.getNumberFormatter().format(amount);

            switch (sub) {
                case "give" -> {
                    plugin.getEconomyManager().deposit(uuid, amount, true);
                    plugin.getDatabaseManager().logTransaction(new Transaction(0, null, senderName, uuid, targetName, amount, Transaction.Type.ADMIN_GIVE, System.currentTimeMillis()));
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("economy.admin-give", "<green>Gave $%amount% to %target%."),
                            Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));

                    Player onlineTarget = Bukkit.getPlayer(uuid);
                    if (onlineTarget != null) {
                        plugin.getScheduler().runEntity(onlineTarget, () -> {
                            String bar = plugin.getConfigService().getMessages().getString("economy.actionbar-gain",
                                    "<green>+$%amount%</green> <dark_gray>»</dark_gray> <gray>Balance updated</gray>");
                            plugin.getTextService().sendActionBar(onlineTarget, bar, Map.of("%amount%", formattedAmount));
                        });
                    }
                }
                case "take", "remove" -> {
                    boolean success = plugin.getEconomyManager().withdraw(uuid, amount, true);
                    if (!success) {
                        plugin.getTextService().sendMessage(sender,
                                "<red>Could not take $%amount% from %target% (insufficient funds).",
                                Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));
                        return;
                    }
                    plugin.getDatabaseManager().logTransaction(new Transaction(0, uuid, targetName, null, senderName, amount, Transaction.Type.ADMIN_TAKE, System.currentTimeMillis()));
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("economy.admin-take", "<green>Removed $%amount% from %target%."),
                            Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));

                    Player onlineTarget = Bukkit.getPlayer(uuid);
                    if (onlineTarget != null) {
                        plugin.getScheduler().runEntity(onlineTarget, () -> {
                            String bar = plugin.getConfigService().getMessages().getString("economy.actionbar-loss",
                                    "<red>-$%amount%</red> <dark_gray>»</dark_gray> <gray>Balance updated</gray>");
                            plugin.getTextService().sendActionBar(onlineTarget, bar, Map.of("%amount%", formattedAmount));
                        });
                    }
                }
                case "set" -> {
                    plugin.getEconomyManager().setBalance(uuid, targetName, amount, true);
                    plugin.getDatabaseManager().logTransaction(new Transaction(0, null, senderName, uuid, targetName, amount, Transaction.Type.ADMIN_SET, System.currentTimeMillis()));
                    plugin.getTextService().sendMessage(sender,
                            plugin.getConfigService().getMessages().getString("economy.admin-set", "<green>Set %target%'s balance to $%amount%."),
                            Map.of("%amount%", formattedAmount, "%target%", targetName, "%player%", targetName));

                    Player onlineTarget = Bukkit.getPlayer(uuid);
                    if (onlineTarget != null) {
                        plugin.getScheduler().runEntity(onlineTarget, () -> {
                            String bar = plugin.getConfigService().getMessages().getString("economy.actionbar-set",
                                    "<yellow>$%amount%</yellow> <dark_gray>»</dark_gray> <gray>Balance set</gray>");
                            plugin.getTextService().sendActionBar(onlineTarget, bar, Map.of("%amount%", formattedAmount));
                        });
                    }
                }
                default -> sendHelp(sender);
            }
        }));

        return true;
    }

    private void sendHelp(CommandSender sender) {
        Messages.send(plugin, sender, "economy", "friendlyeconomy-admin-staff-commands");
        Messages.send(plugin, sender, "economy", "eco-give-add-money-to-player");
        Messages.send(plugin, sender, "economy", "eco-take-remove-money-from-player");
        Messages.send(plugin, sender, "economy", "eco-set-set-player-balance");
        Messages.send(plugin, sender, "economy", "eco-see-check-player-balance");
        Messages.send(plugin, sender, "economy", "eco-reset-reset-player-balance");
        Messages.send(plugin, sender, "economy", "eco-migrate-folder-import-essentialsx-balances");
        Messages.send(plugin, sender, "economy", "eco-resetall-confirm-wipe-all-economy");
    }

    private void runForSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            plugin.getScheduler().runEntity(player, action);
        } else {
            plugin.getScheduler().runGlobal(action);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("swiftpay.admin") && !sender.hasPermission("friendlycore.economy.admin") && !sender.hasPermission("friendlycore.admin")) return List.of();
        if (args.length == 1) {
            return List.of("give", "take", "remove", "set", "see", "reset", "migrate", "resetall");
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("resetall") && !args[0].equalsIgnoreCase("migrate")) {
            String search = args[1].toLowerCase();
            List<String> list = new ArrayList<>();
            for (String name : plugin.getRedisService().getOnlinePlayerNames()) {
                if (name.toLowerCase().startsWith(search)) {
                    list.add(name);
                }
            }
            return list;
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take") || args[0].equalsIgnoreCase("set"))) {
            return List.of("100", "1000", "10k", "1m");
        }
        return List.of();
    }
}
