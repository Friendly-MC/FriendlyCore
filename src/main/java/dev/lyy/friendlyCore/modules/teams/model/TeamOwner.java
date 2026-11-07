package dev.lyy.friendlycore.modules.teams.model;

import java.util.UUID;

/**
 * Specialized member representing the team leader, bypassing standard role checks.
 */
public class TeamOwner extends TeamMember {

    public TeamOwner(UUID uuid, String username, TeamRole ownerRole, long joinedTimestamp) {
        super(uuid, username, ownerRole, joinedTimestamp);
    }

    @Override
    public boolean hasPermission(RolePermission permission) {
        return true;
    }

    @Override
    public boolean isOwner() {
        return true;
    }
}
