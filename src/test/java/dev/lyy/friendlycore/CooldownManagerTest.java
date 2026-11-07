package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.CooldownManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class CooldownManagerTest {

    private CooldownManager cooldownManager;

    @BeforeEach
    public void setup() {
        cooldownManager = new CooldownManager();
    }

    @Test
    public void testBasicCooldown() {
        UUID uuid = UUID.randomUUID();
        String category = "drill";

        Assertions.assertFalse(cooldownManager.isOnCooldown(category, uuid, 1000));
        Assertions.assertEquals(0, cooldownManager.getRemainingMillis(category, uuid, 1000));

        cooldownManager.setCooldown(category, uuid);
        Assertions.assertTrue(cooldownManager.isOnCooldown(category, uuid, 1000));
        Assertions.assertTrue(cooldownManager.getRemainingMillis(category, uuid, 1000) > 0);
        Assertions.assertTrue(cooldownManager.getRemainingSeconds(category, uuid, 1000) > 0);

        cooldownManager.resetCooldown(category, uuid);
        Assertions.assertFalse(cooldownManager.isOnCooldown(category, uuid, 1000));
        Assertions.assertEquals(0, cooldownManager.getRemainingMillis(category, uuid, 1000));
    }

    @Test
    public void testNullSafety() {
        Assertions.assertFalse(cooldownManager.isOnCooldown(null, null, 1000));
        Assertions.assertFalse(cooldownManager.isOnCooldown("test", null, 1000));
        Assertions.assertFalse(cooldownManager.isOnCooldown(null, UUID.randomUUID(), 1000));

        cooldownManager.setCooldown(null, null);
        cooldownManager.resetCooldown(null, null);
        Assertions.assertEquals(0, cooldownManager.getRemainingMillis(null, null, 1000));
    }

    @Test
    public void testZeroOrNegativeCooldown() {
        UUID uuid = UUID.randomUUID();
        cooldownManager.setCooldown("test", uuid);

        Assertions.assertFalse(cooldownManager.isOnCooldown("test", uuid, 0));
        Assertions.assertFalse(cooldownManager.isOnCooldown("test", uuid, -100));
        Assertions.assertEquals(0, cooldownManager.getRemainingMillis("test", uuid, 0));
    }

    @Test
    public void testCleanup() {
        UUID uuid = UUID.randomUUID();
        cooldownManager.setCooldown("test", uuid);
        cooldownManager.cleanup();
        Assertions.assertTrue(cooldownManager.isOnCooldown("test", uuid, 10_000));
    }

    @Test
    public void testDailyKitCooldownRetentionAfterCleanup() {
        UUID uuid = UUID.randomUUID();
        long oneDayMillis = 86_400_000L;
        cooldownManager.setCooldown("kit:daily", uuid, oneDayMillis);

        cooldownManager.cleanup();
        Assertions.assertTrue(cooldownManager.isOnCooldown("kit:daily", uuid, oneDayMillis));
        Assertions.assertTrue(cooldownManager.getRemainingMillis("kit:daily", uuid, oneDayMillis) > 0);
    }

    @Test
    public void testWholeSecondCeilCalculations() {
        UUID uuid = UUID.randomUUID();
        cooldownManager.setCooldown("test_ceil", uuid);

        // For a 2500ms duration right after setCooldown, remaining seconds ceil should be 3s
        long sec = cooldownManager.getRemainingSeconds("test_ceil", uuid, 2500L);
        Assertions.assertEquals(3L, sec);

        double secDouble = cooldownManager.getRemainingSecondsDouble("test_ceil", uuid, 2500L);
        Assertions.assertTrue(secDouble >= 2.4 && secDouble <= 2.5);
    }

    @Test
    void explicitExpiryOverridesBackendDurationAndDelayedSyncCannotShortenIt() {
        UUID uuid = UUID.randomUUID();
        long expiry = System.currentTimeMillis() + 60_000;
        cooldownManager.setCooldownUntil("rtp", uuid, expiry);
        cooldownManager.setCooldownUntil("rtp", uuid, expiry - 30_000);
        Assertions.assertTrue(cooldownManager.getRemainingMillis("rtp", uuid, 1000) > 59_000);
        Assertions.assertTrue(cooldownManager.isOnCooldown("rtp", uuid, 0));
        Assertions.assertFalse(cooldownManager.tryAcquire("rtp", uuid, 1000));
        cooldownManager.resetCooldown("rtp", uuid);
        Assertions.assertTrue(cooldownManager.tryAcquire("rtp", uuid, 1000));
        Assertions.assertTrue(cooldownManager.isOnCooldown("rtp", uuid, 1000));
    }

    @Test
    void concurrentAcquisitionHasOneWinner() throws Exception {
        UUID uuid = UUID.randomUUID();
        var start = new java.util.concurrent.CountDownLatch(1);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] callers = new Thread[16];
        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(() -> {
                try {
                    start.await();
                    if (cooldownManager.tryAcquire("rtp", uuid, 60_000)) winners.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            callers[i].start();
        }
        start.countDown();
        for (Thread caller : callers) caller.join();
        Assertions.assertEquals(1, winners.get());
    }
}
