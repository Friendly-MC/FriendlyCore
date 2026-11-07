package dev.lyy.friendlycore;

import com.google.gson.Gson;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossServerPacketSecurityTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void signedPacketSurvivesSerializationAndRejectsWrongSecret() {
        CrossServerPacket packet = new CrossServerPacket("server-a",
                CrossServerPacket.Action.CROSS_SERVER_PAY, UUID.randomUUID(), "Alex",
                250.0, 25.0, "Steve");
        packet.sign(SECRET);

        CrossServerPacket decoded = new Gson().fromJson(new Gson().toJson(packet), CrossServerPacket.class);
        assertTrue(decoded.hasValidSignature(SECRET));
        assertFalse(decoded.hasValidSignature("abcdef0123456789abcdef0123456789"));
        assertEquals(25.0, decoded.getTransactionAmount());
        assertNotNull(decoded.getNonce());
        assertTrue(decoded.getTimestamp() > 0);
    }

    @Test
    void unsignedPacketIsRejected() {
        CrossServerPacket packet = new CrossServerPacket("server-a",
                CrossServerPacket.Action.GLOBAL_RESET, null, null, 0.0, null);
        assertFalse(packet.hasValidSignature(SECRET));
    }
}
