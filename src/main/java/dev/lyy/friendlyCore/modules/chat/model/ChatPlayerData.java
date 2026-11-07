package dev.lyy.friendlycore.modules.chat.model;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent data model for player chat settings, ignore list, and infractions.
 */
public class ChatPlayerData {

    private final UUID uuid;
    private String username;
    private int points;
    private boolean messagesClosed;
    private final Set<UUID> ignoredPlayers;
    private long lastInfractionTime;

    public ChatPlayerData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        this.points = 0;
        this.messagesClosed = false;
        this.ignoredPlayers = ConcurrentHashMap.newKeySet();
        this.lastInfractionTime = 0L;
    }

    public ChatPlayerData(UUID uuid, String username, int points, boolean messagesClosed,
                          Set<UUID> ignoredPlayers, long lastInfractionTime) {
        this.uuid = uuid;
        this.username = username;
        this.points = points;
        this.messagesClosed = messagesClosed;
        this.ignoredPlayers = ConcurrentHashMap.newKeySet();
        if (ignoredPlayers != null) {
            this.ignoredPlayers.addAll(ignoredPlayers);
        }
        this.lastInfractionTime = lastInfractionTime;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = Math.max(0, points);
    }

    public void addPoints(int amount) {
        this.points += Math.max(0, amount);
    }

    public void takePoints(int amount) {
        this.points = Math.max(0, this.points - amount);
    }

    public boolean hasMessagesClosed() {
        return messagesClosed;
    }

    public void setMessagesClosed(boolean messagesClosed) {
        this.messagesClosed = messagesClosed;
    }

    public Set<UUID> getIgnoredPlayers() {
        return ignoredPlayers;
    }

    public boolean isIgnoring(UUID targetUuid) {
        return targetUuid != null && ignoredPlayers.contains(targetUuid);
    }

    public void addIgnored(UUID targetUuid) {
        if (targetUuid != null) {
            ignoredPlayers.add(targetUuid);
        }
    }

    public void removeIgnored(UUID targetUuid) {
        if (targetUuid != null) {
            ignoredPlayers.remove(targetUuid);
        }
    }

    public void clearIgnored() {
        ignoredPlayers.clear();
    }

    public long getLastInfractionTime() {
        return lastInfractionTime;
    }

    public void setLastInfractionTime(long lastInfractionTime) {
        this.lastInfractionTime = lastInfractionTime;
    }
}
