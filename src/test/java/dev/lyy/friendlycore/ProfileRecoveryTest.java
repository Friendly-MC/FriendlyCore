package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRecoveryTest {
    @Test
    void pvpEventsDuringOutageMergeOnceWithoutOverwritingStoredTotals() {
        UUID uuid = UUID.randomUUID();
        PlayerStats pending = PlayerStats.pending(uuid, "Alex");
        pending.recordKill();
        pending.recordKill();
        pending.recordDeath();
        pending.recordKill();
        assertFalse(pending.isDirty(), "A pending profile must not save zero-based totals");
        PlayerStats stored = new PlayerStats(uuid, "Alex", 100, 20, 6, 7);
        pending.restore(stored);
        pending.restore(stored);
        assertEquals(103, pending.getKills());
        assertEquals(21, pending.getDeaths());
        assertEquals(1, pending.getCurrentStreak());
        assertEquals(8, pending.getHighestStreak());
        assertTrue(pending.isDirty());
    }

    @Test
    void pvpStreakContinuesWhenNoDeathOccurredWhileLoading() {
        var uuid = UUID.randomUUID();
        var pending = PlayerStats.pending(uuid, "Alex");
        pending.recordKill();
        pending.restore(new PlayerStats(uuid, "Alex", 10, 2, 4, 4));
        assertEquals(5, pending.getCurrentStreak());
        assertEquals(5, pending.getHighestStreak());
    }

    @Test
    void tpaRestoresUntouchedPreferencesAndKeepsChangesMadeDuringOutage() {
        UUID uuid = UUID.randomUUID();
        TpaProfile pending = TpaProfile.pending(uuid, "Alex");
        pending.setTpAutoEnabled(true);
        pending.incrementDailyCount();
        assertFalse(pending.isDirty());
        var stored = new TpaProfile(uuid, "Alex", false, false, false, false,
            7, LocalDate.now().toString(), 1);
        pending.restore(stored);
        pending.restore(stored);
        assertFalse(pending.isTpaEnabled());
        assertFalse(pending.isTpaHereEnabled());
        assertFalse(pending.isShowGui());
        assertTrue(pending.isTpAutoEnabled());
        assertEquals(8, pending.getDailyCount());
        assertTrue(pending.isDirty());
    }
}
