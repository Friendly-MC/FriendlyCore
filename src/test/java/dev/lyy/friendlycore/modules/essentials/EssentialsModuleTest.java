package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.essentials.commands.GamemodeCommand;
import dev.lyy.friendlycore.modules.essentials.commands.TimeCommand;
import dev.lyy.friendlycore.modules.essentials.model.AdminTeleportTicket;
import dev.lyy.friendlycore.modules.essentials.model.EnderchestSession;
import dev.lyy.friendlycore.modules.essentials.util.InventorySerializer;
import dev.lyy.friendlycore.modules.essentials.util.SpeedUtil;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Unit Tests for FriendlyCore Admin Essentials Module.
 */
public class EssentialsModuleTest {

    @Test
    @DisplayName("Test Speed formula calculations for fly and walk speeds")
    public void testSpeedCalculations() {
        // Fly speed defaults: default = 0.1f, max = 1.0f
        // Speed 1 -> default (0.1f)
        float fly1 = SpeedUtil.calculateBukkitSpeed(1.0f, true, 1.0f);
        assertEquals(0.1f, fly1, 0.001f);

        // Speed 10 -> max (1.0f)
        float fly10 = SpeedUtil.calculateBukkitSpeed(10.0f, true, 1.0f);
        assertEquals(1.0f, fly10, 0.001f);

        // Speed 0.5 -> 0.5 * 0.1 = 0.05f
        float flyHalf = SpeedUtil.calculateBukkitSpeed(0.5f, true, 1.0f);
        assertEquals(0.05f, flyHalf, 0.001f);

        // Walk speed defaults: default = 0.2f, max = 1.0f
        // Speed 1 -> default (0.2f)
        float walk1 = SpeedUtil.calculateBukkitSpeed(1.0f, false, 1.0f);
        assertEquals(0.2f, walk1, 0.001f);

        // Speed 10 -> max (1.0f)
        float walk10 = SpeedUtil.calculateBukkitSpeed(10.0f, false, 1.0f);
        assertEquals(1.0f, walk10, 0.001f);

        // Reverse calculation
        float userSpeedFly10 = SpeedUtil.calculateUserSpeed(1.0f, true, 1.0f);
        assertEquals(10.0f, userSpeedFly10, 0.01f);

        float userSpeedFly1 = SpeedUtil.calculateUserSpeed(0.1f, true, 1.0f);
        assertEquals(1.0f, userSpeedFly1, 0.01f);
    }

