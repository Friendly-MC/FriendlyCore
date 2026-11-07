package dev.lyy.friendlycore.modules.kits;

/**
 * Result of a kit claim attempt.
 */
public record ClaimResult(
        Status status,
        String message,
        Kit kit,
        boolean proceedToBuy
) {
    public enum Status {
        SUCCESS,
        NO_PERMISSION,
        COOLDOWN,
        ONE_TIME_ALREADY_CLAIMED,
        NO_SPACE,
        REQUIREMENTS_NOT_MET,
        NEEDS_PURCHASE,
        LOCKED,
        ERROR
    }

    public static ClaimResult success(Kit kit) {
        return new ClaimResult(Status.SUCCESS, null, kit, false);
    }

    public static ClaimResult proceedToBuy(Kit kit) {
        return new ClaimResult(Status.NEEDS_PURCHASE, null, kit, true);
    }

    public static ClaimResult error(Status status, String message, Kit kit) {
        return new ClaimResult(status, message, kit, false);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
