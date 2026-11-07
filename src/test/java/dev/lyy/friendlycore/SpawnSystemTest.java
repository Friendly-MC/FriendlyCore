package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SpawnSystemTest {

    @Test
    public void testCoordinateFormatting() {
        double x = 120.456;
        double y = 64.0;
        double z = -350.789;

        String formattedX = String.format("%.2f", x);
        String formattedY = String.format("%.2f", y);
        String formattedZ = String.format("%.2f", z);

        Assertions.assertEquals("120.46", formattedX);
        Assertions.assertEquals("64.00", formattedY);
        Assertions.assertEquals("-350.79", formattedZ);
    }

    @Test
    public void testSpawnDataJsonSerialization() {
        SpawnData data = new SpawnData("lobby", "world_nether", 100.5, 65.0, -200.5, 90.0f, 0.0f);
        String json = data.toJson();
        Assertions.assertNotNull(json);

        SpawnData deserialized = SpawnData.fromJson(json);
        Assertions.assertNotNull(deserialized);
        Assertions.assertEquals("lobby", deserialized.server());
        Assertions.assertEquals("world_nether", deserialized.world());
        Assertions.assertEquals(100.5, deserialized.x());
        Assertions.assertEquals(65.0, deserialized.y());
        Assertions.assertEquals(-200.5, deserialized.z());
        Assertions.assertEquals(90.0f, deserialized.yaw());
        Assertions.assertEquals(0.0f, deserialized.pitch());
    }

    @Test
    public void testSpawnDataConfigSerialization() {
        YamlConfiguration config = new YamlConfiguration();
        SpawnData original = new SpawnData("hub-1", "spawn", 0.5, 70.0, 0.5, 180.0f, 15.0f);
        original.writeToConfig(config.createSection("spawn.location"));

        SpawnData loaded = SpawnData.fromConfig(config.getConfigurationSection("spawn.location"), "lobby");
        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("hub-1", loaded.server());
        Assertions.assertEquals("spawn", loaded.world());
        Assertions.assertEquals(0.5, loaded.x());
        Assertions.assertEquals(70.0, loaded.y());
        Assertions.assertEquals(0.5, loaded.z());
        Assertions.assertEquals(180.0f, loaded.yaw());
        Assertions.assertEquals(15.0f, loaded.pitch());
    }

    @Test
    public void testCrossServerSpawnPacket() {
        UUID playerUuid = UUID.randomUUID();
        CrossServerPacket ticketPacket = new CrossServerPacket(
                "survival-1",
                CrossServerPacket.Action.SPAWN_TICKET,
                playerUuid,
                "TestPlayer",
                0.0,
                "lobby"
        );

        Assertions.assertEquals("survival-1", ticketPacket.getSourceServer());
        Assertions.assertEquals(CrossServerPacket.Action.SPAWN_TICKET, ticketPacket.getAction());
        Assertions.assertEquals(playerUuid, ticketPacket.getTargetUuid());
        Assertions.assertEquals("TestPlayer", ticketPacket.getTargetName());
        Assertions.assertEquals("lobby", ticketPacket.getExtra());
    }

    @Test
    public void testJoinSpawnAndRespawnConfigDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("join-spawn.mode", "FIRST_JOIN");
        config.set("join-spawn.delay-ticks", 2L);
        config.set("join-spawn.force-spawn-worlds", List.of("world_nether", "world_the_end"));

        config.set("respawn.mode", "NO_BED");
        config.set("respawn.sound", "ENTITY_PLAYER_LEVELUP");

        config.set("first-join.broadcast-enabled", true);
        config.set("first-join.title.enabled", true);
        config.set("first-join.sound.name", "UI_TOAST_CHALLENGE_COMPLETE");

        config.set("join-spawn.last-location.enabled", true);
        config.set("join-spawn.last-location.hub-servers", List.of("spawn-eco"));
        config.set("join-spawn.last-location.spawn-worlds", List.of("spawn"));
        config.set("join-spawn.last-location.blindness-ticks", 80);
        config.set("join-spawn.last-location.failsafe-ticks", 160);
        config.set("join-spawn.last-location.darkness", true);
        config.set("join-spawn.last-location.freeze", true);

        Assertions.assertEquals("FIRST_JOIN", config.getString("join-spawn.mode"));
        Assertions.assertEquals(2L, config.getLong("join-spawn.delay-ticks"));
        Assertions.assertEquals(2, config.getStringList("join-spawn.force-spawn-worlds").size());
        Assertions.assertTrue(config.getBoolean("join-spawn.last-location.enabled", true));
        Assertions.assertEquals("NO_BED", config.getString("respawn.mode"));
        Assertions.assertEquals("ENTITY_PLAYER_LEVELUP", config.getString("respawn.sound"));
        Assertions.assertTrue(config.getBoolean("first-join.broadcast-enabled"));
        Assertions.assertTrue(config.getBoolean("first-join.title.enabled"));
        Assertions.assertEquals("UI_TOAST_CHALLENGE_COMPLETE", config.getString("first-join.sound.name"));
    }

    @Test
    public void testBeaconRewardIsPinnedToOneConfiguredBlock() {
        var stream = getClass().getClassLoader().getResourceAsStream("modules/spawn/config.yml");
        Assertions.assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        Assertions.assertTrue(config.getBoolean("beacon-reward.require-coordinates"));
        Assertions.assertTrue(config.contains("beacon-reward.x"));
        Assertions.assertTrue(config.contains("beacon-reward.y"));
        Assertions.assertTrue(config.contains("beacon-reward.z"));
        Assertions.assertTrue(config.getBoolean("beacon-reward.strike-lightning"));
        Assertions.assertEquals("BEACON", config.getString("beacon-reward.target-material"));
        Assertions.assertEquals("fc givekit beacon %player%", config.getString("beacon-reward.dispatch-command"));
    }

    @Test
    public void testMobProtectionGlitchEndermiteAndSilverfishConfig() {
        var stream = getClass().getClassLoader().getResourceAsStream("modules/spawn/config.yml");
        Assertions.assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        Assertions.assertTrue(config.contains("mob-protection"));
        Assertions.assertFalse(config.getBoolean("mob-protection.enabled"));
        Assertions.assertTrue(config.getStringList("mob-protection.spawn-worlds").contains("spawn"));
        Assertions.assertTrue(config.getBoolean("mob-protection.block-all-hostile"));
        Assertions.assertTrue(config.getBoolean("mob-protection.block-ender-pearl-endermite"));
        Assertions.assertTrue(config.getBoolean("mob-protection.block-silverfish-infestation"));
        Assertions.assertTrue(config.getBoolean("mob-protection.allow-plugin-spawns"));

        List<String> blockedEntities = config.getStringList("mob-protection.blocked-entities");
        Assertions.assertTrue(blockedEntities.contains("ENDERMITE"));
        Assertions.assertTrue(blockedEntities.contains("SILVERFISH"));
        Assertions.assertTrue(blockedEntities.contains("WITHER"));
    }

    @Test
    public void testCrossServerRespawnPacketAndExtra() {
        UUID playerUuid = UUID.randomUUID();
        CrossServerPacket respawnPacket = new CrossServerPacket(
                "overworld-1",
                CrossServerPacket.Action.SPAWN_TICKET,
                playerUuid,
                "DyingPlayer",
                0.0,
                "spawn-eco"
        );

        Assertions.assertEquals("overworld-1", respawnPacket.getSourceServer());
        Assertions.assertEquals(CrossServerPacket.Action.SPAWN_TICKET, respawnPacket.getAction());
        Assertions.assertEquals(playerUuid, respawnPacket.getTargetUuid());
        Assertions.assertEquals("DyingPlayer", respawnPacket.getTargetName());
        Assertions.assertEquals("spawn-eco", respawnPacket.getExtra());
    }

    @Test
    public void testRespawnConfigOptions() {
        var stream = getClass().getClassLoader().getResourceAsStream("modules/spawn/config.yml");
        Assertions.assertNotNull(stream);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

        Assertions.assertTrue(config.contains("respawn.mode"));
        Assertions.assertEquals("NO_BED", config.getString("respawn.mode"));
        Assertions.assertEquals("ENTITY_PLAYER_LEVELUP", config.getString("respawn.sound"));
    }
}
