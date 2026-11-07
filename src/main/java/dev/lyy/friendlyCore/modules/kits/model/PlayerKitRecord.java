package dev.lyy.friendlycore.modules.kits.model;

/**
 * Record of a player's claim state for a specific kit.
 */
public class PlayerKitRecord {

    private final String kitName;
    private volatile long cooldownUntil;
    private volatile boolean oneTimeClaimed;
    private volatile boolean bought;
    private volatile long lastClaimed;

    public PlayerKitRecord(String kitName) {
        this(kitName, 0L, false, false, 0L);
    }

    public PlayerKitRecord(String kitName, long cooldownUntil, boolean oneTimeClaimed, boolean bought, long lastClaimed) {
        this.kitName = kitName;
        this.cooldownUntil = cooldownUntil;
        this.oneTimeClaimed = oneTimeClaimed;
        this.bought = bought;
        this.lastClaimed = lastClaimed;
    }

    public String getKitName() {
        return kitName;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    public void setCooldownUntil(long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    public boolean isOneTimeClaimed() {
        return oneTimeClaimed;
    }

    public void setOneTimeClaimed(boolean oneTimeClaimed) {
        this.oneTimeClaimed = oneTimeClaimed;
    }

    public boolean isBought() {
        return bought;
    }

    public void setBought(boolean bought) {
        this.bought = bought;
    }

    public long getLastClaimed() {
        return lastClaimed;
    }

    public void setLastClaimed(long lastClaimed) {
        this.lastClaimed = lastClaimed;
    }

    public void merge(PlayerKitRecord other) {
        if (other == null) return;
        this.cooldownUntil = Math.max(this.cooldownUntil, other.cooldownUntil);
        this.oneTimeClaimed = this.oneTimeClaimed || other.oneTimeClaimed;
        this.bought = this.bought || other.bought;
        this.lastClaimed = Math.max(this.lastClaimed, other.lastClaimed);
    }

    public PlayerKitRecord clone() {
        return new PlayerKitRecord(kitName, cooldownUntil, oneTimeClaimed, bought, lastClaimed);
    }
}
