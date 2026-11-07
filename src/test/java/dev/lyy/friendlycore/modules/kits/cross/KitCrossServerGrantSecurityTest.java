package dev.lyy.friendlycore.modules.kits.cross;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KitCrossServerGrantSecurityTest {

    @Test
    void remoteGrantPayloadRoundTripsUniqueGrantId() {
        String grantId = UUID.randomUUID().toString();
        var payload = new KitCrossServerService.RemoteGivePayload(grantId, "starter", true, "CONSOLE");
        var parsed = KitCrossServerService.RemoteGivePayload.parse(payload.serialize());
        assertNotNull(parsed);
        assertEquals(grantId, parsed.grantId());
        assertEquals("starter", parsed.kitName());
        assertTrue(parsed.checkCooldown());
    }

    @Test
    void remoteGrantPayloadRejectsLegacyMalformedAndUnboundedInputs() {
        assertNull(KitCrossServerService.RemoteGivePayload.parse("starter;false;CONSOLE"));
        assertNull(KitCrossServerService.RemoteGivePayload.parse("not-a-uuid;starter;false;CONSOLE"));
        assertNull(KitCrossServerService.RemoteGivePayload.parse(
                UUID.randomUUID() + ";starter;not-boolean;CONSOLE"));
        assertNull(KitCrossServerService.RemoteGivePayload.parse("A".repeat(257)));
    }
}
