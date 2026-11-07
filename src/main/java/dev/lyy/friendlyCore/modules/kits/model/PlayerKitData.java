package dev.lyy.friendlycore.modules.kits.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe Player Kit data holder with dirty-tracking and non-blocking versioning.
 */
public class PlayerKitData {

    private final UUID uuid;
    private volatile String username;
    private final ConcurrentHashMap<String, PlayerKitRecord> records = new ConcurrentHashMap<>();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicLong version = new AtomicLong(0);
    private volatile long savedVersion = 0;

    public PlayerKitData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username != null ? username : "Unknown";
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username != null && !username.equals(this.username)) {
            this.username = username;
            markDirty();
        }
    }

    public Map<String, PlayerKitRecord> getRecords() {
        return Collections.unmodifiableMap(records);
    }

    public PlayerKitRecord getRecord(String kitName) {
        if (kitName == null) return null;
        return records.get(kitName.toLowerCase(Locale.ROOT));
    }

    public PlayerKitRecord getOrCreateRecord(String kitName) {
        if (kitName == null) return null;
        return records.computeIfAbsent(kitName.toLowerCase(Locale.ROOT), PlayerKitRecord::new);
    }

    public void mergeRecord(PlayerKitRecord incoming) {
        if (incoming == null || incoming.getKitName() == null) return;
        String key = incoming.getKitName().toLowerCase(Locale.ROOT);
        records.compute(key, (k, existing) -> {
            if (existing == null) {
                return incoming.clone();
            }
            existing.merge(incoming);
            return existing;
        });
    }

    public void addRecord(PlayerKitRecord record) {
        if (record == null) return;
        mergeRecord(record);
    }

    public long getCooldownUntil(String kitName) {
        PlayerKitRecord record = getRecord(kitName);
        return record != null ? record.getCooldownUntil() : 0L;
    }

    public void setCooldownUntil(String kitName, long cooldownUntil) {
        PlayerKitRecord record = getOrCreateRecord(kitName);
        record.setCooldownUntil(cooldownUntil);
        record.setLastClaimed(System.currentTimeMillis());
        markDirty();
    }

    public boolean isOneTimeClaimed(String kitName) {
        PlayerKitRecord record = getRecord(kitName);
        return record != null && record.isOneTimeClaimed();
    }

    public void setOneTimeClaimed(String kitName, boolean claimed) {
        PlayerKitRecord record = getOrCreateRecord(kitName);
        record.setOneTimeClaimed(claimed);
        record.setLastClaimed(System.currentTimeMillis());
        markDirty();
    }

    public boolean isBought(String kitName) {
        PlayerKitRecord record = getRecord(kitName);
        return record != null && record.isBought();
    }

    public void setBought(String kitName, boolean bought) {
        PlayerKitRecord record = getOrCreateRecord(kitName);
        record.setBought(bought);
        markDirty();
    }

    public void resetKit(String kitName) {
        if (kitName == null) return;
        if (records.remove(kitName.toLowerCase(Locale.ROOT)) != null) {
            markDirty();
        }
    }

    public void resetAll() {
        if (!records.isEmpty()) {
            records.clear();
            markDirty();
        }
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public void markDirty() {
        this.dirty.set(true);
        this.version.incrementAndGet();
    }

    public long getCurrentVersion() {
        return version.get();
    }

    public void markSaved(long savedVer) {
        this.savedVersion = savedVer;
        if (this.version.get() <= savedVer) {
            this.dirty.set(false);
        }
    }
}
