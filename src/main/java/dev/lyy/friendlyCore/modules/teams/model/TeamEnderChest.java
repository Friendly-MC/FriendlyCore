package dev.lyy.friendlycore.modules.teams.model;

import dev.lyy.friendlycore.FriendlyCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared virtual EnderChest / Vault for teams.
 * <p>
 * Handles item serialization via Paper's native byte codec with Bukkit object
 * streams as a fallback, encoded into Base64 for database persistence.
 */
public class TeamEnderChest implements InventoryHolder {

    private static final int MAX_ENCODED_BYTES = 12_000_000;
    private static final int MAX_DECODED_BYTES = 10_000_000;
    private static final int MAX_ITEM_BYTES = 8_000_000;

    private int rows;
    private int size;
    private Component title;
    private Team team;
    private Inventory inventory;
    private volatile boolean dirty;
    private volatile String storedSnapshot = "";

    public TeamEnderChest(int rows, Component title) {
        this(rows, title, null);
    }

    public TeamEnderChest(int rows, Component title, Team team) {
        this.rows = Math.min(6, Math.max(1, rows));
        this.size = this.rows * 9;
        this.title = title != null ? title : Component.text("Team Enderchest");
        this.team = team;
        this.dirty = false;
    }

    public String getStoredSnapshot() {
        return storedSnapshot;
    }

    /** Preserve stored and migrated bytes; only decode on the vault owner's entity thread. */
    public void setStoredSnapshot(String snapshot) {
        storedSnapshot = snapshot != null ? snapshot : "";
    }

