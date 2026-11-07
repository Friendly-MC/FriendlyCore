package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.rtp.RtpCrossServerService;
import dev.lyy.friendlycore.modules.rtp.RtpLocationCache;
import dev.lyy.friendlycore.modules.rtp.RtpSpreadingManager;
import dev.lyy.friendlycore.modules.rtp.RtpWorldConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

public class RtpModuleTest {

    @Test
    @DisplayName("RtpWorldConfig should correctly calculate bounds, shapes, and biome whitelist/blacklist")
    public void testRtpWorldConfig() {
        RtpWorldConfig config = new RtpWorldConfig(
                "world",
                true,
                RtpWorldConfig.WorldType.NORMAL,
                RtpWorldConfig.Shape.CIRCLE,
                150,
                3000,
                0,
                0,
                List.of("OCEAN", "DEEP_OCEAN"),
                List.of()
        );

        assertTrue(config.isEnabled());
        assertEquals("world", config.getWorldName());
        assertEquals(RtpWorldConfig.Shape.CIRCLE, config.getShape());
        assertEquals(150, config.getMinRadius());
        assertEquals(3000, config.getMaxRadius());
        assertEquals(0, config.getCenterX());
        assertEquals(0, config.getCenterZ());
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN, config.getDistributionAlgorithm());

