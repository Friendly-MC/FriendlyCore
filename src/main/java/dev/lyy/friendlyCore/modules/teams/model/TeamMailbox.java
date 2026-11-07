package dev.lyy.friendlycore.modules.teams.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages pending incoming invites for a player or team with automatic expiration cleanup.
 */
public class TeamMailbox {

    private final UUID ownerUuid;
    private final List<TeamInvite> invites = new CopyOnWriteArrayList<>();

    public TeamMailbox(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public List<TeamInvite> getInvites() {
        cleanExpired();
        return Collections.unmodifiableList(new ArrayList<>(invites));
    }

    public void addInvite(TeamInvite invite) {
        if (invite == null) return;
        cleanExpired();
        // Replace previous invite from the same team for this category
        invites.removeIf(i -> i.getTeamUuid().equals(invite.getTeamUuid()) && i.getType() == invite.getType());
        invites.add(invite);
    }

    public TeamInvite getInvite(UUID teamUuid, TeamInvite.InviteType type) {
        cleanExpired();
        for (TeamInvite invite : invites) {
            if (invite.getTeamUuid().equals(teamUuid) && invite.getType() == type) {
                return invite;
            }
        }
        return null;
    }

    public boolean removeInvite(UUID teamUuid, TeamInvite.InviteType type) {
        return invites.removeIf(i -> i.getTeamUuid().equals(teamUuid) && i.getType() == type);
    }

    public void cleanExpired() {
        invites.removeIf(TeamInvite::isExpired);
    }

    public boolean hasInvites() {
        cleanExpired();
        return !invites.isEmpty();
    }
}
