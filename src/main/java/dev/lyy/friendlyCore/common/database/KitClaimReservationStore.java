package dev.lyy.friendlycore.common.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared JDBC reservation store for kit claims across MySQL and SQLite backends.
 * Uses optimistic locking and lease tokens to prevent cross-server duplicate claims.
 */
final class KitClaimReservationStore {
    private static final long RESERVATION_TTL_MILLIS = 60_000L;

    private KitClaimReservationStore() {}

    static KitClaimReservation reserve(Connection conn, boolean mysql, UUID uuid, String username,
                                       String kitName, long proposedCooldownUntil, boolean claimOneTime) throws SQLException {
        if (uuid == null || kitName == null || kitName.isBlank() || kitName.length() > 64) {
            return KitClaimReservation.rejected(KitClaimReservation.Status.ERROR, 0, false, 0);
        }
        String normalizedKit = kitName.toLowerCase(Locale.ROOT);
        String safeUsername = username == null || username.isBlank() ? "Unknown" : username;
        if (safeUsername.length() > 16) safeUsername = safeUsername.substring(0, 16);

        for (int attempt = 0; attempt < 3; attempt++) {
            long now = System.currentTimeMillis();
            String token = UUID.randomUUID().toString();
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Row row = select(conn, mysql, uuid, normalizedKit);
                if (row == null) {
                    long reservedCooldown = Math.max(0L, proposedCooldownUntil);
                    try (PreparedStatement ps = conn.prepareStatement("""
                            INSERT INTO friendlycore_player_kits
                            (uuid,username,kit_name,cooldown_until,one_time_claimed,bought,last_updated,
                             claim_version,reservation_token,reservation_expires_at,reservation_prev_cooldown,reservation_prev_one_time)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                            """)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, safeUsername);
                        ps.setString(3, normalizedKit);
                        ps.setLong(4, reservedCooldown);
                        ps.setBoolean(5, claimOneTime);
                        ps.setBoolean(6, false);
                        ps.setLong(7, now);
                        ps.setLong(8, 1L);
                        ps.setString(9, token);
                        ps.setLong(10, now + RESERVATION_TTL_MILLIS);
                        ps.setLong(11, 0L);
                        ps.setBoolean(12, false);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    return new KitClaimReservation(KitClaimReservation.Status.RESERVED, token, 0, false,
                            reservedCooldown, claimOneTime, 1L);
                }

                if (row.token != null && row.expiresAt > now) {
                    conn.rollback();
                    return KitClaimReservation.rejected(KitClaimReservation.Status.IN_FLIGHT,
                            row.cooldownUntil, row.oneTimeClaimed, row.version);
                }
                // An expired reservation is treated as committed (fail-closed) to prevent duplication if a crash happened post-delivery
                if (claimOneTime && row.oneTimeClaimed) {
                    clearExpiredToken(conn, uuid, normalizedKit, now);
                    conn.commit();
                    return KitClaimReservation.rejected(KitClaimReservation.Status.ONE_TIME_CLAIMED,
                            row.cooldownUntil, true, row.version);
                }
                if (proposedCooldownUntil > 0 && row.cooldownUntil > now) {
                    clearExpiredToken(conn, uuid, normalizedKit, now);
                    conn.commit();
                    return KitClaimReservation.rejected(KitClaimReservation.Status.COOLDOWN_ACTIVE,
                            row.cooldownUntil, row.oneTimeClaimed, row.version);
                }

                long reservedCooldown = proposedCooldownUntil > 0 ? proposedCooldownUntil : row.cooldownUntil;
                boolean reservedOneTime = row.oneTimeClaimed || claimOneTime;
                String update = """
                        UPDATE friendlycore_player_kits SET username=?,cooldown_until=?,one_time_claimed=?,last_updated=?,
                        claim_version=claim_version+1,reservation_token=?,reservation_expires_at=?,
                        reservation_prev_cooldown=?,reservation_prev_one_time=?
                        WHERE uuid=? AND kit_name=? AND claim_version=?
                          AND (reservation_token IS NULL OR reservation_expires_at<=?)
                        """;
                int changed;
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setString(1, safeUsername);
                    ps.setLong(2, reservedCooldown);
                    ps.setBoolean(3, reservedOneTime);
                    ps.setLong(4, now);
                    ps.setString(5, token);
                    ps.setLong(6, now + RESERVATION_TTL_MILLIS);
                    ps.setLong(7, row.cooldownUntil);
                    ps.setBoolean(8, row.oneTimeClaimed);
                    ps.setString(9, uuid.toString());
                    ps.setString(10, normalizedKit);
                    ps.setLong(11, row.version);
                    ps.setLong(12, now);
                    changed = ps.executeUpdate();
                }
                if (changed == 1) {
                    conn.commit();
                    return new KitClaimReservation(KitClaimReservation.Status.RESERVED, token,
                            row.cooldownUntil, row.oneTimeClaimed, reservedCooldown, reservedOneTime, row.version + 1);
                }
                conn.rollback();
            } catch (SQLIntegrityConstraintViolationException conflict) {
                conn.rollback();
            } catch (SQLException failure) {
                conn.rollback();
                if (attempt == 2) throw failure;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
        }
        return KitClaimReservation.rejected(KitClaimReservation.Status.IN_FLIGHT, 0, false, 0);
    }

    static boolean complete(Connection conn, UUID uuid, String kitName, String token) throws SQLException {
        if (!validTokenInput(uuid, kitName, token)) return false;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE friendlycore_player_kits SET reservation_token=NULL,reservation_expires_at=0,
                reservation_prev_cooldown=0,reservation_prev_one_time=0,claim_version=claim_version+1,last_updated=?
                WHERE uuid=? AND kit_name=? AND reservation_token=? AND reservation_expires_at>=?
                """)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setString(2, uuid.toString());
            ps.setString(3, kitName.toLowerCase(Locale.ROOT));
            ps.setString(4, token);
            ps.setLong(5, now);
            return ps.executeUpdate() == 1;
        }
    }

    static boolean rollback(Connection conn, UUID uuid, String kitName, String token) throws SQLException {
        if (!validTokenInput(uuid, kitName, token)) return false;
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE friendlycore_player_kits SET cooldown_until=reservation_prev_cooldown,
                one_time_claimed=reservation_prev_one_time,reservation_token=NULL,reservation_expires_at=0,
                reservation_prev_cooldown=0,reservation_prev_one_time=0,claim_version=claim_version+1,last_updated=?
                WHERE uuid=? AND kit_name=? AND reservation_token=? AND reservation_expires_at>=?
                """)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setString(2, uuid.toString());
            ps.setString(3, kitName.toLowerCase(Locale.ROOT));
            ps.setString(4, token);
            ps.setLong(5, now);
            return ps.executeUpdate() == 1;
        }
    }

    private static boolean validTokenInput(UUID uuid, String kitName, String token) {
        return uuid != null && kitName != null && !kitName.isBlank() && token != null && token.length() == 36;
    }

    private static Row select(Connection conn, boolean mysql, UUID uuid, String kitName) throws SQLException {
        String sql = "SELECT cooldown_until,one_time_claimed,claim_version,reservation_token,reservation_expires_at " +
                "FROM friendlycore_player_kits WHERE uuid=? AND kit_name=?" + (mysql ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getLong(1), rs.getBoolean(2), rs.getLong(3), rs.getString(4), rs.getLong(5)) : null;
            }
        }
    }

    private static void clearExpiredToken(Connection conn, UUID uuid, String kitName, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE friendlycore_player_kits SET reservation_token=NULL,reservation_expires_at=0,
                reservation_prev_cooldown=0,reservation_prev_one_time=0
                WHERE uuid=? AND kit_name=? AND reservation_token IS NOT NULL AND reservation_expires_at<=?
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitName);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    private record Row(long cooldownUntil, boolean oneTimeClaimed, long version, String token, long expiresAt) {}
}