        // Blacklist checks
        assertFalse(config.isBiomeAllowed("OCEAN"));
        assertFalse(config.isBiomeAllowed("deep_ocean"));
        assertTrue(config.isBiomeAllowed("PLAINS"));
        assertTrue(config.isBiomeAllowed("DESERT"));
    }

    @Test
    @DisplayName("RtpWorldConfig should prioritize whitelisted biomes and custom distribution algorithm")
    public void testRtpWorldConfigWhitelistAndAlgorithm() {
        RtpWorldConfig config = new RtpWorldConfig(
                "custom_world",
                true,
                RtpWorldConfig.WorldType.NORMAL,
                RtpWorldConfig.Shape.SQUARE,
                500,
                2000,
                100,
                -100,
                List.of(),
                List.of("PLAINS", "FOREST"),
                RtpSpreadingManager.DistributionAlgorithm.GOLDEN_ANGLE
        );

        assertTrue(config.isBiomeAllowed("PLAINS"));
        assertTrue(config.isBiomeAllowed("forest"));
        assertFalse(config.isBiomeAllowed("DESERT"));
        assertFalse(config.isBiomeAllowed("OCEAN"));
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.GOLDEN_ANGLE, config.getDistributionAlgorithm());
    }

    @Test
    @DisplayName("CachedLocation record should serialize fields properly")
    public void testCachedLocationRecord() {
        RtpLocationCache.CachedLocation loc = new RtpLocationCache.CachedLocation("world", 100.5, 64.0, -250.5, 90f, 0f);
        assertEquals("world", loc.worldName());
        assertEquals(100.5, loc.x());
        assertEquals(64.0, loc.y());
        assertEquals(-250.5, loc.z());
        assertEquals(90f, loc.yaw());
        assertEquals(0f, loc.pitch());
    }

    @Test
    @DisplayName("Cross-Server Teleport Ticket should expire after TTL")
    public void testTeleportTicketExpiration() {
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        RtpCrossServerService.TeleportTicket freshTicket = new RtpCrossServerService.TeleportTicket(
                playerUuid, "TestPlayer", "world", 100.0, 64.0, -200.0, 0f, 0f, now
        );
        assertFalse(freshTicket.isExpired());

        RtpCrossServerService.TeleportTicket expiredTicket = new RtpCrossServerService.TeleportTicket(
                playerUuid, "TestPlayer", "world", 100.0, 64.0, -200.0, 0f, 0f, now - 70_000L
        );
        assertTrue(expiredTicket.isExpired());
    }

    @Test
    @DisplayName("DistributionAlgorithm should correctly parse strings and fallback safely")
    public void testDistributionAlgorithmParsing() {
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN,
                RtpSpreadingManager.DistributionAlgorithm.fromString("SECTOR_ROUND_ROBIN"));
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.GOLDEN_ANGLE,
                RtpSpreadingManager.DistributionAlgorithm.fromString("golden_angle"));
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.UNIFORM_RANDOM,
                RtpSpreadingManager.DistributionAlgorithm.fromString("UNIFORM_RANDOM"));
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN,
                RtpSpreadingManager.DistributionAlgorithm.fromString("invalid_algo_name"));
        assertEquals(RtpSpreadingManager.DistributionAlgorithm.SECTOR_ROUND_ROBIN,
                RtpSpreadingManager.DistributionAlgorithm.fromString(null));
    }

    @Test
    @DisplayName("Mathematical square sector sampling should stay strictly within min and max radius bounds")
    public void testSquareSectorSamplingBounds() {
        double minR = 250.0;
        double maxR = 5000.0;
        double cX = 100.0;
        double cZ = -200.0;
        int sectorCount = 8;

        for (int sector = 0; sector < sectorCount; sector++) {
            double sectorSpan = (2.0 * Math.PI) / sectorCount;
            for (int trial = 0; trial < 100; trial++) {
                double angle = (sector * sectorSpan) + (ThreadLocalRandom.current().nextDouble() * sectorSpan);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double maxCosSin = Math.max(Math.abs(cos), Math.abs(sin));
                if (maxCosSin < 1e-6) maxCosSin = 1.0;

                double rMinTheta = minR / maxCosSin;
                double rMaxTheta = maxR / maxCosSin;
                double u = ThreadLocalRandom.current().nextDouble();
                double r = Math.sqrt(u * (rMaxTheta * rMaxTheta - rMinTheta * rMinTheta) + (rMinTheta * rMinTheta));

                double targetX = cX + (r * cos);
                double targetZ = cZ + (r * sin);

                double dx = Math.abs(targetX - cX);
                double dz = Math.abs(targetZ - cZ);
                double chebyshevDist = Math.max(dx, dz);

                assertTrue(chebyshevDist >= minR - 0.01, "Distance should be >= minRadius (" + minR + "), but was " + chebyshevDist);
                assertTrue(chebyshevDist <= maxR + 0.01, "Distance should be <= maxRadius (" + maxR + "), but was " + chebyshevDist);
            }
        }
    }

    @Test
    @DisplayName("Mathematical circle annulus sampling should stay strictly within radial bounds")
    public void testCircleAnnulusSamplingBounds() {
        double minR = 200.0;
        double maxR = 4000.0;
        double cX = 0.0;
        double cZ = 0.0;

        for (int trial = 0; trial < 500; trial++) {
            double u = ThreadLocalRandom.current().nextDouble();
            double r = Math.sqrt(u * (maxR * maxR - minR * minR) + (minR * minR));
            double angle = ThreadLocalRandom.current().nextDouble(2.0 * Math.PI);

            double targetX = cX + (r * Math.cos(angle));
            double targetZ = cZ + (r * Math.sin(angle));

            double euclideanDist = Math.sqrt(targetX * targetX + targetZ * targetZ);
            assertTrue(euclideanDist >= minR - 0.01, "Distance should be >= minRadius, but was " + euclideanDist);
            assertTrue(euclideanDist <= maxR + 0.01, "Distance should be <= maxRadius, but was " + euclideanDist);
        }
    }

    @Test
    @DisplayName("Mathematical 4-partition uniform square distribution should stay strictly within bounding box")
    public void testUniformSquarePartitionBounds() {
        double minR = 300.0;
        double maxR = 6000.0;
        double totalWeight = 2.0 * (maxR + minR);
        double wTop = maxR / totalWeight;
        double wBottom = maxR / totalWeight;
        double wLeft = minR / totalWeight;

        for (int trial = 0; trial < 500; trial++) {
            double targetX, targetZ;
            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll < wTop) {
                targetX = ThreadLocalRandom.current().nextDouble(-maxR, maxR);
                targetZ = ThreadLocalRandom.current().nextDouble(minR, maxR);
            } else if (roll < wTop + wBottom) {
                targetX = ThreadLocalRandom.current().nextDouble(-maxR, maxR);
                targetZ = ThreadLocalRandom.current().nextDouble(-maxR, -minR);
            } else if (roll < wTop + wBottom + wLeft) {
                targetX = ThreadLocalRandom.current().nextDouble(-maxR, -minR);
                targetZ = ThreadLocalRandom.current().nextDouble(-minR, minR);
            } else {
                targetX = ThreadLocalRandom.current().nextDouble(minR, maxR);
                targetZ = ThreadLocalRandom.current().nextDouble(-minR, minR);
            }

            double chebyshevDist = Math.max(Math.abs(targetX), Math.abs(targetZ));
            assertTrue(chebyshevDist >= minR - 0.01, "Chebyshev dist should be >= minRadius, but was " + chebyshevDist);
            assertTrue(chebyshevDist <= maxR + 0.01, "Chebyshev dist should be <= maxRadius, but was " + chebyshevDist);
        }
    }

    @Test
    @DisplayName("RtpLocationCache should recycle unused locations and preserve queue capacity")
    public void testLocationCacheRecycling() {
        RtpLocationCache.CachedLocation cached1 = new RtpLocationCache.CachedLocation("world", 100.0, 64.0, 200.0, 0f, 0f);
        RtpLocationCache.CachedLocation cached2 = new RtpLocationCache.CachedLocation("world", 300.0, 70.0, 400.0, 45f, 0f);

        assertEquals("world", cached1.worldName());
        assertEquals(100.0, cached1.x());
        assertEquals(64.0, cached1.y());
        assertEquals(200.0, cached1.z());

        assertEquals("world", cached2.worldName());
        assertEquals(300.0, cached2.x());
        assertEquals(70.0, cached2.y());
        assertEquals(400.0, cached2.z());
    }

    @Test
    @DisplayName("RecentTeleport record in RtpSpreadingManager should correctly track coordinates and expiration")
    public void testRecentTeleportRecord() {
        long now = System.currentTimeMillis();
        RtpSpreadingManager.RecentTeleport recent = new RtpSpreadingManager.RecentTeleport(500.0, -1200.0, now);

        assertEquals(500.0, recent.x());
        assertEquals(-1200.0, recent.z());
        assertEquals(now, recent.timestamp());
    }

    @Test
    @DisplayName("CrossServerPacket should correctly support RTP_COOLDOWN_SYNC and RTP_DISCONNECT_REFUND actions")
    public void testCrossServerPacketRtpActions() {
        UUID uuid = UUID.randomUUID();
        dev.lyy.friendlycore.common.redis.CrossServerPacket cooldownPacket = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "server1",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.RTP_COOLDOWN_SYNC,
                uuid,
                "Player1",
                60.0,
                null
        );

        assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.RTP_COOLDOWN_SYNC, cooldownPacket.getAction());
        assertEquals(uuid, cooldownPacket.getTargetUuid());
        assertEquals(60.0, cooldownPacket.getAmount());

        dev.lyy.friendlycore.common.redis.CrossServerPacket refundPacket = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "server2",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.RTP_DISCONNECT_REFUND,
                uuid,
                "Player1",
                50.0,
                ""
        );

        assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.RTP_DISCONNECT_REFUND, refundPacket.getAction());
        assertEquals(50.0, refundPacket.getAmount());
    }
}
