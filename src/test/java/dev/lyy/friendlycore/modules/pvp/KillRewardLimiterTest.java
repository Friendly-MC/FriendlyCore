package dev.lyy.friendlycore.modules.pvp;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class KillRewardLimiterTest {
    @Test
    void repeatedVictimIsNotRewardedUntilCooldownExpires() {
        PvpManager.KillRewardLimiter limiter = new PvpManager.KillRewardLimiter();
        UUID killer = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertTrue(limiter.allow(killer, victim, 1000, 300));
        assertFalse(limiter.allow(killer, victim, 1001, 300));
        assertTrue(limiter.allow(killer, other, 1001, 300));
        assertTrue(limiter.allow(other, victim, 1001, 300));
        assertTrue(limiter.allow(killer, victim, 301000, 300));
        assertFalse(limiter.allow(killer, victim, 301001, 300));
        assertTrue(limiter.allow(killer, victim, 301002, 0));
    }
}
