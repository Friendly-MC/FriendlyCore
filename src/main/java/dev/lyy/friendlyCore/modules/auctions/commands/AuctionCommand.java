package dev.lyy.friendlycore.modules.auctions.commands;

import dev.lyy.friendlycore.common.PlayerLookup;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionCategoryGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionConfirmListingGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionDeletedGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionExpiredGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionHistoryGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionLogsGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMainGui;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMyItemsGui;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Primary player command handler for accessing the auction house GUI, listing items for sale,
 * searching marketplace items, viewing histories, and navigating submenus.
 */
public class AuctionCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;

    public AuctionCommand(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            auctionManager.sendLang(sender, "commands.player-only");
            return true;
        }

        if (!player.hasPermission("axauctions.use") && !player.hasPermission("friendlycore.auctions.use")) {
            auctionManager.sendLang(player, "commands.no-permission");
            return true;
        }

        if (args.length == 0) {
            String defMenu = plugin.getConfigService().get("modules/auctions/config.yml") != null ?
                    plugin.getConfigService().get("modules/auctions/config.yml").getString("default-menu", "main").toLowerCase(Locale.ROOT) : "main";
            switch (defMenu) {
                case "category", "categories" -> new AuctionCategoryGui(plugin, player, null).open();
                case "my-items", "myitems" -> new AuctionMyItemsGui(plugin, player, 1, null).open();
                case "expired", "expired-items" -> new AuctionExpiredGui(plugin, player, 1, null).open();
                case "history" -> new AuctionHistoryGui(plugin, player, 1, null).open();
                case "deleted" -> new AuctionDeletedGui(plugin, player, 1, null).open();
                default -> new AuctionMainGui(plugin, player, 1, null).open();
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "sell" -> handleSell(player, args);
            case "search" -> handleSearch(player, args);
            case "view" -> handleView(player, args);
            case "open" -> handleOpen(player, args);
            case "history" -> {
                if (!player.hasPermission("axauctions.history") && !player.hasPermission("friendlycore.auctions.history")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                boolean global = plugin.getConfigService().get("modules/auctions/config.yml").getBoolean("show-global-history", false);
                new AuctionHistoryGui(plugin, player, global ? null : player.getUniqueId(), 1, null).open();
            }
            case "expired" -> {
                if (!player.hasPermission("axauctions.open") && !player.hasPermission("friendlycore.auctions.open")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                new AuctionExpiredGui(plugin, player, 1, null).open();
            }
            case "myitems", "my-items", "manage" -> {
                if (!player.hasPermission("axauctions.open") && !player.hasPermission("friendlycore.auctions.open")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                new AuctionMyItemsGui(plugin, player, 1, null).open();
            }
            case "categories", "category" -> {
                if (!player.hasPermission("axauctions.open") && !player.hasPermission("friendlycore.auctions.open")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                new AuctionCategoryGui(plugin, player, null).open();
            }
            case "deleted" -> {
                if (!player.hasPermission("axauctions.deleted") && !player.hasPermission("friendlycore.auctions.deleted")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                new AuctionDeletedGui(plugin, player, 1, null).open();
            }
            case "help" -> {
                if (!player.hasPermission("axauctions.help") && !player.hasPermission("friendlycore.auctions.help")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                sendHelp(player);
            }
            default -> {
                // Quick text search when user types /ah <query>
                if (!player.hasPermission("axauctions.search") && !player.hasPermission("friendlycore.auctions.search")) {
                    auctionManager.sendLang(player, "commands.no-permission");
                    return true;
                }
                AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
                user.setSearchFilter(args[0]);
                new AuctionMainGui(plugin, player, 1, null).open();
            }
        }

        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
            auctionManager.sendLang(player, "safety");
            return;
        }

        if (!player.hasPermission("friendlycore.auctions.sell") && !player.hasPermission("axauctions.sell")) {
            auctionManager.sendLang(player, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            auctionManager.sendLang(player, "commands.missing-argument", "%value%", "price");
            return;
        }

        double price;
        try {
            price = dev.lyy.friendlycore.common.NumberFormatter.parseAmount(args[1]);
            if (price <= 0 || Double.isNaN(price) || Double.isInfinite(price)) {
                auctionManager.sendLang(player, "sell.invalid-price");
                return;
            }
        } catch (NumberFormatException e) {
            auctionManager.sendLang(player, "sell.invalid-price");
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            auctionManager.sendLang(player, "sell.no-item");
            return;
        }

        ItemStack toList = item.clone();
        if (args.length >= 3) {
            try {
                int amount = Integer.parseInt(args[2]);
                int maximum = Math.min(64, item.getAmount());
                if (amount <= 0 || amount > maximum) {
                    auctionManager.sendLang(player, "commands.out-of-range",
                            "%number%", String.valueOf(amount),
                            "%min%", "1",
                            "%max%", String.valueOf(maximum));
                    return;
                }
                toList.setAmount(amount);
            } catch (NumberFormatException e) {
                auctionManager.sendLang(player, "commands.invalid-value", "%value%", args[2]);
                return;
            }
        }

        boolean listingConfirm = plugin.getConfigService().get("modules/auctions/config.yml").getBoolean("auction-listing-confirmation", false);
        if (listingConfirm) {
            new AuctionConfirmListingGui(plugin, player, toList, price, null).open();
        } else {
            auctionManager.createAuction(player, toList, price);
        }
    }

    private void handleSearch(Player player, String[] args) {
        if (!player.hasPermission("axauctions.search") && !player.hasPermission("friendlycore.auctions.search")) {
            auctionManager.sendLang(player, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            auctionManager.promptSearchInput(player, query -> {
                AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
                String filter = (query == null || query.isBlank()) ? null : query.trim();
                user.setSearchFilter(filter);
                plugin.getScheduler().runEntity(player, () -> {
                    new AuctionMainGui(plugin, player, 1, null).open();
                });
            });
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(" ");
            sb.append(args[i]);
        }

        AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
        user.setSearchFilter(sb.toString());
        new AuctionMainGui(plugin, player, 1, null).open();
    }

    private void handleView(Player player, String[] args) {
        if (!player.hasPermission("axauctions.view") && !player.hasPermission("friendlycore.auctions.view")) {
            auctionManager.sendLang(player, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
            user.setSearchFilter(player.getName());
            new AuctionMainGui(plugin, player, 1, null).open();
            return;
        }

        String targetName = args[1];
        PlayerLookup.byName(plugin, player, targetName, target -> {
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                auctionManager.sendLang(player, "commands.invalid-player", "%player%", targetName);
                return;
            }
            AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
            user.setSearchFilter(targetName);
            new AuctionMainGui(plugin, player, 1, null).open();
        });
    }

    private void handleOpen(Player player, String[] args) {
        if (!player.hasPermission("axauctions.open") && !player.hasPermission("friendlycore.auctions.open")) {
            auctionManager.sendLang(player, "commands.no-permission");
            return;
        }

        if (args.length < 2) {
            new AuctionMainGui(plugin, player, 1, null).open();
            return;
        }

        String menu = args[1].toLowerCase(Locale.ROOT);
        if (args.length > 2) {
            AuctionUser user = auctionManager.getUser(player.getUniqueId(), player.getName());
            user.setSearchFilter(args[2]);
        }

        switch (menu) {
            case "main" -> new AuctionMainGui(plugin, player, 1, null).open();
            case "my-items", "myitems" -> new AuctionMyItemsGui(plugin, player, 1, null).open();
            case "expired", "expired-items" -> new AuctionExpiredGui(plugin, player, 1, null).open();
            case "history" -> new AuctionHistoryGui(plugin, player, 1, null).open();
            case "logs" -> new AuctionLogsGui(plugin, player, 1, null).open();
            case "deleted" -> new AuctionDeletedGui(plugin, player, 1, null).open();
            case "category", "categories" -> new AuctionCategoryGui(plugin, player, null).open();
            default -> new AuctionMainGui(plugin, player, 1, null).open();
        }
    }

    private void sendHelp(CommandSender sender) {
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        List<String> lines = msgs != null ? msgs.getStringList("help") : List.of();
        for (String line : lines) {
            plugin.getTextService().sendMessage(sender, line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = List.of("sell", "search", "view", "open", "history", "expired", "myitems", "category", "deleted", "help");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) list.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            List<String> menus = List.of("main", "my-items", "expired", "history", "logs", "deleted", "category");
            for (String m : menus) {
                if (m.startsWith(args[1].toLowerCase(Locale.ROOT))) list.add(m);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    list.add(p.getName());
                }
            }
        }
        return list;
    }
}
