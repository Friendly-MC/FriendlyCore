package dev.lyy.friendlycore.modules.teams.model;

import java.util.UUID;

/**
 * Represents an expiring invitation for a player recruitment or team alliance.
 */
public class TeamInvite {

    public enum InviteType {
        PLAYER,
        ALLY
    }

    private final UUID inviterUuid;
    private final String inviterName;
    private final UUID targetUuid;
    private final String targetName;
    private final UUID teamUuid;
    private final String teamTag;
    private final InviteType type;
    private final long createdAt;
    private final long expiresAt;

    public TeamInvite(UUID inviterUuid, String inviterName, UUID targetUuid, String targetName, UUID teamUuid, String teamTag, InviteType type, long lifetimeSeconds) {
        this.inviterUuid = inviterUuid;
        this.inviterName = inviterName != null ? inviterName : "";
        this.targetUuid = targetUuid;
        this.targetName = targetName != null ? targetName : "";
        this.teamUuid = teamUuid;
        this.teamTag = teamTag != null ? teamTag : "";
        this.type = type != null ? type : InviteType.PLAYER;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = this.createdAt + (lifetimeSeconds * 1000L);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public UUID getInviterUuid() {
        return inviterUuid;
    }

    public String getInviterName() {
        return inviterName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public UUID getTeamUuid() {
        return teamUuid;
    }

    public String getTeamTag() {
        return teamTag;
    }

    public InviteType getType() {
        return type;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
