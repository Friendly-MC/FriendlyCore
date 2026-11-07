package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class PlayerStatsTest {

    @Test
    public void testInitialStats() {
        UUID uuid = UUID.randomUUID();
        PlayerStats stats = new PlayerStats(uuid, "Warrior", 10, 5, 2, 8);

        Assertions.assertEquals(uuid, stats.getUuid());
        Assertions.assertEquals("Warrior", stats.getUsername());
        Assertions.assertEquals(10, stats.getKills());
        Assertions.assertEquals(5, stats.getDeaths());
        Assertions.assertEquals(2, stats.getCurrentStreak());
        Assertions.assertEquals(8, stats.getHighestStreak());
        Assertions.assertEquals(2.0, stats.getKDRatio());
    }

    @Test
    public void testRecordKillAndStreak() {
        UUID uuid = UUID.randomUUID();
        PlayerStats stats = new PlayerStats(uuid, "Slayer", 0, 0, 0, 0);

        stats.recordKill();
        Assertions.assertEquals(1, stats.getKills());
        Assertions.assertEquals(1, stats.getCurrentStreak());
        Assertions.assertEquals(1, stats.getHighestStreak());
        Assertions.assertTrue(stats.isDirty());

        stats.recordKill();
        stats.recordKill();
        Assertions.assertEquals(3, stats.getKills());
        Assertions.assertEquals(3, stats.getCurrentStreak());
        Assertions.assertEquals(3, stats.getHighestStreak());

        stats.recordDeath();
        Assertions.assertEquals(1, stats.getDeaths());
        Assertions.assertEquals(0, stats.getCurrentStreak());
        Assertions.assertEquals(3, stats.getHighestStreak()); // Highest streak preserved
        Assertions.assertEquals(3.0, stats.getKDRatio());
    }

    @Test
    public void testKDRatioCalculation() {
        UUID uuid = UUID.randomUUID();
        PlayerStats stats = new PlayerStats(uuid, "NoDeaths", 5, 0, 5, 5);
        Assertions.assertEquals(5.0, stats.getKDRatio());

        PlayerStats stats2 = new PlayerStats(uuid, "Even", 10, 4, 0, 0);
        Assertions.assertEquals(2.5, stats2.getKDRatio());
    }
}
