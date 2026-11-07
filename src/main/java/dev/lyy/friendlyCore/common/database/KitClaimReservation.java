package dev.lyy.friendlycore.common.database;

/**
 * Result of an authoritative kit claim reservation.
 * Holds an opaque token required to commit or roll back the claim transaction.
 */
public record KitClaimReservation(
        Status status,
        String token,
        long previousCooldownUntil,
        boolean previousOneTimeClaimed,
        long reservedCooldownUntil,
        boolean reservedOneTimeClaimed,
        long version
) {
    public enum Status {
        RESERVED,
        COOLDOWN_ACTIVE,
        ONE_TIME_CLAIMED,
        IN_FLIGHT,
        ERROR
    }

    public boolean reserved() {
        return status == Status.RESERVED && token != null;
    }

    public static KitClaimReservation rejected(Status status, long cooldownUntil, boolean oneTimeClaimed, long version) {
        return new KitClaimReservation(status, null, cooldownUntil, oneTimeClaimed,
                cooldownUntil, oneTimeClaimed, version);
    }
}
