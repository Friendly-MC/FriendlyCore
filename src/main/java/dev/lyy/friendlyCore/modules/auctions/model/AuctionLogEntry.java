package dev.lyy.friendlycore.modules.auctions.model;

import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;

import java.util.UUID;

/**
 * Represents an audit log record for an auction marketplace action.
 */
public record AuctionLogEntry(
        int id,
        UUID actorUuid,
        String actorName,
        AuctionActionType actionType,
        String extra,
        long timestamp
) {

    public AuctionLogEntry {
        actorName = actorName != null ? actorName : "Unknown";
        extra = extra != null ? extra : "";
    }

    public int getId() {
        return id;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public String getActorName() {
        return actorName;
    }

    public AuctionActionType getActionType() {
        return actionType;
    }

    public String getExtra() {
        return extra;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
