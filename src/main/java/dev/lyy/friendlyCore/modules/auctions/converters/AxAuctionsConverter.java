package dev.lyy.friendlycore.modules.auctions.converters;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.converters.AxAuctionsSchema.ResultColumns;
import dev.lyy.friendlycore.modules.auctions.converters.AxAuctionsSchema.SourceUser;
import dev.lyy.friendlycore.modules.auctions.converters.AxAuctionsSchema.UsersIndex;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import dev.lyy.friendlycore.tools.converter.SqlTableTransfer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Imports AxAuctions H2/MySQL data into FriendlyCore auction tables.
 */
public class AxAuctionsConverter {

    private static final int BATCH_SIZE = 250;

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;

    public AxAuctionsConverter(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
    }

    public record ImportStats(int users, int items, int history, int deleted, int logs,
                              int notifications, int payouts, int skipped) {
        public int listings() {
            return items;
        }
    }

    public ImportStats convert() throws Exception {
        SourceConnection source = resolveSource();
        plugin.getLogger().info("[Auctions] Connecting to AxAuctions " + source.type() + " database...");

        try (Connection conn = openConnection(source)) {
            List<String> tables = AxAuctionsSchema.listTables(conn);
            if (tables.isEmpty()) {
                throw new IllegalStateException("No tables found in the AxAuctions database. Check prefix/database name.");
            }

            String prefix = source.prefix();
            UsersIndex users = AxAuctionsSchema.loadUsers(conn, tables, prefix);
            Map<Integer, String> currencies = AxAuctionsSchema.loadCurrencies(conn, tables, prefix);
            Set<Integer> allowedCurrencies = AxAuctionsSchema.allowedCurrencyIds(currencies, source.importNonVault());

            plugin.getLogger().info("[Auctions] AxAuctions users=" + users.size()
                    + " currencies=" + currencies
                    + " tables=" + tables.size());

            int userCount = importUsers(users);
            SkipCounter skipped = new SkipCounter();
            int items = importItems(conn, tables, prefix, users, allowedCurrencies, skipped);
            int history = importHistory(conn, tables, prefix, users, allowedCurrencies, skipped);
            int deleted = importDeleted(conn, tables, prefix, users, allowedCurrencies, skipped);
            int logs = importLogs(conn, tables, prefix, users);
            int notifications = importNotifications(conn, tables, prefix, users);
            int payouts = importPayouts(conn, tables, prefix, users, allowedCurrencies, skipped);

            return new ImportStats(userCount, items, history, deleted, logs, notifications, payouts, skipped.value);
        }
    }

    private int importUsers(UsersIndex users) {
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        int count = 0;
        for (SourceUser user : users.all()) {
            batch.add(plugin.getDatabaseManager().saveAuctionUser(new AuctionUser(user.uuid(), user.name(), user.extraLimit())));
            auctionManager.syncUserLimitLocally(user.uuid(), user.extraLimit());
            count++;
            if (batch.size() >= BATCH_SIZE) {
                await(batch);
                batch.clear();
            }
        }
        await(batch);
        return count;
    }

