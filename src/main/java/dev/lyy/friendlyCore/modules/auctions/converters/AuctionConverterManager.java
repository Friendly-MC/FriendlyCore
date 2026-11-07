package dev.lyy.friendlycore.modules.auctions.converters;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Handles asynchronous data migration from various third-party auction plugins
 * (AuctionHouse, CrazyAuctions, Fadah, PlayerAuctions, PlayerShopGUIPlus, zAuctionHouse) into FriendlyCore.
 */
public class AuctionConverterManager {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;
    private final AtomicBoolean converting = new AtomicBoolean(false);

    public AuctionConverterManager(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
    }

    public void convert(CommandSender sender, String pluginName) {
        if (!converting.compareAndSet(false, true)) {
            plugin.getTextService().send(sender, "auctions.convert-already-running");
            return;
        }
        plugin.getScheduler().runAsync(() -> {
            try {
                int count = 0;
                switch (pluginName.toLowerCase(Locale.ROOT)) {
                    case "axauctions", "axauction", "axah" -> {
                        AxAuctionsConverter.ImportStats stats = new AxAuctionsConverter(plugin, auctionManager).convert();
                        plugin.getTextService().send(sender, "auctions.convert-axauctions-summary",
                                "{users}", String.valueOf(stats.users()),
                                "{items}", String.valueOf(stats.items()),
                                "{history}", String.valueOf(stats.history()),
                                "{deleted}", String.valueOf(stats.deleted()),
                                "{logs}", String.valueOf(stats.logs()),
                                "{payouts}", String.valueOf(stats.payouts()),
                                "{notifications}", String.valueOf(stats.notifications()),
                                "{skipped}", String.valueOf(stats.skipped()));
                        count = stats.listings();
                    }
                    case "auctionhouse" -> count = convertAuctionHouse();
                    case "crazyauctions" -> count = convertCrazyAuctions();
                    case "fadah" -> count = convertFadah();
                    case "playerauctions" -> count = convertPlayerAuctions();
                    case "playershopguiplus", "playershopgui+", "playershopgui" -> count = convertPlayerShopGUI();
                    case "zauctionhouse" -> count = convertZAuctionHouse();
                    default -> {
                        plugin.getTextService().send(sender, "auctions.convert-invalid-plugin", "{plugin}", pluginName);
                        return;
                    }
                }
                plugin.getTextService().send(sender, "auctions.convert-success", "{plugin}", pluginName, "{count}", String.valueOf(count));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to convert from " + pluginName, e);
                String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                plugin.getTextService().send(sender, "auctions.convert-failure", "{plugin}", pluginName, "{error}", error);
            } finally {
                converting.set(false);
            }
        });
    }

