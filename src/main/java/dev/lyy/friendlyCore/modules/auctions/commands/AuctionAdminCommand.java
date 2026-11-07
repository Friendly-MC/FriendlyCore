package dev.lyy.friendlycore.modules.auctions.commands;

import dev.lyy.friendlycore.common.PlayerLookup;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionCategoryGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionDeletedGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionExpiredGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionHistoryGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionLogsGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMainGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMyItemsGui;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command handler for auction administrative operations including configuration reloading,
 * force opening menus, inspecting history/logs, adjusting listing limits, and data migration.
 */
public class AuctionAdminCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;

    private static final List<String> RELOAD_CONFIG_FILES = List.of(
            "config.yml", "converter.yml", "categories.yml", "discord.yml", "messages.yml",
            "guis/admin-removal-gui.yml", "guis/category-gui.yml", "guis/confirm-listing-gui.yml",
            "guis/confirm-purchase-gui.yml", "guis/deleted-gui.yml", "guis/expired-items-gui.yml",
            "guis/history-gui.yml", "guis/logs-gui.yml", "guis/main-gui.yml", "guis/my-items-gui.yml",
            "guis/shulker-preview-gui.yml"
    );

    public AuctionAdminCommand(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("friendlycore.auctions.admin") && !sender.hasPermission("axauctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return true;
        }

        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("axauctions.reload") && !sender.hasPermission("friendlycore.auctions.admin")) {
                    auctionManager.sendLang(sender, "commands.no-permission");
                    return true;
                }
                try {
                    for (String file : RELOAD_CONFIG_FILES) {
                        plugin.getConfigService().reload("modules/auctions/" + file);
                    }
                    auctionManager.reload();
                    auctionManager.sendLang(sender, "reload.success");
                } catch (Exception e) {
                    auctionManager.sendLang(sender, "reload.failed", "%file%", "config.yml");
                }
            }
            case "forceopen" -> handleForceOpen(sender, args);
            case "history" -> handleHistory(sender, args);
            case "deleted" -> handleDeleted(sender, args);
            case "logs" -> handleLogs(sender, args);
            case "limit" -> handleLimit(sender, args);
            case "convert" -> handleConvert(sender, args);
            default -> sendAdminHelp(sender);
        }

        return true;
    }

    private void handleForceOpen(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.forceopen") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            auctionManager.sendLang(sender, "commands.missing-argument", "%value%", "player");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            auctionManager.sendLang(sender, "commands.invalid-player", "%player%", args[1]);
            return;
        }

        String menu = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "main";
        if (args.length > 3) {
            AuctionUser user = auctionManager.getUser(target.getUniqueId(), target.getName());
            user.setSearchFilter(args[3]);
        }

        switch (menu) {
            case "main" -> new AuctionMainGui(plugin, target, 1, null).open();
            case "my-items", "myitems" -> new AuctionMyItemsGui(plugin, target, 1, null).open();
            case "expired", "expired-items" -> new AuctionExpiredGui(plugin, target, 1, null).open();
            case "history" -> new AuctionHistoryGui(plugin, target, 1, null).open();
            case "logs" -> new AuctionLogsGui(plugin, target, 1, null).open();
            case "deleted" -> new AuctionDeletedGui(plugin, target, 1, null).open();
            case "category", "categories" -> new AuctionCategoryGui(plugin, target, null).open();
            default -> new AuctionMainGui(plugin, target, 1, null).open();
        }
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.history.admin") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (!(sender instanceof Player player)) {
            auctionManager.sendLang(sender, "commands.player-only");
            return;
        }

        if (args.length > 1) {
            PlayerLookup.byName(plugin, player, args[1], off -> new AuctionHistoryGui(plugin, player, off.getUniqueId(), 1, null).open());
        } else {
            new AuctionHistoryGui(plugin, player, null, 1, null).open();
        }
    }

    private void handleDeleted(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.deleted.admin") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (!(sender instanceof Player player)) {
            auctionManager.sendLang(sender, "commands.player-only");
            return;
        }

        if (args.length > 1) {
            PlayerLookup.byName(plugin, player, args[1], off -> new AuctionDeletedGui(plugin, player, off.getUniqueId(), 1, null).open());
        } else {
            new AuctionDeletedGui(plugin, player, null, 1, null).open();
        }
    }

    private void handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.logs.admin") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (!(sender instanceof Player player)) {
            auctionManager.sendLang(sender, "commands.player-only");
            return;
        }

        if (args.length > 1) {
            PlayerLookup.byName(plugin, player, args[1], off -> new AuctionLogsGui(plugin, player, off.getUniqueId(), 1, null).open());
        } else {
            new AuctionLogsGui(plugin, player, null, 1, null).open();
        }
    }

    private void handleLimit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.limit") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (args.length < 3) {
            auctionManager.sendLang(sender, "commands.missing-argument", "%value%", "player");
            return;
        }

        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!List.of("set", "give", "add", "take", "remove", "reset").contains(mode)) {
            auctionManager.sendLang(sender, "commands.invalid-command");
            return;
        }
        int amount = 0;
        if (!mode.equals("reset")) {
            if (args.length < 4) {
                auctionManager.sendLang(sender, "commands.missing-argument", "%value%", "amount");
                return;
            }
            try {
                amount = Integer.parseInt(args[3]);
                if (amount < 0) {
                    auctionManager.sendLang(sender, "commands.out-of-range", "%number%", String.valueOf(amount), "%min%", "0", "%max%", String.valueOf(Integer.MAX_VALUE));
                    return;
                }
            } catch (NumberFormatException invalidAmount) {
                auctionManager.sendLang(sender, "commands.invalid-value", "%value%", args[3]);
                return;
            }
        }

        final int limitDelta = amount;
        PlayerLookup.byName(plugin, sender, args[2], target -> {
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                auctionManager.sendLang(sender, "commands.invalid-player", "%player%", args[2]);
                return;
            }
            applyLimit(sender, target, args[2], mode, limitDelta);
        });
    }

    private void applyLimit(CommandSender sender, OfflinePlayer target, String inputName, String mode, int limitDelta) {
        String targetName = target.getName() != null ? target.getName() : inputName;
        auctionManager.getUserAsync(target.getUniqueId(), targetName).thenCompose(user -> {
            switch (mode) {
                case "set" -> user.setExtraLimit(limitDelta);
                case "give", "add" -> user.setExtraLimit(user.getExtraLimit() + limitDelta);
                case "take", "remove" -> user.setExtraLimit(Math.max(0, user.getExtraLimit() - limitDelta));
                case "reset" -> user.setExtraLimit(0);
                default -> throw new IllegalStateException("Validated auction limit mode became invalid");
            }
            return plugin.getDatabaseManager().saveAuctionUser(user).thenApply(ignored -> user);
        }).thenAccept(user -> {
            if (auctionManager.getPacketHandler().isCrossServerEnabled()) {
                auctionManager.getPacketHandler().publishUserLimit(user.getUuid(), user.getUsername(), user.getExtraLimit());
            }
            runForSender(sender, () ->
                auctionManager.sendLang(sender, "limit-changed", "%player%", targetName, "%amount%", String.valueOf(user.getExtraLimit())));
        }).exceptionally(error -> {
            runForSender(sender, () -> auctionManager.sendLang(sender, "data-not-loaded-other", "%player%", targetName));
            return null;
        });
    }

    private void runForSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            plugin.getScheduler().runEntity(player, action);
        } else {
            plugin.getScheduler().runGlobal(action);
        }
    }

    private void handleConvert(CommandSender sender, String[] args) {
        if (!sender.hasPermission("axauctions.convert") && !sender.hasPermission("friendlycore.auctions.admin")) {
            auctionManager.sendLang(sender, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            auctionManager.sendLang(sender, "commands.missing-argument", "%value%", "plugin");
            return;
        }

        auctionManager.sendLang(sender, "converting");
        auctionManager.getConverterManager().convert(sender, args[1]);
    }

    private void sendAdminHelp(CommandSender sender) {
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        List<String> lines = msgs != null ? msgs.getStringList("admin-help") : List.of();
        for (String line : lines) {
            plugin.getTextService().sendMessage(sender, line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = List.of("reload", "forceopen", "history", "deleted", "logs", "limit", "convert");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) list.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("convert")) {
            List<String> convs = List.of("axauctions", "auctionhouse", "crazyauctions", "fadah", "playerauctions", "playershopgui", "zauctionhouse");
            for (String c : convs) {
                if (c.startsWith(args[1].toLowerCase(Locale.ROOT))) list.add(c);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("limit")) {
            List<String> modes = List.of("set", "give", "take", "reset");
            for (String m : modes) {
                if (m.startsWith(args[1].toLowerCase(Locale.ROOT))) list.add(m);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("forceopen")) {
            List<String> menus = List.of("main", "my-items", "expired", "history", "logs", "deleted", "category");
            for (String m : menus) {
                if (m.startsWith(args[2].toLowerCase(Locale.ROOT))) list.add(m);
            }
        }
        return list;
    }
}
