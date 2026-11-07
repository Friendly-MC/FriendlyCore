package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.SafeTeleportUtil;
import dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket;
import dev.lyy.friendlycore.modules.tpa.SafeTeleportChecker;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaTeleportTicket;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test validating SafeTeleportUtil in-flight debouncing,
 * multi-threaded cross-server arrival ticket single-claim guarantees,
 * and unified hazard/safety validation.
 */
class SafeTeleportUtilTest {

    @Test
    void testInFlightDebouncing() {
        UUID playerUuid = UUID.randomUUID();

        assertFalse(SafeTeleportUtil.isTeleporting(playerUuid));

        // When in flight is cleared
        SafeTeleportUtil.clearInFlight(playerUuid);
        assertFalse(SafeTeleportUtil.isTeleporting(playerUuid));
    }

    @Test
    void testHazardDetection() {
        assertTrue(SafeTeleportUtil.isHazard(Material.LAVA));
        assertTrue(SafeTeleportUtil.isHazard(Material.FIRE));
        assertTrue(SafeTeleportUtil.isHazard(Material.SOUL_FIRE));
        assertTrue(SafeTeleportUtil.isHazard(Material.MAGMA_BLOCK));
        assertTrue(SafeTeleportUtil.isHazard(Material.CACTUS));
        assertTrue(SafeTeleportUtil.isHazard(Material.SWEET_BERRY_BUSH));
        assertTrue(SafeTeleportUtil.isHazard(Material.WITHER_ROSE));
        assertTrue(SafeTeleportUtil.isHazard(Material.POWDER_SNOW));
        assertTrue(SafeTeleportUtil.isHazard(Material.POINTED_DRIPSTONE));

        assertFalse(SafeTeleportUtil.isHazard(Material.STONE));
        assertFalse(SafeTeleportUtil.isHazard(Material.GRASS_BLOCK));
        assertFalse(SafeTeleportUtil.isHazard(Material.OAK_PLANKS));
        assertFalse(SafeTeleportUtil.isHazard(Material.AIR));
        assertFalse(SafeTeleportUtil.isHazard(null));
    }

    @Test
    void testSafeTeleportCheckerDelegation() {
        assertTrue(SafeTeleportChecker.isHazard(Material.LAVA));
        assertTrue(SafeTeleportChecker.isHazard(Material.FIRE));
        assertFalse(SafeTeleportChecker.isHazard(Material.STONE));
        assertFalse(SafeTeleportChecker.isHazard(Material.DIAMOND_BLOCK));
    }

    @Test
    void testNullSafeGuards() {
        assertFalse(SafeTeleportUtil.isLocationSafe(null));
        assertNull(SafeTeleportUtil.getSafeDestination(null));
        assertNull(SafeTeleportUtil.getHighestSafeLocation(null));
        assertNull(SafeTeleportUtil.getLowestSafeLocation(null));
        assertFalse(SafeTeleportUtil.isSuffocating(null));
        assertFalse(SafeTeleportUtil.isWalkableGround(null));

        CompletableFuture<Boolean> nullTpFuture = SafeTeleportUtil.teleportAsync(null, null);
        assertNotNull(nullTpFuture);
        assertTrue(nullTpFuture.isDone());
        assertFalse(nullTpFuture.join());

        CompletableFuture<Boolean> nullLeashedFuture = SafeTeleportUtil.teleportWithLeashedEntities(null, null);
        assertNotNull(nullLeashedFuture);
        assertTrue(nullLeashedFuture.isDone());
        assertFalse(nullLeashedFuture.join());
    }

    @Test
    void testConcurrentCrossServerTicketSingleClaim() throws InterruptedException {
        // Simulates the exact race condition where Redis arrival packet and PlayerJoinEvent
        // attempt to claim and execute the cross-server ticket simultaneously on two threads.
        Map<UUID, TpaTeleportTicket> arrivalTickets = new ConcurrentHashMap<>();
        UUID travelerUuid = UUID.randomUUID();

        TpaTeleportTicket ticket = new TpaTeleportTicket(
                travelerUuid, "Traveler",
                UUID.randomUUID(), "Host",
                "world", 100.0, 64.0, 100.0, 0f, 0f,
                TpaRequest.Type.TPA, 60_000L
        );

        arrivalTickets.put(travelerUuid, ticket);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successfulClaims = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Atomic claim: remove from map
                    TpaTeleportTicket claimed = arrivalTickets.remove(travelerUuid);
                    if (claimed != null) {
                        successfulClaims.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one thread must have successfully claimed the ticket
        assertEquals(1, successfulClaims.get(), "Only exactly ONE thread should claim the arrival ticket!");
        assertNull(arrivalTickets.get(travelerUuid));
    }

    @Test
    void testHomeCrossServerPacketIntegrity() {
        UUID playerUuid = UUID.randomUUID();
        HomeTeleportPacket packet = new HomeTeleportPacket(
                "survival-2", playerUuid, "PlayerOne",
                "world_nether", 123.5, 75.0, -456.5, 90.0f, 0.0f
        );

        assertEquals("survival-2", packet.getTargetServer());
        assertEquals(playerUuid, packet.getPlayerUuid());
        assertEquals("PlayerOne", packet.getPlayerName());
        assertEquals("world_nether", packet.getWorldName());
        assertEquals(123.5, packet.getX());
        assertEquals(75.0, packet.getY());
        assertEquals(-456.5, packet.getZ());
        assertEquals(90.0f, packet.getYaw());
        assertEquals(0.0f, packet.getPitch());
        assertFalse(packet.isTeamHome());

        HomeTeleportPacket teamPacket = new HomeTeleportPacket(
                "survival-2", playerUuid, "PlayerOne",
                "world_nether", 123.5, 75.0, -456.5, 90.0f, 0.0f, true
        );
        assertTrue(teamPacket.isTeamHome());
    }

    @Test
    void testPortalHazards() {
        assertTrue(SafeTeleportUtil.isHazard(Material.NETHER_PORTAL));
        assertTrue(SafeTeleportUtil.isHazard(Material.END_PORTAL));
        assertTrue(SafeTeleportUtil.isHazard(Material.END_GATEWAY));
        assertTrue(SafeTeleportChecker.isHazard(Material.NETHER_PORTAL));
        assertTrue(SafeTeleportChecker.isHazard(Material.END_PORTAL));
        assertTrue(SafeTeleportChecker.isHazard(Material.END_GATEWAY));
    }

    @Test
    void testHomesAndTeamsTicketExpiration() {
        dev.lyy.friendlycore.modules.homes.HomesManager.PendingCrossTeleport expiredHomeTicket =
                new dev.lyy.friendlycore.modules.homes.HomesManager.PendingCrossTeleport(null, System.currentTimeMillis() - 65_000L);
        assertTrue(expiredHomeTicket.isExpired());

        dev.lyy.friendlycore.modules.homes.HomesManager.PendingCrossTeleport validHomeTicket =
                new dev.lyy.friendlycore.modules.homes.HomesManager.PendingCrossTeleport(null, System.currentTimeMillis());
        assertFalse(validHomeTicket.isExpired());

        dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport expiredTeamTicket =
                new dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport(null, System.currentTimeMillis() - 65_000L);
        assertTrue(expiredTeamTicket.isExpired());

        dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport validTeamTicket =
                new dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager.PendingCrossTeleport(null, System.currentTimeMillis());
        assertFalse(validTeamTicket.isExpired());
    }
}
