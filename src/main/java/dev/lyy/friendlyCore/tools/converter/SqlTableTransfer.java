package dev.lyy.friendlycore.tools.converter;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic JDBC table replicator and type converter.
 * Streams records between source databases (H2, SQLite) and target MySQL instances
 * with dynamic schema mapping and batch transactions.
 */
public class SqlTableTransfer {

    private static final Logger LOGGER = Logger.getLogger(SqlTableTransfer.class.getName());
    private static final int DEFAULT_BATCH_SIZE = 500;

    static {
        registerDriverQuietly("org.h2.Driver");
        registerDriverQuietly("org.sqlite.JDBC");
        registerDriverQuietly("com.mysql.cj.jdbc.Driver");
    }

    private static void registerDriverQuietly(String driverClassName) {
        try {
            Class<?> clazz = Class.forName(driverClassName);
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
        } catch (Throwable ignored) {}
    }

    public static class DriverShim implements Driver {
        private final Driver driver;
        public DriverShim(Driver d) { this.driver = d; }
        @Override public boolean acceptsURL(String u) throws SQLException { return driver.acceptsURL(u); }
        @Override public Connection connect(String u, Properties p) throws SQLException { return driver.connect(u, p); }
        @Override public int getMajorVersion() { return driver.getMajorVersion(); }
        @Override public int getMinorVersion() { return driver.getMinorVersion(); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException { return driver.getPropertyInfo(u, p); }
        @Override public boolean jdbcCompliant() { return driver.jdbcCompliant(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return driver.getParentLogger(); }
    }

    /**
     * Creates a connection with direct driver instantiation to bypass isolated classloader limitations.
     */
    public static Connection createConnection(String url, String user, String pass) throws SQLException {
        Properties props = new Properties();
        if (user != null) props.setProperty("user", user);
        if (pass != null) props.setProperty("password", pass);

        try {
            if (url.startsWith("jdbc:h2:")) {
                try {
                    Class<?> driverClass = Class.forName("org.h2.Driver");
                    Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                    Connection conn = driver.connect(url, props);
                    if (conn != null) return conn;
                } catch (Throwable ignored) {}
            } else if (url.startsWith("jdbc:sqlite:")) {
                try {
                    Class<?> driverClass = Class.forName("org.sqlite.JDBC");
                    Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                    Connection conn = driver.connect(url, props);
                    if (conn != null) return conn;
                } catch (Throwable ignored) {}
            } else if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
                try {
                    Class<?> driverClass = Class.forName("com.mysql.cj.jdbc.Driver");
                    Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
                    Connection conn = driver.connect(url, props);
                    if (conn != null) return conn;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return DriverManager.getConnection(url, props);
    }

    public record TableTransferResult(String tableName, int rowsMigrated, boolean success, String errorMessage) {}

    /**
     * Copies all tables from source connection to target MySQL connection.
     */
    public static List<TableTransferResult> transferAllTables(Connection srcConn, Connection targetConn, String tablePrefix) throws SQLException {
        List<TableTransferResult> results = new ArrayList<>();
        List<String> tables = getTableNames(srcConn);

        // Sort tables so parent tables come before dependent child tables
        tables.sort((t1, t2) -> Integer.compare(getTablePriority(t1), getTablePriority(t2)));

        boolean isMySQL = isMySQL(targetConn);
        if (isMySQL) {
            try (Statement stmt = targetConn.createStatement()) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");
                stmt.execute("SET UNIQUE_CHECKS = 0;");
            } catch (SQLException ignored) {}
        }

        try {
            for (String table : tables) {
                if (table.equalsIgnoreCase("sqlite_sequence") || table.equalsIgnoreCase("schema_version")) {
                    continue;
                }
                String prefix = (tablePrefix != null && !tablePrefix.isBlank()) ? tablePrefix.trim() : "";
                String targetTable;
                if (!prefix.isEmpty()) {
                    String normPrefix = prefix.endsWith("_") ? prefix : prefix + "_";
                    targetTable = table.toLowerCase(Locale.ROOT).startsWith(normPrefix.toLowerCase(Locale.ROOT))
                            ? table
                            : normPrefix + table;
                } else {
                    targetTable = table;
                }
                if (isMySQL) {
                    targetTable = targetTable.toLowerCase(Locale.ROOT);
                }

                try {
                    int count = transferSingleTable(srcConn, targetConn, table, targetTable);
                    results.add(new TableTransferResult(table, count, true, null));
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, "Failed transferring table: " + table, ex);
                    results.add(new TableTransferResult(table, 0, false, ex.getMessage()));
                }
            }
        } finally {
            if (isMySQL) {
                try (Statement stmt = targetConn.createStatement()) {
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
                    stmt.execute("SET UNIQUE_CHECKS = 1;");
                } catch (SQLException ignored) {}
            }
        }

        return results;
    }

    private static int getTablePriority(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        if (lower.contains("server")) return 1;
        if (lower.contains("world")) return 2;
        if (lower.contains("user") || lower.contains("player") || lower.contains("nation")) return 3;
        if (lower.contains("land") && !lower.contains("claim")) return 4;
        if (lower.contains("item") || lower.contains("order") || lower.contains("spawner")) return 5;
        return 10;
    }

    /**
     * Transfers a single table from source to target MySQL.
     */
    public static int transferSingleTable(Connection srcConn, Connection targetConn, String srcTable, String targetTable) throws SQLException {
        String sampleSql = "SELECT * FROM " + quoteIdentifier(srcTable) + " WHERE 1=0";
        List<ColumnDef> columns = new ArrayList<>();

        try (Statement stmt = srcConn.createStatement();
             ResultSet rs = stmt.executeQuery(sampleSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            for (int i = 1; i <= colCount; i++) {
                String colName = meta.getColumnName(i);
                int colType = meta.getColumnType(i);
                String typeName = meta.getColumnTypeName(i);
                int precision = meta.getPrecision(i);
                int scale = meta.getScale(i);
                columns.add(new ColumnDef(colName, colType, typeName, precision, scale));
            }
        }

        if (columns.isEmpty()) {
            return 0;
        }

        boolean isMySQL = isMySQL(targetConn);

        // Ensure table exists on target database
        createTableIfNotExists(srcConn, srcTable, targetConn, targetTable, columns, isMySQL);

        StringBuilder insertSql;
        if (isMySQL) {
            insertSql = new StringBuilder("INSERT INTO ").append(quoteMySQLIdentifier(targetTable)).append(" (");
            StringBuilder placeholders = new StringBuilder();
            StringBuilder onDuplicate = new StringBuilder(" ON DUPLICATE KEY UPDATE ");

            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                if (i > 0) {
                    insertSql.append(", ");
                    placeholders.append(", ");
                    onDuplicate.append(", ");
                }
                String qCol = quoteMySQLIdentifier(col.name);
                insertSql.append(qCol);
                placeholders.append("?");
                onDuplicate.append(qCol).append(" = VALUES(").append(qCol).append(")");
            }
            insertSql.append(") VALUES (").append(placeholders).append(")").append(onDuplicate);
        } else {
            insertSql = new StringBuilder("INSERT OR REPLACE INTO ").append(quoteIdentifier(targetTable)).append(" (");
            StringBuilder placeholders = new StringBuilder();

            for (int i = 0; i < columns.size(); i++) {
                ColumnDef col = columns.get(i);
                if (i > 0) {
                    insertSql.append(", ");
                    placeholders.append(", ");
                }
                insertSql.append(quoteIdentifier(col.name));
                placeholders.append("?");
            }
            insertSql.append(") VALUES (").append(placeholders).append(")");
        }

        int totalMigrated = 0;
        String selectSql = "SELECT * FROM " + quoteIdentifier(srcTable);

        targetConn.setAutoCommit(false);
        try (Statement selectStmt = srcConn.createStatement();
             ResultSet rs = selectStmt.executeQuery(selectSql);
             PreparedStatement insertPs = targetConn.prepareStatement(insertSql.toString())) {

            int currentBatch = 0;
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    ColumnDef col = columns.get(i);
                    Object val = rs.getObject(i + 1);

                    boolean isTimestamp = isTimestampColumn(col);
                    if (val == null) {
                        insertPs.setNull(i + 1, isTimestamp ? Types.TIMESTAMP : col.sqlType);
                    } else if (isTimestamp) {
                        Timestamp ts = parseTimestamp(val);
                        if (ts != null) {
                            insertPs.setTimestamp(i + 1, ts);
                        } else {
                            insertPs.setNull(i + 1, Types.TIMESTAMP);
                        }
                    } else if (val instanceof byte[] bytes) {
                        insertPs.setBytes(i + 1, bytes);
                    } else if (val instanceof Blob blob) {
                        insertPs.setBytes(i + 1, blob.getBytes(1, (int) blob.length()));
                    } else if (val instanceof Boolean b) {
                        insertPs.setBoolean(i + 1, b);
                    } else if (val instanceof Timestamp ts) {
                        insertPs.setTimestamp(i + 1, ts);
                    } else if (val instanceof Number num) {
                        insertPs.setObject(i + 1, num);
                    } else {
                        insertPs.setString(i + 1, val.toString());
                    }
                }

                insertPs.addBatch();
                currentBatch++;
                totalMigrated++;

                if (currentBatch >= DEFAULT_BATCH_SIZE) {
                    insertPs.executeBatch();
                    targetConn.commit();
                    currentBatch = 0;
                }
            }

            if (currentBatch > 0) {
                insertPs.executeBatch();
                targetConn.commit();
            }
        } catch (SQLException ex) {
            targetConn.rollback();
            throw ex;
        } finally {
            targetConn.setAutoCommit(true);
        }

