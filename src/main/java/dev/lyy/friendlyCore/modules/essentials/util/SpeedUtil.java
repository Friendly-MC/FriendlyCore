package dev.lyy.friendlycore.modules.essentials.util;

/**
 * Utility for mapping between Essentials user speed levels (0-10) and internal Bukkit speeds (0.0-1.0).
 */
public final class SpeedUtil {

    private SpeedUtil() {}

    public static final float DEFAULT_FLY_SPEED = 0.1f;
    public static final float DEFAULT_WALK_SPEED = 0.2f;

    /**
     * Converts an Essentials user-facing speed (0.0 - 10.0) to Bukkit internal move speed (0.0 - 1.0).
     *
     * @param userSpeed User specified speed from 0 to 10
     * @param isFly     True for fly speed, false for walk speed
     * @param maxSpeed  Maximum allowed speed (up to 1.0)
     * @return Clamped and mapped Bukkit speed value
     */
    public static float calculateBukkitSpeed(float userSpeed, boolean isFly, float maxSpeed) {
        if (userSpeed < 0.0001f) {
            userSpeed = 0.0001f;
        } else if (userSpeed > 10.0f) {
            userSpeed = 10.0f;
        }

        if (maxSpeed <= 0f || maxSpeed > 1.0f) {
            maxSpeed = 1.0f;
        }

        float defaultSpeed = isFly ? DEFAULT_FLY_SPEED : DEFAULT_WALK_SPEED;

        if (userSpeed < 1.0f) {
            return Math.max(0.0001f, Math.min(maxSpeed, defaultSpeed * userSpeed));
        } else {
            float ratio = ((userSpeed - 1.0f) / 9.0f) * (maxSpeed - defaultSpeed);
            return Math.max(0.0001f, Math.min(maxSpeed, ratio + defaultSpeed));
        }
    }

    /**
     * Converts a Bukkit internal move speed (0.0 - 1.0) back to Essentials user-facing speed (0.0 - 10.0).
     */
    public static float calculateUserSpeed(float bukkitSpeed, boolean isFly, float maxSpeed) {
        if (maxSpeed <= 0f || maxSpeed > 1.0f) {
            maxSpeed = 1.0f;
        }
        float defaultSpeed = isFly ? DEFAULT_FLY_SPEED : DEFAULT_WALK_SPEED;

        if (bukkitSpeed <= defaultSpeed) {
            return (bukkitSpeed / defaultSpeed);
        } else {
            float delta = maxSpeed - defaultSpeed;
            if (delta <= 0.00001f) return 10.0f;
            float ratio = (bukkitSpeed - defaultSpeed) / delta;
            return 1.0f + (ratio * 9.0f);
        }
    }
}
