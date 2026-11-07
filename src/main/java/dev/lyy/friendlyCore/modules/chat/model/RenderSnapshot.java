package dev.lyy.friendlycore.modules.chat.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * In-memory preview snapshot of a player's item, inventory, enderchest, or shulker box.
 */
public class RenderSnapshot {

    private static final int MAX_SNAPSHOT_JSON_CHARS = 1_000_000;
    private static final int MAX_ENCODED_ITEM_CHARS = 750_000;
    private static final int MAX_DECOMPRESSED_BYTES = 2_000_000;
    private static final int MAX_ITEM_BYTES = 1_000_000;
    private static final int MAX_INVENTORY_SLOTS = 54;

    public enum RenderType {
        ITEM,
        INVENTORY,
        ENDERCHEST,
        SHULKER
    }

    private final UUID snapshotId;
    private final RenderType type;
    private final UUID ownerUuid;
    private final String ownerName;
    private final ItemStack item;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final long createdAt;

    public RenderSnapshot(UUID snapshotId, RenderType type, UUID ownerUuid, String ownerName,
                          ItemStack item, ItemStack[] contents, ItemStack[] armor, ItemStack offHand) {
        this.snapshotId = snapshotId;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.item = item != null ? item.clone() : null;
        this.contents = contents != null ? cloneArray(contents) : new ItemStack[0];
        this.armor = armor != null ? cloneArray(armor) : new ItemStack[0];
        this.offHand = offHand != null ? offHand.clone() : null;
        this.createdAt = System.currentTimeMillis();
    }

    private static ItemStack[] cloneArray(ItemStack[] array) {
        ItemStack[] cloned = new ItemStack[array.length];
        for (int i = 0; i < array.length; i++) {
            cloned[i] = array[i] != null ? array[i].clone() : null;
        }
        return cloned;
    }

    public UUID getSnapshotId() {
        return snapshotId;
    }

    public RenderType getType() {
        return type;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public ItemStack getItem() {
        return item != null ? item.clone() : null;
    }

    public ItemStack[] getContents() {
        return cloneArray(contents);
    }

    public ItemStack[] getArmor() {
        return cloneArray(armor);
    }

    public ItemStack getOffHand() {
        return offHand != null ? offHand.clone() : null;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Serializes this snapshot into a JSON string with compressed Base64-encoded ItemStacks for cross-server transport and caching.
     */
    public String serializeToJson(com.google.gson.Gson gson) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("snapshotId", snapshotId.toString());
        map.put("type", type.name());
        map.put("ownerUuid", ownerUuid != null ? ownerUuid.toString() : "");
        map.put("ownerName", ownerName != null ? ownerName : "Player");
        map.put("createdAt", createdAt);

        if (item != null) {
            map.put("item", encodeItem(item));
        }
        if (offHand != null) {
            map.put("offHand", encodeItem(offHand));
        }
        if (contents != null && contents.length > 0) {
            map.put("contents", serializeItemArray(contents));
        }
        if (armor != null && armor.length > 0) {
            map.put("armor", serializeItemArray(armor));
        }
        return gson.toJson(map);
    }

    /**
     * Deserializes a RenderSnapshot from a JSON string with support for both compressed and legacy payloads.
     */
    public static RenderSnapshot deserializeFromJson(String json, com.google.gson.Gson gson) {
        if (json == null || json.isEmpty() || json.length() > MAX_SNAPSHOT_JSON_CHARS) return null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = gson.fromJson(json, java.util.Map.class);
            UUID snapshotId = UUID.fromString((String) map.get("snapshotId"));
            RenderType type = RenderType.valueOf((String) map.get("type"));
            String ownerUuidStr = (String) map.get("ownerUuid");
            UUID ownerUuid = (ownerUuidStr != null && !ownerUuidStr.isEmpty()) ? UUID.fromString(ownerUuidStr) : null;
            String ownerName = (String) map.getOrDefault("ownerName", "Player");

            ItemStack item = map.containsKey("item") ? decodeItem((String) map.get("item")) : null;
            ItemStack offHand = map.containsKey("offHand") ? decodeItem((String) map.get("offHand")) : null;
            ItemStack[] contents = map.containsKey("contents") ? deserializeItemArray((String) map.get("contents"), 36) : new ItemStack[0];
            ItemStack[] armor = map.containsKey("armor") ? deserializeItemArray((String) map.get("armor"), 4) : new ItemStack[0];

            return new RenderSnapshot(snapshotId, type, ownerUuid, ownerName, item, contents, armor, offHand);
        } catch (Exception e) {
            return null;
        }
    }

