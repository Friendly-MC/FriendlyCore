package dev.lyy.friendlycore.common.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.Transaction;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * SQLite database manager utilizing HikariCP with Write-Ahead Logging (WAL)
 * mode enabled for concurrency and crash resilience.
 */
public class SQLiteDatabase implements DatabaseManager {

    private final FriendlyCore plugin;
    private HikariDataSource dataSource;
    private final java.util.Set<String> incompatibleAuctionWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SQLiteDatabase(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    private ItemStack deserializeAuctionStack(byte[] bytes, String context) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException incompatible) {
            if (incompatibleAuctionWarnings.add(context)) {
                plugin.getLogger().log(Level.WARNING,
                        "Skipping " + context + " because its item data is not supported by this server version", incompatible);
            }
            return null;
        }
    }

    @Override
    public void initialize() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File dbFile = new File(dataFolder, "database.db");

        HikariConfig config = new HikariConfig();
        config.setPoolName("FriendlyCore-SQLite");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(30000);
        // Driver properties: a multi-statement init SQL runs only its first PRAGMA.
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_economy (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    balance DOUBLE NOT NULL DEFAULT 0.0,
                    pay_enabled BOOLEAN NOT NULL DEFAULT 1,
                    last_updated BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_eco_balance ON friendlycore_economy(balance DESC);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_eco_username ON friendlycore_economy(username);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sender_uuid VARCHAR(36),
                    sender_name VARCHAR(36),
                    receiver_uuid VARCHAR(36),
                    receiver_name VARCHAR(36),
                    amount DOUBLE NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_tx_sender ON friendlycore_transactions(sender_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_tx_receiver ON friendlycore_transactions(receiver_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_tx_timestamp ON friendlycore_transactions(timestamp DESC);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_stats (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    kills INT NOT NULL DEFAULT 0,
                    deaths INT NOT NULL DEFAULT 0,
                    streak INT NOT NULL DEFAULT 0,
                    highest_streak INT NOT NULL DEFAULT 0
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_stats_kills ON friendlycore_stats(kills DESC);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_tpa_profiles (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    tpa_enabled BOOLEAN NOT NULL DEFAULT 1,
                    tphere_enabled BOOLEAN NOT NULL DEFAULT 1,
                    tpauto_enabled BOOLEAN NOT NULL DEFAULT 0,
                    show_gui BOOLEAN NOT NULL DEFAULT 1,
                    daily_count INT NOT NULL DEFAULT 0,
                    last_teleport_day VARCHAR(16) NOT NULL,
                    last_updated BIGINT NOT NULL
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_player_kits (
                    uuid VARCHAR(36) NOT NULL,
                    username VARCHAR(16) NOT NULL,
                    kit_name VARCHAR(64) NOT NULL,
                    cooldown_until BIGINT NOT NULL DEFAULT 0,
                    one_time_claimed BOOLEAN NOT NULL DEFAULT 0,
                    bought BOOLEAN NOT NULL DEFAULT 0,
                    last_updated BIGINT NOT NULL,
                    claim_version BIGINT NOT NULL DEFAULT 0,
                    reservation_token VARCHAR(36),
                    reservation_expires_at BIGINT NOT NULL DEFAULT 0,
                    reservation_prev_cooldown BIGINT NOT NULL DEFAULT 0,
                    reservation_prev_one_time BOOLEAN NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, kit_name)
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_kits_name ON friendlycore_player_kits(kit_name);");
            addColumnIfMissing(stmt, "friendlycore_player_kits", "claim_version", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "friendlycore_player_kits", "reservation_token", "VARCHAR(36) NULL");
            addColumnIfMissing(stmt, "friendlycore_player_kits", "reservation_expires_at", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "friendlycore_player_kits", "reservation_prev_cooldown", "BIGINT NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "friendlycore_player_kits", "reservation_prev_one_time", "BOOLEAN NOT NULL DEFAULT 0");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_nightvision (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT 1,
                    last_updated BIGINT NOT NULL
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_homes (
                    uuid VARCHAR(36) NOT NULL,
                    slot INT NOT NULL,
                    name VARCHAR(64) DEFAULT '',
                    server_id VARCHAR(64) NOT NULL DEFAULT '',
                    world VARCHAR(64) NOT NULL,
                    x DOUBLE NOT NULL,
                    y DOUBLE NOT NULL,
                    z DOUBLE NOT NULL,
                    yaw FLOAT NOT NULL DEFAULT 0.0,
                    pitch FLOAT NOT NULL DEFAULT 0.0,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (uuid, slot)
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_pets (
                    uuid VARCHAR(36) NOT NULL,
                    pet_id VARCHAR(64) NOT NULL,
                    unlocked_at BIGINT NOT NULL,
                    PRIMARY KEY (uuid, pet_id)
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_player_pet_settings (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    active_pet VARCHAR(64),
                    secondary_pet VARCHAR(64),
                    hide_pet BOOLEAN NOT NULL DEFAULT 0,
                    last_updated BIGINT NOT NULL
                );
            """);

            addColumnIfMissing(stmt, "friendlycore_player_pet_settings", "secondary_pet", "VARCHAR(64) NULL");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_chat_data (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    points INT NOT NULL DEFAULT 0,
                    messages_closed BOOLEAN NOT NULL DEFAULT 0,
                    last_infraction_time BIGINT NOT NULL DEFAULT 0
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_chat_ignored (
                    player_uuid VARCHAR(36) NOT NULL,
                    ignored_uuid VARCHAR(36) NOT NULL,
                    PRIMARY KEY (player_uuid, ignored_uuid)
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_chat_infractions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    message VARCHAR(256) NOT NULL,
                    points INT NOT NULL,
                    server VARCHAR(32) NOT NULL,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_chat_infractions_uuid ON friendlycore_chat_infractions(player_uuid);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_settings (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    scoreboard BOOLEAN NOT NULL DEFAULT 1,
                    chat BOOLEAN NOT NULL DEFAULT 1,
                    tpa BOOLEAN NOT NULL DEFAULT 1,
                    pm BOOLEAN NOT NULL DEFAULT 1,
                    nightvision BOOLEAN NOT NULL DEFAULT 0,
                    pay_alerts BOOLEAN NOT NULL DEFAULT 1,
                    sounds BOOLEAN NOT NULL DEFAULT 1,
                    last_updated BIGINT NOT NULL
                );
            """);

            // Auctions module tables (only create when module is enabled)
            if (plugin.getConfigService().getMainConfig().getBoolean("modules.auctions", false)) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_bytes BLOB NOT NULL,
                    seller_uuid VARCHAR(36) NOT NULL,
                    seller_name VARCHAR(36) NOT NULL,
                    price DOUBLE NOT NULL,
                    start_time BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_items_seller ON friendlycore_auction_items (seller_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_items_start ON friendlycore_auction_items (start_time);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_users (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    extra_limit INTEGER NOT NULL DEFAULT 0
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_bytes BLOB NOT NULL,
                    seller_uuid VARCHAR(36) NOT NULL,
                    seller_name VARCHAR(36) NOT NULL,
                    buyer_uuid VARCHAR(36) NOT NULL,
                    buyer_name VARCHAR(36) NOT NULL,
                    price DOUBLE NOT NULL,
                    sold_time BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_hist_seller ON friendlycore_auction_history (seller_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_hist_buyer ON friendlycore_auction_history (buyer_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_hist_time ON friendlycore_auction_history (sold_time DESC);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_deleted (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_bytes BLOB NOT NULL,
                    seller_uuid VARCHAR(36) NOT NULL,
                    seller_name VARCHAR(36) NOT NULL,
                    price DOUBLE NOT NULL,
                    start_time BIGINT NOT NULL,
                    deleted_time BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_del_seller ON friendlycore_auction_deleted (seller_uuid);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    actor_uuid VARCHAR(36) NOT NULL,
                    actor_name VARCHAR(36) NOT NULL,
                    action_type INTEGER NOT NULL,
                    extra TEXT,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_logs_actor ON friendlycore_auction_logs (actor_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_logs_time ON friendlycore_auction_logs (timestamp DESC);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_uuid VARCHAR(36) NOT NULL,
                    item_id INTEGER NOT NULL,
                    message TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_notif_user ON friendlycore_auction_notifications (user_uuid);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_notification_dedup (
                    dedupe_key VARCHAR(191) PRIMARY KEY,
                    created_at BIGINT NOT NULL
                );
            """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_auction_payouts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_uuid VARCHAR(36) NOT NULL,
                    amount DOUBLE NOT NULL,
                    reason VARCHAR(255),
                    created_at BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ah_payouts_user ON friendlycore_auction_payouts (user_uuid);");
            }

            // Orders module tables (only create when module is enabled)
            if (plugin.getConfigService().getMainConfig().getBoolean("modules.orders", false)) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_orders (
                    order_id VARCHAR(36) PRIMARY KEY,
                    creator_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    item_bytes BLOB NOT NULL,
                    total_amount BIGINT NOT NULL,
                    price_per_item DOUBLE NOT NULL,
                    delivered_amount BIGINT NOT NULL DEFAULT 0,
                    claimed_amount BIGINT NOT NULL DEFAULT 0,
                    total_paid DOUBLE NOT NULL DEFAULT 0.0,
                    creation_time BIGINT NOT NULL,
                    duration BIGINT NOT NULL,
                    cancelled BOOLEAN NOT NULL DEFAULT 0,
                    state INTEGER NOT NULL DEFAULT 0
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_orders_creator ON friendlycore_orders (creator_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_orders_state ON friendlycore_orders (state);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_orders_created ON friendlycore_orders (creation_time);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_order_deliveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    order_id VARCHAR(36) NOT NULL,
                    buyer_uuid VARCHAR(36) NOT NULL,
                    seller_uuid VARCHAR(36) NOT NULL,
                    seller_name VARCHAR(64) NOT NULL,
                    item_bytes BLOB NOT NULL,
                    quantity INTEGER NOT NULL,
                    delivered_at BIGINT NOT NULL,
                    claimed BOOLEAN NOT NULL DEFAULT 0
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_deliv_buyer ON friendlycore_order_deliveries (buyer_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_deliv_claimed ON friendlycore_order_deliveries (claimed);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_order_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    order_id VARCHAR(36) NOT NULL,
                    buyer_uuid VARCHAR(36) NOT NULL,
                    seller_uuid VARCHAR(36) NOT NULL,
                    seller_name VARCHAR(64) NOT NULL,
                    quantity INTEGER NOT NULL,
                    unit_price DOUBLE NOT NULL,
                    total_paid DOUBLE NOT NULL,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_hist_buyer ON friendlycore_order_history (buyer_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_hist_seller ON friendlycore_order_history (seller_uuid);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_order_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp BIGINT NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    order_id VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    item_type VARCHAR(64) NOT NULL,
                    amount BIGINT NOT NULL,
                    price DOUBLE NOT NULL,
                    details TEXT
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_logs_player ON friendlycore_order_logs (player_uuid);");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_logs_order ON friendlycore_order_logs (order_id);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_order_notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_uuid VARCHAR(36) NOT NULL,
                    order_id VARCHAR(36) NOT NULL,
                    message VARCHAR(512) NOT NULL,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_notif_user ON friendlycore_order_notifications (user_uuid);");

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_order_payouts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_uuid VARCHAR(36) NOT NULL,
                    amount DOUBLE NOT NULL,
                    reason VARCHAR(256) NOT NULL,
                    timestamp BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_order_payouts_user ON friendlycore_order_payouts (user_uuid);");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize SQLite tables", e);
        }
    }

    private void addColumnIfMissing(Statement stmt, String table, String column, String definition) throws SQLException {
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException failure) {
            if (!hasColumn(stmt.getConnection(), table, column)) throw failure;
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<AccountData> loadAccount(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_economy WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        double balance = rs.getDouble("balance");
                        boolean payEnabled = rs.getBoolean("pay_enabled");
                        long lastUpdated = rs.getLong("last_updated");

                        AccountData data = new AccountData(uuid, username != null ? username : name, balance);
                        data.setPayEnabled(payEnabled);
                        data.setLastUpdated(lastUpdated);
                        data.setDirty(false);
                        return data;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load account for " + uuid, e);
                throw new CompletionException(e);
            }
            double startBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
            AccountData fresh = new AccountData(uuid, username != null ? username : "Unknown", startBal);
            fresh.setDirty(true);
            return fresh;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<UUID> getUuidByUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            if (username == null || username.isEmpty()) return null;
            String sql = "SELECT uuid FROM friendlycore_economy WHERE LOWER(username) = LOWER(?) LIMIT 1";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("uuid"));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to query SQLite UUID for username: " + username, e);
            }
            return null;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveAccount(AccountData account) {
        if (account == null) return CompletableFuture.completedFuture(null);
        AccountData snapshot = account.snapshot();
        long now = Math.max(System.currentTimeMillis(), snapshot.getLastUpdated());
        snapshot.setLastUpdated(now);
        long version = snapshot.getCurrentVersion();
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    pay_enabled = excluded.pay_enabled,
                    last_updated = MAX(friendlycore_economy.last_updated, excluded.last_updated);
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, snapshot.getUuid().toString());
                ps.setString(2, snapshot.getUsername());
                ps.setDouble(3, snapshot.getBalance());
                ps.setBoolean(4, snapshot.isPayEnabled());
                ps.setLong(5, snapshot.getLastUpdated());
                ps.executeUpdate();
                account.markSaved(version);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save account for " + snapshot.getUuid(), e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveAccountsBatch(Collection<AccountData> accounts) {
        if (accounts == null || accounts.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> saveAccountsBatchSync(accounts), plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public void saveAccountsBatchSync(Collection<AccountData> accounts) {
        if (accounts == null || accounts.isEmpty()) return;
        List<AccountData> dirtyAccounts = new ArrayList<>();
        List<AccountData> snapshots = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AccountData account : accounts) {
            if (account != null && account.isDirty()) {
                dirtyAccounts.add(account);
                AccountData snap = account.snapshot();
                snap.setLastUpdated(Math.max(now, snap.getLastUpdated() + 1L));
                snapshots.add(snap);
                versions.add(snap.getCurrentVersion());
            }
        }
        if (snapshots.isEmpty()) return;

        String sql = """
            INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                balance = excluded.balance,
                pay_enabled = excluded.pay_enabled,
                last_updated = MAX(friendlycore_economy.last_updated, excluded.last_updated);
        """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                for (AccountData snap : snapshots) {
                    ps.setString(1, snap.getUuid().toString());
                    ps.setString(2, snap.getUsername());
                    ps.setDouble(3, snap.getBalance());
                    ps.setBoolean(4, snap.isPayEnabled());
                    ps.setLong(5, snap.getLastUpdated());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                for (int i = 0; i < dirtyAccounts.size(); i++) {
                    dirtyAccounts.get(i).markSaved(versions.get(i));
                }
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to batch save accounts", e);
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    @Override
    public CompletableFuture<Void> setPlayerBalance(UUID uuid, String username, double balance) {
        return CompletableFuture.runAsync(() -> {
            if (uuid == null) return;
            long now = System.currentTimeMillis();
            String sql = """
                INSERT INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = CASE WHEN excluded.username IS NOT NULL AND excluded.username != '' AND excluded.username != 'Unknown' THEN excluded.username ELSE friendlycore_economy.username END,
                    balance = excluded.balance,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username != null ? username : "Unknown");
                ps.setDouble(3, balance);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to set player balance in SQLite for " + uuid, e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> atomicAdjustBalance(UUID uuid, String username, double delta, double maxBalance) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        plugin.getScheduler().getAsyncExecutor().execute(() -> {
            if (future.isCancelled()) return;
            if (uuid == null || delta == 0.0 || Double.isNaN(delta) || Double.isInfinite(delta)) {
                future.complete(false);
                return;
            }
            double startBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
            long now = System.currentTimeMillis();
            String insertSql = """
                INSERT OR IGNORE INTO friendlycore_economy (uuid, username, balance, pay_enabled, last_updated)
                VALUES (?, ?, ?, 1, ?);
            """;
            String updateSql = """
                UPDATE friendlycore_economy
                SET balance = ROUND(balance + ?, 2),
                    last_updated = ?
                WHERE uuid = ?
                  AND (balance + ? >= 0.0)
                  AND (balance + ? <= ?);
            """;
            try (Connection conn = dataSource.getConnection()) {
                if (future.isCancelled()) return;
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement psIns = conn.prepareStatement(insertSql)) {
                        psIns.setString(1, uuid.toString());
                        psIns.setString(2, username != null ? username : "Unknown");
                        psIns.setDouble(3, startBal);
                        psIns.setLong(4, now);
                        psIns.executeUpdate();
                    }

                    int affected;
                    try (PreparedStatement psUpd = conn.prepareStatement(updateSql)) {
                        psUpd.setDouble(1, delta);
                        psUpd.setLong(2, now);
                        psUpd.setString(3, uuid.toString());
                        psUpd.setDouble(4, delta);
                        psUpd.setDouble(5, delta);
                        psUpd.setDouble(6, maxBalance);
                        affected = psUpd.executeUpdate();
                    }

                    if (future.isCancelled()) {
                        conn.rollback();
                        return;
                    }

                    if (affected > 0) {
                        conn.commit();
                        future.complete(true);
                    } else {
                        conn.rollback();
                        future.complete(false);
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed atomicAdjustBalance in SQLite for " + uuid, e);
                future.complete(false);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Unexpected error in atomicAdjustBalance in SQLite for " + uuid, t);
                future.complete(false);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<List<AccountData>> getTopBalances(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<AccountData> top = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_economy ORDER BY balance DESC LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String username = rs.getString("username");
                        double balance = rs.getDouble("balance");
                        AccountData data = new AccountData(uuid, username, balance);
                        data.setPayEnabled(rs.getBoolean("pay_enabled"));
                        data.setLastUpdated(rs.getLong("last_updated"));
                        data.setDirty(false);
                        top.add(data);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load top balances", e);
            }
            return top;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> logTransaction(Transaction tx) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_transactions (sender_uuid, sender_name, receiver_uuid, receiver_name, amount, type, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?);
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tx.getSenderUuid() != null ? tx.getSenderUuid().toString() : null);
                ps.setString(2, tx.getSenderName());
                ps.setString(3, tx.getReceiverUuid() != null ? tx.getReceiverUuid().toString() : null);
                ps.setString(4, tx.getReceiverName());
                ps.setDouble(5, tx.getAmount());
                ps.setString(6, tx.getType().name());
                ps.setLong(7, tx.getTimestamp());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to log transaction", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<Transaction>> getRecentTransactions(UUID uuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<Transaction> list = new ArrayList<>();
            String sql = """
                SELECT * FROM friendlycore_transactions
                WHERE sender_uuid = ? OR receiver_uuid = ?
                ORDER BY timestamp DESC LIMIT ?;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, uuid.toString());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String senderStr = rs.getString("sender_uuid");
                        UUID senderUuid = senderStr != null ? UUID.fromString(senderStr) : null;
                        String senderName = rs.getString("sender_name");
                        String recStr = rs.getString("receiver_uuid");
                        UUID recUuid = recStr != null ? UUID.fromString(recStr) : null;
                        String recName = rs.getString("receiver_name");
                        double amount = rs.getDouble("amount");
                        Transaction.Type type;
                        try {
                            type = Transaction.Type.valueOf(rs.getString("type"));
                        } catch (Exception ignored) {
                            type = Transaction.Type.SYSTEM;
                        }
                        long timestamp = rs.getLong("timestamp");

                        list.add(new Transaction(id, senderUuid, senderName, recUuid, recName, amount, type, timestamp));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to fetch transactions for " + uuid, e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<PlayerStats> loadStats(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_stats WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        int kills = rs.getInt("kills");
                        int deaths = rs.getInt("deaths");
                        int streak = rs.getInt("streak");
                        int highestStreak = rs.getInt("highest_streak");
                        PlayerStats stats = new PlayerStats(uuid, username != null ? username : name, kills, deaths, streak, highestStreak);
                        stats.setDirty(false);
                        return stats;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player stats for " + uuid, e);
                throw new CompletionException(e);
            }
            PlayerStats fresh = new PlayerStats(uuid, username != null ? username : "Unknown", 0, 0, 0, 0);
            fresh.setDirty(true);
            return fresh;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveStatsBatch(Collection<PlayerStats> statsCollection) {
        if (statsCollection == null || statsCollection.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            List<PlayerStats> dirtyStats = new ArrayList<>();
            List<Long> versions = new ArrayList<>();
            for (PlayerStats stats : statsCollection) {
                if (stats != null && stats.isDirty()) {
                    dirtyStats.add(stats);
                    versions.add(stats.getCurrentVersion());
                }
            }
            if (dirtyStats.isEmpty()) return;

            String sql = """
                INSERT INTO friendlycore_stats (uuid, username, kills, deaths, streak, highest_streak)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    kills = excluded.kills,
                    deaths = excluded.deaths,
                    streak = excluded.streak,
                    highest_streak = excluded.highest_streak;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                conn.setAutoCommit(false);
                try {
                    for (PlayerStats stats : dirtyStats) {
                        ps.setString(1, stats.getUuid().toString());
                        ps.setString(2, stats.getUsername());
                        ps.setInt(3, stats.getKills());
                        ps.setInt(4, stats.getDeaths());
                        ps.setInt(5, stats.getCurrentStreak());
                        ps.setInt(6, stats.getHighestStreak());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                    for (int i = 0; i < dirtyStats.size(); i++) {
                        dirtyStats.get(i).markSaved(versions.get(i));
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save stats", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.tpa.TpaProfile> loadTpaProfile(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_tpa_profiles WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("username");
                        boolean tpaEnabled = rs.getBoolean("tpa_enabled");
                        boolean tpaHereEnabled = rs.getBoolean("tphere_enabled");
                        boolean tpAutoEnabled = rs.getBoolean("tpauto_enabled");
                        boolean showGui = rs.getBoolean("show_gui");
                        int dailyCount = rs.getInt("daily_count");
                        String lastDay = rs.getString("last_teleport_day");
                        long lastUpdated = rs.getLong("last_updated");
                        return new dev.lyy.friendlycore.modules.tpa.TpaProfile(uuid, name, tpaEnabled, tpaHereEnabled, tpAutoEnabled, showGui, dailyCount, lastDay, lastUpdated);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load TPA profile for " + username, e);
                throw new CompletionException(e);
            }
            return new dev.lyy.friendlycore.modules.tpa.TpaProfile(uuid, username);
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveTpaProfile(dev.lyy.friendlycore.modules.tpa.TpaProfile profile) {
        return CompletableFuture.runAsync(() -> {
            if (profile == null) return;
            long ver = profile.getVersion();
            String sql = """
                INSERT INTO friendlycore_tpa_profiles (uuid, username, tpa_enabled, tphere_enabled, tpauto_enabled, show_gui, daily_count, last_teleport_day, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    tpa_enabled = excluded.tpa_enabled,
                    tphere_enabled = excluded.tphere_enabled,
                    tpauto_enabled = excluded.tpauto_enabled,
                    show_gui = excluded.show_gui,
                    daily_count = excluded.daily_count,
                    last_teleport_day = excluded.last_teleport_day,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, profile.getUuid().toString());
                ps.setString(2, profile.getUsername());
                ps.setBoolean(3, profile.isTpaEnabled());
                ps.setBoolean(4, profile.isTpaHereEnabled());
                ps.setBoolean(5, profile.isTpAutoEnabled());
                ps.setBoolean(6, profile.isShowGui());
                ps.setInt(7, profile.getDailyCount());
                ps.setString(8, profile.getLastTeleportDay());
                ps.setLong(9, profile.getLastUpdated());
                ps.executeUpdate();
                profile.markSaved(ver);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save TPA profile for " + profile.getUsername(), e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveTpaProfilesBatch(Collection<dev.lyy.friendlycore.modules.tpa.TpaProfile> profiles) {
        return CompletableFuture.runAsync(() -> {
            if (profiles == null || profiles.isEmpty()) return;
            List<dev.lyy.friendlycore.modules.tpa.TpaProfile> dirtyProfiles = new ArrayList<>();
            List<Long> versions = new ArrayList<>();
            for (dev.lyy.friendlycore.modules.tpa.TpaProfile p : profiles) {
                if (p.isDirty()) {
                    dirtyProfiles.add(p);
                    versions.add(p.getVersion());
                }
            }
            if (dirtyProfiles.isEmpty()) return;

            String sql = """
                INSERT INTO friendlycore_tpa_profiles (uuid, username, tpa_enabled, tphere_enabled, tpauto_enabled, show_gui, daily_count, last_teleport_day, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    tpa_enabled = excluded.tpa_enabled,
                    tphere_enabled = excluded.tphere_enabled,
                    tpauto_enabled = excluded.tpauto_enabled,
                    show_gui = excluded.show_gui,
                    daily_count = excluded.daily_count,
                    last_teleport_day = excluded.last_teleport_day,
                    last_updated = excluded.last_updated;
            """;

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (dev.lyy.friendlycore.modules.tpa.TpaProfile p : dirtyProfiles) {
                        ps.setString(1, p.getUuid().toString());
                        ps.setString(2, p.getUsername());
                        ps.setBoolean(3, p.isTpaEnabled());
                        ps.setBoolean(4, p.isTpaHereEnabled());
                        ps.setBoolean(5, p.isTpAutoEnabled());
                        ps.setBoolean(6, p.isShowGui());
                        ps.setInt(7, p.getDailyCount());
                        ps.setString(8, p.getLastTeleportDay());
                        ps.setLong(9, p.getLastUpdated());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                    for (int i = 0; i < dirtyProfiles.size(); i++) {
                        dirtyProfiles.get(i).markSaved(versions.get(i));
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save TPA profiles", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.kits.model.PlayerKitData> loadPlayerKitData(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            dev.lyy.friendlycore.modules.kits.model.PlayerKitData data = new dev.lyy.friendlycore.modules.kits.model.PlayerKitData(uuid, username);
            String sql = "SELECT * FROM friendlycore_player_kits WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String kitName = rs.getString("kit_name");
                        long cd = rs.getLong("cooldown_until");
                        boolean oneTime = rs.getBoolean("one_time_claimed");
                        boolean bought = rs.getBoolean("bought");
                        long updated = rs.getLong("last_updated");
                        data.addRecord(new dev.lyy.friendlycore.modules.kits.model.PlayerKitRecord(kitName, cd, oneTime, bought, updated));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load kit data for " + uuid, e);
                throw new CompletionException(e);
            }
            return data;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerKitData(dev.lyy.friendlycore.modules.kits.model.PlayerKitData data) {
        if (data == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            long ver = data.getCurrentVersion();
            String sql = """
                INSERT INTO friendlycore_player_kits (uuid, username, kit_name, cooldown_until, one_time_claimed, bought, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid, kit_name) DO UPDATE SET
                    username = excluded.username,
                    cooldown_until = CASE WHEN friendlycore_player_kits.reservation_token IS NULL AND excluded.last_updated >= friendlycore_player_kits.last_updated THEN MAX(friendlycore_player_kits.cooldown_until, excluded.cooldown_until) ELSE friendlycore_player_kits.cooldown_until END,
                    one_time_claimed = CASE WHEN friendlycore_player_kits.reservation_token IS NULL AND excluded.last_updated >= friendlycore_player_kits.last_updated THEN friendlycore_player_kits.one_time_claimed OR excluded.one_time_claimed ELSE friendlycore_player_kits.one_time_claimed END,
                    bought = CASE WHEN excluded.last_updated >= friendlycore_player_kits.last_updated THEN friendlycore_player_kits.bought OR excluded.bought ELSE friendlycore_player_kits.bought END,
                    last_updated = MAX(friendlycore_player_kits.last_updated, excluded.last_updated);
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                conn.setAutoCommit(false);
                try {
                    for (dev.lyy.friendlycore.modules.kits.model.PlayerKitRecord r : data.getRecords().values()) {
                        ps.setString(1, data.getUuid().toString());
                        ps.setString(2, data.getUsername());
                        ps.setString(3, r.getKitName().toLowerCase(Locale.ROOT));
                        ps.setLong(4, r.getCooldownUntil());
                        ps.setBoolean(5, r.isOneTimeClaimed());
                        ps.setBoolean(6, r.isBought());
                        ps.setLong(7, r.getLastClaimed());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                    data.markSaved(ver);
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player kit data for " + data.getUsername(), e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerKitDataBatch(Collection<dev.lyy.friendlycore.modules.kits.model.PlayerKitData> batch) {
        if (batch == null || batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            List<dev.lyy.friendlycore.modules.kits.model.PlayerKitData> dirty = new ArrayList<>();
            List<Long> versions = new ArrayList<>();
            for (dev.lyy.friendlycore.modules.kits.model.PlayerKitData d : batch) {
                if (d != null && d.isDirty()) {
                    dirty.add(d);
                    versions.add(d.getCurrentVersion());
                }
            }
            if (dirty.isEmpty()) return;

            String sql = """
                INSERT INTO friendlycore_player_kits (uuid, username, kit_name, cooldown_until, one_time_claimed, bought, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid, kit_name) DO UPDATE SET
                    username = excluded.username,
                    cooldown_until = CASE WHEN friendlycore_player_kits.reservation_token IS NULL AND excluded.last_updated >= friendlycore_player_kits.last_updated THEN MAX(friendlycore_player_kits.cooldown_until, excluded.cooldown_until) ELSE friendlycore_player_kits.cooldown_until END,
                    one_time_claimed = CASE WHEN friendlycore_player_kits.reservation_token IS NULL AND excluded.last_updated >= friendlycore_player_kits.last_updated THEN friendlycore_player_kits.one_time_claimed OR excluded.one_time_claimed ELSE friendlycore_player_kits.one_time_claimed END,
                    bought = CASE WHEN excluded.last_updated >= friendlycore_player_kits.last_updated THEN friendlycore_player_kits.bought OR excluded.bought ELSE friendlycore_player_kits.bought END,
                    last_updated = MAX(friendlycore_player_kits.last_updated, excluded.last_updated);
            """;

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (dev.lyy.friendlycore.modules.kits.model.PlayerKitData d : dirty) {
                        for (dev.lyy.friendlycore.modules.kits.model.PlayerKitRecord r : d.getRecords().values()) {
                            ps.setString(1, d.getUuid().toString());
                            ps.setString(2, d.getUsername());
                            ps.setString(3, r.getKitName().toLowerCase(Locale.ROOT));
                            ps.setLong(4, r.getCooldownUntil());
                            ps.setBoolean(5, r.isOneTimeClaimed());
                            ps.setBoolean(6, r.isBought());
                            ps.setLong(7, r.getLastClaimed());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                    conn.commit();
                    for (int i = 0; i < dirty.size(); i++) {
                        dirty.get(i).markSaved(versions.get(i));
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save player kit data", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> resetPlayerKitData(String kitName, UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql;
            if (uuid != null) {
                sql = "DELETE FROM friendlycore_player_kits WHERE LOWER(kit_name) = LOWER(?) AND uuid = ?";
            } else {
                sql = "DELETE FROM friendlycore_player_kits WHERE LOWER(kit_name) = LOWER(?)";
            }

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, kitName);
                if (uuid != null) {
                    ps.setString(2, uuid.toString());
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset player kit data for kit " + kitName, e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<KitClaimReservation> reserveKitClaim(UUID uuid, String username, String kitName,
                                                                   long cooldownUntil, boolean oneTime) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return KitClaimReservationStore.reserve(conn, false, uuid, username, kitName, cooldownUntil, oneTime);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reserve kit claim for " + uuid + "/" + kitName, e);
                return KitClaimReservation.rejected(KitClaimReservation.Status.ERROR, 0, false, 0);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> completeKitClaimReservation(UUID uuid, String kitName, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return KitClaimReservationStore.complete(conn, uuid, kitName, token);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to complete kit claim reservation", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> rollbackKitClaimReservation(UUID uuid, String kitName, String token) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return KitClaimReservationStore.rollback(conn, uuid, kitName, token);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to roll back kit claim reservation", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> loadNightVision(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT enabled FROM friendlycore_nightvision WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("enabled");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load night vision for " + uuid, e);
                throw new CompletionException(e);
            }
            return null;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveNightVision(UUID uuid, String username, boolean enabled) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_nightvision (uuid, username, enabled, last_updated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    enabled = excluded.enabled,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, username != null ? username : "Unknown");
                ps.setBoolean(3, enabled);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save night vision for " + uuid, e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveNightVisionBatch(java.util.Map<UUID, Boolean> batch) {
        if (batch == null || batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_nightvision (uuid, username, enabled, last_updated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    enabled = excluded.enabled,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    long now = System.currentTimeMillis();
                    for (java.util.Map.Entry<UUID, Boolean> entry : batch.entrySet()) {
                        ps.setString(1, entry.getKey().toString());
                        ps.setString(2, "Unknown");
                        ps.setBoolean(3, entry.getValue());
                        ps.setLong(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save night vision data", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<java.util.Map<UUID, Boolean>> loadAllNightVision() {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<UUID, Boolean> map = new java.util.concurrent.ConcurrentHashMap<>();
            String sql = "SELECT uuid, enabled FROM friendlycore_nightvision";
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        map.put(uuid, rs.getBoolean("enabled"));
                    } catch (IllegalArgumentException ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load all night vision data", e);
                throw new CompletionException(e);
            }
            return map;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.homes.model.PlayerHomes> loadPlayerHomes(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            dev.lyy.friendlycore.modules.homes.model.PlayerHomes profile = new dev.lyy.friendlycore.modules.homes.model.PlayerHomes(uuid, username);
            String sql = "SELECT * FROM friendlycore_homes WHERE uuid = ? ORDER BY slot ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int slot = rs.getInt("slot");
                        String name = rs.getString("name");
                        String serverId = rs.getString("server_id");
                        String world = rs.getString("world");
                        double x = rs.getDouble("x");
                        double y = rs.getDouble("y");
                        double z = rs.getDouble("z");
                        float yaw = rs.getFloat("yaw");
                        float pitch = rs.getFloat("pitch");
                        long createdAt = rs.getLong("created_at");

                        dev.lyy.friendlycore.modules.homes.model.HomeData home = new dev.lyy.friendlycore.modules.homes.model.HomeData(
                                uuid, slot, name, serverId, world, x, y, z, yaw, pitch, createdAt
                        );
                        profile.getHomes().put(slot, home);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load SQLite homes for " + uuid, e);
                throw new CompletionException(e);
            }
            return profile;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerHomes(dev.lyy.friendlycore.modules.homes.model.PlayerHomes profile) {
        if (profile == null) return CompletableFuture.completedFuture(null);
        return savePlayerHomesBatch(List.of(profile));
    }

    @Override
    public CompletableFuture<Void> savePlayerHomesBatch(Collection<dev.lyy.friendlycore.modules.homes.model.PlayerHomes> batch) {
        if (batch == null || batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String insertSql = "INSERT INTO friendlycore_homes (uuid, slot, name, server_id, world, x, y, z, yaw, pitch, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(uuid, slot) DO UPDATE SET " +
                    "name = excluded.name, " +
                    "server_id = excluded.server_id, " +
                    "world = excluded.world, " +
                    "x = excluded.x, " +
                    "y = excluded.y, " +
                    "z = excluded.z, " +
                    "yaw = excluded.yaw, " +
                    "pitch = excluded.pitch, " +
                    "created_at = excluded.created_at";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement psIns = conn.prepareStatement(insertSql)) {
                        for (dev.lyy.friendlycore.modules.homes.model.PlayerHomes p : batch) {
                            String uuidStr = p.getPlayerUuid().toString();
                            for (dev.lyy.friendlycore.modules.homes.model.HomeData home : p.getHomes().values()) {
                                psIns.setString(1, uuidStr);
                                psIns.setInt(2, home.getSlot());
                                psIns.setString(3, home.getName() != null ? home.getName() : "");
                                psIns.setString(4, home.getServerId() != null ? home.getServerId() : "");
                                psIns.setString(5, home.getWorldName());
                                psIns.setDouble(6, home.getX());
                                psIns.setDouble(7, home.getY());
                                psIns.setDouble(8, home.getZ());
                                psIns.setFloat(9, home.getYaw());
                                psIns.setFloat(10, home.getPitch());
                                psIns.setLong(11, home.getCreatedAt());
                                psIns.addBatch();
                            }
                        }
                        psIns.executeBatch();
                    }

                    conn.commit();
                    for (dev.lyy.friendlycore.modules.homes.model.PlayerHomes p : batch) {
                        if (p != null) p.setDirty(false);
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save SQLite player homes", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> deleteHome(UUID uuid, int slot) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM friendlycore_homes WHERE uuid = ? AND slot = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, slot);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete SQLite home slot " + slot + " for " + uuid, e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> deleteAllHomes(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM friendlycore_homes WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete all SQLite homes for " + uuid, e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> resetEconomyData() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM friendlycore_economy;");
                stmt.executeUpdate("DELETE FROM friendlycore_transactions;");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset SQLite economy data", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> pruneTransactions(long olderThanTimestamp) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM friendlycore_transactions WHERE timestamp < ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, olderThanTimestamp);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to prune SQLite transactions", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.pets.model.PlayerPetData> loadPlayerPetData(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sqlSettings = "SELECT * FROM friendlycore_player_pet_settings WHERE uuid = ?";
            String sqlPets = "SELECT * FROM friendlycore_pets WHERE uuid = ?";
            String activePet = null;
            String secondaryPet = null;
            boolean hidePet = false;
            long lastUpdated = System.currentTimeMillis();
            String resolvedName = username != null ? username : "";

            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(sqlSettings)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            resolvedName = rs.getString("username");
                            activePet = rs.getString("active_pet");
                            secondaryPet = rs.getString("secondary_pet");
                            hidePet = rs.getBoolean("hide_pet");
                            lastUpdated = rs.getLong("last_updated");
                        }
                    }
                }

                java.util.Set<String> unlocked = new java.util.HashSet<>();
                java.util.Map<String, Long> unlockedAt = new java.util.HashMap<>();
                try (PreparedStatement ps = conn.prepareStatement(sqlPets)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String petId = rs.getString("pet_id");
                            long time = rs.getLong("unlocked_at");
                            if (petId != null && !petId.isBlank()) {
                                unlocked.add(petId.toLowerCase());
                                unlockedAt.put(petId.toLowerCase(), time);
                            }
                        }
                    }
                }
                return new dev.lyy.friendlycore.modules.pets.model.PlayerPetData(uuid, resolvedName, unlocked, unlockedAt, activePet, secondaryPet, hidePet, lastUpdated);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load SQLite pet data for " + uuid, e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerPetData(dev.lyy.friendlycore.modules.pets.model.PlayerPetData data) {
        if (data == null) return CompletableFuture.completedFuture(null);
        var snapshot = data.snapshot();
        return CompletableFuture.runAsync(() -> {
            String sqlSettings = """
                INSERT INTO friendlycore_player_pet_settings (uuid, username, active_pet, secondary_pet, hide_pet, last_updated)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    active_pet = excluded.active_pet,
                    secondary_pet = excluded.secondary_pet,
                    hide_pet = excluded.hide_pet,
                    last_updated = excluded.last_updated;
            """;
            String sqlInsertPet = """
                INSERT OR IGNORE INTO friendlycore_pets (uuid, pet_id, unlocked_at)
                VALUES (?, ?, ?);
            """;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(sqlSettings)) {
                        ps.setString(1, snapshot.getUuid().toString());
                        ps.setString(2, snapshot.getUsername());
                        ps.setString(3, snapshot.getActivePet());
                        ps.setString(4, snapshot.getSecondaryActivePet());
                        ps.setBoolean(5, snapshot.isHidePet());
                        ps.setLong(6, snapshot.getLastUpdated());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(sqlInsertPet)) {
                        for (String petId : snapshot.getUnlockedPets()) {
                            ps.setString(1, snapshot.getUuid().toString());
                            ps.setString(2, petId.toLowerCase());
                            ps.setLong(3, snapshot.getUnlockedAt(petId));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    conn.commit();
                    data.markSaved(snapshot.getLastUpdated());
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save SQLite pet data for " + snapshot.getUuid(), e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerPetDataBatch(Collection<dev.lyy.friendlycore.modules.pets.model.PlayerPetData> batch) {
        if (batch == null || batch.isEmpty()) return CompletableFuture.completedFuture(null);
        var sources = batch.stream().filter(java.util.Objects::nonNull).toList();
        var snapshots = sources.stream().map(dev.lyy.friendlycore.modules.pets.model.PlayerPetData::snapshot).toList();
        return CompletableFuture.runAsync(() -> {
            String sqlSettings = """
                INSERT INTO friendlycore_player_pet_settings (uuid, username, active_pet, secondary_pet, hide_pet, last_updated)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    active_pet = excluded.active_pet,
                    secondary_pet = excluded.secondary_pet,
                    hide_pet = excluded.hide_pet,
                    last_updated = excluded.last_updated;
            """;
            String sqlInsertPet = """
                INSERT OR IGNORE INTO friendlycore_pets (uuid, pet_id, unlocked_at)
                VALUES (?, ?, ?);
            """;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement psSettings = conn.prepareStatement(sqlSettings);
                         PreparedStatement psPets = conn.prepareStatement(sqlInsertPet)) {
                        for (dev.lyy.friendlycore.modules.pets.model.PlayerPetData data : snapshots) {
                            if (data == null) continue;
                            psSettings.setString(1, data.getUuid().toString());
                            psSettings.setString(2, data.getUsername());
                            psSettings.setString(3, data.getActivePet());
                            psSettings.setString(4, data.getSecondaryActivePet());
                            psSettings.setBoolean(5, data.isHidePet());
                            psSettings.setLong(6, data.getLastUpdated());
                            psSettings.addBatch();

                            for (String petId : data.getUnlockedPets()) {
                                psPets.setString(1, data.getUuid().toString());
                                psPets.setString(2, petId.toLowerCase());
                                psPets.setLong(3, data.getUnlockedAt(petId));
                                psPets.addBatch();
                            }
                        }
                        psSettings.executeBatch();
                        psPets.executeBatch();
                    }
                    conn.commit();
                    for (int index = 0; index < sources.size(); index++) {
                        sources.get(index).markSaved(snapshots.get(index).getLastUpdated());
                    }
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save SQLite pet data batch (" + batch.size() + ")", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> unlockPet(UUID uuid, String petId) {
        return CompletableFuture.supplyAsync(() -> {
            if (uuid == null || petId == null || petId.isBlank()) return false;
            String sql = "INSERT OR IGNORE INTO friendlycore_pets (uuid, pet_id, unlocked_at) VALUES (?, ?, ?);";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, petId.toLowerCase());
                ps.setLong(3, System.currentTimeMillis());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to unlock SQLite pet " + petId + " for " + uuid, e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> lockPet(UUID uuid, String petId) {
        return CompletableFuture.supplyAsync(() -> {
            if (uuid == null || petId == null || petId.isBlank()) return false;
            String sql = "DELETE FROM friendlycore_pets WHERE uuid = ? AND pet_id = ?;";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, petId.toLowerCase());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to lock SQLite pet " + petId + " for " + uuid, e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> setActivePet(UUID uuid, String petId) {
        return setActivePets(uuid, petId == null ? java.util.List.of() : java.util.List.of(petId));
    }

    @Override
    public CompletableFuture<Void> setActivePets(UUID uuid, java.util.List<String> petIds) {
        java.util.List<String> pets = java.util.List.copyOf(petIds);
        if (pets.size() > 2) throw new IllegalArgumentException("Only two pets can be equipped");
        return CompletableFuture.runAsync(() -> {
            if (uuid == null) return;
            String sql = """
                INSERT INTO friendlycore_player_pet_settings (uuid, username, active_pet, secondary_pet, hide_pet, last_updated)
                VALUES (?, '', ?, ?, 0, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    active_pet = excluded.active_pet,
                    secondary_pet = excluded.secondary_pet,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pets.isEmpty() ? null : pets.get(0));
                ps.setString(3, pets.size() < 2 ? null : pets.get(1));
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new CompletionException("Failed to set active pet in SQLite for " + uuid, e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> setHidePet(UUID uuid, boolean hide) {
        return CompletableFuture.runAsync(() -> {
            if (uuid == null) return;
            String sql = """
                INSERT INTO friendlycore_player_pet_settings (uuid, username, active_pet, hide_pet, last_updated)
                VALUES (?, '', NULL, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    hide_pet = excluded.hide_pet,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setBoolean(2, hide);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to set hide pet in SQLite for " + uuid, e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.chat.model.ChatPlayerData> loadChatPlayerData(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_chat_data WHERE uuid = ?";
            String ignSql = "SELECT ignored_uuid FROM friendlycore_chat_ignored WHERE player_uuid = ?";
            int points = 0;
            boolean closed = false;
            long lastInf = 0L;
            java.util.Set<UUID> ignored = new java.util.HashSet<>();
            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            points = rs.getInt("points");
                            closed = rs.getBoolean("messages_closed");
                            lastInf = rs.getLong("last_infraction_time");
                        }
                    }
                }
                try (PreparedStatement psIgn = conn.prepareStatement(ignSql)) {
                    psIgn.setString(1, uuid.toString());
                    try (ResultSet rs = psIgn.executeQuery()) {
                        while (rs.next()) {
                            try {
                                ignored.add(UUID.fromString(rs.getString("ignored_uuid")));
                            } catch (Exception ignoredEx) {}
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load chat player data for " + uuid, e);
                throw new CompletionException(e);
            }
            return new dev.lyy.friendlycore.modules.chat.model.ChatPlayerData(uuid, username, points, closed, ignored, lastInf);
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveChatPlayerData(dev.lyy.friendlycore.modules.chat.model.ChatPlayerData data) {
        return CompletableFuture.runAsync(() -> {
            if (data == null) return;
            String sql = """
                INSERT INTO friendlycore_chat_data (uuid, username, points, messages_closed, last_infraction_time)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    points = excluded.points,
                    messages_closed = excluded.messages_closed,
                    last_infraction_time = excluded.last_infraction_time;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, data.getUsername() != null ? data.getUsername() : "Unknown");
                ps.setInt(3, data.getPoints());
                ps.setBoolean(4, data.hasMessagesClosed());
                ps.setLong(5, data.getLastInfractionTime());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save chat player data for " + data.getUuid(), e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveChatPlayerDataBatch(Collection<dev.lyy.friendlycore.modules.chat.model.ChatPlayerData> batch) {
        return CompletableFuture.runAsync(() -> {
            if (batch == null || batch.isEmpty()) return;
            String sql = """
                INSERT INTO friendlycore_chat_data (uuid, username, points, messages_closed, last_infraction_time)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    points = excluded.points,
                    messages_closed = excluded.messages_closed,
                    last_infraction_time = excluded.last_infraction_time;
            """;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (dev.lyy.friendlycore.modules.chat.model.ChatPlayerData data : batch) {
                        ps.setString(1, data.getUuid().toString());
                        ps.setString(2, data.getUsername() != null ? data.getUsername() : "Unknown");
                        ps.setInt(3, data.getPoints());
                        ps.setBoolean(4, data.hasMessagesClosed());
                        ps.setLong(5, data.getLastInfractionTime());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save chat player data", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> addChatIgnored(UUID playerUuid, UUID ignoredUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO friendlycore_chat_ignored (player_uuid, ignored_uuid) VALUES (?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, ignoredUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add chat ignored in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> removeChatIgnored(UUID playerUuid, UUID ignoredUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM friendlycore_chat_ignored WHERE player_uuid = ? AND ignored_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, ignoredUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove chat ignored in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> clearChatIgnored(UUID playerUuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM friendlycore_chat_ignored WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to clear chat ignored in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> logChatInfraction(UUID playerUuid, String message, int points, String server) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_chat_infractions (player_uuid, message, points, server, timestamp) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, message != null && message.length() > 256 ? message.substring(0, 256) : (message != null ? message : ""));
                ps.setInt(3, points);
                ps.setString(4, server != null ? server : "global");
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to log chat infraction in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.settings.PlayerSettings> loadSettings(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_settings WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        java.util.Map<dev.lyy.friendlycore.modules.settings.SettingType, Boolean> map = new java.util.EnumMap<>(dev.lyy.friendlycore.modules.settings.SettingType.class);
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD, rs.getBoolean("scoreboard"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.CHAT, rs.getBoolean("chat"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.TPA, rs.getBoolean("tpa"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.PRIVATE_MESSAGES, rs.getBoolean("pm"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.NIGHT_VISION, rs.getBoolean("nightvision"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS, rs.getBoolean("pay_alerts"));
                        map.put(dev.lyy.friendlycore.modules.settings.SettingType.SOUNDS, rs.getBoolean("sounds"));
                        long lastUpdated = rs.getLong("last_updated");
                        return new dev.lyy.friendlycore.modules.settings.PlayerSettings(uuid, username, map, lastUpdated);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player settings from SQLite for " + uuid, e);
                throw new CompletionException(e);
            }
            return new dev.lyy.friendlycore.modules.settings.PlayerSettings(uuid, username);
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveSettings(dev.lyy.friendlycore.modules.settings.PlayerSettings settings) {
        if (settings == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_settings (uuid, username, scoreboard, chat, tpa, pm, nightvision, pay_alerts, sounds, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    scoreboard = excluded.scoreboard,
                    chat = excluded.chat,
                    tpa = excluded.tpa,
                    pm = excluded.pm,
                    nightvision = excluded.nightvision,
                    pay_alerts = excluded.pay_alerts,
                    sounds = excluded.sounds,
                    last_updated = excluded.last_updated;
            """;
            long version = settings.getVersion();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, settings.getUuid().toString());
                ps.setString(2, settings.getUsername());
                ps.setBoolean(3, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD));
                ps.setBoolean(4, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.CHAT));
                ps.setBoolean(5, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.TPA));
                ps.setBoolean(6, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.PRIVATE_MESSAGES));
                ps.setBoolean(7, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.NIGHT_VISION));
                ps.setBoolean(8, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS));
                ps.setBoolean(9, settings.get(dev.lyy.friendlycore.modules.settings.SettingType.SOUNDS));
                ps.setLong(10, settings.getLastUpdated());
                ps.executeUpdate();
                settings.markSaved(version);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player settings to SQLite for " + settings.getUuid(), e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveSettingsBatch(Collection<dev.lyy.friendlycore.modules.settings.PlayerSettings> batch) {
        if (batch == null || batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO friendlycore_settings (uuid, username, scoreboard, chat, tpa, pm, nightvision, pay_alerts, sounds, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    username = excluded.username,
                    scoreboard = excluded.scoreboard,
                    chat = excluded.chat,
                    tpa = excluded.tpa,
                    pm = excluded.pm,
                    nightvision = excluded.nightvision,
                    pay_alerts = excluded.pay_alerts,
                    sounds = excluded.sounds,
                    last_updated = excluded.last_updated;
            """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                conn.setAutoCommit(false);
                java.util.Map<dev.lyy.friendlycore.modules.settings.PlayerSettings, Long> versions = new java.util.HashMap<>();
                for (dev.lyy.friendlycore.modules.settings.PlayerSettings s : batch) {
                    if (s == null) continue;
                    long v = s.getVersion();
                    versions.put(s, v);
                    ps.setString(1, s.getUuid().toString());
                    ps.setString(2, s.getUsername());
                    ps.setBoolean(3, s.get(dev.lyy.friendlycore.modules.settings.SettingType.SCOREBOARD));
                    ps.setBoolean(4, s.get(dev.lyy.friendlycore.modules.settings.SettingType.CHAT));
                    ps.setBoolean(5, s.get(dev.lyy.friendlycore.modules.settings.SettingType.TPA));
                    ps.setBoolean(6, s.get(dev.lyy.friendlycore.modules.settings.SettingType.PRIVATE_MESSAGES));
                    ps.setBoolean(7, s.get(dev.lyy.friendlycore.modules.settings.SettingType.NIGHT_VISION));
                    ps.setBoolean(8, s.get(dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS));
                    ps.setBoolean(9, s.get(dev.lyy.friendlycore.modules.settings.SettingType.SOUNDS));
                    ps.setLong(10, s.getLastUpdated());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                conn.setAutoCommit(true);
                versions.forEach(dev.lyy.friendlycore.modules.settings.PlayerSettings::markSaved);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to batch save player settings to SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    // Auctions module operations

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem>> loadActiveAuctions() {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem> list = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_auction_items ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    byte[] bytes = rs.getBytes("item_bytes");
                    org.bukkit.inventory.ItemStack item = deserializeAuctionStack(bytes, "active auction #" + id);
                    UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                    String sellerName = rs.getString("seller_name");
                    double price = rs.getDouble("price");
                    long startTime = rs.getLong("start_time");

                    if (item != null) {
                        list.add(new dev.lyy.friendlycore.modules.auctions.model.AuctionItem(id, item, sellerUuid, sellerName, price, startTime));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load active auctions from SQLite", e);
                throw new CompletionException(e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Integer> insertAuctionItem(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_items (item_bytes, seller_uuid, seller_name, price, start_time) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBytes(1, item.getItemStack().serializeAsBytes());
                ps.setString(2, item.getSellerUuid().toString());
                ps.setString(3, item.getSellerName());
                ps.setDouble(4, item.getPrice());
                ps.setLong(5, item.getStartTime());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction item into SQLite", e);
            }
            return -1;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> purchaseAuctionItem(int id, double price, long earliestStart, UUID buyerUuid, String buyerName) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return dev.lyy.friendlycore.modules.auctions.storage.AuctionTransactions.purchase(
                        conn, id, price, earliestStart, buyerUuid.toString(), buyerName);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> expireAuctionItem(int id, long startTime) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement statement = conn.prepareStatement("UPDATE friendlycore_auction_items SET start_time = ? WHERE id = ?")) {
                statement.setLong(1, startTime);
                statement.setInt(2, id);
                return statement.executeUpdate() == 1;
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics> loadAuctionStatistics(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return dev.lyy.friendlycore.modules.auctions.storage.AuctionTransactions.statistics(conn, uuid);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> archiveAuctionItem(int id) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return dev.lyy.friendlycore.modules.auctions.storage.AuctionTransactions.archive(conn, id);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> removeAuctionItem(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM friendlycore_auction_items WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to remove auction item ID " + id + " from SQLite", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> updateAuctionItem(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE friendlycore_auction_items SET item_bytes = ?, seller_uuid = ?, seller_name = ?, price = ?, start_time = ? WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, item.getItemStack().serializeAsBytes());
                ps.setString(2, item.getSellerUuid().toString());
                ps.setString(3, item.getSellerName());
                ps.setDouble(4, item.getPrice());
                ps.setLong(5, item.getStartTime());
                ps.setInt(6, item.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to update auction item ID " + item.getId() + " in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionItem> loadAuctionItemById(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_auction_items WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] bytes = rs.getBytes("item_bytes");
                        org.bukkit.inventory.ItemStack item = deserializeAuctionStack(bytes, "auction history #" + id);
                        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                        String sellerName = rs.getString("seller_name");
                        double price = rs.getDouble("price");
                        long startTime = rs.getLong("start_time");
                        if (item != null) {
                            return new dev.lyy.friendlycore.modules.auctions.model.AuctionItem(id, item, sellerUuid, sellerName, price, startTime);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load auction item by ID from SQLite", e);
            }
            return null;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionUser> loadAuctionUser(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_auction_users WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String uname = rs.getString("username");
                        int extra = rs.getInt("extra_limit");
                        return new dev.lyy.friendlycore.modules.auctions.model.AuctionUser(uuid, uname, extra);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load auction user from SQLite", e);
                throw new CompletionException(e);
            }
            return new dev.lyy.friendlycore.modules.auctions.model.AuctionUser(uuid, name, 0);
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> saveAuctionUser(dev.lyy.friendlycore.modules.auctions.model.AuctionUser user) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_users (uuid, username, extra_limit) VALUES (?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, extra_limit = excluded.extra_limit";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, user.getUuid().toString());
                ps.setString(2, user.getName());
                ps.setInt(3, user.getExtraLimit());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save auction user in SQLite", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertAuctionHistory(dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry entry) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_history (item_bytes, seller_uuid, seller_name, buyer_uuid, buyer_name, price, sold_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, entry.getItemStack().serializeAsBytes());
                ps.setString(2, entry.getSellerUuid().toString());
                ps.setString(3, entry.getSellerName());
                ps.setString(4, entry.getBuyerUuid().toString());
                ps.setString(5, entry.getBuyerName());
                ps.setDouble(6, entry.getPrice());
                ps.setLong(7, entry.getSoldTime());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction history into SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry>> loadAuctionHistory(UUID userUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry> list = new ArrayList<>();
            String sql = userUuid == null ?
                    "SELECT * FROM friendlycore_auction_history ORDER BY sold_time DESC LIMIT ?" :
                    "SELECT * FROM friendlycore_auction_history WHERE seller_uuid = ? OR buyer_uuid = ? ORDER BY sold_time DESC LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                if (userUuid == null) {
                    ps.setInt(1, limit);
                } else {
                    ps.setString(1, userUuid.toString());
                    ps.setString(2, userUuid.toString());
                    ps.setInt(3, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        byte[] bytes = rs.getBytes("item_bytes");
                        org.bukkit.inventory.ItemStack item = deserializeAuctionStack(bytes, "deleted auction #" + id);
                        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                        String sellerName = rs.getString("seller_name");
                        UUID buyerUuid = UUID.fromString(rs.getString("buyer_uuid"));
                        String buyerName = rs.getString("buyer_name");
                        double price = rs.getDouble("price");
                        long soldTime = rs.getLong("sold_time");
                        if (item != null) {
                            list.add(new dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry(id, item, sellerUuid, sellerName, buyerUuid, buyerName, price, soldTime));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load auction history from SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertAuctionDeleted(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_deleted (item_bytes, seller_uuid, seller_name, price, start_time, deleted_time) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, item.getItemStack().serializeAsBytes());
                ps.setString(2, item.getSellerUuid().toString());
                ps.setString(3, item.getSellerName());
                ps.setDouble(4, item.getPrice());
                ps.setLong(5, item.getStartTime());
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction deleted item into SQLite", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem>> loadAuctionDeleted(UUID userUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem> list = new ArrayList<>();
            String sql = userUuid == null ?
                    "SELECT * FROM friendlycore_auction_deleted ORDER BY deleted_time DESC LIMIT ?" :
                    "SELECT * FROM friendlycore_auction_deleted WHERE seller_uuid = ? ORDER BY deleted_time DESC LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                if (userUuid == null) {
                    ps.setInt(1, limit);
                } else {
                    ps.setString(1, userUuid.toString());
                    ps.setInt(2, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        byte[] bytes = rs.getBytes("item_bytes");
                        org.bukkit.inventory.ItemStack item = deserializeAuctionStack(bytes, "auction item #" + id);
                        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                        String sellerName = rs.getString("seller_name");
                        double price = rs.getDouble("price");
                        long startTime = rs.getLong("start_time");
                        if (item != null) {
                            dev.lyy.friendlycore.modules.auctions.model.AuctionItem ai = new dev.lyy.friendlycore.modules.auctions.model.AuctionItem(id, item, sellerUuid, sellerName, price, startTime);
                            ai.setItemState(dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState.DELETED);
                            list.add(ai);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load deleted auctions from SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> removeAuctionDeleted(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM friendlycore_auction_deleted WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete archived item from SQLite", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertAuctionLog(dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry entry) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_logs (actor_uuid, actor_name, action_type, extra, timestamp) VALUES (?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, entry.getActorUuid().toString());
                ps.setString(2, entry.getActorName());
                ps.setInt(3, entry.getActionType().getId());
                ps.setString(4, entry.getExtra());
                ps.setLong(5, entry.getTimestamp());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction log into SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry>> loadAuctionLogs(UUID userUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry> list = new ArrayList<>();
            String sql = userUuid == null ?
                    "SELECT * FROM friendlycore_auction_logs ORDER BY timestamp DESC LIMIT ?" :
                    "SELECT * FROM friendlycore_auction_logs WHERE actor_uuid = ? ORDER BY timestamp DESC LIMIT ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                if (userUuid == null) {
                    ps.setInt(1, limit);
                } else {
                    ps.setString(1, userUuid.toString());
                    ps.setInt(2, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        UUID actorUuid = UUID.fromString(rs.getString("actor_uuid"));
                        String actorName = rs.getString("actor_name");
                        dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType actionType =
                                dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType.fromId(rs.getInt("action_type"));
                        String extra = rs.getString("extra");
                        long time = rs.getLong("timestamp");
                        list.add(new dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry(id, actorUuid, actorName, actionType, extra, time));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load auction logs from SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertAuctionNotification(UUID userUuid, int itemId, String message) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_notifications (user_uuid, item_id, message, created_at) VALUES (?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, userUuid.toString());
                ps.setInt(2, itemId);
                ps.setString(3, message);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction notification into SQLite", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> insertAuctionNotificationOnce(UUID userUuid, int itemId, String eventKey, String message) {
        return CompletableFuture.supplyAsync(() -> {
            String dedupeKey = userUuid + ":" + itemId + ":" + eventKey;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    int inserted;
                    try (PreparedStatement claim = conn.prepareStatement(
                            "INSERT OR IGNORE INTO friendlycore_auction_notification_dedup (dedupe_key, created_at) VALUES (?, ?)")) {
                        claim.setString(1, dedupeKey);
                        claim.setLong(2, System.currentTimeMillis());
                        inserted = claim.executeUpdate();
                    }
                    if (inserted == 1) {
                        try (PreparedStatement notification = conn.prepareStatement(
                                "INSERT INTO friendlycore_auction_notifications (user_uuid, item_id, message, created_at) VALUES (?, ?, ?, ?)")) {
                            notification.setString(1, userUuid.toString());
                            notification.setInt(2, itemId);
                            notification.setString(3, message);
                            notification.setLong(4, System.currentTimeMillis());
                            notification.executeUpdate();
                        }
                    }
                    conn.commit();
                    return inserted == 1;
                } catch (SQLException failure) {
                    conn.rollback();
                    throw failure;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<String>> fetchAndClearAuctionNotifications(UUID userUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> result = new ArrayList<>();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    List<Integer> ids = new ArrayList<>();
                    List<String> candidates = new ArrayList<>();
                    try (PreparedStatement select = conn.prepareStatement(
                            "SELECT * FROM friendlycore_auction_notifications WHERE user_uuid = ? ORDER BY id ASC")) {
                        select.setString(1, userUuid.toString());
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                ids.add(rs.getInt("id"));
                                candidates.add(rs.getString("message"));
                            }
                        }
                    }
                    try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM friendlycore_auction_notifications WHERE id = ? AND user_uuid = ?")) {
                        for (int i = 0; i < ids.size(); i++) {
                            delete.setInt(1, ids.get(i));
                            delete.setString(2, userUuid.toString());
                            if (delete.executeUpdate() == 1) result.add(candidates.get(i));
                        }
                    }
                    conn.commit();
                } catch (SQLException failure) {
                    conn.rollback();
                    throw failure;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new CompletionException(failure);
            }
            return result;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertAuctionPayout(UUID userUuid, double amount, String reason) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_auction_payouts (user_uuid, amount, reason, created_at) VALUES (?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, userUuid.toString());
                ps.setDouble(2, amount);
                ps.setString(3, reason);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert auction payout into SQLite", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionPayout>> fetchAndClearAuctionPayouts(UUID userUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                return dev.lyy.friendlycore.modules.auctions.storage.AuctionTransactions.claimPayouts(conn, userUuid);
            } catch (SQLException failure) {
                plugin.getLogger().log(Level.SEVERE, "Failed to claim auction payouts for " + userUuid + " from SQLite", failure);
                throw new CompletionException(failure);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadActiveOrders() {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.orders.model.BuyOrder> list = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_orders WHERE state = 0 AND cancelled = 0";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapOrderResultSet(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load active orders from SQLite", e);
                throw new CompletionException(e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadOrdersByCreator(UUID creatorUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.orders.model.BuyOrder> list = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_orders WHERE creator_uuid = ? ORDER BY creation_time DESC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, creatorUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapOrderResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load creator orders from SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<dev.lyy.friendlycore.modules.orders.model.BuyOrder> loadOrderById(UUID orderId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM friendlycore_orders WHERE order_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapOrderResultSet(rs);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load order by id from SQLite", e);
            }
            return null;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertOrder(dev.lyy.friendlycore.modules.orders.model.BuyOrder order) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_orders (order_id, creator_uuid, player_name, item_bytes, total_amount, price_per_item, delivered_amount, claimed_amount, total_paid, creation_time, duration, cancelled, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, order.getOrderId().toString());
                ps.setString(2, order.getCreatorId().toString());
                ps.setString(3, order.getPlayerName());
                ps.setBytes(4, order.getItem() != null ? order.getItem().serializeAsBytes() : new byte[0]);
                ps.setLong(5, order.getTotalAmount());
                ps.setDouble(6, order.getPricePerItem());
                ps.setLong(7, order.getDeliveredAmount());
                ps.setLong(8, order.getClaimedAmount());
                ps.setDouble(9, order.getTotalPaid());
                ps.setLong(10, order.getCreationTime());
                ps.setLong(11, order.getDuration());
                ps.setBoolean(12, order.isCancelled());
                ps.setInt(13, order.getState().getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert order into SQLite", e);
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertDelivery(dev.lyy.friendlycore.modules.orders.model.OrderDelivery delivery) {
        if (delivery == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            String insertDelivSql = "INSERT INTO friendlycore_order_deliveries (order_id, buyer_uuid, seller_uuid, seller_name, item_bytes, quantity, delivered_at, claimed) VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement psDeliv = conn.prepareStatement(insertDelivSql, Statement.RETURN_GENERATED_KEYS)) {
                psDeliv.setString(1, delivery.getOrderId().toString());
                psDeliv.setString(2, delivery.getBuyerUuid().toString());
                psDeliv.setString(3, delivery.getSellerUuid().toString());
                psDeliv.setString(4, delivery.getSellerName());
                psDeliv.setBytes(5, delivery.getItem() != null ? delivery.getItem().serializeAsBytes() : new byte[0]);
                psDeliv.setInt(6, delivery.getQuantity());
                psDeliv.setLong(7, delivery.getDeliveredAt());
                psDeliv.executeUpdate();
                try (ResultSet gk = psDeliv.getGeneratedKeys()) {
                    if (gk.next()) {
                        delivery.setId(gk.getInt(1));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert delivery in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> fulfillOrderAtomic(UUID orderId, long quantity, double payout,
                                                          dev.lyy.friendlycore.modules.orders.model.OrderDelivery delivery,
                                                          dev.lyy.friendlycore.modules.orders.model.OrderHistoryEntry history) {
        return CompletableFuture.supplyAsync(() -> {
            String updateOrderSql = "UPDATE friendlycore_orders SET delivered_amount = delivered_amount + ?, total_paid = total_paid + ?, state = CASE WHEN delivered_amount + ? >= total_amount THEN 1 ELSE 0 END WHERE order_id = ? AND delivered_amount + ? <= total_amount AND cancelled = 0 AND state = 0";
            String insertDelivSql = "INSERT INTO friendlycore_order_deliveries (order_id, buyer_uuid, seller_uuid, seller_name, item_bytes, quantity, delivered_at, claimed) VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
            String insertHistSql = "INSERT INTO friendlycore_order_history (order_id, buyer_uuid, seller_uuid, seller_name, quantity, unit_price, total_paid, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement psUpdate = conn.prepareStatement(updateOrderSql)) {
                        psUpdate.setLong(1, quantity);
                        psUpdate.setDouble(2, payout);
                        psUpdate.setLong(3, quantity);
                        psUpdate.setString(4, orderId.toString());
                        psUpdate.setLong(5, quantity);

                        int rows = psUpdate.executeUpdate();
                        if (rows == 0) {
                            conn.rollback();
                            return false;
                        }
                    }

                    if (delivery != null) {
                        try (PreparedStatement psDeliv = conn.prepareStatement(insertDelivSql, Statement.RETURN_GENERATED_KEYS)) {
                            psDeliv.setString(1, delivery.getOrderId().toString());
                            psDeliv.setString(2, delivery.getBuyerUuid().toString());
                            psDeliv.setString(3, delivery.getSellerUuid().toString());
                            psDeliv.setString(4, delivery.getSellerName());
                            psDeliv.setBytes(5, delivery.getItem() != null ? delivery.getItem().serializeAsBytes() : new byte[0]);
                            psDeliv.setInt(6, delivery.getQuantity());
                            psDeliv.setLong(7, delivery.getDeliveredAt());
                            psDeliv.executeUpdate();
                            try (ResultSet gk = psDeliv.getGeneratedKeys()) {
                                if (gk.next()) {
                                    delivery.setId(gk.getInt(1));
                                }
                            }
                        }
                    }

                    if (history != null) {
                        try (PreparedStatement psHist = conn.prepareStatement(insertHistSql)) {
                            psHist.setString(1, history.getOrderId().toString());
                            psHist.setString(2, history.getBuyerUuid().toString());
                            psHist.setString(3, history.getSellerUuid().toString());
                            psHist.setString(4, history.getSellerName());
                            psHist.setInt(5, history.getQuantity());
                            psHist.setDouble(6, history.getUnitPrice());
                            psHist.setDouble(7, history.getTotalPaid());
                            psHist.setLong(8, history.getTimestamp());
                            psHist.executeUpdate();
                        }
                    }

                    conn.commit();
                    return true;
                } catch (SQLException ex) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw ex;
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed atomic order fulfillment in SQLite", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> cancelOrderAtomic(UUID orderId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE friendlycore_orders SET cancelled = 1, state = 2 WHERE order_id = ? AND cancelled = 0 AND state = 0";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                int rows = ps.executeUpdate();
                return rows > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed atomic order cancel in SQLite", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> expireOrderAtomic(UUID orderId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "UPDATE friendlycore_orders SET state = 3 WHERE order_id = ? AND state = 0 AND cancelled = 0";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed atomic order expiration in SQLite", e);
                throw new CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadExpiredActiveOrders(long now) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.orders.model.BuyOrder> list = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_orders WHERE state = 0 AND cancelled = 0 AND (creation_time + duration) <= ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapOrderResultSet(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load expired active orders in SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> incrementClaimedAmount(UUID orderId, int amount) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE friendlycore_orders SET claimed_amount = claimed_amount + ? WHERE order_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, amount);
                ps.setString(2, orderId.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to increment claimed amount in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Boolean> deleteOrder(UUID orderId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM friendlycore_orders WHERE order_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orderId.toString());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete order in SQLite", e);
                return false;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.OrderDelivery>> loadUnclaimedDeliveries(UUID buyerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.orders.model.OrderDelivery> list = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_order_deliveries WHERE buyer_uuid = ? AND claimed = 0 ORDER BY id ASC";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, buyerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        UUID orderId = UUID.fromString(rs.getString("order_id"));
                        UUID sellerUuid = UUID.fromString(rs.getString("seller_uuid"));
                        String sellerName = rs.getString("seller_name");
                        byte[] itemBytes = rs.getBytes("item_bytes");
                        ItemStack item = itemBytes != null && itemBytes.length > 0 ? ItemStack.deserializeBytes(itemBytes) : null;
                        int quantity = rs.getInt("quantity");
                        long deliveredAt = rs.getLong("delivered_at");
                        list.add(new dev.lyy.friendlycore.modules.orders.model.OrderDelivery(id, orderId, buyerUuid, sellerUuid, sellerName, item, quantity, deliveredAt, false));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load unclaimed deliveries in SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<Integer>> claimDeliveries(UUID buyerUuid, List<Integer> deliveryIds) {
        return CompletableFuture.supplyAsync(() -> {
            List<Integer> claimedIds = new ArrayList<>();
            if (deliveryIds == null || deliveryIds.isEmpty()) return claimedIds;

            StringBuilder selectSql = new StringBuilder("SELECT id FROM friendlycore_order_deliveries WHERE buyer_uuid = ? AND claimed = 0 AND id IN (");
            for (int i = 0; i < deliveryIds.size(); i++) {
                selectSql.append(i > 0 ? ",?" : "?");
            }
            selectSql.append(")");

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement psSelect = conn.prepareStatement(selectSql.toString())) {
                        psSelect.setString(1, buyerUuid.toString());
                        for (int i = 0; i < deliveryIds.size(); i++) {
                            psSelect.setInt(i + 2, deliveryIds.get(i));
                        }
                        try (ResultSet rs = psSelect.executeQuery()) {
                            while (rs.next()) {
                                claimedIds.add(rs.getInt("id"));
                            }
                        }
                    }

                    if (!claimedIds.isEmpty()) {
                        StringBuilder updateSql = new StringBuilder("UPDATE friendlycore_order_deliveries SET claimed = 1 WHERE buyer_uuid = ? AND id IN (");
                        for (int i = 0; i < claimedIds.size(); i++) {
                            updateSql.append(i > 0 ? ",?" : "?");
                        }
                        updateSql.append(")");
                        try (PreparedStatement psUpdate = conn.prepareStatement(updateSql.toString())) {
                            psUpdate.setString(1, buyerUuid.toString());
                            for (int i = 0; i < claimedIds.size(); i++) {
                                psUpdate.setInt(i + 2, claimedIds.get(i));
                            }
                            psUpdate.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw e;
                } finally {
                    try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to claim deliveries in SQLite", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return claimedIds;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> restoreClaimedDeliveries(UUID buyerUuid, List<Integer> deliveryIds) {
        return CompletableFuture.runAsync(() -> {
            if (deliveryIds.isEmpty()) return;
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_order_deliveries SET claimed = 0 WHERE buyer_uuid = ? AND id = ? AND claimed = 1")) {
                    for (int id : deliveryIds) {
                        ps.setString(1, buyerUuid.toString());
                        ps.setInt(2, id);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<Void> insertOrderLog(dev.lyy.friendlycore.modules.orders.model.OrderLogEntry entry) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_order_logs (timestamp, action, order_id, player_uuid, player_name, item_type, amount, price, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, entry.getTimestamp());
                ps.setString(2, entry.getAction());
                ps.setString(3, entry.getOrderId().toString());
                ps.setString(4, entry.getPlayerId().toString());
                ps.setString(5, entry.getPlayerName());
                ps.setString(6, entry.getItemType());
                ps.setLong(7, entry.getAmount());
                ps.setDouble(8, entry.getPrice());
                ps.setString(9, entry.getDetails());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert order log in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }


    @Override
    public CompletableFuture<Void> insertOrderNotification(UUID userUuid, UUID orderId, String message) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO friendlycore_order_notifications (user_uuid, order_id, message, timestamp) VALUES (?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, userUuid.toString());
                ps.setString(2, orderId != null ? orderId.toString() : "");
                ps.setString(3, message);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert order notification in SQLite", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.OrderNotification>> fetchAndClearOrderNotifications(UUID userUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<dev.lyy.friendlycore.modules.orders.model.OrderNotification> list = new ArrayList<>();
            String selectSql = "SELECT * FROM friendlycore_order_notifications WHERE user_uuid = ? ORDER BY id ASC";
            String deleteSql = "DELETE FROM friendlycore_order_notifications WHERE user_uuid = ?";
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, userUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String oidStr = rs.getString("order_id");
                            UUID orderId = oidStr != null && !oidStr.isEmpty() ? UUID.fromString(oidStr) : null;
                            String message = rs.getString("message");
                            long ts = rs.getLong("timestamp");
                            list.add(new dev.lyy.friendlycore.modules.orders.model.OrderNotification(userUuid, orderId, message, ts));
                        }
                    }
                }
                if (!list.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                        ps.setString(1, userUuid.toString());
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to fetch/clear order notifications in SQLite", e);
            }
            return list;
        }, plugin.getScheduler().getAsyncExecutor());
    }


    private dev.lyy.friendlycore.modules.orders.model.BuyOrder mapOrderResultSet(ResultSet rs) throws SQLException {
        UUID orderId = UUID.fromString(rs.getString("order_id"));
        UUID creatorUuid = UUID.fromString(rs.getString("creator_uuid"));
        String playerName = rs.getString("player_name");
        byte[] itemBytes = rs.getBytes("item_bytes");
        ItemStack item = itemBytes != null && itemBytes.length > 0 ? ItemStack.deserializeBytes(itemBytes) : null;
        long totalAmount = rs.getLong("total_amount");
        double pricePerItem = rs.getDouble("price_per_item");
        long deliveredAmount = rs.getLong("delivered_amount");
        long claimedAmount = rs.getLong("claimed_amount");
        double totalPaid = rs.getDouble("total_paid");
        long creationTime = rs.getLong("creation_time");
        long duration = rs.getLong("duration");
        boolean cancelled = rs.getBoolean("cancelled");
        int stateId = rs.getInt("state");
        return new dev.lyy.friendlycore.modules.orders.model.BuyOrder(orderId, creatorUuid, playerName, item, totalAmount, pricePerItem, deliveredAmount, claimedAmount, totalPaid, creationTime, duration, cancelled, dev.lyy.friendlycore.modules.orders.enums.OrderState.fromId(stateId));
    }

    @Override
    public CompletableFuture<Void> resetAllData() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM friendlycore_economy;");
                stmt.executeUpdate("DELETE FROM friendlycore_transactions;");
                stmt.executeUpdate("DELETE FROM friendlycore_stats;");
                stmt.executeUpdate("DELETE FROM friendlycore_tpa_profiles;");
                stmt.executeUpdate("DELETE FROM friendlycore_player_kits;");
                stmt.executeUpdate("DELETE FROM friendlycore_nightvision;");
                stmt.executeUpdate("DELETE FROM friendlycore_homes;");
                stmt.executeUpdate("DELETE FROM friendlycore_pets;");
                stmt.executeUpdate("DELETE FROM friendlycore_player_pet_settings;");
                stmt.executeUpdate("DELETE FROM friendlycore_chat_data;");
                stmt.executeUpdate("DELETE FROM friendlycore_chat_ignored;");
                stmt.executeUpdate("DELETE FROM friendlycore_chat_infractions;");
                stmt.executeUpdate("DELETE FROM friendlycore_settings;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_items;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_users;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_history;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_deleted;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_logs;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_notifications;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_notification_dedup;");
                stmt.executeUpdate("DELETE FROM friendlycore_auction_payouts;");
                stmt.executeUpdate("DELETE FROM friendlycore_orders;");
                stmt.executeUpdate("DELETE FROM friendlycore_order_deliveries;");
                stmt.executeUpdate("DELETE FROM friendlycore_order_history;");
                stmt.executeUpdate("DELETE FROM friendlycore_order_logs;");
                stmt.executeUpdate("DELETE FROM friendlycore_order_notifications;");
                stmt.executeUpdate("DELETE FROM friendlycore_order_payouts;");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reset SQLite data", e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("SQLite DataSource is not initialized or closed");
        }
        return dataSource.getConnection();
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
