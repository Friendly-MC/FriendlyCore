package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.CooldownManager;
import dev.lyy.friendlycore.modules.placeholders.FriendlyExpansion;
import dev.lyy.friendlycore.modules.placeholders.MoneyTitlesExpansion;
import dev.lyy.friendlycore.modules.placeholders.PlayerKitsExpansion;
import dev.lyy.friendlycore.modules.placeholders.SwiftPayExpansion;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class FriendlyExpansionTest {

    @Test
    @DisplayName("Expansion identifiers and backward compatibility delegates should match expected values")
    public void testExpansionIdentifiers() {
        PlayerKitsExpansion pkExp = new PlayerKitsExpansion(null, null);
        Assertions.assertEquals("playerkits", pkExp.getIdentifier());
        Assertions.assertTrue(pkExp.persist());

        SwiftPayExpansion spExp = new SwiftPayExpansion(null, null);
        Assertions.assertEquals("swiftpay", spExp.getIdentifier());
        Assertions.assertTrue(spExp.persist());

        MoneyTitlesExpansion mtExp = new MoneyTitlesExpansion(null, null);
        Assertions.assertEquals("moneytitles", mtExp.getIdentifier());
        Assertions.assertTrue(mtExp.persist());

        FriendlyExpansion fcExp = new FriendlyExpansion(null);
        Assertions.assertEquals("friendlycore", fcExp.getIdentifier());
        Assertions.assertTrue(fcExp.persist());
    }

    @Test
    @DisplayName("SpawnWarmupSession should accurately track remaining integer seconds")
    public void testSpawnWarmupSession() {
        UUID uuid = UUID.randomUUID();
        SpawnManager.SpawnWarmupSession session = new SpawnManager.SpawnWarmupSession(uuid, 3, null);

        Assertions.assertEquals(uuid, session.getPlayerUuid());
        Assertions.assertEquals(3, session.getTotalSeconds());
        Assertions.assertEquals(3, session.getRemainingSeconds());

        session.decrement();
        Assertions.assertEquals(2, session.getRemainingSeconds());

        session.decrement();
        Assertions.assertEquals(1, session.getRemainingSeconds());

        session.decrement();
        Assertions.assertEquals(0, session.getRemainingSeconds());

        // Should not drop below 0 in getRemainingSeconds
        session.decrement();
        Assertions.assertEquals(0, session.getRemainingSeconds());
    }

    @Test
    @DisplayName("CooldownManager should produce integer ceil seconds (e.g. 3, 2, 1) rather than decimal fractions")
    public void testWholeSecondsCooldownCalculations() {
        CooldownManager cd = new CooldownManager();
        UUID uuid = UUID.randomUUID();

        // Not on cooldown
        Assertions.assertEquals(0, cd.getRemainingSeconds("spawn", uuid, 5000L));

        cd.setCooldown("spawn", uuid);

        // Immediate query for 5s cooldown should return 5s
        long remaining = cd.getRemainingSeconds("spawn", uuid, 5000L);
        Assertions.assertTrue(remaining >= 4 && remaining <= 5);

        // Immediate query for 2.5s duration should ceil to 3s
        long remaining25 = cd.getRemainingSeconds("spawn", uuid, 2500L);
        Assertions.assertEquals(3L, remaining25);

        // Immediate query for 1.1s duration should ceil to 2s
        long remaining11 = cd.getRemainingSeconds("spawn", uuid, 1100L);
        Assertions.assertEquals(2L, remaining11);

        // Immediate query for 0.4s duration should ceil to 1s
        long remaining04 = cd.getRemainingSeconds("spawn", uuid, 400L);
        Assertions.assertEquals(1L, remaining04);
    }

    @Test
    @DisplayName("SpawnData coordinate formatting should be clean and deterministic")
    public void testSpawnDataFormatting() {
        SpawnData data = new SpawnData("hub-1", "spawn_world", 100.5, 64.0, -200.5, 90.0f, 0.0f);
        Assertions.assertEquals("hub-1", data.server());
        Assertions.assertEquals("spawn_world", data.world());
        Assertions.assertEquals(100.5, data.x());
        Assertions.assertEquals(64.0, data.y());
        Assertions.assertEquals(-200.5, data.z());
        Assertions.assertEquals(90.0f, data.yaw());
        Assertions.assertEquals(0.0f, data.pitch());
    }

    @Test
    @DisplayName("PvpEconomyRecord should hold accurate snapshot of victim and killer financial transactions")
    public void testPvpEconomyRecord() {
        long now = System.currentTimeMillis();
        dev.lyy.friendlycore.modules.pvp.PvpEconomyManager.PvpEconomyRecord record =
                new dev.lyy.friendlycore.modules.pvp.PvpEconomyManager.PvpEconomyRecord("VictimPlayer", "KillerPlayer", 750.0, 0.0, "750", "0", now);

        Assertions.assertEquals("VictimPlayer", record.victimName());
        Assertions.assertEquals("KillerPlayer", record.killerName());
        Assertions.assertEquals(750.0, record.moneyLost());
        Assertions.assertEquals(0.0, record.moneyGained());
        Assertions.assertEquals("750", record.formattedLost());
        Assertions.assertEquals("0", record.formattedGained());
        Assertions.assertEquals(now, record.timestamp());
    }
}