    @Test
    @DisplayName("Test Gamemode input parsing")
    public void testGamemodeParsing() {
        assertEquals(GameMode.CREATIVE, GamemodeCommand.parseGameMode("c"));
        assertEquals(GameMode.CREATIVE, GamemodeCommand.parseGameMode("1"));
        assertEquals(GameMode.CREATIVE, GamemodeCommand.parseGameMode("creative"));

        assertEquals(GameMode.SURVIVAL, GamemodeCommand.parseGameMode("s"));
        assertEquals(GameMode.SURVIVAL, GamemodeCommand.parseGameMode("0"));
        assertEquals(GameMode.SURVIVAL, GamemodeCommand.parseGameMode("survival"));

        assertEquals(GameMode.ADVENTURE, GamemodeCommand.parseGameMode("a"));
        assertEquals(GameMode.ADVENTURE, GamemodeCommand.parseGameMode("2"));
        assertEquals(GameMode.ADVENTURE, GamemodeCommand.parseGameMode("adventure"));

        assertEquals(GameMode.SPECTATOR, GamemodeCommand.parseGameMode("sp"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.parseGameMode("3"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.parseGameMode("spectator"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.parseGameMode("spec"));

        assertNull(GamemodeCommand.parseGameMode("invalidMode"));

        assertEquals(GameMode.CREATIVE, GamemodeCommand.getDirectMode("gmc"));
        assertEquals(GameMode.SURVIVAL, GamemodeCommand.getDirectMode("gms"));
        assertEquals(GameMode.ADVENTURE, GamemodeCommand.getDirectMode("gma"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.getDirectMode("gmsp"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.getDirectMode("gmspec"));
    }

    @Test
    @DisplayName("Test Time ticks parsing")
    public void testTimeParsing() {
        assertEquals(1000L, TimeCommand.parseTimeTicks("day"));
        assertEquals(2000L, TimeCommand.parseTimeTicks("morning"));
        assertEquals(6000L, TimeCommand.parseTimeTicks("noon"));
        assertEquals(9000L, TimeCommand.parseTimeTicks("afternoon"));
        assertEquals(12000L, TimeCommand.parseTimeTicks("sunset"));
        assertEquals(13000L, TimeCommand.parseTimeTicks("night"));
        assertEquals(18000L, TimeCommand.parseTimeTicks("midnight"));
        assertEquals(23000L, TimeCommand.parseTimeTicks("sunrise"));

        assertEquals(5000L, TimeCommand.parseTimeTicks("5000"));
        assertEquals(6500L, TimeCommand.parseTimeTicks("6500t"));
        assertNull(TimeCommand.parseTimeTicks("invalid"));
    }

    @Test
    @DisplayName("Test Base64 Inventory Serializer with empty and null inputs")
    public void testInventorySerializerEdgeCases() {
        assertEquals("", InventorySerializer.toBase64(null));
        assertEquals("", InventorySerializer.toBase64(new ItemStack[0]));

        ItemStack[] deserializedNull = InventorySerializer.fromBase64(null);
        assertNotNull(deserializedNull);
        assertEquals(0, deserializedNull.length);

        ItemStack[] deserializedEmpty = InventorySerializer.fromBase64("");
        assertNotNull(deserializedEmpty);
        assertEquals(0, deserializedEmpty.length);

        ItemStack[] emptyArray = new ItemStack[41];
        String encoded = InventorySerializer.toBase64(emptyArray);
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());

        ItemStack[] roundTrip = InventorySerializer.fromBase64(encoded);
        assertNotNull(roundTrip);
        assertEquals(41, roundTrip.length);
        for (ItemStack is : roundTrip) {
            assertNull(is);
        }
    }

    @Test
    @DisplayName("Test Admin Teleport Ticket lifecycle")
    public void testAdminTeleportTicket() {
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();

        AdminTeleportTicket ticket = new AdminTeleportTicket(
                playerUuid, "TestPlayer", targetUuid, "TargetPlayer",
                "world", 100.5, 64.0, -200.5, 90.0f, 0.0f
        );

        assertEquals(playerUuid, ticket.getPlayerUuid());
        assertEquals("TestPlayer", ticket.getPlayerName());
        assertEquals("world", ticket.getWorldName());
        assertEquals(100.5, ticket.getX());
        assertEquals(64.0, ticket.getY());
        assertEquals(-200.5, ticket.getZ());
        assertFalse(ticket.isExpired());
    }

    @Test
    @DisplayName("Test Cross-Server Packet signature and actions")
    public void testCrossServerPacketSecurity() {
        String secret = "0123456789abcdef0123456789abcdef"; // 32 chars
        CrossServerPacket packet = new CrossServerPacket(
                "server-1",
                CrossServerPacket.Action.ADMIN_GAMEMODE_SET,
                UUID.randomUUID(),
                "TargetPlayer",
                0.0,
                "CREATIVE"
        );

        packet.sign(secret);
        assertNotNull(packet.getSignature());
        assertNotNull(packet.getNonce());
        assertTrue(packet.hasValidSignature(secret));
        assertFalse(packet.hasValidSignature("wrongsecretwrongsecretwrongsecret32"));
    }

    @Test
    @DisplayName("Test EnderchestSession tracking and modifiedByViewer flag protection")
    public void testEnderchestSessionModificationState() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        EnderchestSession session = new EnderchestSession(
                viewer, target, "TargetPlayer",
                EnderchestSession.SessionType.LOCAL_ONLINE, "survival-1", true, null
        );

        assertEquals(viewer, session.getViewerUuid());
        assertEquals(target, session.getTargetUuid());
        assertEquals("TargetPlayer", session.getTargetName());
        assertEquals(EnderchestSession.SessionType.LOCAL_ONLINE, session.getSessionType());
        assertEquals("survival-1", session.getTargetServer());
        assertTrue(session.isEditable());
        assertFalse(session.isModifiedByViewer(), "New session must default to modifiedByViewer=false to prevent clobbering");

        // When viewer makes an edit
        session.setModifiedByViewer(true);
        assertTrue(session.isModifiedByViewer(), "Session should record that viewer modified contents");

        // When changes are committed or reset
        session.setModifiedByViewer(false);
        assertFalse(session.isModifiedByViewer());
    }

    @Test
    @DisplayName("Test EnderchestSession types")
    public void testEnderchestSessionTypes() {
        for (EnderchestSession.SessionType type : EnderchestSession.SessionType.values()) {
            assertNotNull(type);
        }
        assertEquals(3, EnderchestSession.SessionType.values().length);
    }

    @Test
    @DisplayName("Test InvseeSession tracking and modifiedByViewer flag protection")
    public void testInvseeSessionModificationState() {
        UUID viewer = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        dev.lyy.friendlycore.modules.essentials.model.InvseeSession session =
                new dev.lyy.friendlycore.modules.essentials.model.InvseeSession(
                        viewer, target, "TargetPlayer",
                        dev.lyy.friendlycore.modules.essentials.model.InvseeSession.SessionType.LOCAL_ONLINE,
                        "survival-1", true, null
                );

        assertEquals(viewer, session.getViewerUuid());
        assertEquals(target, session.getTargetUuid());
        assertEquals("TargetPlayer", session.getTargetName());
        assertEquals(dev.lyy.friendlycore.modules.essentials.model.InvseeSession.SessionType.LOCAL_ONLINE, session.getSessionType());
        assertEquals("survival-1", session.getTargetServer());
        assertTrue(session.isEditable());
        assertFalse(session.isModifiedByViewer(), "New invsee session must default to modifiedByViewer=false");

        // When viewer makes an edit
        session.setModifiedByViewer(true);
        assertTrue(session.isModifiedByViewer(), "Session should record that viewer modified contents");

        // When changes are committed or reset
        session.setModifiedByViewer(false);
        assertFalse(session.isModifiedByViewer());
    }

    @Test
    @DisplayName("Test InvseeSession types")
    public void testInvseeSessionTypes() {
        for (dev.lyy.friendlycore.modules.essentials.model.InvseeSession.SessionType type :
                dev.lyy.friendlycore.modules.essentials.model.InvseeSession.SessionType.values()) {
            assertNotNull(type);
        }
        assertEquals(3, dev.lyy.friendlycore.modules.essentials.model.InvseeSession.SessionType.values().length);
    }
}
