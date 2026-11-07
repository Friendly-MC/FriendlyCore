package dev.lyy.friendlycore.modules.auctions.model;

import dev.lyy.friendlycore.modules.auctions.enums.AuctionSorting;

import java.util.UUID;

/**
 * Stores persistent auction user profile data (e.g. bonus listing limits)
 * and in-memory GUI session state (active category filter, sorting preference, search query).
 */
public class AuctionUser {

    private final UUID uuid;
    private volatile String name;
    private volatile int extraLimit;
    private long extraLimitVersion;

    private volatile String selectedCategory = "all";
    private volatile AuctionSorting selectedSorting = AuctionSorting.NEWEST;
    private volatile String searchFilter = null;

    public AuctionUser(UUID uuid, String name, int extraLimit) {
        this.uuid = uuid;
        this.name = name;
        this.extraLimit = extraLimit;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getExtraLimit() {
        return extraLimit;
    }

    public synchronized void setExtraLimit(int extraLimit) {
        this.extraLimit = Math.max(0, extraLimit);
        extraLimitVersion++;
    }

    public synchronized long getExtraLimitVersion() {
        return extraLimitVersion;
    }

    public synchronized void applyLoadedExtraLimit(int extraLimit, long expectedVersion) {
        if (extraLimitVersion == expectedVersion) {
            this.extraLimit = Math.max(0, extraLimit);
        }
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public void setSelectedCategory(String selectedCategory) {
        this.selectedCategory = selectedCategory != null ? selectedCategory : "all";
    }

    public AuctionSorting getSelectedSorting() {
        return selectedSorting;
    }

    public void setSelectedSorting(AuctionSorting selectedSorting) {
        this.selectedSorting = selectedSorting != null ? selectedSorting : AuctionSorting.NEWEST;
    }

    public String getSearchFilter() {
        return searchFilter;
    }

    public void setSearchFilter(String searchFilter) {
        this.searchFilter = searchFilter;
    }
}
