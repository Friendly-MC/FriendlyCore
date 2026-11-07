package dev.lyy.friendlycore.modules.orders.enums;

public enum OrderSort {
    MOST_PAID("Most Paid"),
    MOST_DELIVERED("Most Delivered"),
    RECENTLY_LISTED("Recently Listed"),
    MOST_MONEY_PER_ITEM("Most Money Per Item");

    private final String displayName;

    OrderSort(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
