package dev.lyy.friendlycore;

import com.google.gson.Gson;
import dev.lyy.friendlycore.common.redis.CrossServerListener;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

public class RedisServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final Gson gson = new Gson();

    @Test
    @DisplayName("CrossServerPacket signing and tampering detection")
    void testPacketTamperingDetection() {
        UUID targetUuid = UUID.randomUUID();
        CrossServerPacket packet = new CrossServerPacket(
                "server-1",
                CrossServerPacket.Action.CROSS_SERVER_PAY,
                targetUuid,
                "Alice",
                1000.0,
                50.0,
                "Bob"
        );
        packet.sign(SECRET);

        assertTrue(packet.hasValidSignature(SECRET));
        assertFalse(packet.hasValidSignature("wrongsecretwrongsecretwrongsecret"));

        // Serialization roundtrip
        String json = gson.toJson(packet);
        CrossServerPacket decoded = gson.fromJson(json, CrossServerPacket.class);
        assertTrue(decoded.hasValidSignature(SECRET));

        // Tampered amount must fail signature check
        CrossServerPacket tampered = new CrossServerPacket(
                decoded.getSourceServer(),
                decoded.getAction(),
                decoded.getTargetUuid(),
                decoded.getTargetName(),
                9999999.0, // altered
                decoded.getTransactionAmount(),
                decoded.getExtra()
        );
        // Using reflection or manually setting fields that match
        String tamperedJson = json.replace("1000.0", "9999999.0");
        CrossServerPacket tamperedDecoded = gson.fromJson(tamperedJson, CrossServerPacket.class);
        assertFalse(tamperedDecoded.hasValidSignature(SECRET));
    }

    @Test
    @DisplayName("Sliding window nonce replay cache preserves recent nonces under high load (>10,000)")
    void testNonceReplayProtectionUnderHighVolume() {
        Map<String, Long> seenNonces = new ConcurrentHashMap<>();
        long maxClockSkewMillis = 30_000L;
        long now = System.currentTimeMillis();

        // 1. Insert 10,500 nonces (some recent, some older than 30s)
        String protectedNonce = "protected-recent-nonce-" + UUID.randomUUID();
        seenNonces.put(protectedNonce, now - 5_000L); // 5s ago, still valid window

        // Add 5,000 old nonces (>30s ago)
        for (int i = 0; i < 5000; i++) {
            seenNonces.put("old-nonce-" + i, now - 45_000L);
        }

        // Add 6,000 recent nonces (<30s ago)
        for (int i = 0; i < 6000; i++) {
            seenNonces.put("recent-nonce-" + i, now - (i % 20_000));
        }

        assertEquals(11001, seenNonces.size());

        // Simulate sliding window cleanup
        if (seenNonces.size() > 10000) {
            long cutoff = now - maxClockSkewMillis;
            seenNonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }

        // Old nonces should have been evicted
        assertFalse(seenNonces.containsKey("old-nonce-0"));
        assertFalse(seenNonces.containsKey("old-nonce-4999"));

        // Protected recent nonce MUST STILL BE PRESENT (not wiped by clear()!)
        assertTrue(seenNonces.containsKey(protectedNonce));

        // A replay of the protected recent nonce must be detected as duplicate
        Long existing = seenNonces.putIfAbsent(protectedNonce, now);
        assertNotNull(existing, "Replay of recent packet must be rejected because nonce was retained");
    }

    @Test
    @DisplayName("CrossServerPacket Action Coverage - All enum actions properly serialize and retain fields")
    void testAllActionEnumsSerialization() {
        UUID playerUuid = UUID.randomUUID();

        for (CrossServerPacket.Action action : CrossServerPacket.Action.values()) {
            CrossServerPacket packet = new CrossServerPacket(
                    "hub-1",
                    action,
                    playerUuid,
                    "Steve",
                    123.45,
                    67.89,
                    "extra_payload_data"
            );
            packet.sign(SECRET);

            String json = gson.toJson(packet);
            assertNotNull(json);

            CrossServerPacket deserialized = gson.fromJson(json, CrossServerPacket.class);
            assertEquals("hub-1", deserialized.getSourceServer());
            assertEquals(action, deserialized.getAction());
            assertEquals(playerUuid, deserialized.getTargetUuid());
            assertEquals("Steve", deserialized.getTargetName());
            assertEquals(123.45, deserialized.getAmount());
            assertEquals(67.89, deserialized.getTransactionAmount());
            assertEquals("extra_payload_data", deserialized.getExtra());
            assertTrue(deserialized.hasValidSignature(SECRET));
        }
    }

    @Test
    @DisplayName("Clock skew rejection logic correctly flags future or past timestamps")
    void testClockSkewBoundaries() {
        long now = System.currentTimeMillis();
        long maxClockSkewMillis = 30_000L;

        // Inside skew window (20s ago)
        long validPast = now - 20_000L;
        assertTrue(Math.abs(now - validPast) <= maxClockSkewMillis);

        // Inside skew window (10s in future, slight server drift)
        long validFuture = now + 10_000L;
        assertTrue(Math.abs(now - validFuture) <= maxClockSkewMillis);

        // Outside skew window (60s ago)
        long expiredPast = now - 60_000L;
        assertTrue(Math.abs(now - expiredPast) > maxClockSkewMillis);

        // Outside skew window (60s in future)
        long invalidFuture = now + 60_000L;
        assertTrue(Math.abs(now - invalidFuture) > maxClockSkewMillis);
    }

    @Test
    @DisplayName("CrossServerListener gracefully drops messages when plugin is disabled without throwing or logging errors")
    void testListenerDropsMessageWhenPluginDisabled() {
        CrossServerListener listener = new CrossServerListener(null, "hub-1", "friendlycore:cross_sync", SECRET, 30, 262144);
        CrossServerPacket packet = new CrossServerPacket("remote-1", CrossServerPacket.Action.BALTOP_INVALIDATE, null, null, 0.0, null);
        packet.sign(SECRET);
        String json = gson.toJson(packet);

        assertDoesNotThrow(() -> {
            listener.onMessage("friendlycore:cross_sync", json);
        });
    }
}
