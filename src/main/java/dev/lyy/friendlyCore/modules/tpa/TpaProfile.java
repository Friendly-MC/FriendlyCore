package dev.lyy.friendlycore.modules.tpa;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory profile holding player TPA preferences, toggles, daily usage counts,
 * and active request state. Thread-safe for Folia and async access.
 */
public class TpaProfile {

    private final UUID uuid;
    private volatile String username;
    private volatile boolean tpaEnabled;
    private volatile boolean tpaHereEnabled;
    private volatile boolean tpAutoEnabled;
    private volatile boolean showGui;
    private final AtomicInteger dailyCount;
    private volatile String lastTeleportDay;
    private volatile long lastUpdated;

    private final AtomicLong currentVersion = new AtomicLong(0);
    private final AtomicLong savedVersion = new AtomicLong(0);
    private volatile boolean awaitingLoad;
    private int changedSettings;

    public static TpaProfile pending(UUID uuid, String username) {
        TpaProfile profile = new TpaProfile(uuid, username);
        profile.awaitingLoad = true;
        return profile;
    }

    /** Merge only explicitly changed preferences into the confirmed database profile. */
    public synchronized void restore(TpaProfile stored) {
        if (!awaitingLoad) return;
        if ((changedSettings & 1) == 0) tpaEnabled = stored.isTpaEnabled();
        if ((changedSettings & 2) == 0) tpaHereEnabled = stored.isTpaHereEnabled();
        if ((changedSettings & 4) == 0) tpAutoEnabled = stored.isTpAutoEnabled();
        if ((changedSettings & 8) == 0) showGui = stored.isShowGui();
        checkDailyReset();
        dailyCount.addAndGet(stored.getDailyCount());
        awaitingLoad = false;
    }

    // Target player UUID -> cooldown expiry timestamp (ms)
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    // Sender player UUID -> pending incoming TPA request
    private final Map<UUID, TpaRequest> incomingRequests = new ConcurrentHashMap<>();

    // Target player UUID -> pending outgoing TPA request
    private final Map<UUID, TpaRequest> outgoingRequests = new ConcurrentHashMap<>();

    public TpaProfile(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        this.tpaEnabled = true;
        this.tpaHereEnabled = true;
        this.tpAutoEnabled = false;
        this.showGui = true;
        this.dailyCount = new AtomicInteger(0);
        this.lastTeleportDay = LocalDate.now().toString();
        this.lastUpdated = System.currentTimeMillis();
    }

    public TpaProfile(UUID uuid, String username, boolean tpaEnabled, boolean tpaHereEnabled, boolean tpAutoEnabled, boolean showGui, int dailyCount, String lastTeleportDay, long lastUpdated) {
        this.uuid = uuid;
        this.username = username;
        this.tpaEnabled = tpaEnabled;
        this.tpaHereEnabled = tpaHereEnabled;
        this.tpAutoEnabled = tpAutoEnabled;
        this.showGui = showGui;
        this.dailyCount = new AtomicInteger(dailyCount);
        this.lastTeleportDay = lastTeleportDay != null ? lastTeleportDay : LocalDate.now().toString();
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

    public boolean isTpaEnabled() {
        return tpaEnabled;
    }

    public synchronized void setTpaEnabled(boolean tpaEnabled) {
        if (awaitingLoad) changedSettings |= 1;
        this.tpaEnabled = tpaEnabled;
        markDirty();
    }

    public boolean isTpaHereEnabled() {
        return tpaHereEnabled;
    }

    public synchronized void setTpaHereEnabled(boolean tpaHereEnabled) {
        if (awaitingLoad) changedSettings |= 2;
        this.tpaHereEnabled = tpaHereEnabled;
        markDirty();
    }

    public boolean isTpAutoEnabled() {
        return tpAutoEnabled;
    }

    public synchronized void setTpAutoEnabled(boolean tpAutoEnabled) {
        if (awaitingLoad) changedSettings |= 4;
        this.tpAutoEnabled = tpAutoEnabled;
        markDirty();
    }

    public boolean isShowGui() {
        return showGui;
    }

    public synchronized void setShowGui(boolean showGui) {
        if (awaitingLoad) changedSettings |= 8;
        this.showGui = showGui;
        markDirty();
    }

    public int getDailyCount() {
        checkDailyReset();
        return dailyCount.get();
    }

    public void setDailyCount(int count) {
        this.dailyCount.set(count);
        markDirty();
    }

    public synchronized void incrementDailyCount() {
        checkDailyReset();
        this.dailyCount.incrementAndGet();
        markDirty();
    }

    public String getLastTeleportDay() {
        return lastTeleportDay;
    }

    public void setLastTeleportDay(String lastTeleportDay) {
        this.lastTeleportDay = lastTeleportDay;
        markDirty();
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public long getVersion() {
        return currentVersion.get();
    }

    public boolean isDirty() {
        return !awaitingLoad && currentVersion.get() > savedVersion.get();
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

    private synchronized void checkDailyReset() {
        String today = LocalDate.now().toString();
        if (!today.equals(this.lastTeleportDay)) {
            this.lastTeleportDay = today;
            this.dailyCount.set(0);
            markDirty();
        }
    }

    public boolean isOnCooldown(UUID targetUuid) {
        if (targetUuid == null) return false;
        Long expires = playerCooldowns.get(targetUuid);
        if (expires == null) return false;
        if (System.currentTimeMillis() >= expires) {
            playerCooldowns.remove(targetUuid);
            return false;
        }
        return true;
    }

    public long getCooldownRemainingMs(UUID targetUuid) {
        if (targetUuid == null) return 0;
        Long expires = playerCooldowns.get(targetUuid);
        if (expires == null) return 0;
        long remaining = expires - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public void setCooldown(UUID targetUuid, long durationMs) {
        if (targetUuid != null && durationMs > 0) {
            playerCooldowns.put(targetUuid, System.currentTimeMillis() + durationMs);
        }
    }

    public Map<UUID, TpaRequest> getIncomingRequests() {
        return incomingRequests;
    }

    public Map<UUID, TpaRequest> getOutgoingRequests() {
        return outgoingRequests;
    }

    public void addIncomingRequest(TpaRequest request) {
        incomingRequests.put(request.getSenderUuid(), request);
    }

    public void removeIncomingRequest(UUID senderUuid) {
        incomingRequests.remove(senderUuid);
    }

    public void addOutgoingRequest(TpaRequest request) {
        outgoingRequests.put(request.getTargetUuid(), request);
    }

    public void removeOutgoingRequest(UUID targetUuid) {
        outgoingRequests.remove(targetUuid);
    }
}