    public static String serializeItemArray(ItemStack[] items) {
        if (items == null || items.length == 0) return "";
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

            int nonNullCount = 0;
            for (ItemStack item : items) {
                if (item != null && !item.isEmpty() && !item.getType().isAir()) {
                    nonNullCount++;
                }
            }

            dos.writeInt(items.length);
            dos.writeInt(nonNullCount);

            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                if (item != null && !item.isEmpty() && !item.getType().isAir()) {
                    byte[] bytes = item.serializeAsBytes();
                    dos.writeInt(i);
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            }
            dos.flush();
            byte[] raw = baos.toByteArray();
            byte[] compressed = compress(raw);
            return "gz:" + java.util.Base64.getEncoder().encodeToString(compressed);
        } catch (Exception e) {
            return "";
        }
    }

    public static ItemStack[] deserializeItemArray(String data, int defaultLength) {
        if (data == null || data.isEmpty() || data.length() > MAX_ENCODED_ITEM_CHARS) return new ItemStack[0];
        try {
            if (data.startsWith("gz:")) {
                byte[] decoded = java.util.Base64.getDecoder().decode(data.substring(3));
                byte[] decompressed = decompress(decoded);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(decompressed);
                java.io.DataInputStream dis = new java.io.DataInputStream(bais);

                int length = dis.readInt();
                int nonNullCount = dis.readInt();
                if (length < 0 || length > MAX_INVENTORY_SLOTS
                        || nonNullCount < 0 || nonNullCount > length) {
                    return new ItemStack[0];
                }
                ItemStack[] result = new ItemStack[Math.max(length, defaultLength)];

                for (int c = 0; c < nonNullCount; c++) {
                    int slot = dis.readInt();
                    int byteLen = dis.readInt();
                    if (byteLen <= 0 || byteLen > MAX_ITEM_BYTES || byteLen > dis.available()) {
                        return new ItemStack[0];
                    }
                    byte[] itemBytes = new byte[byteLen];
                    dis.readFully(itemBytes);
                    if (slot >= 0 && slot < result.length) {
                        result[slot] = ItemStack.deserializeBytes(itemBytes);
                    }
                }
                return result;
            } else {
                java.util.List<ItemStack> list = dev.lyy.friendlycore.modules.kits.KitSerializer.fromBase64(data);
                return list != null ? list.toArray(new ItemStack[0]) : new ItemStack[0];
            }
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }

    private static String encodeItem(ItemStack item) {
        if (item == null || item.isEmpty() || item.getType().isAir()) return null;
        try {
            byte[] raw = item.serializeAsBytes();
            if (raw.length > 128) {
                try {
                    byte[] compressed = compress(raw);
                    return "gz:" + java.util.Base64.getEncoder().encodeToString(compressed);
                } catch (Exception ignored) {
                    return java.util.Base64.getEncoder().encodeToString(raw);
                }
            } else {
                return java.util.Base64.getEncoder().encodeToString(raw);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static ItemStack decodeItem(String data) {
        if (data == null || data.isEmpty() || data.length() > MAX_ENCODED_ITEM_CHARS) return null;
        try {
            if (data.startsWith("gz:")) {
                byte[] decoded = java.util.Base64.getDecoder().decode(data.substring(3));
                byte[] decompressed = decompress(decoded);
                return ItemStack.deserializeBytes(decompressed);
            } else {
                byte[] itemBytes = java.util.Base64.getDecoder().decode(data);
                return ItemStack.deserializeBytes(itemBytes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] compress(byte[] data) throws java.io.IOException {
        if (data == null || data.length == 0) return new byte[0];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] decompress(byte[] compressed) throws java.io.IOException {
        if (compressed == null || compressed.length == 0) return new byte[0];
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(compressed);
        try (java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(bais)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(
                    Math.min(compressed.length * 4, MAX_DECOMPRESSED_BYTES));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzis.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new java.io.IOException("Compressed chat snapshot exceeds the decompressed size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
