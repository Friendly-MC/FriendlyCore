package dev.lyy.friendlycore.modules.teams.model;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a team domain model containing all team state, member hierarchies,
 * shared vault storage, homes, color tags, and alliances.
 */
public class Team {

    public static final String DEFAULT_HOME = "home";
    public static final String DEFAULT_COLOR = "#00b1ff";

    private final UUID uuid;
    private String name;
    private String tag;
    private TeamOwner owner;
    private final Map<UUID, TeamMember> members = new ConcurrentHashMap<>();
    private final List<UUID> allies = new CopyOnWriteArrayList<>();
    private double bankBalance;
    private TeamEnderChest enderChest;
    private final Map<String, TeamHome> homes = new ConcurrentHashMap<>();
    private int maxHomes;
    private long lastTagChange;
    private String colorKey;
    private boolean friendlyFireEnabled;
    private boolean closed;
    private int rank;
    private double score;
    private final long createdAt;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong savedRevision = new AtomicLong();
    private boolean remoteUpdate;

    public Team(UUID uuid, String name, String tag, TeamOwner owner) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.name = name != null && !name.isBlank() ? name : tag;
        this.tag = Objects.requireNonNull(tag, "Tag cannot be null");
        this.owner = owner;
        if (owner != null) {
            this.members.put(owner.getUniqueId(), owner);
        }
        this.bankBalance = 0.0;
        this.enderChest = new TeamEnderChest(6, Component.text("Team Enderchest"), this);
        this.maxHomes = 1;
        this.lastTagChange = 0L;
        this.colorKey = DEFAULT_COLOR;
        this.friendlyFireEnabled = true;
        this.closed = false;
        this.rank = 0;
        this.score = 0.0;
        this.createdAt = System.currentTimeMillis();
        setDirty(true);
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public synchronized void setName(String name) {
        this.name = name;
        setDirty(true);
    }

    public String getTag() {
        return tag;
    }

    public synchronized void setTag(String tag) {
        this.tag = tag;
        setDirty(true);
    }

    public TeamOwner getOwner() {
        return owner;
    }

    public synchronized void setOwner(TeamOwner owner) {
        this.owner = owner;
        if (owner != null) {
            this.members.put(owner.getUniqueId(), owner);
        }
        setDirty(true);
    }

    public boolean isOwner(UUID playerUuid) {
        return owner != null && owner.getUniqueId().equals(playerUuid);
    }

    public Map<UUID, TeamMember> getMembers() {
        return members;
    }

    public Collection<TeamMember> getMemberList() {
        return members.values();
    }

    public boolean hasMember(UUID playerUuid) {
        return playerUuid != null && members.containsKey(playerUuid);
    }

    public TeamMember getMember(UUID playerUuid) {
        if (playerUuid == null) return null;
        return members.get(playerUuid);
    }

    public TeamMember getMemberByName(String username) {
        if (username == null) return null;
        for (TeamMember member : members.values()) {
            if (username.equalsIgnoreCase(member.getUsername())) return member;
        }
        return null;
    }

    public synchronized void addMember(TeamMember member) {
        if (member == null) return;
        members.put(member.getUniqueId(), member);
        setDirty(true);
    }

    public synchronized void removeMember(UUID playerUuid) {
        if (playerUuid == null) return;
        members.remove(playerUuid);
        setDirty(true);
    }

    public int getMemberCount() {
        return members.size();
    }

    public List<UUID> getAllies() {
        return allies;
    }

    public boolean isAlly(UUID otherTeamUuid) {
        return otherTeamUuid != null && allies.contains(otherTeamUuid);
    }

    public synchronized void addAlly(UUID otherTeamUuid) {
        if (otherTeamUuid == null || allies.contains(otherTeamUuid)) return;
        allies.add(otherTeamUuid);
        setDirty(true);
    }

    public synchronized void removeAlly(UUID otherTeamUuid) {
        if (otherTeamUuid == null) return;
        allies.remove(otherTeamUuid);
        setDirty(true);
    }

