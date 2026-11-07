package dev.lyy.friendlycore.modules.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Serializes asynchronous operations that share either of two economy accounts
 * while still allowing unrelated account pairs to proceed concurrently.
 */
final class AccountOperationQueue {

    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public synchronized <T> CompletableFuture<T> submit(UUID first, UUID second,
                                                         Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> predecessors = CompletableFuture.allOf(
                tolerateFailure(tails.get(first)), tolerateFailure(tails.get(second)));
        CompletableFuture<T> result = predecessors.thenCompose(ignored -> operation.get());
        CompletableFuture<Void> tail = result.handle((value, failure) -> null);
        tails.put(first, tail);
        tails.put(second, tail);
        tail.whenComplete((ignored, failure) -> {
            tails.remove(first, tail);
            tails.remove(second, tail);
        });
        return result;
    }

    private CompletableFuture<Void> tolerateFailure(CompletableFuture<Void> future) {
        return future == null ? CompletableFuture.completedFuture(null) : future.exceptionally(error -> null);
    }
}
