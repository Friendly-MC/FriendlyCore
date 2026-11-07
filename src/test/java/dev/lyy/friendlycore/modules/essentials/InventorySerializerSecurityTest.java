package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.modules.essentials.util.InventorySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySerializerSecurityTest {

    private static byte[] magic() {
        return new byte[]{(byte) 0xFF, 'C', 'I', 'S', '1'};
    }

    @Test
    @DisplayName("Legacy ObjectInputStream payload with 2^31-1 item count is rejected without allocating")
    void legacyObjectStreamPayloadCannotForceHugeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(Integer.MAX_VALUE);
        }
        String payload = Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertTrue(assertDoesNotThrow(() -> InventorySerializer.fromBase64(payload)).length == 0);
    }

    @Test
    @DisplayName("Safe-format payload with 2^31-1 item count is rejected before allocation")
    void rejectsHugeDeclaredItemCountBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(magic());
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(Integer.MAX_VALUE);
        }
        String payload = Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertTrue(assertDoesNotThrow(() -> InventorySerializer.fromBase64(payload)).length == 0);
    }

    @Test
    @DisplayName("Safe-format payload with huge per-item byte length is rejected before allocation")
    void rejectsHugeDeclaredItemBytesBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(magic());
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(Integer.MAX_VALUE);
        }
        String payload = Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertTrue(assertDoesNotThrow(() -> InventorySerializer.fromBase64(payload)).length == 0);
    }

    @Test
    @DisplayName("Garbage input is rejected safely")
    void rejectsGarbageInput() {
        assertTrue(assertDoesNotThrow(() -> InventorySerializer.fromBase64("!!!not-base64!!!")).length == 0);
        assertTrue(assertDoesNotThrow(() -> InventorySerializer.fromBase64("A".repeat(2_000_001))).length == 0);
    }
}
