package dev.lyy.friendlycore.modules.teams.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VaultOwnershipTest {
    private Connection database(boolean mysql) throws SQLException {
        Connection conn = DriverManager.getConnection(mysql
                ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL" : "jdbc:sqlite::memory:");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE friendlycore_team_states (team_uuid VARCHAR(36) PRIMARY KEY, deleted BOOLEAN NOT NULL DEFAULT 0)");
            stmt.execute("CREATE TABLE friendlycore_teams(uuid VARCHAR(36) PRIMARY KEY, enderchest_data TEXT)");
            stmt.execute("CREATE TABLE friendlycore_team_vault_leases(team_uuid VARCHAR(36) PRIMARY KEY, holder_uuid VARCHAR(36), server_id VARCHAR(128), expires_at BIGINT)");
        }
        return conn;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void staleSessionCannotSaveOrReleaseNewSession(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            UUID team = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO friendlycore_teams VALUES (?, 'item')")) {
                ps.setString(1, team.toString()); ps.executeUpdate();
            }
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team, first, "server"));
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team, second, "server"), "Reopen blocked before close commits");
            assertTrue(TeamsDao.saveVault(conn, team, first, "server", "empty"));
            TeamsDao.releaseVaultLease(conn, team, first, "server");
            assertFalse(TeamsDao.saveVault(conn, team, first, "server", "item"), "No owner means no write");
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team, second, "server"));
            TeamsDao.releaseVaultLease(conn, team, first, "server");
            assertFalse(TeamsDao.saveVault(conn, team, first, "server", "item"));
            assertTrue(TeamsDao.saveVault(conn, team, second, "server", "empty"));
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT enderchest_data FROM friendlycore_teams")) {
                assertTrue(rs.next()); assertEquals("empty", rs.getString(1));
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredOrOrphanedOwnershipCannotBeStolen(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            UUID team = UUID.randomUUID(), first = UUID.randomUUID();
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team, first, "one"));
            conn.createStatement().executeUpdate("UPDATE friendlycore_team_vault_leases SET expires_at=0");
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team, UUID.randomUUID(), "two"));
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team, first, "two"));
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team, first, "one"), "Exact-token retry is idempotent");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedSaveKeepsOwnership(boolean mysql) throws Exception {
        try (Connection conn = database(mysql)) {
            UUID team = UUID.randomUUID(), token = UUID.randomUUID();
            assertTrue(TeamsDao.acquireVaultLease(conn, mysql, team, token, "one"));
            assertFalse(TeamsDao.saveVault(conn, team, token, "one", "empty"), "Missing team must fail");
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team, UUID.randomUUID(), "two"));
            conn.createStatement().execute("DROP TABLE friendlycore_teams");
            assertThrows(SQLException.class, () -> TeamsDao.saveVault(conn, team, token, "one", "empty"));
            assertFalse(TeamsDao.acquireVaultLease(conn, mysql, team, UUID.randomUUID(), "two"));
        }
    }
}
