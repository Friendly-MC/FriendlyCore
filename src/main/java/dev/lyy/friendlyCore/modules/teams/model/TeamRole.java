package dev.lyy.friendlycore.modules.teams.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Configurable hierarchical role determining rank priorities and granular permissions within a team.
 */
public class TeamRole {

    private final int priority;
    private final String id;
    private final String name;
    private final Set<RolePermission> permissions;

    public TeamRole(int priority, String id, String name, Set<RolePermission> permissions) {
        this.priority = priority;
        this.id = id != null ? id : String.valueOf(priority);
        this.name = name != null ? name : "Member";
        this.permissions = permissions == null || permissions.isEmpty() ? EnumSet.noneOf(RolePermission.class) : EnumSet.copyOf(permissions);
    }

    public int getPriority() {
        return priority;
    }

    public String getId() {
        return id;
    }

    public String getKey() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<RolePermission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    public boolean hasPermission(RolePermission permission) {
        if (priority == -1) return true; // Team leader inherently retains all permissions
        return permissions.contains(permission) || switch (permission) {
            case INVITE, KICK, PROMOTE, DEMOTE -> permissions.contains(RolePermission.MANAGE_MEMBERS);
            case CHANGE_TAG -> permissions.contains(RolePermission.SET_NAME);
            case DELETE_HOME -> permissions.contains(RolePermission.SET_HOME);
            default -> false;
        };
    }

    public boolean isOwner() {
        return priority == -1;
    }

    public boolean isHigherThan(TeamRole other) {
        if (other == null) return true;
        if (this.priority == -1) return other.priority != -1;
        if (other.priority == -1) return false;
        return this.priority > other.priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamRole teamRole)) return false;
        return priority == teamRole.priority && Objects.equals(name, teamRole.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(priority, name);
    }

    @Override
    public String toString() {
        return name + " (" + priority + ")";
    }
}