    private void importBatch(List<AuctionItem> batch) {
        if (batch == null || batch.isEmpty()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        for (AuctionItem ai : batch) {
            futures.add(plugin.getDatabaseManager().insertAuctionItem(ai).thenAccept(id -> {
                if (id > 0) {
                    auctionManager.cacheItem(new AuctionItem(id, ai.getItemStack(), ai.getSellerUuid(), ai.getSellerName(), ai.getPrice(), ai.getStartTime()));
                    auctionManager.getPacketHandler().publishItemInsert(id);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private int convertAuctionHouse() throws Exception {
        File dbFile = new File(plugin.getDataFolder().getParentFile(), "AuctionHouse/auctions.db");
        if (!dbFile.exists()) {
            throw new IllegalStateException("AuctionHouse database file not found at " + dbFile.getPath());
        }

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(250);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM auctions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] itemBytes = rs.getBytes("item");
                ItemStack item = itemBytes != null ? ItemStack.deserializeBytes(itemBytes) : null;
                if (item == null) continue;

                UUID seller = UUID.fromString(rs.getString("seller"));
                String sellerName = Bukkit.getOfflinePlayer(seller).getName();
                double price = rs.getDouble("price");

                AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
                batch.add(ai);
                count++;

                if (batch.size() >= 250) {
                    importBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }

    private int convertCrazyAuctions() throws Exception {
        File dataFile = new File(plugin.getDataFolder().getParentFile(), "CrazyAuctions/data.yml");
        if (!dataFile.exists()) {
            throw new IllegalStateException("CrazyAuctions data.yml not found at " + dataFile.getPath());
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (!cfg.contains("Items")) return 0;

        int count = 0;
        var sec = cfg.getConfigurationSection("Items");
        if (sec == null) return 0;

        List<AuctionItem> batch = new ArrayList<>(250);
        for (String num : sec.getKeys(false)) {
            ItemStack item = sec.getItemStack(num + ".Item");
            if (item == null) continue;

            String sellerStr = sec.getString(num + ".Seller");
            UUID seller = sellerStr != null ? UUID.fromString(sellerStr) : UUID.randomUUID();
            String sellerName = Bukkit.getOfflinePlayer(seller).getName();
            double price = sec.getDouble(num + ".Price", 10.0);

            AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
            batch.add(ai);
            count++;

            if (batch.size() >= 250) {
                importBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }

    private int convertFadah() throws Exception {
        File dbFile = new File(plugin.getDataFolder().getParentFile(), "Fadah/data.db");
        if (!dbFile.exists()) {
            throw new IllegalStateException("Fadah database file not found at " + dbFile.getPath());
        }

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(250);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM listings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] itemBytes = rs.getBytes("item");
                ItemStack item = itemBytes != null ? ItemStack.deserializeBytes(itemBytes) : null;
                if (item == null) continue;

                UUID seller = UUID.fromString(rs.getString("seller"));
                String sellerName = Bukkit.getOfflinePlayer(seller).getName();
                double price = rs.getDouble("price");

                AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
                batch.add(ai);
                count++;

                if (batch.size() >= 250) {
                    importBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }

    private int convertPlayerAuctions() throws Exception {
        File dbFile = new File(plugin.getDataFolder().getParentFile(), "PlayerAuctions/database.db");
        if (!dbFile.exists()) {
            throw new IllegalStateException("PlayerAuctions database file not found at " + dbFile.getPath());
        }

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(250);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM auction_items");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] itemBytes = rs.getBytes("item");
                ItemStack item = itemBytes != null ? ItemStack.deserializeBytes(itemBytes) : null;
                if (item == null) continue;

                UUID seller = UUID.fromString(rs.getString("uuid"));
                String sellerName = Bukkit.getOfflinePlayer(seller).getName();
                double price = rs.getDouble("price");

                AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
                batch.add(ai);
                count++;

                if (batch.size() >= 250) {
                    importBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }

    private int convertPlayerShopGUI() throws Exception {
        File dbFile = new File(plugin.getDataFolder().getParentFile(), "PlayerShopGUIPlus/data.db");
        if (!dbFile.exists()) {
            throw new IllegalStateException("PlayerShopGUIPlus database file not found at " + dbFile.getPath());
        }

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(250);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM shops");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] itemBytes = rs.getBytes("item_stack");
                ItemStack item = itemBytes != null ? ItemStack.deserializeBytes(itemBytes) : null;
                if (item == null) continue;

                UUID seller = UUID.fromString(rs.getString("owner"));
                String sellerName = Bukkit.getOfflinePlayer(seller).getName();
                double price = rs.getDouble("price");

                AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
                batch.add(ai);
                count++;

                if (batch.size() >= 250) {
                    importBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }

    private int convertZAuctionHouse() throws Exception {
        File dbFile = new File(plugin.getDataFolder().getParentFile(), "zAuctionHouse/storage.db");
        if (!dbFile.exists()) {
            throw new IllegalStateException("zAuctionHouse database file not found at " + dbFile.getPath());
        }

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(250);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM items");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] itemBytes = rs.getBytes("item");
                ItemStack item = itemBytes != null ? ItemStack.deserializeBytes(itemBytes) : null;
                if (item == null) continue;

                UUID seller = UUID.fromString(rs.getString("seller"));
                String sellerName = Bukkit.getOfflinePlayer(seller).getName();
                double price = rs.getDouble("price");

                AuctionItem ai = new AuctionItem(0, item, seller, sellerName != null ? sellerName : "Unknown", price, System.currentTimeMillis());
                batch.add(ai);
                count++;

                if (batch.size() >= 250) {
                    importBatch(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            importBatch(batch);
            batch.clear();
        }
        return count;
    }
}
