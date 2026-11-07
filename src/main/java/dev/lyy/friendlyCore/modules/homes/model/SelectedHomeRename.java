package dev.lyy.friendlycore.modules.homes.model;

import java.util.UUID;

/**
 * Tracks an active in-chat home renaming session for a player.
 */
public class SelectedHomeRename {

    private final UUID targetPlayerUuid;
    private final int slot;
    private final long timestamp;

    public SelectedHomeRename(UUID targetPlayerUuid, int slot) {
        this.targetPlayerUuid = targetPlayerUuid;
        this.slot = slot;
        this.timestamp = System.currentTimeMillis();
    }

    public UUID getTargetPlayerUuid() {
        return targetPlayerUuid;
    }

    public int getSlot() {
        return slot;
    }

    public boolean isExpired(long ttlMillis) {
        return System.currentTimeMillis() - timestamp > ttlMillis;
    }
}
