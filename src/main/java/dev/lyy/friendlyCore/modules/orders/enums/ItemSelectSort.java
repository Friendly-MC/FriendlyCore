package dev.lyy.friendlycore.modules.orders.enums;

public enum ItemSelectSort {
    A_Z("A-Z"),
    Z_A("Z-A"),
    HIGHEST_PRICE("Highest Price"),
    LOWEST_PRICE("Lowest Price");

    private final String displayName;

    ItemSelectSort(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
