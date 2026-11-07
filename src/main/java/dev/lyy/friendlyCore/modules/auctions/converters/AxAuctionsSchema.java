package dev.lyy.friendlycore.modules.auctions.converters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Schema helpers for reading AxAuctions H2/MySQL tables (v1 UUID columns and v2 integer FKs).
 */
public final class AxAuctionsSchema {

    private static final Pattern DASHLESS_UUID = Pattern.compile(
            "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})");

    private AxAuctionsSchema() {}

    public record SourceUser(int id, UUID uuid, String name, int extraLimit) {}

    public static final class UsersIndex {
        private final Map<Integer, SourceUser> byId = new HashMap<>();
        private final Map<UUID, SourceUser> byUuid = new HashMap<>();

        public void add(SourceUser user) {
            if (user == null || user.uuid() == null) return;
            if (user.id() >= 0) byId.put(user.id(), user);
            byUuid.put(user.uuid(), user);
        }

        public SourceUser byId(int id) {
            return byId.get(id);
        }

        public SourceUser byUuid(UUID uuid) {
            return uuid == null ? null : byUuid.get(uuid);
        }

        public Collection<SourceUser> all() {
            return byUuid.values();
        }

        public int size() {
            return byUuid.size();
        }

        /**
         * Resolves a seller/buyer cell that may be an integer user id or a UUID.
         */
        public SourceUser resolve(Object rawIdOrUuid, String fallbackName) {
            Integer id = parseInt(rawIdOrUuid);
            if (id != null) {
                SourceUser byNumericId = byId.get(id);
                if (byNumericId != null) return byNumericId;
            }
            UUID uuid = parseUuid(rawIdOrUuid);
            if (uuid != null) {
                SourceUser known = byUuid.get(uuid);
                if (known != null) return known;
                String name = fallbackName != null && !fallbackName.isBlank() ? fallbackName : "Unknown";
                return new SourceUser(-1, uuid, name, 0);
            }
            return null;
        }
    }

    public static final class ResultColumns {
        private final Map<String, String> names = new LinkedHashMap<>();

        public static ResultColumns of(ResultSet rs) throws SQLException {
            ResultColumns columns = new ResultColumns();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                if (label == null || label.isBlank()) {
                    label = meta.getColumnName(i);
                }
                if (label != null) {
                    columns.names.put(label.toLowerCase(Locale.ROOT), label);
                }
            }
            return columns;
        }

        public String find(String... aliases) {
            for (String alias : aliases) {
                String hit = names.get(alias.toLowerCase(Locale.ROOT));
                if (hit != null) return hit;
            }
            return null;
        }

        public boolean has(String... aliases) {
            return find(aliases) != null;
        }
    }

    public static List<String> listTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(conn.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null) {
                    String upper = schema.toUpperCase(Locale.ROOT);
                    if (upper.equals("INFORMATION_SCHEMA") || upper.equals("SYSTEM_LOBS") || upper.startsWith("PG_")) {
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

    public static String findTable(Collection<String> tables, String prefix, String... suffixes) {
        if (tables == null || tables.isEmpty() || suffixes == null) return null;
        String normalizedPrefix = normalizePrefix(prefix);
        List<String> candidates = new ArrayList<>();
        for (String suffix : suffixes) {
            if (suffix == null || suffix.isBlank()) continue;
            String cleanSuffix = suffix.startsWith("_") ? suffix.substring(1) : suffix;
            if (!normalizedPrefix.isEmpty()) {
                candidates.add(normalizedPrefix + "_" + cleanSuffix);
            }
            candidates.add(cleanSuffix);
        }
        for (String candidate : candidates) {
            for (String table : tables) {
                if (table.equalsIgnoreCase(candidate)) return table;
            }
        }
        return null;
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null) return "";
        String trimmed = prefix.trim();
        while (trimmed.endsWith("_")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String quote(Connection conn, String identifier) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        boolean mysql = product != null && (product.toLowerCase(Locale.ROOT).contains("mysql")
                || product.toLowerCase(Locale.ROOT).contains("mariadb"));
        if (mysql) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static UUID parseUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid;
        if (value instanceof byte[] bytes) {
            if (bytes.length == 16) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                return new UUID(buffer.getLong(), buffer.getLong());
            }
            value = new String(bytes, StandardCharsets.UTF_8);
        }
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        if (text.length() == 32 && text.indexOf('-') < 0) {
            text = DASHLESS_UUID.matcher(text).replaceFirst("$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static Integer parseInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean isVaultCurrency(String name) {
        return name != null && name.trim().equalsIgnoreCase("Vault");
    }

    /** Only explicitly identified Vault records can be converted to Vault balances. */
    public static java.util.Set<Integer> allowedCurrencyIds(Map<Integer, String> currencies, boolean importNonVault) {
        java.util.Set<Integer> allowed = new java.util.HashSet<>();
        if (currencies != null) {
            currencies.forEach((id, name) -> { if (isVaultCurrency(name)) allowed.add(id); });
        }
        return allowed;
    }

    public static boolean isCurrencyAllowed(Integer currencyId, java.util.Set<Integer> allowedIds) {
        return currencyId != null && allowedIds != null && allowedIds.contains(currencyId);
    }

    public static UsersIndex loadUsers(Connection conn, List<String> tables, String prefix) throws SQLException {
        UsersIndex index = new UsersIndex();
        String table = findTable(tables, prefix, "users");
        if (table == null) return index;

        String sql = "SELECT * FROM " + quote(conn, table);
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            ResultColumns columns = ResultColumns.of(rs);
            String idCol = columns.find("id", "user_id");
            String uuidCol = columns.find("uuid", "unique_id", "player_uuid");
            String nameCol = columns.find("name", "username", "player_name", "seller_name");
            String extraCol = columns.find("extralimit", "extra_limit", "extra_listings", "extra", "listings");
            while (rs.next()) {
                UUID uuid = uuidCol != null ? parseUuid(rs.getObject(uuidCol)) : null;
                if (uuid == null) continue;
                int id = idCol != null ? rs.getInt(idCol) : -1;
                String name = nameCol != null ? rs.getString(nameCol) : null;
                if (name == null || name.isBlank()) name = "Unknown";
                int extra = extraCol != null ? rs.getInt(extraCol) : 0;
                index.add(new SourceUser(id, uuid, name, Math.max(0, extra)));
            }
        }
        return index;
    }

    public static Map<Integer, String> loadCurrencies(Connection conn, List<String> tables, String prefix) throws SQLException {
        Map<Integer, String> currencies = new LinkedHashMap<>();
        String table = findTable(tables, prefix, "currencies");
        if (table == null) return currencies;

        String sql = "SELECT * FROM " + quote(conn, table);
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            ResultColumns columns = ResultColumns.of(rs);
            String idCol = columns.find("id", "currency_id");
            String nameCol = columns.find("name", "currency", "identifier");
            if (idCol == null) return currencies;
            while (rs.next()) {
                int id = rs.getInt(idCol);
                String name = nameCol != null ? rs.getString(nameCol) : String.valueOf(id);
                currencies.put(id, name != null ? name : "");
            }
        }
        return currencies;
    }
}
