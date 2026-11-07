package dev.lyy.friendlycore.modules.teams.storage;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.model.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Database access layer for team state persistence, supporting SQLite and MySQL/MariaDB.
 * <p>
 * Handles asynchronous bulk transactions, distributed vault lease locking,
 * atomic bank mutations and recoverable payment delivery.
 */
public class TeamsDao {

    private final FriendlyCore plugin;
    private final boolean isMySQL;
    private CompletableFuture<?> metadataWrites = CompletableFuture.completedFuture(null);

    private synchronized <T> CompletableFuture<T> enqueueWrite(java.util.function.Supplier<T> write) {
        CompletableFuture<T> next = metadataWrites.handle((ignored, error) -> null)
                .thenApplyAsync(ignored -> write.get(), plugin.getScheduler().getAsyncExecutor());
        metadataWrites = next;
        return next;
    }

    public TeamsDao(FriendlyCore plugin) {
        this.plugin = plugin;
        String type = plugin.getConfigService().getMainConfig().getString("database.type", "SQLITE");
        this.isMySQL = type.equalsIgnoreCase("MYSQL") || type.equalsIgnoreCase("MARIADB");
    }

    private Connection getConnection() throws SQLException {
        return plugin.getDatabaseManager().getConnection();
    }

    public void initializeTables() {
        try (Connection conn = getConnection()) { initializeTables(conn, isMySQL); }
        catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Teams database tables", error);
            throw new java.util.concurrent.CompletionException(error);
        }
    }

    static void initializeTables(Connection conn, boolean mysql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (mysql) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS friendlycore_teams (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(512) NOT NULL,
                        tag VARCHAR(32) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL,
                        owner_name VARCHAR(36),
                        members_data MEDIUMTEXT,
                        allies_data TEXT,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        enderchest_data MEDIUMTEXT,
                        homes_data TEXT,
                        max_homes INT NOT NULL DEFAULT 1,
                        last_tag_change BIGINT NOT NULL DEFAULT 0,
                        color_key VARCHAR(32) NOT NULL DEFAULT '#00b1ff',
                        friendly_fire BOOLEAN NOT NULL DEFAULT 1,
                        closed BOOLEAN NOT NULL DEFAULT 0,
                        score DOUBLE NOT NULL DEFAULT 0.0,
                        rank_pos INT NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL DEFAULT 0,
                        INDEX idx_teams_tag (tag),
                        INDEX idx_teams_owner (owner_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS friendlycore_team_players (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(36) NOT NULL,
                        team_uuid VARCHAR(36),
                        stats_data VARCHAR(512),
                        last_updated BIGINT NOT NULL,
                        INDEX idx_player_team (team_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """);
            } else {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS friendlycore_teams (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(512) NOT NULL,
                        tag VARCHAR(32) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL,
                        owner_name VARCHAR(36),
                        members_data TEXT,
                        allies_data TEXT,
                        balance DOUBLE NOT NULL DEFAULT 0.0,
                        enderchest_data TEXT,
                        homes_data TEXT,
                        max_homes INTEGER NOT NULL DEFAULT 1,
                        last_tag_change BIGINT NOT NULL DEFAULT 0,
                        color_key VARCHAR(32) NOT NULL DEFAULT '#00b1ff',
                        friendly_fire BOOLEAN NOT NULL DEFAULT 1,
                        closed BOOLEAN NOT NULL DEFAULT 0,
                        score DOUBLE NOT NULL DEFAULT 0.0,
                        rank_pos INTEGER NOT NULL DEFAULT 0,
                        created_at BIGINT NOT NULL DEFAULT 0
                    );
                """);
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_teams_tag ON friendlycore_teams (tag);");
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_teams_owner ON friendlycore_teams (owner_uuid);");

                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS friendlycore_team_players (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(36) NOT NULL,
                        team_uuid VARCHAR(36),
                        stats_data VARCHAR(512),
                        last_updated BIGINT NOT NULL
                    );
                """);
                stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fc_player_team ON friendlycore_team_players (team_uuid);");
            }

            if (mysql) {
                int nameCapacity;
                try (ResultSet columns = stmt.executeQuery("SELECT name FROM friendlycore_teams WHERE 1 = 0")) {
                    nameCapacity = columns.getMetaData().getPrecision(1);
                }
                if (nameCapacity < 512) stmt.executeUpdate("ALTER TABLE friendlycore_teams MODIFY COLUMN name VARCHAR(512) NOT NULL");
            }
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_team_vault_leases (
                    team_uuid VARCHAR(36) PRIMARY KEY,
                    holder_uuid VARCHAR(36) NOT NULL,
                    server_id VARCHAR(128) NOT NULL,
                    expires_at BIGINT NOT NULL
                );
            """);
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS friendlycore_team_states (team_uuid VARCHAR(36) PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT 0)" + (mysql ? " ENGINE=InnoDB" : ""));
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS friendlycore_team_payments (
                    payment_uuid VARCHAR(36) PRIMARY KEY, team_uuid VARCHAR(36) NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL, amount DOUBLE NOT NULL,
                    reason VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL
                )
                """ + (mysql ? " ENGINE=InnoDB" : ""));
        }
    }

    public CompletableFuture<List<Team>> loadAllTeams(Map<String, TeamRole> roleRegistry, TeamRole defaultMemberRole, TeamRole ownerRole) {
        return CompletableFuture.supplyAsync(() -> {
            List<Team> teams = new ArrayList<>();
            String sql = "SELECT * FROM friendlycore_teams";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("name");
                        String tag = rs.getString("tag");
                        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
                        String ownerName = rs.getString("owner_name");

                        TeamOwner owner = new TeamOwner(ownerUuid, ownerName, ownerRole, 0L);
                        Team team = new Team(uuid, name, tag, owner);
                        team.setBankBalance(rs.getDouble("balance"));
                        team.setMaxHomes(rs.getInt("max_homes"));
                        team.setLastTagChange(rs.getLong("last_tag_change"));
                        team.setColorKey(rs.getString("color_key"));
                        team.setFriendlyFireEnabled(rs.getBoolean("friendly_fire"));
                        team.setClosed(rs.getBoolean("closed"));
                        team.setScore(rs.getDouble("score"));
                        team.setRank(rs.getInt("rank_pos"));

                        String ecData = rs.getString("enderchest_data");
                        if (ecData != null && !ecData.isBlank()) {
                            team.getEnderChest().setStoredSnapshot(ecData);
                        }

                        // Encoded format: name==world;x;y;z;yaw;pitch[;server] separated by '||'
                        String homesData = rs.getString("homes_data");
                        if (homesData != null && !homesData.isBlank()) {
                            String[] homeEntries = homesData.contains("||") ? homesData.split("\\|\\|") : new String[]{homesData};
                            for (String hEntry : homeEntries) {
                                TeamHome home = TeamHome.parse(hEntry);
                                if (home != null) {
                                    team.getHomes().put(home.getName().toLowerCase(), home);
                                }
                            }
                        }

                        String alliesData = rs.getString("allies_data");
                        if (alliesData != null && !alliesData.isBlank()) {
                            String[] allyUuids = alliesData.split(",");
                            for (String aUuidStr : allyUuids) {
                                if (!aUuidStr.isBlank()) {
                                    try {
                                        team.getAllies().add(UUID.fromString(aUuidStr.trim()));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        // Encoded format: uuid;username;roleId;joinedTime separated by '||'
                        String membersData = rs.getString("members_data");
                        if (membersData != null && !membersData.isBlank()) {
                            String[] memberEntries = membersData.contains("||") ? membersData.split("\\|\\|") : new String[]{membersData};
                            for (String mEntry : memberEntries) {
                                if (mEntry.isBlank()) continue;
                                String[] mTokens = mEntry.split(";");
                                if (mTokens.length >= 2) {
                                    try {
                                        UUID mUuid = UUID.fromString(mTokens[0]);
                                        String mName = mTokens[1];
                                        String roleId = mTokens.length > 2 ? mTokens[2] : "6";
                                        long joined = mTokens.length > 3 ? Long.parseLong(mTokens[3]) : System.currentTimeMillis();

                                        TeamRole role = roleRegistry.getOrDefault(roleId, defaultMemberRole);
                                        if (mUuid.equals(ownerUuid)) {
                                            team.setOwner(new TeamOwner(mUuid, mName, ownerRole, joined));
                                        } else {
                                            team.addMember(new TeamMember(mUuid, mName, role, joined));
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }

                        team.setDirty(false);
                        teams.add(team);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Error parsing team record", e);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading teams from database", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return teams;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public CompletableFuture<Void> saveTeamsBatch(Collection<Team> teams) {
        List<Team> batch = teams == null ? List.of() : List.copyOf(teams);
        return enqueueWrite(() -> {
            try (Connection conn = getConnection()) { saveTeams(conn, isMySQL, batch); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
            return null;
        });
    }

    static void saveTeams(Connection conn, boolean mysql, List<Team> batch) throws SQLException {
        Map<Team, Long> revisions = new HashMap<>();

        String sqliteSql = """
            INSERT INTO friendlycore_teams (
                uuid, name, tag, owner_uuid, owner_name, members_data, allies_data, balance,
                enderchest_data, homes_data, max_homes, last_tag_change, color_key, friendly_fire,
                closed, score, rank_pos, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name = excluded.name,
                tag = excluded.tag,
                owner_uuid = excluded.owner_uuid,
                owner_name = excluded.owner_name,
                members_data = excluded.members_data,
                allies_data = excluded.allies_data,
                homes_data = excluded.homes_data,
                max_homes = excluded.max_homes,
                last_tag_change = excluded.last_tag_change,
                color_key = excluded.color_key,
                friendly_fire = excluded.friendly_fire,
                closed = excluded.closed,
                score = excluded.score,
                rank_pos = excluded.rank_pos;
        """;

        String mysqlSql = """
            INSERT INTO friendlycore_teams (
                uuid, name, tag, owner_uuid, owner_name, members_data, allies_data, balance,
                enderchest_data, homes_data, max_homes, last_tag_change, color_key, friendly_fire,
                closed, score, rank_pos, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                tag = VALUES(tag),
                owner_uuid = VALUES(owner_uuid),
                owner_name = VALUES(owner_name),
                members_data = VALUES(members_data),
                allies_data = VALUES(allies_data),
                homes_data = VALUES(homes_data),
                max_homes = VALUES(max_homes),
                last_tag_change = VALUES(last_tag_change),
                color_key = VALUES(color_key),
                friendly_fire = VALUES(friendly_fire),
                closed = VALUES(closed),
                score = VALUES(score),
                rank_pos = VALUES(rank_pos);
        """;

        String sql = mysql ? mysqlSql : sqliteSql;

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Team team : batch) {
                if (!lockTeamState(conn, mysql, team.getUniqueId())) continue;
                synchronized (team) {
                    revisions.put(team, team.getRevision());
                    ps.setString(1, team.getUniqueId().toString());
                    ps.setString(2, team.getName());
                    ps.setString(3, team.getTag());
                    ps.setString(4, team.getOwner() != null ? team.getOwner().getUniqueId().toString() : "");
                    ps.setString(5, team.getOwner() != null ? team.getOwner().getUsername() : "");

                    StringBuilder sbMembers = new StringBuilder();
                    for (TeamMember m : team.getMemberList()) {
                        if (sbMembers.length() > 0) sbMembers.append("||");
                        sbMembers.append(m.getUniqueId()).append(";")
                                 .append(m.getUsername()).append(";")
                                 .append(m.getRole() != null ? m.getRole().getId() : "6").append(";")
                                 .append(m.getJoinedTimestamp());
                    }
                    ps.setString(6, sbMembers.toString());

                    StringBuilder sbAllies = new StringBuilder();
                    for (UUID aUuid : team.getAllies()) {
                        if (sbAllies.length() > 0) sbAllies.append(",");
                        sbAllies.append(aUuid.toString());
                    }
                    ps.setString(7, sbAllies.toString());

                    ps.setDouble(8, team.getBankBalance());
                    ps.setString(9, team.getEnderChest() != null ? team.getEnderChest().getStoredSnapshot() : "");

                    StringBuilder sbHomes = new StringBuilder();
                    for (TeamHome h : team.getHomes().values()) {
                        if (sbHomes.length() > 0) sbHomes.append("||");
                        sbHomes.append(h.serialize());
                    }
                    ps.setString(10, sbHomes.toString());

                    ps.setInt(11, team.getMaxHomes());
                    ps.setLong(12, team.getLastTagChange());
                    ps.setString(13, team.getColorKey());
                    ps.setBoolean(14, team.isFriendlyFireEnabled());
                    ps.setBoolean(15, team.isClosed());
                    ps.setDouble(16, team.getScore());
                    ps.setInt(17, team.getRank());
                    ps.setLong(18, team.getCreatedAt());

                    ps.addBatch();
                }
            }

            ps.executeBatch();
            conn.commit();
            revisions.forEach(Team::markSaved);
        } catch (SQLException | RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackEx) {
                e.addSuppressed(rollbackEx);
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {}
        }
    }

    /** Locks a durable lifecycle row so stale metadata saves cannot recreate disbanded teams. */
    static boolean lockTeamState(Connection conn, boolean mysql, UUID teamUuid) throws SQLException {
        String insert = mysql
                ? "INSERT INTO friendlycore_team_states(team_uuid,deleted) VALUES(?,0) ON DUPLICATE KEY UPDATE team_uuid=team_uuid"
                : "INSERT INTO friendlycore_team_states(team_uuid,deleted) VALUES(?,0) ON CONFLICT(team_uuid) DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, teamUuid.toString()); ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_team_states SET deleted=deleted WHERE team_uuid=?")) {
            ps.setString(1, teamUuid.toString()); ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT deleted FROM friendlycore_team_states WHERE team_uuid=?" + (mysql ? " FOR UPDATE" : ""))) {
            ps.setString(1, teamUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && !rs.getBoolean(1); }
        }
    }

    public record Payment(UUID id, UUID teamUuid, UUID playerUuid, double amount, String reason) {}
    public record Disband(boolean deleted, Payment payout) {}
    public record Withdrawal(BankMutation bank, Payment payout) {}

    public CompletableFuture<Disband> disbandTeam(UUID teamUuid, boolean returnBalance) {
        return enqueueWrite(() -> {
            try (Connection conn = getConnection()) {
                return disbandTeam(conn, isMySQL, teamUuid, returnBalance);
            } catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }

    static Disband disbandTeam(Connection conn, boolean mysql, UUID teamUuid, boolean returnBalance) throws SQLException {
        conn.setAutoCommit(false);
        try {
            if (!lockTeamState(conn, mysql, teamUuid)) { conn.rollback(); return new Disband(false, null); }
            // Lock the bank row against withdrawals before reading the authoritative balance.
            try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_teams SET balance=balance WHERE uuid=?")) {
                ps.setString(1, teamUuid.toString()); ps.executeUpdate();
            }
            Payment payout = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT owner_uuid,balance FROM friendlycore_teams WHERE uuid=?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { conn.rollback(); return new Disband(false, null); }
                    double amount = rs.getDouble(2);
                    if (returnBalance && amount > 0) payout = new Payment(UUID.randomUUID(), teamUuid, UUID.fromString(rs.getString(1)), amount, "disband");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM friendlycore_team_vault_leases WHERE team_uuid=?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { conn.rollback(); return new Disband(false, null); }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_team_states SET deleted=1 WHERE team_uuid=?")) {
                ps.setString(1, teamUuid.toString()); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM friendlycore_teams WHERE uuid=?")) {
                ps.setString(1, teamUuid.toString()); ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_team_players SET team_uuid=NULL WHERE team_uuid=?")) {
                ps.setString(1, teamUuid.toString()); ps.executeUpdate();
            }
            if (payout != null) insertPayment(conn, payout, "PENDING");
            conn.commit();
            return new Disband(true, payout);
        } catch (SQLException | RuntimeException error) {
            conn.rollback(); throw error;
        } finally { conn.setAutoCommit(true); }
    }

    public CompletableFuture<Payment> preparePayment(UUID teamUuid, UUID playerUuid, double amount, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            Payment payment = new Payment(UUID.randomUUID(), teamUuid, playerUuid, amount, reason);
            try (Connection conn = getConnection()) { insertPayment(conn, payment, "PREPARED"); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
            return payment;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static void insertPayment(Connection conn, Payment payment, String state) throws SQLException {
        if (!Double.isFinite(payment.amount()) || payment.amount() <= 0) throw new IllegalArgumentException("Invalid payment amount");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO friendlycore_team_payments VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, payment.id().toString()); ps.setString(2, payment.teamUuid().toString());
            ps.setString(3, payment.playerUuid().toString()); ps.setDouble(4, payment.amount());
            ps.setString(5, payment.reason()); ps.setString(6, state); ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public CompletableFuture<Boolean> transitionPayment(UUID paymentId, String from, String to) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection()) { return transitionPayment(conn, paymentId, from, to); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static boolean transitionPayment(Connection conn, UUID id, String from, String to) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_team_payments SET state=? WHERE payment_uuid=? AND state=?")) {
            ps.setString(1, to); ps.setString(2, id.toString()); ps.setString(3, from);
            return ps.executeUpdate() == 1;
        }
    }

    public CompletableFuture<List<Payment>> loadPendingPayments(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            List<Payment> payments = new ArrayList<>();
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT * FROM friendlycore_team_payments WHERE player_uuid=? AND state='PENDING'")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) payments.add(new Payment(UUID.fromString(rs.getString("payment_uuid")),
                            UUID.fromString(rs.getString("team_uuid")), playerUuid, rs.getDouble("amount"), rs.getString("reason")));
                }
            } catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
            return payments;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public CompletableFuture<Withdrawal> reserveWithdrawal(UUID teamUuid, UUID playerUuid, double amount, double limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection()) { return reserveWithdrawal(conn, teamUuid, playerUuid, amount, limit); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static Withdrawal reserveWithdrawal(Connection conn, UUID teamUuid, UUID playerUuid, double amount, double limit) throws SQLException {
        if (!Double.isFinite(amount) || amount <= 0 || !Double.isFinite(limit) || limit < 0) return new Withdrawal(new BankMutation(false, -1), null);
        conn.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement ps = conn.prepareStatement("UPDATE friendlycore_teams SET balance=balance-? WHERE uuid=? AND balance>=?")) {
                ps.setDouble(1, amount); ps.setString(2, teamUuid.toString()); ps.setDouble(3, amount);
                changed = ps.executeUpdate();
            }
            double balance;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_teams WHERE uuid=?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) { balance = rs.next() ? rs.getDouble(1) : -1; }
            }
            Payment payment = changed == 1 ? new Payment(UUID.randomUUID(), teamUuid, playerUuid, amount, "withdrawal") : null;
            if (payment != null) insertPayment(conn, payment, "PENDING");
            conn.commit();
            return new Withdrawal(new BankMutation(changed == 1, balance), payment);
        } catch (SQLException | RuntimeException error) { conn.rollback(); throw error; }
        finally { conn.setAutoCommit(true); }
    }

    public record BankMutation(boolean success, double balance) {}

    /** Atomically applies a bank delta in shared storage, enforcing both zero and the configured cap. */
    public CompletableFuture<BankMutation> mutateBank(UUID teamUuid, double delta, double limit) {
        return CompletableFuture.supplyAsync(() -> {
            if (teamUuid == null || !Double.isFinite(delta) || delta == 0 || !Double.isFinite(limit) || limit < 0) {
                return new BankMutation(false, -1.0);
            }
            int maxAttempts = 3;
            long backoffMs = 50;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try (Connection conn = getConnection()) {
                    boolean origAutoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        String update = "UPDATE friendlycore_teams SET balance = balance + ? WHERE uuid = ? AND balance + ? >= 0 AND balance + ? <= ?";
                        int changed;
                        try (PreparedStatement ps = conn.prepareStatement(update)) {
                            ps.setDouble(1, delta);
                            ps.setString(2, teamUuid.toString());
                            ps.setDouble(3, delta);
                            ps.setDouble(4, delta);
                            ps.setDouble(5, limit);
                            changed = ps.executeUpdate();
                        }
                        double balance = loadBankBalance(conn, teamUuid);
                        conn.commit();
                        return new BankMutation(changed == 1, balance);
                    } catch (SQLException e) {
                        try {
                            conn.rollback();
                        } catch (SQLException rbEx) {
                            e.addSuppressed(rbEx);
                        }
                        throw e;
                    } finally {
                        try {
                            conn.setAutoCommit(origAutoCommit);
                        } catch (SQLException ignored) {}
                    }
                } catch (SQLException e) {
                    boolean isTransient = isTransientLockException(e);
                    if (isTransient && attempt < maxAttempts) {
                        plugin.getLogger().log(Level.WARNING, "Transient lock wait timeout mutating team bank " + teamUuid + " (attempt " + attempt + "/" + maxAttempts + "), retrying in " + backoffMs + "ms...");
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return new BankMutation(false, -1.0);
                        }
                        backoffMs *= 2;
                        continue;
                    }
                    plugin.getLogger().log(Level.SEVERE, "Failed to mutate team bank " + teamUuid, e);
                    throw new java.util.concurrent.CompletionException(e);
                }
            }
            return new BankMutation(false, -1.0);
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public static boolean isTransientLockException(SQLException e) {
        if (e == null) {
            return false;
        }
        if (e instanceof java.sql.SQLTransactionRollbackException) {
            return true;
        }
        int errorCode = e.getErrorCode();
        if (errorCode == 1205 || errorCode == 1213) {
            return true;
        }
        String sqlState = e.getSQLState();
        if (sqlState != null && (sqlState.startsWith("40") || sqlState.equals("41000"))) {
            return true;
        }
        if (e.getNextException() != null && e.getNextException() != e) {
            if (isTransientLockException(e.getNextException())) {
                return true;
            }
        }
        if (e.getCause() instanceof SQLException causeSql && causeSql != e) {
            return isTransientLockException(causeSql);
        }
        return false;
    }

    public CompletableFuture<Double> loadBankBalance(UUID teamUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection()) {
                return loadBankBalance(conn, teamUuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh team bank " + teamUuid, e);
                return -1.0;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    private double loadBankBalance(Connection conn, UUID teamUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM friendlycore_teams WHERE uuid = ?")) {
            ps.setString(1, teamUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : -1.0;
            }
        }
    }

    // holderUuid is a fresh session token, never a player UUID. Ownership does not
    // expire: reopening an abandoned snapshot can duplicate withdrawn items.
    public CompletableFuture<Boolean> acquireVaultLease(UUID teamUuid, UUID holderUuid, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection()) {
                return acquireVaultLease(conn, isMySQL, teamUuid, holderUuid, serverId);
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static boolean acquireVaultLease(Connection conn, boolean mysql, UUID teamUuid, UUID token, String serverId) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (!lockTeamState(conn, mysql, teamUuid)) { conn.rollback(); return false; }
            String sql = mysql
                    ? "INSERT INTO friendlycore_team_vault_leases(team_uuid,holder_uuid,server_id,expires_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE team_uuid=team_uuid"
                    : "INSERT INTO friendlycore_team_vault_leases(team_uuid,holder_uuid,server_id,expires_at) VALUES(?,?,?,?) ON CONFLICT(team_uuid) DO NOTHING";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, teamUuid.toString());
                ps.setString(2, token.toString());
                ps.setString(3, serverId);
                ps.setLong(4, Long.MAX_VALUE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT holder_uuid,server_id FROM friendlycore_team_vault_leases WHERE team_uuid=?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    boolean acquired = rs.next() && token.toString().equals(rs.getString(1)) && serverId.equals(rs.getString(2));
                    conn.commit();
                    return acquired;
                }
            }
        } catch (SQLException error) { conn.rollback(); throw error; }
        finally { conn.setAutoCommit(autoCommit); }
    }

    public CompletableFuture<Void> releaseVaultLease(UUID teamUuid, UUID holderUuid, String serverId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection()) {
                releaseVaultLease(conn, teamUuid, holderUuid, serverId);
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static void releaseVaultLease(Connection conn, UUID teamUuid, UUID token, String serverId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM friendlycore_team_vault_leases WHERE team_uuid=? AND holder_uuid=? AND server_id=?")) {
            ps.setString(1, teamUuid.toString());
            ps.setString(2, token.toString());
            ps.setString(3, serverId);
            ps.executeUpdate();
        }
    }

    public CompletableFuture<String> loadVault(UUID teamUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT enderchest_data FROM friendlycore_teams WHERE uuid=?")) {
                ps.setString(1, teamUuid.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Objects.requireNonNullElse(rs.getString(1), "") : null; }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load team vault", e); return null;
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public CompletableFuture<Boolean> saveVault(UUID teamUuid, UUID holderUuid, String serverId, String data) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection()) {
                return saveVault(conn, teamUuid, holderUuid, serverId, data);
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, plugin.getScheduler().getAsyncExecutor());
    }

    static boolean saveVault(Connection conn, UUID teamUuid, UUID token, String serverId, String data) throws SQLException {
        String sql = "UPDATE friendlycore_teams SET enderchest_data=? WHERE uuid=? AND "
                + "EXISTS (SELECT 1 FROM friendlycore_team_vault_leases WHERE team_uuid=? AND holder_uuid=? AND server_id=?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Objects.requireNonNull(data));
            ps.setString(2, teamUuid.toString());
            ps.setString(3, teamUuid.toString());
            ps.setString(4, token.toString());
            ps.setString(5, serverId);
            return ps.executeUpdate() == 1;
        }
    }

    public CompletableFuture<Map<UUID, TeamPlayerStats>> loadAllPlayerStats() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, TeamPlayerStats> statsMap = new ConcurrentHashMap<>();
            String sql = "SELECT player_uuid, username, stats_data FROM friendlycore_team_players";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        String name = rs.getString("username");
                        String statsData = rs.getString("stats_data");
                        TeamPlayerStats stats = TeamPlayerStats.deserialize(uuid, name, statsData);
                        statsMap.put(uuid, stats);
                    } catch (Exception ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load player stats", e);
                throw new java.util.concurrent.CompletionException(e);
            }
            return statsMap;
        }, plugin.getScheduler().getAsyncExecutor());
    }

    public CompletableFuture<Void> savePlayerStatsBatch(Collection<TeamPlayerStats> statsList, Map<UUID, UUID> playerToTeamMap) {
        List<TeamPlayerStats> batch = statsList == null ? List.of() : List.copyOf(statsList);
        Map<UUID, UUID> memberships = playerToTeamMap == null ? Map.of() : Map.copyOf(playerToTeamMap);
        return enqueueWrite(() -> {
            try (Connection conn = getConnection()) { savePlayerStats(conn, isMySQL, batch, memberships); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
            return null;
        });
    }

    static void savePlayerStats(Connection conn, boolean mysql, List<TeamPlayerStats> batch, Map<UUID, UUID> memberships) throws SQLException {
        Map<TeamPlayerStats, Long> revisions = new HashMap<>();

        String sqliteSql = """
            INSERT INTO friendlycore_team_players (player_uuid, username, team_uuid, stats_data, last_updated)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                username = excluded.username,
                team_uuid = excluded.team_uuid,
                stats_data = excluded.stats_data,
                last_updated = excluded.last_updated;
        """;

        String mysqlSql = """
            INSERT INTO friendlycore_team_players (player_uuid, username, team_uuid, stats_data, last_updated)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                username = VALUES(username),
                team_uuid = VALUES(team_uuid),
                stats_data = VALUES(stats_data),
                last_updated = VALUES(last_updated);
        """;

        String sql = mysql ? mysqlSql : sqliteSql;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            long now = System.currentTimeMillis();

            for (TeamPlayerStats stats : batch) {
                revisions.put(stats, stats.getRevision());
                UUID teamUuid = memberships.get(stats.getPlayerUuid());
                if (teamUuid != null && !lockTeamState(conn, mysql, teamUuid)) teamUuid = null;
                ps.setString(1, stats.getPlayerUuid().toString());
                ps.setString(2, stats.getUsername());
                ps.setString(3, teamUuid != null ? teamUuid.toString() : null);
                ps.setString(4, stats.serialize());
                ps.setLong(5, now);
                ps.addBatch();
            }

            ps.executeBatch();
            conn.commit();
            revisions.forEach(TeamPlayerStats::markSaved);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

}
