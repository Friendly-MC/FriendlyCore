package dev.lyy.friendlycore.modules.auctions.enums;

import java.util.Locale;

/**
 * Defines available sorting orders for auction house listings.
 */
public enum AuctionSorting {
    NEWEST("Newest", "newest"),
    OLDEST("Oldest", "oldest"),
    CHEAPEST("Lowest Price", "cheapest"),
    PRICIEST("Highest Price", "priciest");

    private final String displayName;
    private final String id;

    AuctionSorting(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getId() {
        return id;
    }

    public AuctionSorting next() {
        AuctionSorting[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public AuctionSorting previous() {
        AuctionSorting[] values = values();
        return values[(ordinal() - 1 + values.length) % values.length];
    }

    public static AuctionSorting fromId(String input) {
        return fromString(input);
    }

    public static AuctionSorting fromString(String input) {
        if (input == null || input.isBlank()) return NEWEST;
        for (AuctionSorting sorting : values()) {
            if (sorting.id.equalsIgnoreCase(input) || sorting.name().equalsIgnoreCase(input)) {
                return sorting;
            }
        }
        return NEWEST;
    }
}
