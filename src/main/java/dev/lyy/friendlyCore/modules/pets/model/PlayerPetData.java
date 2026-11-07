package dev.lyy.friendlycore.modules.pets.model;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe container for a player's pet collection and preferences.
 */
public class PlayerPetData {

    private final UUID uuid;
    private String username;
    private final Set<String> unlockedPets;
    private final Map<String, Long> unlockedAt;
    public static final int MAX_ACTIVE_PETS = 2;
    private volatile List<String> activePets = List.of();
    private volatile boolean hidePet;
    private volatile long lastUpdated;
    private volatile boolean dirty;

    public PlayerPetData(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username != null ? username : "";
        this.unlockedPets = ConcurrentHashMap.newKeySet();
        this.unlockedAt = new ConcurrentHashMap<>();
        this.hidePet = false;
        this.lastUpdated = System.currentTimeMillis();
        this.dirty = false;
    }

    public PlayerPetData(UUID uuid, String username, Set<String> unlockedPets,
                         Map<String, Long> unlockedAt, String activePet,
                         boolean hidePet, long lastUpdated) {
        this(uuid, username, unlockedPets, unlockedAt, activePet, null, hidePet, lastUpdated);
    }

    public PlayerPetData(UUID uuid, String username, Set<String> unlockedPets,
                         Map<String, Long> unlockedAt, String activePet, String secondaryPet,
                         boolean hidePet, long lastUpdated) {
        this.uuid = uuid;
        this.username = username != null ? username : "";
        this.unlockedPets = ConcurrentHashMap.newKeySet();
        if (unlockedPets != null) {
            this.unlockedPets.addAll(unlockedPets);
        }
        this.unlockedAt = new ConcurrentHashMap<>();
        if (unlockedAt != null) {
            this.unlockedAt.putAll(unlockedAt);
        }
        setActivePets(java.util.Arrays.asList(activePet, secondaryPet));
        this.hidePet = hidePet;
        this.lastUpdated = lastUpdated > 0 ? lastUpdated : System.currentTimeMillis();
        this.dirty = false;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username != null && !username.isBlank()) {
            this.username = username;
        }
    }

    public Set<String> getUnlockedPets() {
        return Collections.unmodifiableSet(unlockedPets);
    }

    public boolean hasPet(String petId) {
        if (petId == null || petId.isBlank()) return false;
        return unlockedPets.contains(petId.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean unlockPet(String petId) {
        if (petId == null || petId.isBlank()) return false;
        String id = petId.toLowerCase(Locale.ROOT);
        boolean added = unlockedPets.add(id);
        if (added) {
            unlockedAt.put(id, System.currentTimeMillis());
            this.lastUpdated = Math.max(System.currentTimeMillis(), this.lastUpdated + 1);
            this.dirty = true;
        }
        return added;
    }

    public synchronized boolean lockPet(String petId) {
        if (petId == null || petId.isBlank()) return false;
        String id = petId.toLowerCase(Locale.ROOT);
        boolean removed = unlockedPets.remove(id);
        unlockedAt.remove(id);
        unequipPet(id);
        if (removed) {
            this.lastUpdated = Math.max(System.currentTimeMillis(), this.lastUpdated + 1);
            this.dirty = true;
        }
        return removed;
    }

    public Map<String, Long> getUnlockedAtMap() {
        return Collections.unmodifiableMap(unlockedAt);
    }

    public long getUnlockedAt(String petId) {
        if (petId == null) return 0L;
        return unlockedAt.getOrDefault(petId.toLowerCase(Locale.ROOT), 0L);
    }

    public String getActivePet() {
        List<String> pets = activePets;
        return pets.isEmpty() ? null : pets.getFirst();
    }

    public void setActivePet(String activePet) {
        setActivePets(activePet == null ? List.of() : List.of(activePet));
    }

    public List<String> getActivePets() {
        return activePets;
    }

    public String getSecondaryActivePet() {
        List<String> pets = activePets;
        return pets.size() < 2 ? null : pets.get(1);
    }

    public boolean isActivePet(String petId) {
        return petId != null && activePets.contains(petId.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean equipPet(String petId) {
        if (!hasPet(petId) || isActivePet(petId) || activePets.size() >= MAX_ACTIVE_PETS) return false;
        List<String> pets = new ArrayList<>(activePets);
        pets.add(petId);
        setActivePets(pets);
        return true;
    }

    public synchronized boolean unequipPet(String petId) {
        if (!isActivePet(petId)) return false;
        List<String> pets = new ArrayList<>(activePets);
        pets.remove(petId.toLowerCase(Locale.ROOT));
        setActivePets(pets);
        return true;
    }

    public synchronized void setActivePets(Collection<String> petIds) {
        List<String> pets = new ArrayList<>();
        if (petIds != null) {
            for (String id : petIds) {
                if (id == null || id.isBlank()) continue;
                id = id.trim().toLowerCase(Locale.ROOT);
                if (hasPet(id) && !pets.contains(id)) pets.add(id);
                if (pets.size() == MAX_ACTIVE_PETS) break;
            }
        }
        if (!activePets.equals(pets)) {
            this.activePets = List.copyOf(pets);
            this.lastUpdated = Math.max(System.currentTimeMillis(), this.lastUpdated + 1);
            this.dirty = true;
        }
    }

    public boolean isHidePet() {
        return hidePet;
    }

    public synchronized void setHidePet(boolean hidePet) {
        if (this.hidePet != hidePet) {
            this.hidePet = hidePet;
            this.lastUpdated = Math.max(System.currentTimeMillis(), this.lastUpdated + 1);
            this.dirty = true;
        }
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public synchronized PlayerPetData snapshot() {
        return new PlayerPetData(uuid, username, Set.copyOf(unlockedPets), Map.copyOf(unlockedAt),
                getActivePet(), getSecondaryActivePet(), hidePet, lastUpdated);
    }

    public synchronized void markSaved(long savedAt) {
        if (lastUpdated == savedAt) dirty = false;
    }
}
