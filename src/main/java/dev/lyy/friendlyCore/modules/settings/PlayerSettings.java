package dev.lyy.friendlycore.modules.settings;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe model holding a player's customizable settings.
 */
public class PlayerSettings {

    private final UUID uuid;
    private volatile String username;
    private final Map<SettingType, Boolean> values = new ConcurrentHashMap<>();
    private volatile long lastUpdated;

    private final AtomicLong currentVersion = new AtomicLong(0);
    private final AtomicLong savedVersion = new AtomicLong(0);

    public PlayerSettings(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        for (SettingType type : SettingType.values()) {
            this.values.put(type, type.getDefaultValue());
        }
        this.lastUpdated = System.currentTimeMillis();
    }

    public PlayerSettings(UUID uuid, String username, Map<SettingType, Boolean> initialValues, long lastUpdated) {
        this.uuid = uuid;
        this.username = username;
        for (SettingType type : SettingType.values()) {
            this.values.put(type, type.getDefaultValue());
        }
        if (initialValues != null) {
            this.values.putAll(initialValues);
        }
        this.lastUpdated = lastUpdated;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
        markDirty();
    }

    public boolean get(SettingType type) {
        if (type == null) return false;
        return values.getOrDefault(type, type.getDefaultValue());
    }

    public boolean set(SettingType type, boolean value) {
        if (type == null) return false;
        Boolean previous = values.put(type, value);
        if (previous == null || previous != value) {
            markDirty();
            return true;
        }
        return false;
    }

    public boolean toggle(SettingType type) {
        if (type == null) return false;
        boolean next = !get(type);
        set(type, next);
        return next;
    }

    public Map<SettingType, Boolean> getAll() {
        return Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public long getLastUpdated() {
        return lastUpdated;
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
