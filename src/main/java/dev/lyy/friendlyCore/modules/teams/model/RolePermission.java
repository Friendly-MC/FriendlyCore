package dev.lyy.friendlycore.modules.teams.model;

/**
 * Granular permissions that can be assigned to custom team roles.
 */
public enum RolePermission {
    MANAGE_MEMBERS,
    SET_NAME,
    SWITCH_FRIENDLY_FIRE,
    ACCESS_BANK,
    MANAGE_ALLIANCE,
    USE_HOME,
    SET_HOME,
    DELETE_HOME,
    ACCESS_ENDERCHEST,
    INVITE,
    KICK,
    PROMOTE,
    DEMOTE,
    CHANGE_TAG,
    CHANGE_COLOR;

    public static RolePermission fromString(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        for (RolePermission perm : values()) {
            if (perm.name().equalsIgnoreCase(normalized)) {
                return perm;
            }
        }
        return null;
    }
}
