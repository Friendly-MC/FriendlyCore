package dev.lyy.friendlycore.modules.essentials.util;

import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for serializing and deserializing Bukkit ItemStacks and inventories
 * to Base64 strings for cross-server synchronization and database persistence.
 *
 * <p>Uses Paper's {@link ItemStack#serializeAsBytes()} / {@link ItemStack#deserializeBytes(byte[])}
 * (safe length-prefixed NBT payloads) instead of Java object serialization, which is
 * vulnerable to memory exhaustion and deserialization gadget attacks on untrusted input.</p>
 */
public final class InventorySerializer {

    // Magic header whose first 4 bytes decode to a NEGATIVE int. Legacy readers
    // (which interpret the first 4 bytes as an item count) throw a caught
    // NegativeArraySizeException instead of attempting a multi-GB allocation.
    private static final byte[] MAGIC = new byte[]{(byte) 0xFF, 'C', 'I', 'S', '1'};
    private static final int MAX_ITEMS = 256;
    private static final int MAX_ENCODED_CHARS = 8_000_000;
    private static final int MAX_DECODED_BYTES = 6_000_000;
    private static final int MAX_ITEM_BYTES = 3_000_000;

    private InventorySerializer() {}

    /**
     * Serializes an array of ItemStacks to a Base64 encoded string.
     */
    public static String toBase64(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return "";
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (items.length > MAX_ITEMS) throw new IllegalArgumentException("Too many inventory items");
            outputStream.write(MAGIC);
            DataOutputStream dataOutput = new DataOutputStream(outputStream);
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                if (item == null || item.isEmpty() || item.getType().isAir()) {
                    dataOutput.writeInt(0);
                } else {
                    byte[] bytes = item.serializeAsBytes();
                    if (bytes.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("Inventory item payload is too large");
                    if (outputStream.size() + Integer.BYTES + bytes.length > MAX_DECODED_BYTES) {
                        throw new IllegalArgumentException("Inventory payload is too large");
                    }
                    dataOutput.writeInt(bytes.length);
                    dataOutput.write(bytes);
                }
            }
            dataOutput.flush();
            if (outputStream.size() > MAX_DECODED_BYTES) throw new IllegalArgumentException("Inventory payload is too large");
            String encoded = Base64Coder.encodeLines(outputStream.toByteArray());
            if (encoded.length() > MAX_ENCODED_CHARS) throw new IllegalArgumentException("Encoded inventory payload is too large");
            return encoded;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize item stacks to Base64", e);
        }
    }

    /**
     * Deserializes a Base64 encoded string into an array of ItemStacks.
     * Malformed, oversized, or legacy (pre-safe-format) data yields an empty array
     * instead of throwing or allocating attacker-controlled amounts of memory.
     */
    public static ItemStack[] fromBase64(String data) {
        if (data == null || data.trim().isEmpty()) {
            return new ItemStack[0];
        }
        try {
            if (data.length() > MAX_ENCODED_CHARS) return new ItemStack[0];
            byte[] decoded = Base64Coder.decodeLines(data);
            if (decoded.length < MAGIC.length || decoded.length > MAX_DECODED_BYTES) return new ItemStack[0];
            ByteArrayInputStream inputStream = new ByteArrayInputStream(decoded);
            byte[] magic = new byte[MAGIC.length];
            inputStream.read(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) return new ItemStack[0];
            }
            DataInputStream dataInput = new DataInputStream(inputStream);

            int length = dataInput.readInt();
            if (length < 0 || length > MAX_ITEMS) {
                dataInput.close();
                return new ItemStack[0];
            }
            List<ItemStack> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                int byteLen = dataInput.readInt();
                if (byteLen == 0) {
                    items.add(null);
                    continue;
                }
                if (byteLen < 0 || byteLen > MAX_ITEM_BYTES || byteLen > dataInput.available()) {
                    return new ItemStack[0];
                }
                byte[] bytes = new byte[byteLen];
                dataInput.readFully(bytes);
                // No Java-serialization fallback: it reopens gadget deserialization on network input.
                // An undecodable item rejects the whole payload; reading it as an empty slot would make
                // a save delete the real item.
                ItemStack item;
                try {
                    item = ItemStack.deserializeBytes(bytes);
                } catch (Throwable undecodable) {
                    return new ItemStack[0];
                }
                items.add(item);
            }
            dataInput.close();
            return items.toArray(new ItemStack[0]);
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }
}
