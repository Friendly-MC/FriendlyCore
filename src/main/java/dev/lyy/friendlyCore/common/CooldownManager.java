package dev.lyy.friendlycore.common;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooldowns with either a caller-supplied duration or an explicit expiry. */
public class CooldownManager {
    private record Cooldown(long startedAt, long expiresAt) {
        long remaining(long now, long duration) {
            return Math.max(0, (expiresAt == 0 ? startedAt + Math.max(0, duration) : expiresAt) - now);
        }
    }

    private final ConcurrentHashMap<String, Cooldown> cooldowns = new ConcurrentHashMap<>();

    private String key(String category, UUID uuid) { return category + ":" + uuid; }

    public boolean isOnCooldown(String category, UUID uuid, long cooldownMillis) {
        return getRemainingMillis(category, uuid, cooldownMillis) > 0;
    }

    public boolean tryAcquire(String category, UUID uuid, long cooldownMillis) {
        if (cooldownMillis <= 0 || uuid == null || category == null) return true;
        long now = System.currentTimeMillis();
        AtomicBoolean acquired = new AtomicBoolean();
        cooldowns.compute(key(category, uuid), (key, previous) -> {
            if (previous == null || previous.remaining(now, cooldownMillis) == 0) {
                acquired.set(true);
                return new Cooldown(now, 0);
            }
            return previous;
        });
        return acquired.get();
    }

    public long getRemainingMillis(String category, UUID uuid, long cooldownMillis) {
        if (uuid == null || category == null) return 0;
        Cooldown cooldown = cooldowns.get(key(category, uuid));
        return cooldown == null ? 0 : cooldown.remaining(System.currentTimeMillis(), cooldownMillis);
    }

    public long getRemainingSeconds(String category, UUID uuid, long cooldownMillis) {
        return (long) Math.ceil(getRemainingMillis(category, uuid, cooldownMillis) / 1000.0);
    }

    public double getRemainingSecondsDouble(String category, UUID uuid, long cooldownMillis) {
        return Math.ceil(getRemainingMillis(category, uuid, cooldownMillis) / 100.0) / 10.0;
    }

    public void setCooldown(String category, UUID uuid) {
        if (category != null && uuid != null) cooldowns.put(key(category, uuid), new Cooldown(System.currentTimeMillis(), 0));
    }

    public void setCooldown(String category, UUID uuid, long durationMillis) {
        if (durationMillis <= 0) resetCooldown(category, uuid);
        else setCooldownUntil(category, uuid, System.currentTimeMillis() + durationMillis);
    }

    public void setCooldownUntil(String category, UUID uuid, long expiresAt) {
        if (category == null || uuid == null || expiresAt <= System.currentTimeMillis()) return;
        cooldowns.compute(key(category, uuid), (key, previous) ->
                previous != null && previous.expiresAt() > expiresAt ? previous : new Cooldown(0, expiresAt));
    }

    public void resetCooldown(String category, UUID uuid) {
        if (category != null && uuid != null) cooldowns.remove(key(category, uuid));
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> entry.getValue().expiresAt() == 0
                ? now - entry.getValue().startedAt() > 604_800_000L : entry.getValue().expiresAt() <= now);
    }
}
