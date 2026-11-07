package dev.lyy.friendlycore.modules.economy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a thread-safe player economy account cached in memory.
 */
public class AccountData {

    private final UUID uuid;
    private volatile String username;
    private volatile double balance;
    private final AtomicBoolean payEnabled = new AtomicBoolean(true);
    private volatile long lastUpdated;
    private final AtomicLong currentVersion = new AtomicLong(0);
    private final AtomicLong savedVersion = new AtomicLong(0);
    private final AtomicBoolean placeholder = new AtomicBoolean(false);

    public static final double MAX_BALANCE = 1.0E15; // 1 Quadrillion cap (1.0E17 cents fits safely within Long.MAX_VALUE)

    public AccountData(UUID uuid, String username, double balance) {
        this(uuid, username, balance, false);
    }

    public AccountData(UUID uuid, String username, double balance, boolean placeholder) {
        this.uuid = uuid;
        this.username = username != null ? username : "Unknown";
        this.balance = roundCurrency(balance);
        this.lastUpdated = System.currentTimeMillis();
        this.placeholder.set(placeholder);
    }

    public AccountData(AccountData other) {
        synchronized (other) {
            this.uuid = other.uuid;
            this.username = other.username;
            this.balance = other.balance;
            this.payEnabled.set(other.payEnabled.get());
            this.lastUpdated = other.lastUpdated;
            this.currentVersion.set(other.currentVersion.get());
            this.savedVersion.set(other.savedVersion.get());
            this.placeholder.set(other.placeholder.get());
        }
    }

    private static double roundCurrency(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) return 0.0;
        if (val < 0.0) return 0.0;
        if (val > MAX_BALANCE) return MAX_BALANCE;
        return Math.round(val * 100.0) / 100.0;
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

    public synchronized double getBalance() {
        return balance;
    }

    public synchronized void setBalance(double balance) {
        if (Double.isNaN(balance) || Double.isInfinite(balance) || balance < 0) return;
        this.balance = roundCurrency(balance);
        this.lastUpdated = System.currentTimeMillis();
        this.currentVersion.incrementAndGet();
    }

    public synchronized boolean has(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) return false;
        return this.balance >= amount;
    }

    public synchronized boolean deposit(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) return false;
        if (this.balance + amount > MAX_BALANCE) return false;
        this.balance = roundCurrency(this.balance + amount);
        this.lastUpdated = System.currentTimeMillis();
        this.currentVersion.incrementAndGet();
        return true;
    }

    public synchronized boolean withdraw(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0 || this.balance < amount) return false;
        this.balance = roundCurrency(this.balance - amount);
        this.lastUpdated = System.currentTimeMillis();
        this.currentVersion.incrementAndGet();
        return true;
    }

    public boolean isPayEnabled() {
        return payEnabled.get();
    }

    public void setPayEnabled(boolean enabled) {
        this.payEnabled.set(enabled);
        this.currentVersion.incrementAndGet();
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public boolean isDirty() {
        return currentVersion.get() > savedVersion.get();
    }

    public synchronized void setDirty(boolean dirty) {
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

    public synchronized AccountData snapshot() {
        return new AccountData(this);
    }

    public boolean isPlaceholder() {
        return placeholder.get();
    }

    public void setPlaceholder(boolean placeholder) {
        this.placeholder.set(placeholder);
    }

    public boolean isLoaded() {
        return !placeholder.get();
    }

    public void setLoaded(boolean loaded) {
        this.placeholder.set(!loaded);
    }
}
