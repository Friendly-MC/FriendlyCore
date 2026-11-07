package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.essentials.commands.ClearInventoryCommand;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for Inventory Clear System (/clear, /clean, /ci, /clearinventory).
 */
public class ClearInventoryTest {

    @Test
    @DisplayName("Test Material parsing in ClearInventoryCommand")
    public void testMaterialParsing() {
        assertEquals(Material.DIRT, ClearInventoryCommand.parseMaterial("dirt"));
        assertEquals(Material.DIAMOND, ClearInventoryCommand.parseMaterial("DIAMOND"));
        assertEquals(Material.STONE, ClearInventoryCommand.parseMaterial("minecraft:stone"));
        assertEquals(Material.GOLDEN_APPLE, ClearInventoryCommand.parseMaterial("golden_apple"));

        assertNull(ClearInventoryCommand.parseMaterial(null));
        assertNull(ClearInventoryCommand.parseMaterial(""));
        assertNull(ClearInventoryCommand.parseMaterial("invalid_material_xyz"));
        assertNull(ClearInventoryCommand.parseMaterial("air"));
    }

    @Test
    @DisplayName("Test Amount parsing in ClearInventoryCommand")
    public void testAmountParsing() {
        assertEquals(1, ClearInventoryCommand.parseAmount("1"));
        assertEquals(64, ClearInventoryCommand.parseAmount("64"));
        assertEquals(2304, ClearInventoryCommand.parseAmount("2304"));

        assertEquals(-1, ClearInventoryCommand.parseAmount("*"));
        assertEquals(-1, ClearInventoryCommand.parseAmount("all"));
        assertEquals(-1, ClearInventoryCommand.parseAmount("-1"));

        assertNull(ClearInventoryCommand.parseAmount(null));
        assertNull(ClearInventoryCommand.parseAmount(""));
        assertNull(ClearInventoryCommand.parseAmount("abc"));
        assertNull(ClearInventoryCommand.parseAmount("0"));
        assertNull(ClearInventoryCommand.parseAmount("-5"));
    }

    @Test
    @DisplayName("Test Cross-Server Clear Inventory Packet creation and signing")
    public void testCrossServerClearInventoryPacket() {
        String secret = "test_secret_key_32_characters_12";
        UUID targetUuid = UUID.randomUUID();
        String jsonExtra = "{\"item\":\"DIAMOND\",\"amount\":64,\"includeArmor\":true,\"armorOnly\":false}";

        CrossServerPacket packet = new CrossServerPacket(
                "server-1",
                CrossServerPacket.Action.ADMIN_CLEAR_INVENTORY,
                targetUuid,
                "TargetPlayer",
                0.0,
                jsonExtra
        );

        assertEquals(CrossServerPacket.Action.ADMIN_CLEAR_INVENTORY, packet.getAction());
        assertEquals(targetUuid, packet.getTargetUuid());
        assertEquals("TargetPlayer", packet.getTargetName());
        assertEquals(jsonExtra, packet.getExtra());

        packet.sign(secret);
        assertNotNull(packet.getSignature());
        assertTrue(packet.hasValidSignature(secret));
        assertFalse(packet.hasValidSignature("wrong_secret_key_wrong_length_32"));
    }
}
