package dev.lyy.friendlycore.modules.chat;

import dev.lyy.friendlycore.modules.chat.filter.LeetMap;
import dev.lyy.friendlycore.modules.chat.filter.SimilarityEvaluator;
import dev.lyy.friendlycore.modules.chat.model.ChatChannel;
import dev.lyy.friendlycore.modules.chat.model.ChatMessage;
import dev.lyy.friendlycore.modules.chat.model.ChatPlayerData;
import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatModuleTest {

    @Test
    @DisplayName("Similarity Evaluator calculates correct Levenshtein ratios")
    void testSimilarityEvaluator() {
        assertEquals(1.0, SimilarityEvaluator.evaluateSimilarity("Hello World", "Hello World"), 0.001);
        assertEquals(1.0, SimilarityEvaluator.evaluateSimilarity("hello world", "HELLO WORLD"), 0.001);

        double simHigh = SimilarityEvaluator.evaluateSimilarity("Hello world!!", "Hello world!.");
        assertTrue(simHigh > 0.80, "Expected high similarity ratio, got: " + simHigh);

        double simLow = SimilarityEvaluator.evaluateSimilarity("Minecraft server", "Completely different topic");
        assertTrue(simLow < 0.30, "Expected low similarity ratio, got: " + simLow);

        assertEquals(0.0, SimilarityEvaluator.evaluateSimilarity("", "test"), 0.001);
        assertEquals(0.0, SimilarityEvaluator.evaluateSimilarity(null, "test"), 0.001);
    }

    @Test
    @DisplayName("LeetMap accurately maps obfuscated characters from YAML")
    void testLeetMap() {
        String yaml = """
            map:
              a: "@|4|/\\\\"
              e: "3|€"
              i: "1|!|l"
              o: "0|()"
            """;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        LeetMap leetMap = new LeetMap();
        leetMap.load(config);

        assertEquals("@|4|/\\", leetMap.getReplacement('a'));
        assertEquals("3|€", leetMap.getReplacement('e'));
        assertEquals("1|!|l", leetMap.getReplacement('i'));
        assertEquals("0|()", leetMap.getReplacement('o'));
        assertEquals("z", leetMap.getReplacement('z')); // Unmapped defaults to character
    }

    @Test
    @DisplayName("ChatPlayerData properly handles points, ignored users, and toggle states")
    void testChatPlayerData() {
        UUID uuid = UUID.randomUUID();
        ChatPlayerData data = new ChatPlayerData(uuid, "Steve", 0, false, new HashSet<>(), 0L);

        assertEquals(0, data.getPoints());
        assertFalse(data.hasMessagesClosed());

        data.addPoints(5);
        assertEquals(5, data.getPoints());

        data.takePoints(2);
        assertEquals(3, data.getPoints());

        data.setPoints(10);
        assertEquals(10, data.getPoints());

        UUID ignoredTarget = UUID.randomUUID();
        assertFalse(data.isIgnoring(ignoredTarget));

        data.addIgnored(ignoredTarget);
        assertTrue(data.isIgnoring(ignoredTarget));

        data.removeIgnored(ignoredTarget);
        assertFalse(data.isIgnoring(ignoredTarget));

        data.setMessagesClosed(true);
        assertTrue(data.hasMessagesClosed());
    }

    @Test
    @DisplayName("ChatMessage model manages pipeline states and metadata correctly")
    void testChatMessage() {
        ChatMessage msg = new ChatMessage(null, "Hello World", Component.text("Hello World"), ChatChannel.PUBLIC);

        assertEquals("Hello World", msg.getRawMessage());
        assertEquals(ChatChannel.PUBLIC, msg.getChannel());
        assertFalse(msg.isCancelled());

        msg.setRawMessage("New message");
        assertEquals("New message", msg.getRawMessage());

        msg.cancel("filtered");
        assertTrue(msg.isCancelled());
        assertEquals("filtered", msg.getCancelReason());

        UUID mentionUuid = UUID.randomUUID();
        msg.addMention(mentionUuid);
        assertTrue(msg.getMentions().contains(mentionUuid));

        msg.addInfractionPoints(3);
        assertEquals(3, msg.getInfractionPoints());
        msg.addFlaggedWord("badword");
        assertTrue(msg.getFlaggedWords().contains("badword"));
    }

    @Test
    @DisplayName("TextSpan correctly checks character interval containment")
    void testTextSpan() {
        dev.lyy.friendlycore.modules.chat.filter.ChatFilterManager.TextSpan whitelistSpan =
                new dev.lyy.friendlycore.modules.chat.filter.ChatFilterManager.TextSpan(10, 20);

        // Substring fully inside whitelist span -> whitelisted
        assertTrue(whitelistSpan.contains(12, 18));
        assertTrue(whitelistSpan.contains(10, 20));

        // Substring outside or overlapping -> not whitelisted
        assertFalse(whitelistSpan.contains(0, 5));
        assertFalse(whitelistSpan.contains(8, 15));
        assertFalse(whitelistSpan.contains(15, 25));
    }

    @Test
    @DisplayName("Structured Chat Format builder correctly generates MiniMessage tags")
    void testStructuredChatFormat() {
        String msg = "%luckperms_prefix% &f%customfontlegacy_style:1,{player_name}% &8➠ ";
        String hover = "&#25c8fa&lINFO<br>&#25c8fa&l▮&r &7ʟɪғᴇ &f%player_health_scale% &c❤";
        String action = "SUGGEST_COMMAND:/msg %player_name%";

        String built = dev.lyy.friendlycore.modules.chat.ChatModule.buildStructuredChatFormat(msg, hover, action);
        assertTrue(built.contains("<click:suggest_command:'/msg %player_name%'>"));
        assertTrue(built.contains("<hover:show_text:'"));
        assertTrue(built.contains("</hover></click>"));
        assertTrue(built.contains("<white>{message}</white>"));
    }

    @Test
    @DisplayName("RenderSnapshot serializes and deserializes with 100% fidelity")
    void testRenderSnapshotSerialization() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        UUID snapshotId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        RenderSnapshot original = new RenderSnapshot(
                snapshotId,
                RenderSnapshot.RenderType.INVENTORY,
                ownerUuid,
                "Eangly",
                null,
                null,
                null,
                null
        );

        String json = original.serializeToJson(gson);
        assertNotNull(json);
        assertTrue(json.contains(snapshotId.toString()));
        assertTrue(json.contains("INVENTORY"));
        assertTrue(json.contains("Eangly"));

        RenderSnapshot restored = RenderSnapshot.deserializeFromJson(json, gson);
        assertNotNull(restored);
        assertEquals(snapshotId, restored.getSnapshotId());
        assertEquals(RenderSnapshot.RenderType.INVENTORY, restored.getType());
        assertEquals(ownerUuid, restored.getOwnerUuid());
        assertEquals("Eangly", restored.getOwnerName());
    }

    @Test
    @DisplayName("MiniMessage double-quoted render tags parse cleanly without unclosed tag exceptions")
    void testMiniMessageRenderTags() {
        UUID uuid = UUID.randomUUID();
        String invTag = "<click:run_command:\"/chatinv " + uuid + "\"><hover:show_text:\"<color:#D8D0C3>Click to view <color:#E7B85C>Eangly</color>'s inventory</color>\"><dark_gray>[<color:#6EDBFF>Eangly's Inventory</color>]</dark_gray></hover></click>";

        Component comp = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(invTag);
        assertNotNull(comp);
        assertNotNull(comp.clickEvent());
        assertEquals(net.kyori.adventure.text.event.ClickEvent.runCommand("/chatinv " + uuid), comp.clickEvent());
        assertNotNull(comp.hoverEvent());
    }

    @Test
    @DisplayName("RenderSnapshot item array serialization uses sparse GZIP compression")
    void testRenderSnapshotSparseCompression() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        UUID snapshotId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        // Empty contents
        ItemStack[] emptyContents = new ItemStack[36];
        String emptySerialized = RenderSnapshot.serializeItemArray(emptyContents);
        assertNotNull(emptySerialized);
        assertTrue(emptySerialized.startsWith("gz:"));

        ItemStack[] deserializedEmpty = RenderSnapshot.deserializeItemArray(emptySerialized, 36);
        assertNotNull(deserializedEmpty);
        assertEquals(36, deserializedEmpty.length);
        for (ItemStack item : deserializedEmpty) {
            assertNull(item);
        }

        // Test roundtrip of full snapshot with compressed serialization
        RenderSnapshot original = new RenderSnapshot(
                snapshotId,
                RenderSnapshot.RenderType.INVENTORY,
                ownerUuid,
                "Eangly",
                null,
                emptyContents,
                new ItemStack[4],
                null
        );

        String json = original.serializeToJson(gson);
        assertNotNull(json);
        assertTrue(json.contains("\"contents\":\"gz:"));
        assertTrue(json.contains("\"armor\":\"gz:"));

        RenderSnapshot restored = RenderSnapshot.deserializeFromJson(json, gson);
        assertNotNull(restored);
        assertEquals(snapshotId, restored.getSnapshotId());
        assertEquals(36, restored.getContents().length);
        assertEquals(4, restored.getArmor().length);
    }

    @Test
    @DisplayName("RenderSnapshot handles legacy uncompressed format gracefully")
    void testRenderSnapshotLegacyFallback() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        UUID snapshotId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        // Legacy format without "gz:" prefix
        String legacyJson = """
            {
                "snapshotId": "%s",
                "type": "ITEM",
                "ownerUuid": "%s",
                "ownerName": "Player1",
                "createdAt": %d
            }
            """.formatted(snapshotId.toString(), ownerUuid.toString(), System.currentTimeMillis());

        RenderSnapshot restored = RenderSnapshot.deserializeFromJson(legacyJson, gson);
        assertNotNull(restored);
        assertEquals(snapshotId, restored.getSnapshotId());
        assertEquals(RenderSnapshot.RenderType.ITEM, restored.getType());
        assertEquals("Player1", restored.getOwnerName());
        assertNull(restored.getItem());
    }

    @Test
    @DisplayName("RegexFactory is resilient against ReDoS pathological punctuation strings")
    void testRegexFactoryRedosResilience() {
        String yaml = """
            map:
              a: "@|4|/\\\\"
              e: "3|€"
              i: "1|!|l"
              o: "0|()"
            """;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        LeetMap leetMap = new LeetMap();
        leetMap.load(config);

        dev.lyy.friendlycore.modules.chat.filter.RegexFactory regexFactory = new dev.lyy.friendlycore.modules.chat.filter.RegexFactory(leetMap);
        java.util.regex.Pattern pattern = regexFactory.generateWordPattern("test", false);
        assertNotNull(pattern);

        String pathological = "||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||||!";
        long start = System.currentTimeMillis();
        boolean matched = pattern.matcher(pathological).find();
        long duration = System.currentTimeMillis() - start;

        assertFalse(matched);
        assertTrue(duration < 200, "Regex matching took too long: " + duration + "ms");
    }

    @Test
    @DisplayName("RenderSnapshot rejects oversized inventory lengths before allocating")
    void testRenderSnapshotRejectsOversizedInventoryLength() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(Integer.MAX_VALUE);
            out.writeInt(0);
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write(raw.toByteArray());
        }

        String payload = "gz:" + Base64.getEncoder().encodeToString(compressed.toByteArray());
        assertEquals(0, RenderSnapshot.deserializeItemArray(payload, 36).length);
    }

    @Test
    @DisplayName("RenderSnapshot bounds compressed payload expansion")
    void testRenderSnapshotRejectsCompressionBomb() throws Exception {
        byte[] oversized = new byte[2_100_000];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(compressed)) {
            gzip.write(oversized);
        }

        String payload = "gz:" + Base64.getEncoder().encodeToString(compressed.toByteArray());
        assertEquals(0, RenderSnapshot.deserializeItemArray(payload, 36).length);
    }

    @Test
    @DisplayName("RenderChatMessage separates header from message to prevent hover/click leaking")
    void testRenderChatMessageNoEventLeaking() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);

        dev.lyy.friendlycore.FriendlyCore plugin = (dev.lyy.friendlycore.FriendlyCore) unsafe.allocateInstance(dev.lyy.friendlycore.FriendlyCore.class);
        java.lang.reflect.Field tsField = dev.lyy.friendlycore.FriendlyCore.class.getDeclaredField("textService");
        tsField.setAccessible(true);
        tsField.set(plugin, new dev.lyy.friendlycore.common.TextService());

        ChatModule chat = (ChatModule) unsafe.allocateInstance(ChatModule.class);
        java.lang.reflect.Field pluginField = ChatModule.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        pluginField.set(chat, plugin);

        java.lang.reflect.Field isStructuredField = ChatModule.class.getDeclaredField("isStructuredFormat");
        isStructuredField.setAccessible(true);
        isStructuredField.set(chat, true);

        java.lang.reflect.Field structMsgField = ChatModule.class.getDeclaredField("structuredFormatMessage");
        structMsgField.setAccessible(true);
        structMsgField.set(chat, "<gray>[Admin]</gray> <white>%player%</white><dark_gray> ➠ </dark_gray>");

        java.lang.reflect.Field structHoverField = ChatModule.class.getDeclaredField("structuredFormatHover");
        structHoverField.setAccessible(true);
        structHoverField.set(chat, "<yellow>Player:</yellow> %player%<br><gray>Rank: %group%</gray>\r\n<gray>Ping: %ping%ms</gray>");

        java.lang.reflect.Field structActionField = ChatModule.class.getDeclaredField("structuredFormatAction");
        structActionField.setAccessible(true);
        structActionField.set(chat, "SUGGEST_COMMAND:/msg %player%");

        java.util.Map<String, String> placeholders = java.util.Map.of(
                "player", "Steve",
                "group", "Admin",
                "ping", "15"
        );
        Component msgComp = Component.text("Hello cross-server world!");
        Component rendered = chat.renderChatMessage(null, placeholders, msgComp);

        assertNotNull(rendered);
        assertNull(rendered.clickEvent(), "Root component must NOT have a click event");
        assertNull(rendered.hoverEvent(), "Root component must NOT have a hover event");

        assertEquals(2, rendered.children().size(), "Root must wrap header and message as sibling children");
        Component headerChild = rendered.children().get(0);
        Component messageChild = rendered.children().get(1);

        assertNotNull(headerChild.hoverEvent(), "Header should retain hover event");
        assertNotNull(headerChild.clickEvent(), "Header should retain click event");
        assertEquals(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/msg Steve"), headerChild.clickEvent());

        assertNull(messageChild.hoverEvent(), "Message must NOT inherit header hover event");
        assertNull(messageChild.clickEvent(), "Message must NOT inherit header click event");
    }

    @Test
    @DisplayName("Cross-server public chat payload preserves displayName and metadata")
    void testCrossServerPayloadPreservesDisplayName() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<String, String> payload = java.util.Map.ofEntries(
                java.util.Map.entry("senderUuid", UUID.randomUUID().toString()),
                java.util.Map.entry("senderName", "Steve"),
                java.util.Map.entry("displayName", "SuperSteve"),
                java.util.Map.entry("serverName", "survival"),
                java.util.Map.entry("prefix", "[VIP]"),
                java.util.Map.entry("suffix", ""),
                java.util.Map.entry("group", "vip"),
                java.util.Map.entry("healthScale", "18"),
                java.util.Map.entry("ping", "32"),
                java.util.Map.entry("balance", "999.99"),
                java.util.Map.entry("message", "Hello remote server!")
        );

        String json = gson.toJson(payload);
        java.util.Map<String, String> received = gson.fromJson(json, java.util.Map.class);

        assertEquals("SuperSteve", received.get("displayName"));
        assertEquals("Steve", received.get("senderName"));
        assertEquals("[VIP]", received.get("prefix"));
        assertEquals("vip", received.get("group"));
        assertEquals("18", received.get("healthScale"));
        assertEquals("32", received.get("ping"));
        assertEquals("999.99", received.get("balance"));
    }

}
