package dev.lyy.friendlycore.tools.converter;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Migration service for transferring external plugin databases (AxAuctions, DonutOrder,
 * VirtualSpawner, Lands) from local storage (H2 / SQLite) directly into MySQL.
 */
public class DatabaseConverterService {

    private final FriendlyCore plugin;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public DatabaseConverterService(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return RUNNING.get();
    }

    public CompletableFuture<ConvertResult> convertAll(CommandSender sender) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>A database conversion is already running.</gray>");
            return CompletableFuture.completedFuture(new ConvertResult("all", 0, 0, false, "Already running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                send(sender, "<gradient:#00d2ff:#3a7bd5><bold>✦ DATABASE CONVERTER</bold></gradient> <dark_gray>»</dark_gray> <gray>Starting migration for all plugins...</gray>");
                int totalTables = 0;
                int totalRows = 0;

                ConvertResult r1 = convertAxAuctionsInternal(null, sender);
                totalTables += r1.tablesMigrated();
                totalRows += r1.rowsMigrated();

                ConvertResult r2 = convertDonutOrderInternal(null, sender);
                totalTables += r2.tablesMigrated();
                totalRows += r2.rowsMigrated();

                ConvertResult r3 = convertVirtualSpawnerInternal(null, sender);
                totalTables += r3.tablesMigrated();
                totalRows += r3.rowsMigrated();

                ConvertResult r4 = convertLandsInternal(null, sender);
                totalTables += r4.tablesMigrated();
                totalRows += r4.rowsMigrated();

                send(sender, "<gradient:#00e676:#00b0ff><bold>✔ ALL PLUGINS CONVERTED</bold></gradient>");
                send(sender, " <gray>• Total tables migrated: <green>" + totalTables + "</green></gray>");
                send(sender, " <gray>• Total rows transferred: <aqua>" + totalRows + "</aqua></gray>");

                return new ConvertResult("all", totalTables, totalRows, true, null);
            } finally {
                RUNNING.set(false);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public CompletableFuture<ConvertResult> convertAxAuctions(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>A database conversion is already running.</gray>");
            return CompletableFuture.completedFuture(new ConvertResult("AxAuctions", 0, 0, false, "Already running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return convertAxAuctionsInternal(customFolder, sender);
            } finally {
                RUNNING.set(false);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public ConvertResult convertAxAuctionsInternal(File customFolder, CommandSender sender) {
        long start = System.currentTimeMillis();
        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[AxAuctions]</bold></gradient> <gray>Scanning for AxAuctions H2 database and config...</gray>");

        File baseFolder = customFolder;
        if (baseFolder == null || !baseFolder.exists()) {
            baseFolder = findFolder("AxAuctions");
        }

        if (baseFolder == null || !baseFolder.exists()) {
            String err = "AxAuctions folder not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("AxAuctions", 0, 0, false, err);
        }

        File configFile = new File(baseFolder, "config.yml");
        File h2DbFile = new File(baseFolder, "data.mv.db");
        if (!h2DbFile.exists()) {
            h2DbFile = new File(baseFolder, "data");
        }

        if (!configFile.exists()) {
            String err = "AxAuctions config.yml not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("AxAuctions", 0, 0, false, err);
        }

        if (!h2DbFile.exists()) {
            String err = "AxAuctions data.mv.db not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("AxAuctions", 0, 0, false, err);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String host = config.getString("database.address", "127.0.0.1");
        int port = config.getInt("database.port", 3306);
        String dbName = config.getString("database.database", "admin");
        String user = config.getString("database.username", "admin");
        String pass = config.getString("database.password", "admin");
        String prefix = config.getString("database.prefix", "axauctions");

        String h2Path = h2DbFile.getAbsolutePath().replace('\\', '/');
        if (h2Path.endsWith(".mv.db")) {
            h2Path = h2Path.substring(0, h2Path.length() - 6);
        }

        String h2Url = "jdbc:h2:" + h2Path + ";IFEXISTS=TRUE";
        String mySqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?allowPublicKeyRetrieval=true&useSSL=false";

        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[AxAuctions]</bold></gradient> <gray>Connecting to H2 and MySQL (" + host + ":" + port + "/" + dbName + ")...</gray>");

        int totalTables = 0;
        int totalRows = 0;

        try (Connection srcConn = SqlTableTransfer.createConnection(h2Url, null, null);
             Connection targetConn = SqlTableTransfer.createConnection(mySqlUrl, user, pass)) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, prefix);
            for (SqlTableTransfer.TableTransferResult tr : results) {
                if (tr.success()) {
                    totalTables++;
                    totalRows += tr.rowsMigrated();
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <aqua>" + tr.tableName() + "</aqua>: <green>" + tr.rowsMigrated() + "</green> rows transferred.</gray>");
                } else {
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <red>" + tr.tableName() + "</red> failed: " + tr.errorMessage() + "</gray>");
                }
            }

            config.set("database.type", "mysql");
            config.save(configFile);
            send(sender, " <gray>• Updated <aqua>AxAuctions/config.yml</aqua> <yellow>database.type</yellow> to <green>mysql</green>.</gray>");

            long elapsed = System.currentTimeMillis() - start;
            send(sender, "<gradient:#00e676:#00b0ff><bold>✔ AxAuctions Converted Successfully</bold></gradient> <gray>(" + elapsed + "ms)</gray>");
            return new ConvertResult("AxAuctions", totalTables, totalRows, true, null);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed converting AxAuctions to MySQL", e);
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>AxAuctions migration failed: " + e.getMessage() + "</gray>");
            return new ConvertResult("AxAuctions", totalTables, totalRows, false, e.getMessage());
        }
    }

    public CompletableFuture<ConvertResult> convertDonutOrder(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>A database conversion is already running.</gray>");
            return CompletableFuture.completedFuture(new ConvertResult("DonutOrder", 0, 0, false, "Already running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return convertDonutOrderInternal(customFolder, sender);
            } finally {
                RUNNING.set(false);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public ConvertResult convertDonutOrderInternal(File customFolder, CommandSender sender) {
        long start = System.currentTimeMillis();
        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[DonutOrder]</bold></gradient> <gray>Scanning for DonutOrder SQLite database and config...</gray>");

        File baseFolder = customFolder;
        if (baseFolder == null || !baseFolder.exists()) {
            baseFolder = findFolder("DonutOrder");
        }

        if (baseFolder == null || !baseFolder.exists()) {
            String err = "DonutOrder folder not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("DonutOrder", 0, 0, false, err);
        }

        File configFile = new File(baseFolder, "databases.yml");
        File sqliteFile = new File(baseFolder, "storage.db");

        if (!configFile.exists()) {
            String err = "DonutOrder databases.yml not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("DonutOrder", 0, 0, false, err);
        }

        if (!sqliteFile.exists()) {
            String err = "DonutOrder storage.db not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("DonutOrder", 0, 0, false, err);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String dbName = config.getString("mysql.database", "donutorder");
        String user = config.getString("mysql.user", "root");
        String pass = config.getString("mysql.password", "");
        boolean useSsl = config.getBoolean("mysql.use-ssl", false);

        String sqliteUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        String mySqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true";

        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[DonutOrder]</bold></gradient> <gray>Connecting to SQLite and MySQL (" + host + ":" + port + "/" + dbName + ")...</gray>");

        int totalTables = 0;
        int totalRows = 0;

        try (Connection srcConn = SqlTableTransfer.createConnection(sqliteUrl, null, null);
             Connection targetConn = SqlTableTransfer.createConnection(mySqlUrl, user, pass)) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
            for (SqlTableTransfer.TableTransferResult tr : results) {
                if (tr.success()) {
                    totalTables++;
                    totalRows += tr.rowsMigrated();
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <aqua>" + tr.tableName() + "</aqua>: <green>" + tr.rowsMigrated() + "</green> rows transferred.</gray>");
                } else {
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <red>" + tr.tableName() + "</red> failed: " + tr.errorMessage() + "</gray>");
                }
            }

            config.set("type", "mysql");
            config.save(configFile);
            send(sender, " <gray>• Updated <aqua>DonutOrder/databases.yml</aqua> <yellow>type</yellow> to <green>mysql</green>.</gray>");

            long elapsed = System.currentTimeMillis() - start;
            send(sender, "<gradient:#00e676:#00b0ff><bold>✔ DonutOrder Converted Successfully</bold></gradient> <gray>(" + elapsed + "ms)</gray>");
            return new ConvertResult("DonutOrder", totalTables, totalRows, true, null);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed converting DonutOrder to MySQL", e);
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>DonutOrder migration failed: " + e.getMessage() + "</gray>");
            return new ConvertResult("DonutOrder", totalTables, totalRows, false, e.getMessage());
        }
    }

    public CompletableFuture<ConvertResult> convertVirtualSpawner(CommandSender sender, File customFolder) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>A database conversion is already running.</gray>");
            return CompletableFuture.completedFuture(new ConvertResult("VirtualSpawner", 0, 0, false, "Already running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return convertVirtualSpawnerInternal(customFolder, sender);
            } finally {
                RUNNING.set(false);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public ConvertResult convertVirtualSpawnerInternal(File customFolder, CommandSender sender) {
        long start = System.currentTimeMillis();
        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[VirtualSpawner]</bold></gradient> <gray>Scanning for VirtualSpawner SQLite databases and config...</gray>");

        File baseFolder = customFolder;
        if (baseFolder == null || !baseFolder.exists()) {
            baseFolder = findFolder("VirtualSpawner");
        }

        if (baseFolder == null || !baseFolder.exists()) {
            String err = "VirtualSpawner folder not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("VirtualSpawner", 0, 0, false, err);
        }

        File configFile = new File(baseFolder, "databases.yml");
        File storageDbFile = new File(baseFolder, "storage.db");
        File logsDbFile = new File(baseFolder, "logs.db");

        if (!configFile.exists()) {
            String err = "VirtualSpawner databases.yml not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("VirtualSpawner", 0, 0, false, err);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String host = config.getString("mysql.host", "localhost");
        int port = config.getInt("mysql.port", 3306);
        String dbName = config.getString("mysql.database", "virtualspawner");
        String user = config.getString("mysql.user", "root");
        String pass = config.getString("mysql.password", "");
        boolean useSsl = config.getBoolean("mysql.use-ssl", false);

        String mySqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true";

        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[VirtualSpawner]</bold></gradient> <gray>Connecting to SQLite databases and MySQL (" + host + ":" + port + "/" + dbName + ")...</gray>");

        int totalTables = 0;
        int totalRows = 0;

        try (Connection targetConn = SqlTableTransfer.createConnection(mySqlUrl, user, pass)) {

            if (storageDbFile.exists()) {
                String sqliteUrl = "jdbc:sqlite:" + storageDbFile.getAbsolutePath();
                try (Connection srcConn = SqlTableTransfer.createConnection(sqliteUrl, null, null)) {
                    List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
                    for (SqlTableTransfer.TableTransferResult tr : results) {
                        if (tr.success()) {
                            totalTables++;
                            totalRows += tr.rowsMigrated();
                            send(sender, " <dark_gray>•</dark_gray> <gray>Table <aqua>" + tr.tableName() + "</aqua>: <green>" + tr.rowsMigrated() + "</green> rows transferred.</gray>");
                        }
                    }
                }
            }

            if (logsDbFile.exists()) {
                String sqliteUrl = "jdbc:sqlite:" + logsDbFile.getAbsolutePath();
                try (Connection srcConn = SqlTableTransfer.createConnection(sqliteUrl, null, null)) {
                    List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, null);
                    for (SqlTableTransfer.TableTransferResult tr : results) {
                        if (tr.success()) {
                            totalTables++;
                            totalRows += tr.rowsMigrated();
                            send(sender, " <dark_gray>•</dark_gray> <gray>Table <aqua>" + tr.tableName() + "</aqua> (from logs.db): <green>" + tr.rowsMigrated() + "</green> rows transferred.</gray>");
                        }
                    }
                }
            }

            config.set("type", "mysql");
            config.save(configFile);
            send(sender, " <gray>• Updated <aqua>VirtualSpawner/databases.yml</aqua> <yellow>type</yellow> to <green>mysql</green>.</gray>");

            long elapsed = System.currentTimeMillis() - start;
            send(sender, "<gradient:#00e676:#00b0ff><bold>✔ VirtualSpawner Converted Successfully</bold></gradient> <gray>(" + elapsed + "ms)</gray>");
            return new ConvertResult("VirtualSpawner", totalTables, totalRows, true, null);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed converting VirtualSpawner to MySQL", e);
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>VirtualSpawner migration failed: " + e.getMessage() + "</gray>");
            return new ConvertResult("VirtualSpawner", totalTables, totalRows, false, e.getMessage());
        }
    }

    public CompletableFuture<ConvertResult> convertLands(CommandSender sender, File customFolder) {
        return convertLands(sender, customFolder, null);
    }

    public CompletableFuture<ConvertResult> convertLands(CommandSender sender, File customFolder, String customServerId) {
        if (!RUNNING.compareAndSet(false, true)) {
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>A database conversion is already running.</gray>");
            return CompletableFuture.completedFuture(new ConvertResult("Lands", 0, 0, false, "Already running"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return convertLandsInternal(customFolder, customServerId, sender);
            } finally {
                RUNNING.set(false);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public ConvertResult convertLandsInternal(File customFolder, CommandSender sender) {
        return convertLandsInternal(customFolder, null, sender);
    }

    public ConvertResult convertLandsInternal(File customFolder, String customServerId, CommandSender sender) {
        long start = System.currentTimeMillis();
        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[Lands]</bold></gradient> <gray>Scanning for Lands SQLite database and config...</gray>");

        File baseFolder = customFolder;
        if (baseFolder == null || !baseFolder.exists()) {
            baseFolder = findFolder("Lands");
        }

        if (baseFolder == null || !baseFolder.exists()) {
            String err = "Lands folder not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("Lands", 0, 0, false, err);
        }

        File configFile = new File(baseFolder, "config.yml");
        File sqliteFile = new File(baseFolder, "Data/database_v2.db");
        if (!sqliteFile.exists()) {
            sqliteFile = new File(baseFolder, "Data/database.db");
            if (!sqliteFile.exists()) {
                sqliteFile = new File(baseFolder, "database_v2.db");
                if (!sqliteFile.exists()) {
                    sqliteFile = new File(baseFolder, "database.db");
                }
            }
        }

        if (!configFile.exists()) {
            String err = "Lands config.yml not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("Lands", 0, 0, false, err);
        }

        if (!sqliteFile.exists()) {
            String err = "Lands database_v2.db not found.";
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>" + err + "</gray>");
            return new ConvertResult("Lands", 0, 0, false, err);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String host = getFirstNonEmptyString(config,
                "database.mysql-v2.address",
                "database.mysql-v2.host",
                "database.mysql-v2.address_2",
                "localhost");
        int port = config.getInt("database.mysql-v2.port",
                config.getInt("database.mysql-v2.port_2", 3306));
        String dbName = getFirstNonEmptyString(config,
                "database.mysql-v2.database",
                "database.mysql-v2.database_2",
                "lands");
        String user = getFirstNonEmptyString(config,
                "database.mysql-v2.username",
                "database.mysql-v2.user",
                "database.mysql-v2.username_2",
                "root");
        String pass = config.getString("database.mysql-v2.password",
                config.getString("database.mysql-v2.password_2", ""));
        boolean useSsl = config.getBoolean("database.mysql-v2.use-ssl",
                config.getBoolean("database.mysql-v2.ssl_2",
                config.getBoolean("database.mysql-v2.ssl", false)));
        String prefix = getFirstNonEmptyString(config,
                "database.mysql-v2.table-prefix_2",
                "database.mysql-v2.table-prefix",
                "database.mysql-v2.prefix",
                "lands_");

        String resolvedServerId = customServerId;
        if (resolvedServerId == null || resolvedServerId.isBlank()) {
            resolvedServerId = getFirstNonEmptyString(config,
                    "server-id",
                    "general.server-name",
                    "database.mysql-v2.server-id",
                    plugin.getConfigService().getMainConfig().getString("redis.server-id", "economy-a"));
        }

        String sqliteUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
        String mySqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true";

        send(sender, "<gradient:#00d2ff:#3a7bd5><bold>[Lands]</bold></gradient> <gray>Connecting to SQLite (" + sqliteFile.getName() + ") and MySQL (" + host + ":" + port + "/" + dbName + ")...</gray>");

        int totalTables = 0;
        int totalRows = 0;

        try (Connection targetConn = SqlTableTransfer.createConnection(mySqlUrl, user, pass);
             Connection srcConn = SqlTableTransfer.createConnection(sqliteUrl, null, null)) {

            List<SqlTableTransfer.TableTransferResult> results = SqlTableTransfer.transferAllTables(srcConn, targetConn, prefix);
            for (SqlTableTransfer.TableTransferResult tr : results) {
                if (tr.success()) {
                    totalTables++;
                    totalRows += tr.rowsMigrated();
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <aqua>" + tr.tableName() + "</aqua>: <green>" + tr.rowsMigrated() + "</green> rows transferred.</gray>");
                } else {
                    send(sender, " <dark_gray>•</dark_gray> <gray>Table <red>" + tr.tableName() + "</red> failed: " + tr.errorMessage() + "</gray>");
                }
            }

            if (resolvedServerId != null && !resolvedServerId.isBlank()) {
                String normPrefix = prefix.endsWith("_") ? prefix : prefix + "_";
                String[] candidateTables = {
                        normPrefix + "chunks",
                        normPrefix + "claims",
                        normPrefix + "worlds",
                        normPrefix + "spawns",
                        normPrefix + "areas",
                        "chunks", "claims", "worlds", "spawns", "areas"
                };

                for (String tbl : candidateTables) {
                    try (java.sql.PreparedStatement ps = targetConn.prepareStatement(
                            "UPDATE `" + tbl + "` SET `server` = ? WHERE `server` = 'default' OR `server` = '' OR `server` IS NULL OR `server` = 'local'")) {
                        ps.setString(1, resolvedServerId);
                        int updated = ps.executeUpdate();
                        if (updated > 0) {
                            send(sender, " <gray>• Tagged <aqua>" + updated + "</aqua> rows in <yellow>" + tbl + "</yellow> with server-id: <green>" + resolvedServerId + "</green></gray>");
                        }
                    } catch (Exception ignored) {}
                }
            }

            config.set("database.mysql-v2.enabled", true);
            config.set("database.mysql-v2.enabled_19", true);
            config.set("database.sqlite-v2.enabled", false);
            config.set("database.sqlite-v2.enabled_19", false);
            if (resolvedServerId != null && !resolvedServerId.isBlank()) {
                config.set("server-id", resolvedServerId);
            }
            config.save(configFile);
            send(sender, " <gray>• Updated <aqua>Lands/config.yml</aqua> <yellow>database.mysql-v2.enabled</yellow> to <green>true</green>, <yellow>server-id</yellow> to <green>" + resolvedServerId + "</green>.</gray>");

            long elapsed = System.currentTimeMillis() - start;
            send(sender, "<gradient:#00e676:#00b0ff><bold>✔ Lands Converted Successfully</bold></gradient> <gray>(" + elapsed + "ms)</gray>");
            return new ConvertResult("Lands", totalTables, totalRows, true, null);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed converting Lands to MySQL", e);
            send(sender, "<gradient:#ff5252:#ff1744><bold>ERROR</bold></gradient> <dark_gray>»</dark_gray> <gray>Lands migration failed: " + e.getMessage() + "</gray>");
            return new ConvertResult("Lands", totalTables, totalRows, false, e.getMessage());
        }
    }

    private String getFirstNonEmptyString(YamlConfiguration config, String... paths) {
        for (int i = 0; i < paths.length - 1; i++) {
            String val = config.getString(paths[i]);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        return paths[paths.length - 1];
    }

    private File findFolder(String name) {
        File[] candidates = {
                new File(plugin.getDataFolder().getParentFile(), name),
                new File(plugin.getDataFolder().getParentFile(), name.toLowerCase(Locale.ROOT)),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/" + name),
                new File(plugin.getDataFolder().getParentFile(), "plugin-config/" + name.toLowerCase(Locale.ROOT)),
                new File("plugins/" + name),
                new File("plugins/" + name.toLowerCase(Locale.ROOT)),
                new File("plugin-config/" + name),
                new File("plugin-config/" + name.toLowerCase(Locale.ROOT)),
                new File(new File("."), "plugin-config/" + name),
                new File(new File("."), name)
        };

        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return null;
    }

    private void send(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(plugin.getTextService().parse(message));
        }
    }

    public record ConvertResult(String pluginName, int tablesMigrated, int rowsMigrated, boolean success, String errorMessage) {}
}
