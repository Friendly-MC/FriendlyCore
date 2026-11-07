package dev.lyy.friendlycore.modules.rtp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpWarmupSessionTest {

    @Test
    void completionCanOnlyBeClaimedOnce() {
        RtpManager.RtpWarmupSession session = new RtpManager.RtpWarmupSession();

        assertTrue(session.tryComplete());
        assertFalse(session.tryComplete());
    }

    @Test
    void cancellationPreventsLateTimerCompletion() {
        RtpManager.RtpWarmupSession session = new RtpManager.RtpWarmupSession();

        session.cancel();

        assertTrue(session.isTerminal());
        assertFalse(session.tryComplete());
    }

    @Test
    void concurrentTimerCallbacksHaveOneWinner() throws InterruptedException {
        RtpManager.RtpWarmupSession session = new RtpManager.RtpWarmupSession();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread[] callbacks = new Thread[8];

        for (int i = 0; i < callbacks.length; i++) {
            callbacks[i] = new Thread(() -> {
                try {
                    start.await();
                    if (session.tryComplete()) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            callbacks[i].start();
        }

        start.countDown();
        for (Thread callback : callbacks) {
            callback.join();
        }

        assertEquals(1, winners.get());
    }
}
