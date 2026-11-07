package dev.lyy.friendlycore.modules.auctions.enums;

/**
 * Identifies the type of audit-logged auction house transaction or administrative action.
 */
public enum AuctionActionType {
    PURCHASE(0, "Purchase"),
    CREATE_AUCTION(1, "Create"),
    CANCEL_SELF(2, "Cancel (Self)"),
    CANCEL_OTHER(3, "Cancel (Admin)"),
    TAKE_ITEM(4, "Admin Clone/Take"),
    PAYOUT(5, "Payout");

    private final int id;
    private final String displayName;

    AuctionActionType(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static AuctionActionType fromId(int id) {
        for (AuctionActionType type : values()) {
            if (type.id == id) return type;
        }
        return PURCHASE;
    }
}