        return totalMigrated;
    }

    private static void createTableIfNotExists(Connection srcConn, String srcTable, Connection targetConn, String tableName, List<ColumnDef> columns, boolean isMySQL) throws SQLException {
        String identifier = isMySQL ? quoteMySQLIdentifier(tableName) : quoteIdentifier(tableName);
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(identifier).append(" (\n");

        List<String> pkColumns = extractPrimaryKeys(srcConn, srcTable, columns);
        Set<String> pkSet = new HashSet<>();
        for (String pk : pkColumns) {
            pkSet.add(pk.toLowerCase(Locale.ROOT));
        }

        for (int i = 0; i < columns.size(); i++) {
            ColumnDef col = columns.get(i);
            if (i > 0) sql.append(",\n");

            boolean isPk = pkSet.contains(col.name.toLowerCase(Locale.ROOT));
            String colIdent = isMySQL ? quoteMySQLIdentifier(col.name) : quoteIdentifier(col.name);
            sql.append("  ").append(colIdent).append(" ").append(isMySQL ? mapToMySQLType(col, isPk) : mapToGenericType(col));
        }

        if (!pkColumns.isEmpty()) {
            StringBuilder pkList = new StringBuilder();
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) pkList.append(", ");
                pkList.append(isMySQL ? quoteMySQLIdentifier(pkColumns.get(i)) : quoteIdentifier(pkColumns.get(i)));
            }
            sql.append(",\n  PRIMARY KEY (").append(pkList).append(")");
        }

        if (isMySQL) {
            sql.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        } else {
            sql.append("\n);");
        }

        try (Statement stmt = targetConn.createStatement()) {
            stmt.executeUpdate(sql.toString());
        }

        // Schema synchronization: ensure all columns from source exist in the target table
        Set<String> existingCols = new HashSet<>();
        try (Statement stmt = targetConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + identifier + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                existingCols.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
            }
        } catch (SQLException ignored) {}

        for (ColumnDef col : columns) {
            boolean isPk = pkSet.contains(col.name.toLowerCase(Locale.ROOT));
            if (!existingCols.contains(col.name.toLowerCase(Locale.ROOT))) {
                String addColSql = "ALTER TABLE " + identifier + " ADD COLUMN "
                        + (isMySQL ? quoteMySQLIdentifier(col.name) : quoteIdentifier(col.name))
                        + " " + (isMySQL ? mapToMySQLType(col, isPk) : mapToGenericType(col));
                try (Statement stmt = targetConn.createStatement()) {
                    stmt.executeUpdate(addColSql);
                } catch (SQLException ex) {
                    LOGGER.log(Level.WARNING, "Could not add missing column " + col.name + " to " + tableName + ": " + ex.getMessage());
                }
            } else if (isMySQL) {
                // If column already exists in MySQL, widen any text/blob columns that might be truncated
                String targetType = mapToMySQLType(col, isPk);
                if (targetType.equals("MEDIUMTEXT") || targetType.equals("LONGBLOB") || targetType.startsWith("TIMESTAMP")) {
                    String modifySql = "ALTER TABLE " + identifier + " MODIFY COLUMN " + quoteMySQLIdentifier(col.name) + " " + targetType;
                    try (Statement stmt = targetConn.createStatement()) {
                        stmt.executeUpdate(modifySql);
                    } catch (SQLException ignored) {}
                }
            }
        }
    }

    private static List<String> extractPrimaryKeys(Connection conn, String tableName, List<ColumnDef> columns) {
        List<String> pkColumns = new ArrayList<>();

        // 1. Try SQLite PRAGMA table_info which is 100% reliable for SQLite composite PKs
        try {
            String pragmaSql = "PRAGMA table_info(" + quoteIdentifier(tableName) + ")";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(pragmaSql)) {
                Map<Integer, String> pkMap = new TreeMap<>();
                while (rs.next()) {
                    int pkIndex = rs.getInt("pk");
                    if (pkIndex > 0) {
                        pkMap.put(pkIndex, rs.getString("name"));
                    }
                }
                pkColumns.addAll(pkMap.values());
            }
        } catch (Exception ignored) {}

        // 2. If not SQLite or PRAGMA returned empty, try standard DatabaseMetaData
        if (pkColumns.isEmpty()) {
            try (ResultSet pkRs = conn.getMetaData().getPrimaryKeys(null, null, tableName)) {
                while (pkRs.next()) {
                    String pkCol = pkRs.getString("COLUMN_NAME");
                    if (pkCol != null && !pkCol.isBlank() && !pkColumns.contains(pkCol)) {
                        pkColumns.add(pkCol);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback heuristic only when table has no explicit PK defined
        if (pkColumns.isEmpty()) {
            for (ColumnDef col : columns) {
                if (col.name.equalsIgnoreCase("id") || col.name.equalsIgnoreCase("uuid")
                        || col.name.equalsIgnoreCase("ulid") || col.name.equalsIgnoreCase("order_id")
                        || col.name.equalsIgnoreCase("log_id")) {
                    pkColumns.add(col.name);
                    break;
                }
            }

            // If it's a chunk table, composite key is (server, world, x, z) or (world, x, z)
            if (pkColumns.isEmpty()) {
                boolean hasServer = false, hasWorld = false, hasX = false, hasZ = false;
                for (ColumnDef col : columns) {
                    String n = col.name.toLowerCase(Locale.ROOT);
                    if (n.equals("server") || n.equals("server_id")) hasServer = true;
                    if (n.equals("world") || n.equals("world_name")) hasWorld = true;
                    if (n.equals("x") || n.equals("chunk_x")) hasX = true;
                    if (n.equals("z") || n.equals("chunk_z")) hasZ = true;
                }
                if (hasWorld && hasX && hasZ) {
                    if (hasServer) pkColumns.add(findColName(columns, "server", "server_id"));
                    pkColumns.add(findColName(columns, "world", "world_name"));
                    pkColumns.add(findColName(columns, "x", "chunk_x"));
                    pkColumns.add(findColName(columns, "z", "chunk_z"));
                }
            }
        }

        return pkColumns;
    }

    private static String findColName(List<ColumnDef> columns, String... candidates) {
        for (String c : candidates) {
            for (ColumnDef col : columns) {
                if (col.name.equalsIgnoreCase(c)) return col.name;
            }
        }
        return candidates[0];
    }

    private static boolean isMySQL(Connection conn) {
        try {
            String name = conn.getMetaData().getDatabaseProductName();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("mysql") || lower.contains("mariadb");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String mapToGenericType(ColumnDef col) {
        if (isTimestampColumn(col)) return "TIMESTAMP";
        int type = col.sqlType;
        if (type == Types.VARCHAR || type == Types.CHAR) return "TEXT";
        if (type == Types.INTEGER || type == Types.TINYINT || type == Types.BOOLEAN) return "INTEGER";
        if (type == Types.BIGINT) return "BIGINT";
        if (type == Types.DOUBLE || type == Types.FLOAT || type == Types.DECIMAL) return "REAL";
        if (type == Types.BLOB || type == Types.VARBINARY) return "BLOB";
        return "TEXT";
    }

    private static String mapToMySQLType(ColumnDef col, boolean isPrimaryKey) {
        if (isTimestampColumn(col)) {
            return "TIMESTAMP NULL DEFAULT NULL";
        }

        String typeName = col.typeName != null ? col.typeName.toUpperCase(Locale.ROOT) : "";
        int type = col.sqlType;

        if (type == Types.VARCHAR || type == Types.CHAR || typeName.contains("VARCHAR") || typeName.contains("CHAR") || typeName.contains("TEXT")) {
            if (col.name.toLowerCase(Locale.ROOT).contains("uuid")) return "VARCHAR(36)";
            if (col.name.equalsIgnoreCase("ulid")) return "VARCHAR(26)";
            if (col.name.equalsIgnoreCase("server") || col.name.equalsIgnoreCase("server_id")) return "VARCHAR(64)";
            if (col.name.equalsIgnoreCase("world") || col.name.equalsIgnoreCase("world_name")) return "VARCHAR(64)";
            if (col.name.equalsIgnoreCase("name") || col.name.equalsIgnoreCase("tag")) return "VARCHAR(64)";

            if (isPrimaryKey) {
                return (col.precision > 0 && col.precision <= 255) ? "VARCHAR(" + col.precision + ")" : "VARCHAR(191)";
            }

            if (col.name.equalsIgnoreCase("extra") || col.name.equalsIgnoreCase("content")
                    || col.name.equalsIgnoreCase("boosters") || col.name.equalsIgnoreCase("items")
                    || col.name.equalsIgnoreCase("data") || col.name.equalsIgnoreCase("members_data")
                    || col.name.equalsIgnoreCase("stats") || col.name.equalsIgnoreCase("message")
                    || col.name.equalsIgnoreCase("lore") || col.name.equalsIgnoreCase("custom_data")
                    || col.name.equalsIgnoreCase("nbt") || col.name.equalsIgnoreCase("json")
                    || col.name.equalsIgnoreCase("chunks") || col.name.equalsIgnoreCase("areas")
                    || col.name.equalsIgnoreCase("blocks") || col.name.equalsIgnoreCase("storage")
                    || col.name.equalsIgnoreCase("invites") || col.name.equalsIgnoreCase("flags")) {
                return "MEDIUMTEXT";
            }
            if (col.precision > 0 && col.precision <= 255) return "VARCHAR(" + col.precision + ")";
            if (col.precision > 255 && col.precision <= 1000) return "VARCHAR(" + col.precision + ")";
            if (col.precision > 1000) return "MEDIUMTEXT";
            return "MEDIUMTEXT";
        }
        if (type == Types.INTEGER || (typeName.contains("INT") && !typeName.contains("BIGINT") && !typeName.contains("TINYINT"))) {
            return "INT";
        }
        if (type == Types.BIGINT || typeName.contains("BIGINT")) {
            return "BIGINT";
        }
        if (type == Types.TINYINT || type == Types.BOOLEAN || typeName.contains("BOOL") || typeName.contains("TINYINT")) {
            return "TINYINT(1)";
        }
        if (type == Types.DOUBLE || type == Types.FLOAT || type == Types.DECIMAL || type == Types.NUMERIC || typeName.contains("DOUBLE") || typeName.contains("FLOAT")) {
            return "DOUBLE";
        }
        if (type == Types.BLOB || type == Types.VARBINARY || type == Types.LONGVARBINARY || typeName.contains("BLOB") || typeName.contains("BINARY") || typeName.contains("BYTEA")) {
            return "LONGBLOB";
        }
        if (type == Types.LONGVARCHAR || type == Types.CLOB || typeName.contains("TEXT") || typeName.contains("CLOB")) {
            return "MEDIUMTEXT";
        }

        return "MEDIUMTEXT";
    }

    private static boolean isTimestampColumn(ColumnDef col) {
        if (col.sqlType == Types.TIMESTAMP || col.sqlType == Types.TIMESTAMP_WITH_TIMEZONE || col.sqlType == Types.DATE) return true;
        if (col.typeName != null) {
            String upper = col.typeName.toUpperCase(Locale.ROOT);
            if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) return true;
        }
        String name = col.name.toLowerCase(Locale.ROOT);
        return name.equals("created_at") || name.equals("last_seen") || name.equals("sent_at")
                || name.equals("started_at") || name.equals("updated_at");
    }

    private static Timestamp parseTimestamp(Object val) {
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts;
        if (val instanceof java.util.Date d) return new Timestamp(d.getTime());
        if (val instanceof Number num) {
            long epoch = num.longValue();
            if (epoch < 100000000000L && epoch > 0) {
                epoch *= 1000L;
            }
            return new Timestamp(epoch);
        }
        String str = val.toString().trim();
        if (str.isEmpty() || str.equalsIgnoreCase("null")) return null;
        try {
            long epoch = Long.parseLong(str);
            if (epoch < 100000000000L && epoch > 0) {
                epoch *= 1000L;
            }
            return new Timestamp(epoch);
        } catch (NumberFormatException ignored) {}
        try {
            return Timestamp.valueOf(str);
        } catch (Exception ignored) {}
        return null;
    }

    private static List<String> getTableNames(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null) {
                    String sUpper = schema.toUpperCase(Locale.ROOT);
                    if (sUpper.equals("INFORMATION_SCHEMA") || sUpper.equals("SYSTEM_LOBS") || sUpper.startsWith("PG_")) {
                        continue;
                    }
                }
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.isBlank()) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    private static String quoteIdentifier(String str) {
        return "\"" + str.replace("\"", "\"\"") + "\"";
    }

    private static String quoteMySQLIdentifier(String str) {
        return "`" + str.replace("`", "``") + "`";
    }

    private record ColumnDef(String name, int sqlType, String typeName, int precision, int scale) {}
}
