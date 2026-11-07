package dev.lyy.friendlycore.modules.homes.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container and cache for all homes belonging to a single player profile.
 */
public class PlayerHomes {

    private final UUID playerUuid;
    private final String username;
    private final Map<Integer, HomeData> homes = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public PlayerHomes(UUID playerUuid, String username) {
        this.playerUuid = playerUuid;
        this.username = username;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getUsername() {
        return username;
    }

    public Map<Integer, HomeData> getHomes() {
        return homes;
    }

    public int getHomesCount() {
        return homes.size();
    }

    public HomeData getHome(int slot) {
        return homes.get(slot);
    }

    /**
     * Resolves a home by numerical slot index, custom name, or common default aliases.
     */
    public HomeData getHomeByNameOrSlot(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String cleanQuery = query.trim();

        try {
            int slot = Integer.parseInt(cleanQuery);
            HomeData bySlot = homes.get(slot);
            if (bySlot != null) {
                return bySlot;
            }
        } catch (NumberFormatException ignored) {}

        for (HomeData home : homes.values()) {
            if (home.getName() != null && !home.getName().isBlank() && home.getName().equalsIgnoreCase(cleanQuery)) {
                return home;
            }
        }

        // Shorthand slot notations (e.g. "home1" or "home 1")
        for (HomeData home : homes.values()) {
            if (String.valueOf(home.getSlot()).equalsIgnoreCase(cleanQuery)
                    || ("home" + home.getSlot()).equalsIgnoreCase(cleanQuery)
                    || ("home " + home.getSlot()).equalsIgnoreCase(cleanQuery)) {
                return home;
            }
        }

        if (cleanQuery.equalsIgnoreCase("home") || cleanQuery.equalsIgnoreCase("default") || cleanQuery.equalsIgnoreCase("main")) {
            HomeData slot1 = homes.get(1);
            if (slot1 != null) {
                return slot1;
            }
            if (!homes.isEmpty()) {
                return homes.values().iterator().next();
            }
        }

        return null;
    }

    public void setHome(HomeData home) {
        if (home != null) {
            homes.put(home.getSlot(), home);
            this.dirty = true;
        }
    }

    public HomeData removeHome(int slot) {
        HomeData removed = homes.remove(slot);
        if (removed != null) {
            this.dirty = true;
        }
        return removed;
    }

    public void clearHomes() {
        homes.clear();
        this.dirty = true;
    }

    public int getNextAvailableSlot(int maxLimit) {
        for (int i = 1; i <= maxLimit; i++) {
            if (!homes.containsKey(i)) {
                return i;
            }
        }
        return -1; // All slots up to limit are occupied
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }
}
