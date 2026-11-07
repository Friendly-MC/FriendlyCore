package dev.lyy.friendlycore;

import com.google.gson.Gson;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaCrossServerPacketPayload;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaTeleportTicket;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TpaCrossServerTest {

    private final Gson gson = new Gson();

    @Test
    void testTeleportTicketLifecycle() throws InterruptedException {
        UUID traveler = UUID.randomUUID();
        UUID host = UUID.randomUUID();

        // Short expiration ticket for testing
        TpaTeleportTicket ticket = new TpaTeleportTicket(
                traveler, "TravelerBob",
                host, "HostAlice",
                "world", 100.5, 64.0, -200.5, 90.0f, 0.0f,
                TpaRequest.Type.TPA, 50L
        );

        assertEquals(traveler, ticket.getTravelerUuid());
        assertEquals("TravelerBob", ticket.getTravelerName());
        assertEquals(host, ticket.getHostUuid());
        assertEquals("HostAlice", ticket.getHostName());
        assertEquals("world", ticket.getWorldName());
        assertEquals(100.5, ticket.getX());
        assertEquals(64.0, ticket.getY());
        assertEquals(-200.5, ticket.getZ());
        assertEquals(90.0f, ticket.getYaw());
        assertEquals(0.0f, ticket.getPitch());
        assertEquals(TpaRequest.Type.TPA, ticket.getType());
        assertFalse(ticket.isExpired());

        Thread.sleep(60L);
        assertTrue(ticket.isExpired());
    }

    @Test
    void testCrossServerPayloadSerialization() {
        UUID reqId = UUID.randomUUID();
        UUID senderUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();

        TpaCrossServerPacketPayload payload = new TpaCrossServerPacketPayload();
        payload.setRequestId(reqId);
        payload.setSenderUuid(senderUuid);
        payload.setSenderName("Alice");
        payload.setTargetUuid(targetUuid);
        payload.setTargetName("Bob");
        payload.setOriginServer("survival-1");
        payload.setTargetServer("survival-2");
        payload.setType(TpaRequest.Type.TPHERE);
        payload.setWorldName("world_nether");
        payload.setX(50.0);
        payload.setY(80.0);
        payload.setZ(-120.0);
        payload.setYaw(180.0f);
        payload.setPitch(-15.0f);

        String json = gson.toJson(payload);
        assertNotNull(json);

        TpaCrossServerPacketPayload deserialized = gson.fromJson(json, TpaCrossServerPacketPayload.class);
        assertNotNull(deserialized);
        assertEquals(reqId, deserialized.getRequestId());
        assertEquals(senderUuid, deserialized.getSenderUuid());
        assertEquals("Alice", deserialized.getSenderName());
        assertEquals(targetUuid, deserialized.getTargetUuid());
        assertEquals("Bob", deserialized.getTargetName());
        assertEquals("survival-1", deserialized.getOriginServer());
        assertEquals("survival-2", deserialized.getTargetServer());
        assertEquals(TpaRequest.Type.TPHERE, deserialized.getType());
        assertEquals("world_nether", deserialized.getWorldName());
        assertEquals(50.0, deserialized.getX());
        assertEquals(80.0, deserialized.getY());
        assertEquals(-120.0, deserialized.getZ());
    }

    @Test
    void testTpaRequestCrossServerState() {
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        TpaRequest req = new TpaRequest(sender, "Sender", target, "Target", TpaRequest.Type.TPA);
        assertFalse(req.isCrossServer());

        req.setOriginServer("server-1");
        req.setTargetServer("server-1");
        assertFalse(req.isCrossServer());

        req.setTargetServer("server-2");
        assertTrue(req.isCrossServer());
    }

    @Test
    void testTabCompletionNetworkFiltering() {
        Set<String> networkPlayers = new LinkedHashSet<>(List.of("Alice", "Bob", "Charlie", "David", "Anna"));
        String senderName = "Alice";
        String search = "a";

        List<String> results = new ArrayList<>();
        for (String name : networkPlayers) {
            if (!name.equalsIgnoreCase(senderName) && name.toLowerCase(Locale.ROOT).startsWith(search.toLowerCase(Locale.ROOT))) {
                results.add(name);
            }
        }

        assertEquals(1, results.size());
        assertEquals("Anna", results.get(0));
        assertFalse(results.contains("Alice")); // Excluded sender

        // Search with empty string (all other players)
        results.clear();
        String emptySearch = "";
        for (String name : networkPlayers) {
            if (!name.equalsIgnoreCase(senderName) && name.toLowerCase(Locale.ROOT).startsWith(emptySearch)) {
                results.add(name);
            }
        }
        assertEquals(4, results.size());
        assertTrue(results.containsAll(List.of("Bob", "Charlie", "David", "Anna")));
        assertFalse(results.contains("Alice"));
    }

    @Test
    void testCoordinatePreservation() {
        UUID traveler = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        double exactX = 1234.5678;
        double exactY = 64.125;
        double exactZ = -9876.5432;
        float exactYaw = 179.5f;
        float exactPitch = -22.5f;

        TpaTeleportTicket ticket = new TpaTeleportTicket(
                traveler, "Traveler", host, "Host",
                "custom_world", exactX, exactY, exactZ, exactYaw, exactPitch,
                TpaRequest.Type.TPA, 60000L
        );

        assertEquals(exactX, ticket.getX(), 0.000001);
        assertEquals(exactY, ticket.getY(), 0.000001);
        assertEquals(exactZ, ticket.getZ(), 0.000001);
        assertEquals(exactYaw, ticket.getYaw(), 0.001f);
        assertEquals(exactPitch, ticket.getPitch(), 0.001f);
        assertEquals("custom_world", ticket.getWorldName());
    }
}
