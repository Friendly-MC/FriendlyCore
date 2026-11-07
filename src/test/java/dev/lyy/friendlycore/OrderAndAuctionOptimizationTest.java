package dev.lyy.friendlycore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrderAndAuctionOptimizationTest {

    @Test
    @DisplayName("AuctionManager: syncTask does not query database notifications or deliveries for all online players")
    void testAuctionManagerSyncTaskDoesNotFloodDatabase() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlycore/modules/auctions/AuctionManager.java"));

        // syncTask definition must not iterate over getOnlinePlayers
        int syncTaskIdx = content.indexOf("this.syncTask = plugin.getScheduler().runGlobalTimer");
        assertTrue(syncTaskIdx >= 0, "AuctionManager must have syncTask scheduled via runGlobalTimer");

        int endTimerIdx = content.indexOf(");", syncTaskIdx);
        assertTrue(endTimerIdx > syncTaskIdx, "syncTask timer closing not found");
        String syncTaskBlock = content.substring(syncTaskIdx, endTimerIdx);
        assertFalse(syncTaskBlock.contains("getOnlinePlayers()"),
                "syncTask must not iterate over Bukkit.getOnlinePlayers()");
        assertFalse(syncTaskBlock.contains("checkPendingDeliveries"),
                "syncTask must not call checkPendingDeliveries periodically for all players");
    }

    @Test
    @DisplayName("OrderManager: Distributed lock calls are completely offloaded to async scheduler executor")
    void testOrderManagerRedisLocksAreAsync() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlycore/modules/orders/OrderManager.java"));

        // acquireLock must return CompletableFuture<Boolean> and use supplyAsync
        assertTrue(content.contains("public CompletableFuture<Boolean> acquireLock(UUID orderId)"),
                "acquireLock must return CompletableFuture<Boolean>");
        assertTrue(content.contains("plugin.getScheduler().getAsyncExecutor()"),
                "OrderManager must dispatch Redis distributed lock operations to async executor");

        // claimDeliveries must not block main thread on acquireDistributedLock
        int claimDeliveriesIdx = content.indexOf("public CompletableFuture<Integer> claimDeliveries(Player player)");
        assertTrue(claimDeliveriesIdx >= 0, "claimDeliveries must exist");
        int claimEndIdx = content.indexOf("private CompletableFuture<Integer> awardClaimedDeliveries(", claimDeliveriesIdx);
        String claimBlock = content.substring(claimDeliveriesIdx, claimEndIdx > 0 ? claimEndIdx : claimDeliveriesIdx + 1500);

        assertTrue(claimBlock.contains("redisLockFuture = CompletableFuture.supplyAsync"),
                "claimDeliveries must acquire Redis distributed lock asynchronously");
        assertFalse(claimBlock.matches("(?s).*boolean redisLocked = plugin\\.getRedisService\\(\\)\\.acquireDistributedLock.*"),
                "claimDeliveries must not synchronously acquire distributed lock on caller thread");
    }

    @Test
    @DisplayName("OrderManager: Distributed lock is only released when it was successfully acquired")
    void testClaimDeliveriesOnlyReleasesWhenLockAcquired() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlycore/modules/orders/OrderManager.java"));
        int claimDeliveriesIdx = content.indexOf("public CompletableFuture<Integer> claimDeliveries(Player player)");
        int claimEndIdx = content.indexOf("private CompletableFuture<Integer> awardClaimedDeliveries(", claimDeliveriesIdx);
        String claimBlock = content.substring(claimDeliveriesIdx, claimEndIdx > 0 ? claimEndIdx : claimDeliveriesIdx + 2500);

        // When redisLocked is false, it returns completedFuture(0) and does not release lock
        int notLockedIdx = claimBlock.indexOf("if (!redisLocked)");
        assertTrue(notLockedIdx >= 0, "Must check !redisLocked");
        int returnZeroIdx = claimBlock.indexOf("return CompletableFuture.completedFuture(0);", notLockedIdx);
        assertTrue(returnZeroIdx > notLockedIdx, "Must return completedFuture(0) when not locked");

        // releaseDistributedLock must be called inside whenComplete of the inner pipeline, not on !redisLocked path
        int releaseLockIdx = claimBlock.indexOf("plugin.getRedisService().releaseDistributedLock(lockKey, lockValue)");
        assertTrue(releaseLockIdx > 0, "Must have releaseDistributedLock in claimDeliveries");
        assertTrue(releaseLockIdx > returnZeroIdx, "releaseDistributedLock must only be attached to acquired pipeline");
    }
}
