package dev.lyy.friendlycore.common.database;

import org.junit.jupiter.api.Test;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseReadRetryTest {
    @Test
    void connectionOutageRetriesWithoutBlockingAndReturnsRealData() {
        var retries = new ArrayDeque<Runnable>();
        var attempts = new AtomicInteger();
        var result = DatabaseReadRetry.load(() -> attempts.incrementAndGet() < 3
            ? CompletableFuture.failedFuture(new SQLNonTransientConnectionException("Disconnected", "08001"))
            : CompletableFuture.completedFuture("stored profile"), () -> true, retries::add);
        assertFalse(result.isDone());
        assertEquals(1, attempts.get());
        retries.remove().run();
        assertFalse(result.isDone());
        retries.remove().run();
        assertEquals("stored profile", result.join());
        assertTrue(retries.isEmpty());
    }

    @Test
    void quitOrReplacedLoginCancelsRetryAndLateCompletion() {
        var needed = new AtomicBoolean(true);
        var retries = new ArrayDeque<Runnable>();
        var result = DatabaseReadRetry.load(() -> CompletableFuture.failedFuture(
            new SQLNonTransientConnectionException("Disconnected", "08001")), needed::get, retries::add);
        needed.set(false);
        retries.remove().run();
        assertTrue(result.isCancelled());
        var pending = new CompletableFuture<String>();
        needed.set(true);
        var late = DatabaseReadRetry.load(() -> pending, needed::get, retries::add);
        needed.set(false);
        pending.complete("old session");
        assertTrue(late.isCancelled());
    }

    @Test
    void permanentSqlErrorDoesNotRetry() {
        var retries = new ArrayDeque<Runnable>();
        var result = DatabaseReadRetry.load(() -> CompletableFuture.failedFuture(
            new SQLSyntaxErrorException("Missing table", "42000")), () -> true, retries::add);
        assertTrue(result.isCompletedExceptionally());
        assertTrue(retries.isEmpty());
    }
}
