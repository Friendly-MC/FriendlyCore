package dev.lyy.friendlycore.modules.kits;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitSerializerSecurityTest {

    @Test
    void rejectsDeclaredItemLengthBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(Integer.MAX_VALUE);
        }
        String payload = Base64.getEncoder().encodeToString(bytes.toByteArray());
        assertTrue(assertDoesNotThrow(() -> KitSerializer.fromBase64(payload)).isEmpty());
    }

    @Test
    void rejectsOversizedEncodedPayload() {
        String payload = "A".repeat(KitSerializer.MAX_ENCODED_CHARS + 1);
        assertTrue(assertDoesNotThrow(() -> KitSerializer.fromBase64(payload)).isEmpty());
    }
}