    private int importItems(Connection conn, List<String> tables, String prefix, UsersIndex users,
                            Set<Integer> allowedCurrencies, SkipCounter skipped) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "items");
        if (table == null) return 0;

        int count = 0;
        List<AuctionItem> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String itemCol = columns.find("item", "item_bytes", "itemstack", "item_stack");
            String sellerCol = columns.find("seller_id", "seller", "seller_uuid");
            String sellerNameCol = columns.find("seller_name", "username", "name");
            String priceCol = columns.find("price", "amount");
            String currencyCol = columns.find("currency_id", "currency");
            String startCol = columns.find("start_time", "starttime", "created", "created_at", "time");
            if (itemCol == null || sellerCol == null || priceCol == null) {
                throw new SQLException("AxAuctions items table is missing required columns (item/seller/price).");
            }

            while (rs.next()) {
                if (!currencyAllowed(rs, currencyCol, allowedCurrencies)) {
                    skipped.value++;
                    continue;
                }
                ItemStack stack = deserializeItem(rs.getBytes(itemCol));
                if (stack == null) {
                    skipped.value++;
                    continue;
                }
                SourceUser seller = users.resolve(rs.getObject(sellerCol), sellerNameCol != null ? rs.getString(sellerNameCol) : null);
                if (seller == null) {
                    skipped.value++;
                    continue;
                }
                Double price = AxAuctionsSchema.parseDouble(rs.getObject(priceCol));
                if (price == null || price < 0) {
                    skipped.value++;
                    continue;
                }
                Long startTime = startCol != null ? AxAuctionsSchema.parseLong(rs.getObject(startCol)) : null;
                if (startTime == null || startTime <= 0) startTime = System.currentTimeMillis();

                batch.add(new AuctionItem(0, stack, seller.uuid(), seller.name(), price, startTime));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    importItemBatch(batch);
                    batch.clear();
                }
            }
        }
        importItemBatch(batch);
        return count;
    }

    private int importHistory(Connection conn, List<String> tables, String prefix, UsersIndex users,
                              Set<Integer> allowedCurrencies, SkipCounter skipped) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "history");
        if (table == null) return 0;

        int count = 0;
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String itemCol = columns.find("item", "item_bytes", "itemstack", "item_stack");
            String sellerCol = columns.find("seller_id", "seller", "seller_uuid");
            String sellerNameCol = columns.find("seller_name");
            String buyerCol = columns.find("buyer_id", "buyer", "buyer_uuid");
            String buyerNameCol = columns.find("buyer_name");
            String priceCol = columns.find("price", "amount");
            String currencyCol = columns.find("currency_id", "currency");
            String soldCol = columns.find("sold_time", "soldtime", "time", "date", "timestamp");
            if (itemCol == null || sellerCol == null || buyerCol == null || priceCol == null) {
                plugin.getLogger().warning("[Auctions] Skipping AxAuctions history; required columns were missing.");
                return 0;
            }

            while (rs.next()) {
                if (!currencyAllowed(rs, currencyCol, allowedCurrencies)) {
                    skipped.value++;
                    continue;
                }
                ItemStack stack = deserializeItem(rs.getBytes(itemCol));
                SourceUser seller = users.resolve(rs.getObject(sellerCol), sellerNameCol != null ? rs.getString(sellerNameCol) : null);
                SourceUser buyer = users.resolve(rs.getObject(buyerCol), buyerNameCol != null ? rs.getString(buyerNameCol) : null);
                Double price = AxAuctionsSchema.parseDouble(rs.getObject(priceCol));
                if (stack == null || seller == null || buyer == null || price == null) {
                    skipped.value++;
                    continue;
                }
                Long soldTime = soldCol != null ? AxAuctionsSchema.parseLong(rs.getObject(soldCol)) : null;
                if (soldTime == null || soldTime <= 0) soldTime = System.currentTimeMillis();

                AuctionHistoryEntry entry = new AuctionHistoryEntry(0, stack, seller.uuid(), seller.name(),
                        buyer.uuid(), buyer.name(), price, soldTime);
                batch.add(plugin.getDatabaseManager().insertAuctionHistory(entry));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    await(batch);
                    batch.clear();
                }
            }
        }
        await(batch);
        return count;
    }

    private int importDeleted(Connection conn, List<String> tables, String prefix, UsersIndex users,
                              Set<Integer> allowedCurrencies, SkipCounter skipped) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "deleted_items", "deleted");
        if (table == null) return 0;

        int count = 0;
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String itemCol = columns.find("item", "item_bytes", "itemstack", "item_stack");
            String sellerCol = columns.find("seller_id", "seller", "seller_uuid");
            String sellerNameCol = columns.find("seller_name");
            String priceCol = columns.find("price", "amount");
            String currencyCol = columns.find("currency_id", "currency");
            String startCol = columns.find("start_time", "starttime");
            String deletedCol = columns.find("deleted_time", "deletedtime", "date", "time");
            if (itemCol == null || sellerCol == null || priceCol == null) {
                plugin.getLogger().warning("[Auctions] Skipping AxAuctions deleted items; required columns were missing.");
                return 0;
            }

            while (rs.next()) {
                if (!currencyAllowed(rs, currencyCol, allowedCurrencies)) {
                    skipped.value++;
                    continue;
                }
                ItemStack stack = deserializeItem(rs.getBytes(itemCol));
                SourceUser seller = users.resolve(rs.getObject(sellerCol), sellerNameCol != null ? rs.getString(sellerNameCol) : null);
                Double price = AxAuctionsSchema.parseDouble(rs.getObject(priceCol));
                if (stack == null || seller == null || price == null) {
                    skipped.value++;
                    continue;
                }
                Long startTime = startCol != null ? AxAuctionsSchema.parseLong(rs.getObject(startCol)) : null;
                Long deletedTime = deletedCol != null ? AxAuctionsSchema.parseLong(rs.getObject(deletedCol)) : null;
                if (startTime == null || startTime <= 0) {
                    startTime = deletedTime != null && deletedTime > 0 ? deletedTime : System.currentTimeMillis();
                }
                AuctionItem item = new AuctionItem(0, stack, seller.uuid(), seller.name(), price, startTime);
                batch.add(plugin.getDatabaseManager().insertAuctionDeleted(item));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    await(batch);
                    batch.clear();
                }
            }
        }
        await(batch);
        return count;
    }

    private int importLogs(Connection conn, List<String> tables, String prefix, UsersIndex users) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "logs");
        if (table == null) return 0;

        int count = 0;
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String userCol = columns.find("user_id", "uuid", "actor_uuid", "actor", "player");
            String nameCol = columns.find("actor_name", "username", "name");
            String typeCol = columns.find("log_type", "action_type", "type", "action");
            String extraCol = columns.find("extra", "data", "info");
            String timeCol = columns.find("date", "time", "timestamp", "created_at");
            if (userCol == null || typeCol == null) {
                plugin.getLogger().warning("[Auctions] Skipping AxAuctions logs; required columns were missing.");
                return 0;
            }

            while (rs.next()) {
                SourceUser actor = users.resolve(rs.getObject(userCol), nameCol != null ? rs.getString(nameCol) : null);
                if (actor == null) continue;
                Integer typeId = AxAuctionsSchema.parseInt(rs.getObject(typeCol));
                AuctionActionType type = AuctionActionType.fromId(typeId != null ? typeId : 0);
                String extra = extraCol != null ? rs.getString(extraCol) : "";
                Long timestamp = timeCol != null ? AxAuctionsSchema.parseLong(rs.getObject(timeCol)) : null;
                if (timestamp == null || timestamp <= 0) timestamp = System.currentTimeMillis();

                AuctionLogEntry entry = new AuctionLogEntry(0, actor.uuid(), actor.name(), type, extra, timestamp);
                batch.add(plugin.getDatabaseManager().insertAuctionLog(entry));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    await(batch);
                    batch.clear();
                }
            }
        }
        await(batch);
        return count;
    }

    private int importNotifications(Connection conn, List<String> tables, String prefix, UsersIndex users) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "notifications");
        if (table == null) return 0;

        int count = 0;
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String userCol = columns.find("user_id", "uuid", "player");
            String itemIdCol = columns.find("item_id", "auction_id");
            String messageCol = columns.find("message", "text", "content");
            if (userCol == null || messageCol == null) return 0;

            while (rs.next()) {
                SourceUser user = users.resolve(rs.getObject(userCol), null);
                String message = rs.getString(messageCol);
                if (user == null || message == null || message.isBlank()) continue;
                int itemId = itemIdCol != null ? rs.getInt(itemIdCol) : 0;
                batch.add(plugin.getDatabaseManager().insertAuctionNotification(user.uuid(), itemId, message));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    await(batch);
                    batch.clear();
                }
            }
        }
        await(batch);
        return count;
    }

    private int importPayouts(Connection conn, List<String> tables, String prefix, UsersIndex users,
                              Set<Integer> allowedCurrencies, SkipCounter skipped) throws SQLException {
        String table = AxAuctionsSchema.findTable(tables, prefix, "pending_payouts", "payouts", "unclaimed");
        if (table == null) return 0;

        int count = 0;
        List<CompletableFuture<?>> batch = new ArrayList<>(BATCH_SIZE);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + AxAuctionsSchema.quote(conn, table))) {
            ResultColumns columns = ResultColumns.of(rs);
            String userCol = columns.find("user_id", "uuid", "player");
            String amountCol = columns.find("amount", "price", "money");
            String currencyCol = columns.find("currency_id", "currency");
            if (userCol == null || amountCol == null) return 0;

            while (rs.next()) {
                if (!currencyAllowed(rs, currencyCol, allowedCurrencies)) {
                    skipped.value++;
                    continue;
                }
                SourceUser user = users.resolve(rs.getObject(userCol), null);
                Double amount = AxAuctionsSchema.parseDouble(rs.getObject(amountCol));
                if (user == null || amount == null || amount <= 0) {
                    skipped.value++;
                    continue;
                }
                batch.add(plugin.getDatabaseManager().insertAuctionPayout(user.uuid(), amount, "AxAuctions pending payout"));
                count++;
                if (batch.size() >= BATCH_SIZE) {
                    await(batch);
                    batch.clear();
                }
            }
        }
        await(batch);
        return count;
    }

    private void importItemBatch(List<AuctionItem> batch) {
        if (batch == null || batch.isEmpty()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>(batch.size());
        for (AuctionItem item : batch) {
            futures.add(plugin.getDatabaseManager().insertAuctionItem(item).thenAccept(id -> {
                if (id > 0) {
                    AuctionItem cached = new AuctionItem(id, item.getItemStack(), item.getSellerUuid(),
                            item.getSellerName(), item.getPrice(), item.getStartTime());
                    auctionManager.cacheItem(cached);
                    auctionManager.getPacketHandler().publishItemInsert(id);
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private static boolean currencyAllowed(ResultSet rs, String currencyCol, Set<Integer> allowedCurrencies) throws SQLException {
        if (currencyCol == null) return false;
        Integer currencyId = AxAuctionsSchema.parseInt(rs.getObject(currencyCol));
        return AxAuctionsSchema.isCurrencyAllowed(currencyId, allowedCurrencies);
    }

    private ItemStack deserializeItem(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Throwable failed) {
            plugin.getLogger().log(Level.WARNING, "[Auctions] Skipping AxAuctions item that could not be deserialized", failed);
            return null;
        }
    }

    private static void await(List<? extends CompletableFuture<?>> batch) {
        if (batch == null || batch.isEmpty()) return;
        CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();
    }

    private Connection openConnection(SourceConnection source) throws SQLException {
        if ("h2".equals(source.type())) {
            return SqlTableTransfer.createConnection(source.jdbcUrl(), null, null);
        }
        return SqlTableTransfer.createConnection(source.jdbcUrl(), source.username(), source.password());
    }

    private SourceConnection resolveSource() {
        FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/converter.yml");
        String type = firstNonBlank(cfg.getString("type"), cfg.getString("database.type"), "mysql").toLowerCase(Locale.ROOT);
        String prefix = firstNonBlank(cfg.getString("prefix"), cfg.getString("database.prefix"), "axauctions");
        String address = firstNonBlank(cfg.getString("address"), cfg.getString("database.address"), "127.0.0.1");
        int port = firstPositive(cfg.getInt("port"), cfg.getInt("database.port"), 3306);
        String database = firstNonBlank(cfg.getString("database.database"),
                isNestedDatabaseName(cfg.getString("database")) ? null : cfg.getString("database"),
                "auctions");
        String username = firstNonBlank(cfg.getString("username"), cfg.getString("database.username"), "");
        String password = firstNonBlank(cfg.getString("password"), cfg.getString("database.password"), "");
        boolean importNonVault = false;
        String h2File = firstNonBlank(cfg.getString("h2-file"), cfg.getString("database.h2-file"), "AxAuctions/data");

        File axConfigFile = new File(plugin.getDataFolder().getParentFile(), "AxAuctions/config.yml");
        if (axConfigFile.isFile()) {
            YamlConfiguration ax = YamlConfiguration.loadConfiguration(axConfigFile);
            if (isBlank(type)) type = firstNonBlank(ax.getString("database.type"), "mysql").toLowerCase(Locale.ROOT);
            if (isBlank(prefix)) prefix = firstNonBlank(ax.getString("database.prefix"), "axauctions");
            if (isBlank(address) || "127.0.0.1".equals(address)) {
                address = firstNonBlank(ax.getString("database.address"), address);
            }
            if (port <= 0) port = firstPositive(ax.getInt("database.port"), 3306);
            if (isBlank(username)) username = firstNonBlank(ax.getString("database.username"), username);
            if (isBlank(password)) password = firstNonBlank(ax.getString("database.password"), password);
            if (isBlank(database) || "auctions".equalsIgnoreCase(database) || "admin".equalsIgnoreCase(database)) {
                database = firstNonBlank(ax.getString("database.database"), database);
            }
        }

        if ("h2".equals(type) || "sqlite".equals(type)) {
            File plugins = plugin.getDataFolder().getParentFile();
            File specified = new File(h2File);
            if (!specified.isAbsolute()) {
                specified = new File(plugins, h2File);
            }
            String path = specified.getAbsolutePath().replace('\\', '/');
            if (path.endsWith(".mv.db")) {
                path = path.substring(0, path.length() - 6);
            }
            File mv = new File(path + ".mv.db");
            if (!mv.exists() && !specified.exists()) {
                throw new IllegalStateException("AxAuctions H2 database not found at " + mv.getPath()
                        + ". Set h2-file in modules/auctions/converter.yml.");
            }
            String url = "jdbc:h2:" + path + ";IFEXISTS=TRUE";
            return new SourceConnection("h2", prefix, url, null, null, importNonVault);
        }

        if (isBlank(username) || isBlank(database)) {
            throw new IllegalStateException("Fill modules/auctions/converter.yml with the AxAuctions MySQL settings "
                    + "(address, port, database, username, password) or keep AxAuctions installed so they can be read automatically.");
        }

        String url = "jdbc:mysql://" + address + ":" + port + "/" + database
                + "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=utf8";
        return new SourceConnection("mysql", prefix, url, username, password, importNonVault);
    }

    private static boolean isNestedDatabaseName(String value) {
        return value == null || value.contains("\n") || value.contains("{");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return null;
    }

    private static int firstPositive(int... values) {
        if (values == null) return 0;
        for (int value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SourceConnection(String type, String prefix, String jdbcUrl, String username, String password,
                                    boolean importNonVault) {}

    private static final class SkipCounter {
        private int value;
    }
}
