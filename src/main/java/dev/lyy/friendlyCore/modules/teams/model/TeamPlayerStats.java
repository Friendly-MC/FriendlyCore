package dev.lyy.friendlycore.modules.teams.model;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe statistics tracked per player for team contributions and leaderboard scoring.
 */
public class TeamPlayerStats {

    private final UUID playerUuid;
    private volatile String username;
    private final AtomicInteger kills;
    private final AtomicInteger deaths;
    private final AtomicInteger mobKills;
    private final AtomicInteger blocksBroken;
    private final AtomicInteger blocksPlaced;
    private final AtomicLong playtimeSeconds;
    private final AtomicInteger level;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong savedRevision = new AtomicLong();

    public TeamPlayerStats(UUID playerUuid, String username) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        this.username = username != null ? username : "";
        this.kills = new AtomicInteger(0);
        this.deaths = new AtomicInteger(0);
        this.mobKills = new AtomicInteger(0);
        this.blocksBroken = new AtomicInteger(0);
        this.blocksPlaced = new AtomicInteger(0);
        this.playtimeSeconds = new AtomicLong(0L);
        this.level = new AtomicInteger(1);
        setDirty(false);
    }

    public TeamPlayerStats(UUID playerUuid, String username, int kills, int deaths, int mobKills, int blocksBroken, int blocksPlaced, long playtimeSeconds, int level) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid cannot be null");
        this.username = username != null ? username : "";
        this.kills = new AtomicInteger(kills);
        this.deaths = new AtomicInteger(deaths);
        this.mobKills = new AtomicInteger(mobKills);
        this.blocksBroken = new AtomicInteger(blocksBroken);
        this.blocksPlaced = new AtomicInteger(blocksPlaced);
        this.playtimeSeconds = new AtomicLong(playtimeSeconds);
        this.level = new AtomicInteger(Math.max(1, level));
        setDirty(false);
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getKills() {
        return kills.get();
    }

    public void incrementKills() {
        this.kills.incrementAndGet();
        setDirty(true);
    }

    public int getDeaths() {
        return deaths.get();
    }

    public void incrementDeaths() {
        this.deaths.incrementAndGet();
        setDirty(true);
    }

    public int getMobKills() {
        return mobKills.get();
    }

    public void incrementMobKills() {
        this.mobKills.incrementAndGet();
        setDirty(true);
    }

    public int getBlocksBroken() {
        return blocksBroken.get();
    }

    public void incrementBlocksBroken() {
        this.blocksBroken.incrementAndGet();
        setDirty(true);
    }

    public int getBlocksPlaced() {
        return blocksPlaced.get();
    }

    public void incrementBlocksPlaced() {
        this.blocksPlaced.incrementAndGet();
        setDirty(true);
    }

    public long getPlaytimeSeconds() {
        return playtimeSeconds.get();
    }

    public void addPlaytimeSeconds(long seconds) {
        this.playtimeSeconds.addAndGet(seconds);
        setDirty(true);
    }

    public int getLevel() {
        return level.get();
    }

    public void setLevel(int level) {
        this.level.set(level);
        setDirty(true);
    }

    public double getKdr() {
        int d = deaths.get();
        if (d == 0) return kills.get();
        return (double) kills.get() / (double) d;
    }

    public boolean isDirty() {
        return revision.get() > savedRevision.get();
    }

    public void setDirty(boolean dirty) {
        if (dirty) revision.incrementAndGet();
        else markSaved(revision.get());
    }

    public long getRevision() { return revision.get(); }

    public void markSaved(long saved) { savedRevision.accumulateAndGet(saved, Math::max); }

    public TeamPlayerStats mergeLoaded(TeamPlayerStats loaded) {
        kills.addAndGet(loaded.getKills());
        deaths.addAndGet(loaded.getDeaths());
        mobKills.addAndGet(loaded.getMobKills());
        blocksBroken.addAndGet(loaded.getBlocksBroken());
        blocksPlaced.addAndGet(loaded.getBlocksPlaced());
        playtimeSeconds.addAndGet(loaded.getPlaytimeSeconds());
        level.accumulateAndGet(loaded.getLevel(), Math::max);
        return this;
    }

    public String serialize() {
        return kills.get() + ";" + deaths.get() + ";" + mobKills.get() + ";" + blocksBroken.get() + ";" + blocksPlaced.get() + ";" + playtimeSeconds.get() + ";" + level.get();
    }

    public static TeamPlayerStats deserialize(UUID uuid, String username, String str) {
        TeamPlayerStats stats = new TeamPlayerStats(uuid, username);
        if (str == null || str.isBlank()) return stats;
        try {
            String[] tokens = str.split(";");
            if (tokens.length >= 7) {
                stats.kills.set(Integer.parseInt(tokens[0]));
                stats.deaths.set(Integer.parseInt(tokens[1]));
                stats.mobKills.set(Integer.parseInt(tokens[2]));
                stats.blocksBroken.set(Integer.parseInt(tokens[3]));
                stats.blocksPlaced.set(Integer.parseInt(tokens[4]));
                stats.playtimeSeconds.set(Long.parseLong(tokens[5]));
                stats.level.set(Integer.parseInt(tokens[6]));
            }
        } catch (Exception ignored) {}
        return stats;
    }
}
