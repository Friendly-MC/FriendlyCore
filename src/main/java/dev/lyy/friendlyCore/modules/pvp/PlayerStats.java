package dev.lyy.friendlycore.modules.pvp;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe PvP statistics model tracking kills, deaths, streaks, and database dirty state.
 */
public class PlayerStats {

    private final UUID uuid;
    private volatile String username;
    private final AtomicInteger kills = new AtomicInteger(0);
    private final AtomicInteger deaths = new AtomicInteger(0);
    private final AtomicInteger currentStreak = new AtomicInteger(0);
    private final AtomicInteger highestStreak = new AtomicInteger(0);
    private final AtomicLong currentVersion = new AtomicLong(0);
    private final AtomicLong savedVersion = new AtomicLong(0);
    private volatile boolean awaitingLoad;
    private int killsBeforeFirstDeath;

    public static PlayerStats pending(UUID uuid, String username) {
        PlayerStats stats = new PlayerStats(uuid, username, 0, 0, 0, 0);
        stats.awaitingLoad = true;
        return stats;
    }

    public boolean isAwaitingLoad() { return awaitingLoad; }

    /** Preserve kills/deaths earned while SQL was unavailable, without saving zero-based totals. */
    public synchronized void restore(PlayerStats stored) {
        if (!awaitingLoad) return;
        int priorStreak = stored.getCurrentStreak();
        if (deaths.get() == 0) currentStreak.addAndGet(priorStreak);
        highestStreak.set(Math.max(highestStreak.get(),
            Math.max(stored.getHighestStreak(), priorStreak + killsBeforeFirstDeath)));
        kills.addAndGet(stored.getKills());
        deaths.addAndGet(stored.getDeaths());
        awaitingLoad = false;
    }

    public PlayerStats(UUID uuid, String username, int kills, int deaths, int currentStreak, int highestStreak) {
        this.uuid = uuid;
        this.username = username != null ? username : "Unknown";
        this.kills.set(kills);
        this.deaths.set(deaths);
        this.currentStreak.set(currentStreak);
        this.highestStreak.set(highestStreak);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username != null && !username.equalsIgnoreCase(this.username)) {
            this.username = username;
            this.currentVersion.incrementAndGet();
        }
    }

    public int getKills() {
        return kills.get();
    }

    public int getDeaths() {
        return deaths.get();
    }

    public int getCurrentStreak() {
        return currentStreak.get();
    }

    public int getHighestStreak() {
        return highestStreak.get();
    }

    public double getKDRatio() {
        int d = deaths.get();
        int k = kills.get();
        if (d == 0) return k;
        return Math.round(((double) k / d) * 100.0) / 100.0;
    }

    public synchronized void recordKill() {
        if (awaitingLoad && deaths.get() == 0) killsBeforeFirstDeath++;
        kills.incrementAndGet();
        int streak = currentStreak.incrementAndGet();
        highestStreak.updateAndGet(curr -> Math.max(curr, streak));
        this.currentVersion.incrementAndGet();
    }

    public synchronized void recordDeath() {
        deaths.incrementAndGet();
        currentStreak.set(0);
        this.currentVersion.incrementAndGet();
    }

    public boolean isDirty() {
        return !awaitingLoad && currentVersion.get() > savedVersion.get();
    }

    public void setDirty(boolean dirty) {
        if (dirty) {
            this.currentVersion.incrementAndGet();
        } else {
            this.savedVersion.set(this.currentVersion.get());
        }
    }

    public long getCurrentVersion() {
        return currentVersion.get();
    }

    public void markSaved(long version) {
        this.savedVersion.updateAndGet(v -> Math.max(v, version));
    }
}
