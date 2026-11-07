package dev.lyy.friendlycore.common.database;

import dev.lyy.friendlycore.FriendlyCore;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Retries profile reads only; transactions with an uncertain outcome must never be replayed. */
public final class DatabaseReadRetry {
    private DatabaseReadRetry() { }

    public static <T> CompletableFuture<T> load(FriendlyCore plugin,
            Supplier<CompletableFuture<T>> read, BooleanSupplier stillNeeded) {
        return load(read, () -> plugin.isEnabled() && stillNeeded.getAsBoolean(),
            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS));
    }

    static <T> CompletableFuture<T> load(Supplier<CompletableFuture<T>> read,
            BooleanSupplier stillNeeded, Executor retryExecutor) {
        CompletableFuture<T> result = new CompletableFuture<>();
        attempt(read, stillNeeded, retryExecutor, result);
        return result;
    }

    private static <T> void attempt(Supplier<CompletableFuture<T>> read, BooleanSupplier stillNeeded,
            Executor retryExecutor, CompletableFuture<T> result) {
        if (result.isDone()) return;
        if (!stillNeeded.getAsBoolean()) {
            result.cancel(false);
            return;
        }
        try {
            read.get().whenComplete((value, error) -> {
                if (error == null) {
                    if (stillNeeded.getAsBoolean()) result.complete(value);
                    else result.cancel(false);
                } else if (isTransient(error)) {
                    try {
                        retryExecutor.execute(() -> attempt(read, stillNeeded, retryExecutor, result));
                    } catch (RuntimeException rejected) {
                        result.completeExceptionally(rejected);
                    }
                } else {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException || cause instanceof SQLRecoverableException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40001"))) return true;
                // SQLite BUSY/LOCKED are temporary too.
                if (sql.getErrorCode() == 5 || sql.getErrorCode() == 6) return true;
            }
        }
        return false;
    }
}
