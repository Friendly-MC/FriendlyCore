package dev.lyy.friendlycore.modules.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountOperationQueueTest {

    @Test
    void serializesOperationsSharingAnAccount() {
        AccountOperationQueue queue = new AccountOperationQueue();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        AtomicInteger starts = new AtomicInteger();
        CompletableFuture<Boolean> firstGate = new CompletableFuture<>();

        CompletableFuture<Boolean> first = queue.submit(a, b, () -> {
            starts.incrementAndGet();
            return firstGate;
        });
        CompletableFuture<Boolean> second = queue.submit(b, c, () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });

        assertEquals(1, starts.get());
        assertFalse(second.isDone());
        firstGate.complete(true);

        assertTrue(first.join());
        assertTrue(second.join());
        assertEquals(2, starts.get());
    }

    @Test
    void allowsDisjointAccountsToProceedConcurrently() {
        AccountOperationQueue queue = new AccountOperationQueue();
        CompletableFuture<Boolean> firstGate = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> {
            starts.incrementAndGet();
            return firstGate;
        });
        CompletableFuture<Boolean> second = queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> {
            starts.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });

        assertEquals(2, starts.get());
        assertTrue(second.join());
        firstGate.complete(true);
    }

    @Test
    void predecessorFailureDoesNotPoisonTheQueue() {
        AccountOperationQueue queue = new AccountOperationQueue();
        UUID shared = UUID.randomUUID();

        CompletableFuture<Boolean> failed = queue.submit(UUID.randomUUID(), shared,
                () -> CompletableFuture.failedFuture(new IllegalStateException("expected")));
        CompletableFuture<Boolean> recovery = queue.submit(shared, UUID.randomUUID(),
                () -> CompletableFuture.completedFuture(true));

        assertTrue(failed.isCompletedExceptionally());
        assertTrue(recovery.join());
    }
}