    public void updateLayout(Component newTitle, int newRows) {
        int clampedRows = Math.min(6, Math.max(1, newRows));
        int newSize = clampedRows * 9;
        boolean titleChanged = newTitle != null && !newTitle.equals(this.title);
        boolean sizeChanged = newSize != this.size;

        if (this.inventory == null || titleChanged || sizeChanged) {
            ItemStack[] oldContents = this.inventory != null ? this.inventory.getContents() : new ItemStack[0];
            this.rows = clampedRows;
            this.size = newSize;
            if (newTitle != null) {
                this.title = newTitle;
            }
            if (Bukkit.getServer() != null) {
                this.inventory = Bukkit.createInventory(this, this.size, this.title);
                if (oldContents != null) {
                    for (int i = 0; i < Math.min(this.size, oldContents.length); i++) {
                        if (oldContents[i] != null) {
                            this.inventory.setItem(i, oldContents[i]);
                        }
                    }
                }
            }
        }
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public int getRows() {
        return rows;
    }

    public int getSize() {
        return size;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null && Bukkit.getServer() != null) {
            this.inventory = Bukkit.createInventory(this, this.size, this.title);
        }
        return inventory;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public ItemStack[] getContents() {
        Inventory inv = getInventory();
        return inv != null ? inv.getContents() : new ItemStack[0];
    }

    public void setContents(ItemStack[] contents) {
        Inventory inv = getInventory();
        if (inv == null || contents == null) return;
        for (int i = 0; i < size; i++) {
            if (i < contents.length) {
                inv.setItem(i, contents[i]);
            } else {
                inv.setItem(i, null);
            }
        }
    }

    /**
     * Serializes items into a compact Base64 representation.
     * Uses Paper ItemStack.serializeAsBytes() if available, with Bukkit fallback.
     */
    public String serialize() {
        Inventory inv = getInventory();
        if (inv == null) throw new IllegalStateException("Vault inventory is unavailable");
        try {
            Map<Integer, byte[]> serializedMap = new HashMap<>();
            ItemStack[] contents = inv.getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item != null && !item.isEmpty() && !item.getType().isAir()) {
                    try {
                        byte[] bytes = item.serializeAsBytes();
                        serializedMap.put(i, bytes);
                    } catch (Throwable fallback) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                            boos.writeObject(item);
                            serializedMap.put(i, baos.toByteArray());
                        }
                    }
                }
            }

            if (serializedMap.isEmpty()) return "";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
                boos.writeInt(serializedMap.size());
                for (Map.Entry<Integer, byte[]> entry : serializedMap.entrySet()) {
                    boos.writeInt(entry.getKey());
                    boos.writeInt(entry.getValue().length);
                    boos.write(entry.getValue());
                }
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize team enderchest", e);
        }
    }

    /**
     * Deserializes items from Base64 (supporting both FriendlyCore and legacy ZelTeams formats).
     */
    public boolean deserialize(String base64Data) {
        Inventory inv = getInventory();
        if (base64Data == null || base64Data.isBlank()) {
            if (inv != null) inv.clear();
            return true;
        }

        String cleanData = base64Data.trim();
        if (cleanData.startsWith("NBT:")) {
            cleanData = cleanData.substring(4).trim();
        }
        if (cleanData.isBlank()) {
            if (inv != null) inv.clear();
            return true;
        }

        try {
            if (cleanData.length() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("encoded vault data is too large");
            byte[] rawBytes = Base64.getDecoder().decode(cleanData);
            if (rawBytes.length > MAX_DECODED_BYTES) throw new IllegalArgumentException("decoded vault data is too large");

            ItemStack[] decoded = new ItemStack[this.size];
            ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
            boolean parseSucceeded = false;

            // Standard Java Object Serialization stream magic bytes (0xAC 0xED)
            if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0xAC && (rawBytes[1] & 0xFF) == 0xED) {
                try (BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                    int headerCount = bois.readInt();
                    if (headerCount >= 0 && headerCount <= 1000) {
                        for (int i = 0; i < headerCount; i++) {
                            int slot = bois.readInt();
                            if (slot < 0 || slot >= this.size) throw new IllegalArgumentException("Vault slot exceeds configured size");
                            int len = bois.readInt();
                            if (len > 0 && len <= MAX_ITEM_BYTES) {
                                byte[] itemBytes = new byte[len];
                                bois.readFully(itemBytes);
                                if (slot >= 0 && slot < this.size) {
                                    ItemStack stack = null;
                                    try {
                                        stack = ItemStack.deserializeBytes(itemBytes);
                                    } catch (Throwable fallback) {
                                        try (BukkitObjectInputStream fallbackIn = new BukkitObjectInputStream(new ByteArrayInputStream(itemBytes))) {
                                            Object obj = fallbackIn.readObject();
                                            if (obj instanceof ItemStack is) {
                                                stack = is;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    if (stack != null && !stack.getType().isAir()) {
                                        decoded[slot] = stack;
                                    } else {
                                        throw new IllegalArgumentException("Invalid vault item");
                                    }
                                }
                            } else {
                                throw new IllegalArgumentException("Invalid vault item length");
                            }
                        }
                        parseSucceeded = true;
                    }
                } catch (Throwable e) {
                    // Fall back to legacy arrays or single-object serializations if header parsing fails
                    try (BukkitObjectInputStream fallbackStream = new BukkitObjectInputStream(new ByteArrayInputStream(rawBytes))) {
                        Object obj = fallbackStream.readObject();
                        if (obj instanceof ItemStack[] array) {
                            for (int i = this.size; i < array.length; i++) {
                                if (array[i] != null && !array[i].getType().isAir()) throw new IllegalArgumentException("Vault exceeds configured size");
                            }
                            for (int i = 0; i < Math.min(this.size, array.length); i++) {
                                decoded[i] = array[i];
                            }
                            parseSucceeded = true;
                        } else if (obj instanceof ItemStack is) {
                            decoded[0] = is;
                            parseSucceeded = true;
                        }
                    } catch (Throwable ignored) {}
                }
            } else {
                try (DataInputStream dis = new DataInputStream(bais)) {
                    int headerCount = dis.readInt();
                    if (headerCount >= 0 && headerCount <= 1000) {
                        for (int i = 0; i < headerCount; i++) {
                            int slot = dis.readInt();
                            if (slot < 0 || slot >= this.size) throw new IllegalArgumentException("Vault slot exceeds configured size");
                            int len = dis.readInt();
                            if (len > 0 && len <= MAX_ITEM_BYTES) {
                                byte[] itemBytes = new byte[len];
                                dis.readFully(itemBytes);
                                if (slot >= 0 && slot < this.size) {
                                    ItemStack stack = null;
                                    try {
                                        stack = ItemStack.deserializeBytes(itemBytes);
                                    } catch (Throwable ignored) {}
                                    if (stack != null && !stack.getType().isAir()) {
                                        decoded[slot] = stack;
                                    } else {
                                        throw new IllegalArgumentException("Invalid vault item");
                                    }
                                }
                            } else {
                                throw new IllegalArgumentException("Invalid vault item length");
                            }
                        }
                        parseSucceeded = true;
                    }
                }
            }

            if (parseSucceeded && inv != null) {
                setContents(decoded);
                return true;
            }
        } catch (Throwable e) {
            FriendlyCore instance = FriendlyCore.getInstance();
            if (instance != null) instance.getLogger().warning("Failed to deserialize team enderchest: " + e.getMessage());
        }
        return false;
    }
}
