package dev.lyy.friendlycore.modules.settings;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SettingsModuleTest {

    @Test
    @DisplayName("Test SettingType key and alias resolutions")
    void testSettingTypeKeys() {
        assertEquals(SettingType.SCOREBOARD, SettingType.fromKey("sb"));
        assertEquals(SettingType.SCOREBOARD, SettingType.fromKey("scoreboard"));
        assertEquals(SettingType.CHAT, SettingType.fromKey("chat"));
        assertEquals(SettingType.CHAT, SettingType.fromKey("globalchat"));
        assertEquals(SettingType.TPA, SettingType.fromKey("tpa"));
        assertEquals(SettingType.TPA, SettingType.fromKey("teleport"));
        assertEquals(SettingType.PRIVATE_MESSAGES, SettingType.fromKey("pm"));
        assertEquals(SettingType.PRIVATE_MESSAGES, SettingType.fromKey("msg"));
        assertEquals(SettingType.NIGHT_VISION, SettingType.fromKey("nv"));
        assertEquals(SettingType.NIGHT_VISION, SettingType.fromKey("nightvision"));
        assertEquals(SettingType.PAY_NOTIFICATIONS, SettingType.fromKey("pay"));
        assertEquals(SettingType.PAY_NOTIFICATIONS, SettingType.fromKey("payalerts"));
        assertEquals(SettingType.SOUNDS, SettingType.fromKey("sounds"));
        assertEquals(SettingType.SOUNDS, SettingType.fromKey("sound"));

        assertNull(SettingType.fromKey("nonexistent_setting"));
    }

    @Test
    @DisplayName("Test PlayerSettings default values, mutations, and dirty tracking")
    void testPlayerSettingsModel() {
        UUID uuid = UUID.randomUUID();
        PlayerSettings settings = new PlayerSettings(uuid, "Steve");

        assertEquals(uuid, settings.getUuid());
        assertEquals("Steve", settings.getUsername());

        // Check defaults
        assertTrue(settings.get(SettingType.SCOREBOARD));
        assertTrue(settings.get(SettingType.CHAT));
        assertTrue(settings.get(SettingType.TPA));
        assertTrue(settings.get(SettingType.PRIVATE_MESSAGES));
        assertFalse(settings.get(SettingType.NIGHT_VISION));
        assertTrue(settings.get(SettingType.PAY_NOTIFICATIONS));
        assertTrue(settings.get(SettingType.SOUNDS));

        // Test toggle
        boolean nextSb = settings.toggle(SettingType.SCOREBOARD);
        assertFalse(nextSb);
        assertFalse(settings.get(SettingType.SCOREBOARD));
        assertTrue(settings.isDirty());

        // Test mark saved
        long currentVer = settings.getVersion();
        settings.markSaved(currentVer);
        assertFalse(settings.isDirty());

        // Mutate night vision
        settings.set(SettingType.NIGHT_VISION, true);
        assertTrue(settings.get(SettingType.NIGHT_VISION));
        assertTrue(settings.isDirty());
    }

    @Test
    @DisplayName("Test CrossServerPacket with SETTINGS_SYNC")
    void testCrossServerPacketSettingsSync() {
        UUID uuid = UUID.randomUUID();
        String secret = "abcdef0123456789abcdef0123456789";
        CrossServerPacket packet = new CrossServerPacket(
                "server-1",
                CrossServerPacket.Action.SETTINGS_SYNC,
                uuid,
                "Alex",
                0,
                "sb:false"
        );
        packet.sign(secret);

        assertTrue(packet.hasValidSignature(secret));
        assertEquals(CrossServerPacket.Action.SETTINGS_SYNC, packet.getAction());
        assertEquals("sb:false", packet.getExtra());
        assertEquals("Alex", packet.getTargetName());
        assertEquals(uuid, packet.getTargetUuid());
    }
}
