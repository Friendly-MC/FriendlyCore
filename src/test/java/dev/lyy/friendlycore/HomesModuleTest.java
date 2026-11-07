package dev.lyy.friendlycore;

import com.google.gson.Gson;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Homes Module data structures, slot management, and cross-server packets.
 */
public class HomesModuleTest {

    private final Gson gson = new Gson();

    @Test
    @DisplayName("Test HomeData initialization and display name logic")
    void testHomeData() {
        UUID uuid = UUID.randomUUID();
        HomeData home1 = new HomeData(uuid, 1, "Base", "survival-1", "world", 100.5, 64.0, -200.5, 90.0f, 0.0f, System.currentTimeMillis());

        assertEquals(1, home1.getSlot());
        assertEquals("Base", home1.getName());
        assertEquals("Base", home1.getDisplayName());
        assertEquals("survival-1", home1.getServerId());
        assertEquals("world", home1.getWorldName());
        assertEquals(100.5, home1.getX());
        assertEquals(64.0, home1.getY());
        assertEquals(-200.5, home1.getZ());
        assertTrue(home1.isSameServer("survival-1"));
        assertFalse(home1.isSameServer("lobby"));

        HomeData unnamedHome = new HomeData(uuid, 3, "", "survival-1", "world_nether", 0, 70, 0, 0, 0, System.currentTimeMillis());
        assertEquals("3", unnamedHome.getDisplayName());
    }

    @Test
    @DisplayName("Test PlayerHomes slot management and next available slot resolver")
    void testPlayerHomesSlotManagement() {
        UUID uuid = UUID.randomUUID();
        PlayerHomes profile = new PlayerHomes(uuid, "Steve");

        assertEquals("Steve", profile.getUsername());
        assertEquals(0, profile.getHomesCount());
        assertFalse(profile.isDirty());

        // Add home 1 and 2
        HomeData home1 = new HomeData(uuid, 1, "Base", "survival-1", "world", 100, 64, 100, 0, 0, System.currentTimeMillis());
        HomeData home2 = new HomeData(uuid, 2, "Mine", "survival-1", "world", 200, 12, 200, 0, 0, System.currentTimeMillis());

        profile.setHome(home1);
        profile.setHome(home2);

        assertTrue(profile.isDirty());
        assertEquals(2, profile.getHomesCount());
        assertEquals(home1, profile.getHome(1));
        assertEquals(home2, profile.getHome(2));

        // Get by name (case-insensitive)
        assertEquals(home1, profile.getHomeByNameOrSlot("base"));
        assertEquals(home1, profile.getHomeByNameOrSlot("BASE"));
        assertEquals(home2, profile.getHomeByNameOrSlot("2"));

        // Slot finding
        assertEquals(3, profile.getNextAvailableSlot(5));

        // Remove home 1
        profile.removeHome(1);
        assertEquals(1, profile.getHomesCount());
        assertNull(profile.getHome(1));
        assertEquals(1, profile.getNextAvailableSlot(5));

        // When all max slots full
        profile.setHome(new HomeData(uuid, 1, "H1", "s1", "w", 0, 0, 0, 0, 0, 0));
        profile.setHome(new HomeData(uuid, 2, "H2", "s1", "w", 0, 0, 0, 0, 0, 0));
        profile.setHome(new HomeData(uuid, 3, "H3", "s1", "w", 0, 0, 0, 0, 0, 0));
        assertEquals(3, profile.getHomesCount());
        assertEquals(-1, profile.getNextAvailableSlot(3));
    }

    @Test
    @DisplayName("Test cross-server HomeTeleportPacket serialization and deserialization")
    void testCrossServerPacketSerialization() {
        UUID uuid = UUID.randomUUID();
        HomeTeleportPacket packet = new HomeTeleportPacket(
                "survival-2",
                uuid,
                "Alex",
                "world_the_end",
                1500.25,
                75.0,
                -3000.75,
                180.0f,
                -45.0f
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        HomeTeleportPacket deserialized = gson.fromJson(json, HomeTeleportPacket.class);
        assertEquals("survival-2", deserialized.getTargetServer());
        assertEquals(uuid, deserialized.getPlayerUuid());
        assertEquals("Alex", deserialized.getPlayerName());
        assertEquals("world_the_end", deserialized.getWorldName());
        assertEquals(1500.25, deserialized.getX());
        assertEquals(75.0, deserialized.getY());
        assertEquals(-3000.75, deserialized.getZ());
        assertEquals(180.0f, deserialized.getYaw());
        assertEquals(-45.0f, deserialized.getPitch());

        CrossServerPacket crossPacket = new CrossServerPacket(
                "survival-1",
                CrossServerPacket.Action.HOME_CROSS_TELEPORT,
                uuid,
                "Alex",
                0.0,
                json
        );

        String crossJson = gson.toJson(crossPacket);
        CrossServerPacket roundTrip = gson.fromJson(crossJson, CrossServerPacket.class);

        assertEquals(CrossServerPacket.Action.HOME_CROSS_TELEPORT, roundTrip.getAction());
        assertEquals("survival-1", roundTrip.getSourceServer());
        assertEquals(uuid, roundTrip.getTargetUuid());
    }

    @Test
    @DisplayName("Test permission loss home pruning logic")
    void testPermissionLossPruning() {
        UUID uuid = UUID.randomUUID();
        PlayerHomes profile = new PlayerHomes(uuid, "Alex");

        // Player previously had 5 homes (VIP)
        for (int i = 1; i <= 5; i++) {
            profile.setHome(new HomeData(uuid, i, "Home " + i, "server-1", "world", i * 10, 64, i * 10, 0, 0, 0));
        }
        assertEquals(5, profile.getHomesCount());

        // Player lost VIP rank -> new limit is 2
        int newLimit = 2;
        profile.getHomes().keySet().removeIf(slot -> slot > newLimit);

        assertEquals(2, profile.getHomesCount());
        assertNotNull(profile.getHome(1));
        assertNotNull(profile.getHome(2));
        assertNull(profile.getHome(3));
        assertNull(profile.getHome(4));
        assertNull(profile.getHome(5));
    }

    @Test
    @DisplayName("Test HomeData same-server and cross-server detection")
    void testCrossServerServerMatching() {
        UUID uuid = UUID.randomUUID();
        HomeData localHome = new HomeData(uuid, 1, "Base", "survival-1", "world", 100, 64, 200, 0, 0, 0);
        assertTrue(localHome.isSameServer("survival-1"));
        assertTrue(localHome.isSameServer("SURVIVAL-1"));
        assertFalse(localHome.isSameServer("survival-2"));

        // Fallback for legacy homes with empty serverId
        HomeData legacyHome = new HomeData(uuid, 2, "Mine", "", "world", 100, 64, 200, 0, 0, 0);
        assertTrue(legacyHome.isSameServer("survival-1"));
        assertTrue(legacyHome.isSameServer("survival-2"));
    }
}