    public synchronized double getBankBalance() {
        return bankBalance;
    }

    public synchronized void setBankBalance(double bankBalance) {
        this.bankBalance = Math.max(0.0, bankBalance);
        setDirty(true);
    }

    public synchronized void setBankBalanceFromStorage(double bankBalance) {
        this.bankBalance = Math.max(0.0, bankBalance);
    }

    public synchronized void deposit(double amount) {
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        this.bankBalance += amount;
        setDirty(true);
    }

    public synchronized boolean withdraw(double amount) {
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount) || bankBalance < amount) return false;
        this.bankBalance -= amount;
        setDirty(true);
        return true;
    }

    public TeamEnderChest getEnderChest() {
        return enderChest;
    }

    public synchronized void setEnderChest(TeamEnderChest enderChest) {
        this.enderChest = enderChest;
        setDirty(true);
    }

    public Map<String, TeamHome> getHomes() {
        return homes;
    }

    public TeamHome getHome(String name) {
        if (name == null || name.isBlank()) name = DEFAULT_HOME;
        return homes.get(name.toLowerCase());
    }

    public synchronized void setHome(String name, Location location) {
        setHome(name, location, "");
    }

    public synchronized void setHome(String name, Location location, String server) {
        if (name == null || name.isBlank()) name = DEFAULT_HOME;
        TeamHome home = TeamHome.fromLocation(name.toLowerCase(), location, server);
        if (home != null) {
            homes.put(name.toLowerCase(), home);
            setDirty(true);
        }
    }

    public synchronized boolean deleteHome(String name) {
        if (name == null || name.isBlank()) name = DEFAULT_HOME;
        boolean removed = homes.remove(name.toLowerCase()) != null;
        if (removed) {
            setDirty(true);
        }
        return removed;
    }

    public int getMaxHomes() {
        return maxHomes;
    }

    public synchronized void setMaxHomes(int maxHomes) {
        this.maxHomes = Math.max(1, maxHomes);
        setDirty(true);
    }

    public long getLastTagChange() {
        return lastTagChange;
    }

    public synchronized void setLastTagChange(long lastTagChange) {
        this.lastTagChange = lastTagChange;
        setDirty(true);
    }

    public String getColorKey() {
        return colorKey;
    }

    public synchronized void setColorKey(String colorKey) {
        this.colorKey = colorKey != null ? colorKey.toLowerCase(Locale.ROOT) : DEFAULT_COLOR;
        setDirty(true);
    }

    public boolean isFriendlyFireEnabled() {
        return friendlyFireEnabled;
    }

    public synchronized void setFriendlyFireEnabled(boolean friendlyFireEnabled) {
        this.friendlyFireEnabled = friendlyFireEnabled;
        setDirty(true);
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized void setClosed(boolean closed) {
        this.closed = closed;
        setDirty(true);
    }

    public int getRank() {
        return rank;
    }

    public synchronized void setRank(int rank) {
        this.rank = rank;
    }

    public double getScore() {
        return score;
    }

    public synchronized void setScore(double score) {
        this.score = score;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isDirty() {
        return revision.get() > savedRevision.get() || (enderChest != null && enderChest.isDirty());
    }

    public synchronized void setDirty(boolean dirty) {
        if (dirty) {
            if (!remoteUpdate) revision.incrementAndGet();
        } else {
            markSaved(revision.get());
        }
        if (!dirty && enderChest != null) {
            enderChest.setDirty(false);
        }
    }

    public long getRevision() { return revision.get(); }

    public void markSaved(long saved) { savedRevision.accumulateAndGet(saved, Math::max); }

    public synchronized void applyRemoteUpdate(Runnable update) {
        remoteUpdate = true;
        try {
            update.run();
        } finally {
            remoteUpdate = false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Team team)) return false;
        return Objects.equals(uuid, team.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public String toString() {
        return "Team{" + "tag='" + tag + '\'' + ", name='" + name + '\'' + ", members=" + members.size() + '}';
    }
}
