package dev.lyy.friendlycore.modules.auctions.model;

import java.util.UUID;

/**
 * Represents a pending monetary payout owed to an offline seller upon their next login.
 */
public record AuctionPayout(int id, UUID userUuid, double amount, String reason, long createdAt) {

    public int getId() {
        return id;
    }

    public UUID getUserUuid() {
        return userUuid;
    }

    public double getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
