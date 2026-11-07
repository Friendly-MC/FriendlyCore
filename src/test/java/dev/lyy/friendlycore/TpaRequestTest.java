package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TpaRequestTest {

    @Test
    void testRequestCreation() {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        TpaRequest request = new TpaRequest(sender, "SenderPlayer", target, "TargetPlayer", TpaRequest.Type.TPA);

        assertNotNull(request.getId());
        assertEquals(sender, request.getSenderUuid());
        assertEquals("SenderPlayer", request.getSenderName());
        assertEquals(target, request.getTargetUuid());
        assertEquals("TargetPlayer", request.getTargetName());
        assertEquals(TpaRequest.Type.TPA, request.getType());
        assertEquals(TpaRequest.Status.PENDING, request.getStatus());
        assertFalse(request.isExpired(120));
    }

    @Test
    void testExpiration() {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        long pastTime = System.currentTimeMillis() - 150000L; // 150s ago
        TpaRequest req = new TpaRequest(UUID.randomUUID(), sender, "Sender", target, "Target", TpaRequest.Type.TPA, pastTime, TpaRequest.Status.PENDING);

        assertTrue(req.isExpired(120));

        req.setStatus(TpaRequest.Status.ACCEPTED);
        assertFalse(req.isExpired(120)); // Not pending anymore
    }
}
