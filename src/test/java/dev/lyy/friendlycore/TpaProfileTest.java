package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TpaProfileTest {

    private UUID playerUuid;
    private TpaProfile profile;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        profile = new TpaProfile(playerUuid, "TestPlayer");
    }

    @Test
    void testDefaultValues() {
        assertEquals(playerUuid, profile.getUuid());
        assertEquals("TestPlayer", profile.getUsername());
        assertTrue(profile.isTpaEnabled());
        assertTrue(profile.isTpaHereEnabled());
        assertFalse(profile.isTpAutoEnabled());
        assertTrue(profile.isShowGui());
        assertEquals(0, profile.getDailyCount());
    }

    @Test
    void testToggleModifications() {
        profile.setTpaEnabled(false);
        assertFalse(profile.isTpaEnabled());
        assertTrue(profile.isDirty());

        profile.setTpaHereEnabled(false);
        assertFalse(profile.isTpaHereEnabled());

        profile.setTpAutoEnabled(true);
        assertTrue(profile.isTpAutoEnabled());

        profile.setShowGui(false);
        assertFalse(profile.isShowGui());
    }

    @Test
    void testCooldownTracking() {
        UUID target = UUID.randomUUID();
        assertFalse(profile.isOnCooldown(target));

        profile.setCooldown(target, 5000L);
        assertTrue(profile.isOnCooldown(target));
        assertTrue(profile.getCooldownRemainingMs(target) > 0);
    }

    @Test
    void testDailyCountIncrement() {
        assertEquals(0, profile.getDailyCount());
        profile.incrementDailyCount();
        assertEquals(1, profile.getDailyCount());
        profile.incrementDailyCount();
        assertEquals(2, profile.getDailyCount());
        assertTrue(profile.isDirty());
    }

    @Test
    void testRequestStorage() {
        UUID sender = UUID.randomUUID();
        TpaRequest req = new TpaRequest(sender, "Sender", playerUuid, "TestPlayer", TpaRequest.Type.TPA);

        profile.addIncomingRequest(req);
        assertEquals(1, profile.getIncomingRequests().size());
        assertSame(req, profile.getIncomingRequests().get(sender));

        profile.removeIncomingRequest(sender);
        assertEquals(0, profile.getIncomingRequests().size());
    }

    @Test
    void testVersionedDirtyState() {
        assertFalse(profile.isDirty());
        long v1 = profile.getVersion();

        profile.setTpaEnabled(false);
        assertTrue(profile.isDirty());
        long v2 = profile.getVersion();
        assertTrue(v2 > v1);

        profile.markSaved(v2);
        assertFalse(profile.isDirty());

        // Concurrently mutated after save batch started
        profile.setShowGui(false);
        long v3 = profile.getVersion();
        assertTrue(v3 > v2);
        assertTrue(profile.isDirty());

        // Older save finishing does not clear newer dirty mutations
        profile.markSaved(v2);
        assertTrue(profile.isDirty());

        profile.markSaved(v3);
        assertFalse(profile.isDirty());
    }
}
