package dev.lyy.friendlycore.modules.kits;

import org.bukkit.inventory.ItemStack;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Modern Paper 1.21+ Base64 serializer for ItemStacks with 100% Component and Data preservation.
 */
public class KitSerializer {

    static final int MAX_ITEMS = 256;
    static final int MAX_ENCODED_CHARS = 2_000_000;
    static final int MAX_DECODED_BYTES = 1_500_000;
    static final int MAX_ITEM_BYTES = 262_144;

    public static String toBase64(List<ItemStack> items) {
        if (items == null || items.isEmpty()) return "";
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutput = new DataOutputStream(outputStream);

            if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("Too many kit items");
            dataOutput.writeInt(items.size());
            for (ItemStack item : items) {
                if (item == null || item.isEmpty() || item.getType().isAir()) {
                    dataOutput.writeInt(0);
                } else {
                    byte[] bytes = item.serializeAsBytes();
                    if (bytes.length > MAX_ITEM_BYTES) throw new IllegalArgumentException("Kit item payload is too large");
                    if (outputStream.size() + Integer.BYTES + bytes.length > MAX_DECODED_BYTES) {
                        throw new IllegalArgumentException("Kit payload is too large");
                    }
                    dataOutput.writeInt(bytes.length);
                    dataOutput.write(bytes);
                }
            }

            dataOutput.close();
            if (outputStream.size() > MAX_DECODED_BYTES) throw new IllegalArgumentException("Kit payload is too large");
            String encoded = Base64Coder.encodeLines(outputStream.toByteArray());
            if (encoded.length() > MAX_ENCODED_CHARS) throw new IllegalArgumentException("Encoded kit payload is too large");
            return encoded;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize item stacks to Base64", e);
        }
    }

    public static List<ItemStack> fromBase64(String data) {
        if (data == null || data.isEmpty()) return new ArrayList<>();
        try {
            if (data.length() > MAX_ENCODED_CHARS) return new ArrayList<>();
            byte[] decoded = Base64Coder.decodeLines(data);
            if (decoded.length > MAX_DECODED_BYTES) return new ArrayList<>();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(decoded);
            DataInputStream dataInput = new DataInputStream(inputStream);

            int size = dataInput.readInt();
            if (size < 0 || size > MAX_ITEMS) {
                dataInput.close();
                return new ArrayList<>();
            }
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int byteLen = dataInput.readInt();
                if (byteLen == 0) {
                    continue;
                }
                if (byteLen < 0 || byteLen > MAX_ITEM_BYTES || byteLen > dataInput.available()) {
                    return new ArrayList<>();
                }
                byte[] bytes = new byte[byteLen];
                dataInput.readFully(bytes);
                items.add(ItemStack.deserializeBytes(bytes));
            }

            dataInput.close();
            return items;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
