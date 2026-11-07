package dev.lyy.friendlycore.common.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KitClaimReservationStoreTest {
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE friendlycore_player_kits (
                      uuid VARCHAR(36) NOT NULL, username VARCHAR(16) NOT NULL, kit_name VARCHAR(64) NOT NULL,
                      cooldown_until BIGINT NOT NULL DEFAULT 0, one_time_claimed BOOLEAN NOT NULL DEFAULT 0,
                      bought BOOLEAN NOT NULL DEFAULT 0, last_updated BIGINT NOT NULL,
                      claim_version BIGINT NOT NULL DEFAULT 0, reservation_token VARCHAR(36),
                      reservation_expires_at BIGINT NOT NULL DEFAULT 0,
                      reservation_prev_cooldown BIGINT NOT NULL DEFAULT 0,
                      reservation_prev_one_time BOOLEAN NOT NULL DEFAULT 0,
                      PRIMARY KEY(uuid, kit_name))
                    """);
        }
    }

    @Test
    void oneTimeReservationIsAtomicAndFailClosed() throws Exception {
        UUID uuid = UUID.randomUUID();
        KitClaimReservation first = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "starter", 0, true);
        assertTrue(first.reserved());

        KitClaimReservation concurrent = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "starter", 0, true);
        assertEquals(KitClaimReservation.Status.IN_FLIGHT, concurrent.status());
        assertTrue(KitClaimReservationStore.complete(connection, uuid, "starter", first.token()));

        KitClaimReservation repeated = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "starter", 0, true);
        assertEquals(KitClaimReservation.Status.ONE_TIME_CLAIMED, repeated.status());
    }

    @Test
    void rollbackRequiresMatchingOpaqueTokenAndRestoresPriorState() throws Exception {
        UUID uuid = UUID.randomUUID();
        long cooldown = System.currentTimeMillis() + 60_000L;
        KitClaimReservation reservation = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "pvp", cooldown, false);
        assertTrue(reservation.reserved());
        assertFalse(KitClaimReservationStore.rollback(connection, uuid, "pvp", UUID.randomUUID().toString()));
        assertTrue(KitClaimReservationStore.rollback(connection, uuid, "pvp", reservation.token()));

        KitClaimReservation retry = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "pvp", cooldown, false);
        assertTrue(retry.reserved());
    }

    @Test
    void completedCooldownReservationRejectsAnotherClaim() throws Exception {
        UUID uuid = UUID.randomUUID();
        long cooldown = System.currentTimeMillis() + 60_000L;
        KitClaimReservation reservation = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "pvp", cooldown, false);
        assertTrue(reservation.reserved());
        assertFalse(KitClaimReservationStore.complete(connection, uuid, "pvp", UUID.randomUUID().toString()));
        assertTrue(KitClaimReservationStore.complete(connection, uuid, "pvp", reservation.token()));

        KitClaimReservation repeated = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "pvp", cooldown + 1, false);
        assertEquals(KitClaimReservation.Status.COOLDOWN_ACTIVE, repeated.status());
    }

    @Test
    void expiredOneTimeReservationIsFailClosedAndCannotRollback() throws Exception {
        UUID uuid = UUID.randomUUID();
        KitClaimReservation reservation = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "once", 0, true);
        assertTrue(reservation.reserved());
        try (var statement = connection.prepareStatement(
                "UPDATE friendlycore_player_kits SET reservation_expires_at=? WHERE uuid=? AND kit_name=?")) {
            statement.setLong(1, System.currentTimeMillis() - 1);
            statement.setString(2, uuid.toString());
            statement.setString(3, "once");
            statement.executeUpdate();
        }

        assertFalse(KitClaimReservationStore.rollback(connection, uuid, "once", reservation.token()));
        KitClaimReservation repeated = KitClaimReservationStore.reserve(connection, false, uuid, "Player", "once", 0, true);
        assertEquals(KitClaimReservation.Status.ONE_TIME_CLAIMED, repeated.status());
    }
}
