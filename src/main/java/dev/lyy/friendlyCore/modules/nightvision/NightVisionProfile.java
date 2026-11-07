package dev.lyy.friendlycore.modules.nightvision;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a player's NightVision preferences and active state with atomic version tracking.
 */
public class NightVisionProfile {

    private final UUID uuid;
    private volatile String username;
    private volatile boolean enabled;
    private volatile long lastUpdated;

    private final AtomicLong currentVersion = new AtomicLong(0);
    private final AtomicLong savedVersion = new AtomicLong(0);

    public NightVisionProfile(UUID uuid, String username, boolean enabled) {
        this.uuid = uuid;
        this.username = username != null ? username : "Unknown";
        this.enabled = enabled;
        this.lastUpdated = System.currentTimeMillis();
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
            markDirty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
        }
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public long getVersion() {
        return currentVersion.get();
    }

    public boolean isDirty() {
        return currentVersion.get() > savedVersion.get();
    }

    public void setDirty(boolean dirty) {
        if (!dirty) {
            this.savedVersion.set(this.currentVersion.get());
        } else {
            markDirty();
        }
    }

    public void markSaved(long version) {
        this.savedVersion.updateAndGet(v -> Math.max(v, version));
    }

    private void markDirty() {
        this.lastUpdated = System.currentTimeMillis();
        this.currentVersion.incrementAndGet();
    }
}
