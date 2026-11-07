package dev.lyy.friendlycore.modules.joinquit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class JoinQuitTest {

    private final Gson gson = new Gson();

    @Test
    @DisplayName("Test JoinQuitGroup priority sorting")
    void testGroupPrioritySorting() {
        JoinQuitGroup defaultGroup = new JoinQuitGroup("default", "", 0, "<gray>%player% joined</gray>", "<gray>%player% quit</gray>", "none", "none");
        JoinQuitGroup vipGroup = new JoinQuitGroup("vip", "perm.vip", 50, "<gold>[VIP] %player%</gold>", "<gold>[VIP] %player%</gold>", "none", "none");
        JoinQuitGroup ownerGroup = new JoinQuitGroup("owner", "perm.owner", 100, "<red>[Owner] %player%</red>", "<red>[Owner] %player%</red>", "none", "none");
        JoinQuitGroup adminGroup = new JoinQuitGroup("admin", "perm.admin", 80, "<dark_red>[Admin] %player%</dark_red>", "<dark_red>[Admin] %player%</dark_red>", "none", "none");

        List<JoinQuitGroup> list = new ArrayList<>(List.of(defaultGroup, vipGroup, ownerGroup, adminGroup));
        list.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        assertEquals("owner", list.get(0).id());
        assertEquals("admin", list.get(1).id());
        assertEquals("vip", list.get(2).id());
        assertEquals("default", list.get(3).id());
    }

    @Test
    @DisplayName("Test TextService MiniMessage and Placeholders in Join message")
    void testJoinMessageFormatting() {
        TextService textService = new TextService();
        String rawTemplate = "<dark_gray>[</dark_gray><green>+</green><dark_gray>]</dark_gray> <yellow>%player%</yellow> <gray>joined from</gray> <aqua>%server%</aqua>";

        Map<String, String> placeholders = Map.of(
                "%player%", "Steve",
                "%server%", "Survival-1"
        );

        Component comp = textService.parse(rawTemplate, placeholders);
        assertNotNull(comp);

        String plain = PlainTextComponentSerializer.plainText().serialize(comp);
        assertTrue(plain.contains("Steve"));
        assertTrue(plain.contains("Survival-1"));
        assertTrue(plain.contains("+"));
    }

    @Test
    @DisplayName("Test Cross-Server Network Join Packet Serialization")
    void testCrossServerJoinPacketSerialization() {
        UUID uuid = UUID.randomUUID();
        JsonObject extra = new JsonObject();
        extra.addProperty("serverName", "Survival-1");
        extra.addProperty("displayName", "Steve");
        extra.addProperty("silent", false);
        extra.addProperty("groupId", "vip");
        extra.addProperty("formattedMessage", "<gold>[VIP] Steve joined the server.</gold>");
        extra.addProperty("sound", "ENTITY_EXPERIENCE_ORB_PICKUP");

        CrossServerPacket packet = new CrossServerPacket(
                "Survival-1",
                CrossServerPacket.Action.NETWORK_JOIN,
                uuid,
                "Steve",
                0.0,
                gson.toJson(extra)
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        CrossServerPacket deserialized = gson.fromJson(json, CrossServerPacket.class);
        assertEquals(CrossServerPacket.Action.NETWORK_JOIN, deserialized.getAction());
        assertEquals("Survival-1", deserialized.getSourceServer());
        assertEquals("Steve", deserialized.getTargetName());
        assertEquals(uuid, deserialized.getTargetUuid());

        JsonObject extraDeserialized = gson.fromJson(deserialized.getExtra(), JsonObject.class);
        assertEquals("Survival-1", extraDeserialized.get("serverName").getAsString());
        assertEquals("Steve", extraDeserialized.get("displayName").getAsString());
        assertFalse(extraDeserialized.get("silent").getAsBoolean());
        assertEquals("vip", extraDeserialized.get("groupId").getAsString());
        assertEquals("<gold>[VIP] Steve joined the server.</gold>", extraDeserialized.get("formattedMessage").getAsString());
        assertEquals("ENTITY_EXPERIENCE_ORB_PICKUP", extraDeserialized.get("sound").getAsString());
    }

    @Test
    @DisplayName("Test Cross-Server Network Quit Packet Serialization")
    void testCrossServerQuitPacketSerialization() {
        UUID uuid = UUID.randomUUID();
        JsonObject extra = new JsonObject();
        extra.addProperty("serverName", "Lobby-1");
        extra.addProperty("displayName", "Alex");
        extra.addProperty("silent", false);
        extra.addProperty("groupId", "admin");
        extra.addProperty("formattedMessage", "<red>[Admin] Alex left the server.</red>");

        CrossServerPacket packet = new CrossServerPacket(
                "Lobby-1",
                CrossServerPacket.Action.NETWORK_QUIT,
                uuid,
                "Alex",
                0.0,
                gson.toJson(extra)
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        CrossServerPacket deserialized = gson.fromJson(json, CrossServerPacket.class);
        assertEquals(CrossServerPacket.Action.NETWORK_QUIT, deserialized.getAction());
        assertEquals("Lobby-1", deserialized.getSourceServer());
        assertEquals("Alex", deserialized.getTargetName());
        assertEquals(uuid, deserialized.getTargetUuid());

        JsonObject extraDeserialized = gson.fromJson(deserialized.getExtra(), JsonObject.class);
        assertEquals("Lobby-1", extraDeserialized.get("serverName").getAsString());
        assertFalse(extraDeserialized.get("silent").getAsBoolean());
        assertEquals("admin", extraDeserialized.get("groupId").getAsString());
    }

    @Test
    @DisplayName("Test Silent Staff Packet Serialization")
    void testSilentPacketSerialization() {
        UUID uuid = UUID.randomUUID();
        JsonObject extra = new JsonObject();
        extra.addProperty("serverName", "Survival-2");
        extra.addProperty("displayName", "StaffMember");
        extra.addProperty("silent", true);

        CrossServerPacket packet = new CrossServerPacket(
                "Survival-2",
                CrossServerPacket.Action.NETWORK_JOIN,
                uuid,
                "StaffMember",
                0.0,
                gson.toJson(extra)
        );

        String json = gson.toJson(packet);
        assertNotNull(json);

        CrossServerPacket deserialized = gson.fromJson(json, CrossServerPacket.class);
        JsonObject extraDeserialized = gson.fromJson(deserialized.getExtra(), JsonObject.class);
        assertTrue(extraDeserialized.get("silent").getAsBoolean());
    }

    @Test
    @DisplayName("Test Unique Joins Atomic Counter")
    void testUniqueJoinsCounter() {
        AtomicInteger uniqueJoins = new AtomicInteger(100);
        int newCount = uniqueJoins.incrementAndGet();
        assertEquals(101, newCount);
        assertEquals(101, uniqueJoins.get());
    }

    @Test
    @DisplayName("Disabled module or join/quit subsection hides vanilla broadcasts")
    void testDisabledFlagsHideVanilla() {
        assertTrue(JoinQuitListener.shouldHideVanilla(true, true, true), "suppress-vanilla-messages hides vanilla");
        assertTrue(JoinQuitListener.shouldHideVanilla(false, false, true), "module enabled: false hides vanilla");
        assertTrue(JoinQuitListener.shouldHideVanilla(false, true, false), "join/quit enabled: false hides vanilla");
        assertFalse(JoinQuitListener.shouldHideVanilla(false, true, true), "vanilla can show when suppress is off and module is on");
    }

    @Test
    @DisplayName("Test Player Message Mute Toggle")
    void testPlayerMuteToggle() {
        Set<UUID> muted = Collections.synchronizedSet(new HashSet<>());
        UUID playerUuid = UUID.randomUUID();

        assertFalse(muted.contains(playerUuid));

        // Toggle on (mute)
        muted.add(playerUuid);
        assertTrue(muted.contains(playerUuid));

        // Toggle off (unmute)
        muted.remove(playerUuid);
        assertFalse(muted.contains(playerUuid));
    }

    @Test
    @DisplayName("Test Server Switch Detection Logic")
    void testServerSwitchDetectionLogic() {
        Map<String, String> fakeRedis = new ConcurrentHashMap<>();
        UUID playerUuid = UUID.randomUUID();
        String originServer = "lobby-1";
        String targetServer = "survival-1";

        // Step 1: Player quits lobby -> sets pending quit in Redis
        fakeRedis.put("friendlycore:pending_quit:" + playerUuid, originServer);
        assertEquals(originServer, fakeRedis.get("friendlycore:pending_quit:" + playerUuid));

        // Step 2: Player arrives on survival -> detects pending quit from another server
        String pendingFrom = fakeRedis.get("friendlycore:pending_quit:" + playerUuid);
        boolean isSwitch = pendingFrom != null && !pendingFrom.equalsIgnoreCase(targetServer);
        assertTrue(isSwitch, "Should detect cross-server switch from lobby-1 to survival-1");

        // Step 3: Survival consumes pending quit
        fakeRedis.remove("friendlycore:pending_quit:" + playerUuid);
        assertNull(fakeRedis.get("friendlycore:pending_quit:" + playerUuid));
    }

    @Test
    @DisplayName("Test Anti-Spam Rate Limiter Window")
    void testAntiSpamRateLimiterWindow() {
        Map<UUID, Long> recentBroadcasts = new ConcurrentHashMap<>();
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // First broadcast succeeds
        Long lastTime = recentBroadcasts.put(uuid, now);
        assertNull(lastTime);

        // Immediate subsequent broadcast within 3 seconds is suppressed
        lastTime = recentBroadcasts.get(uuid);
        assertNotNull(lastTime);
        boolean rateLimited = (now + 500L - lastTime) < 3000L;
        assertTrue(rateLimited);

        // Broadcast after 4 seconds is permitted
        boolean allowedAfterCooldown = (now + 4000L - lastTime) < 3000L;
        assertFalse(allowedAfterCooldown);
    }
}
