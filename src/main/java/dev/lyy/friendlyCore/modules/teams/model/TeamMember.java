package dev.lyy.friendlycore.modules.teams.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a player enrolled in a team with an assigned role hierarchy.
 */
public class TeamMember {

    private final UUID uuid;
    private String username;
    private TeamRole role;
    private final long joinedTimestamp;

    public TeamMember(UUID uuid, String username, TeamRole role, long joinedTimestamp) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.username = username != null ? username : uuid.toString().substring(0, 8);
        this.role = role;
        this.joinedTimestamp = joinedTimestamp > 0 ? joinedTimestamp : System.currentTimeMillis();
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public TeamRole getRole() {
        return role;
    }

    public void setRole(TeamRole role) {
        this.role = role;
    }

    public long getJoinedTimestamp() {
        return joinedTimestamp;
    }

    public boolean hasPermission(RolePermission permission) {
        return role != null && role.hasPermission(permission);
    }

    public boolean isOwner() {
        return role != null && role.isOwner();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamMember that)) return false;
        return Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }
}
