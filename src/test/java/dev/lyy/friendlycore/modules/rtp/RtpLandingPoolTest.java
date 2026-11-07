package dev.lyy.friendlycore.modules.rtp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RtpLandingPoolTest {
    private static final Set<Material> END_STONE = Set.of(Material.END_STONE);

    @Test
    void onlyAllowedGroundWithTwoBlocksOfAirIsLandable() {
        assertTrue(RtpLandingPool.isLandable(Material.END_STONE, Material.AIR, Material.AIR, END_STONE));
        assertFalse(RtpLandingPool.isLandable(Material.OBSIDIAN, Material.AIR, Material.AIR, END_STONE));
        assertFalse(RtpLandingPool.isLandable(Material.BEDROCK, Material.AIR, Material.AIR, END_STONE));
        assertFalse(RtpLandingPool.isLandable(Material.END_STONE, Material.AIR, Material.END_STONE, END_STONE));
        assertFalse(RtpLandingPool.isLandable(Material.END_STONE, Material.WATER, Material.AIR, END_STONE));
    }

    @Test
    void boundsKeepTheBorderMarginAndTheConfiguredRing() {
        // Vanilla island behind /worldborder set 275 around 0,0.
        RtpWorldConfig config = new RtpWorldConfig("world_the_end", true, RtpWorldConfig.WorldType.THE_END,
                RtpWorldConfig.Shape.SQUARE, 8, 121, 0, 0, List.of(), List.of());
        assertTrue(RtpLocationFinder.isWithinBounds(50.5, -50.5, 0, 0, 275, config));
        assertFalse(RtpLocationFinder.isWithinBounds(3.5, 3.5, 0, 0, 275, config), "exit portal");
        assertFalse(RtpLocationFinder.isWithinBounds(122.5, 0.5, 0, 0, 275, config), "past max-radius / border margin");
        assertFalse(RtpLocationFinder.isWithinBounds(0.5, -130.5, 0, 0, 275, config), "inside the 16-block border margin");
    }

    @Test
    void spotsSurviveNegativeCoordinatesAndEveryOneIsUsedBeforeAnyRepeats() {
        long[] spots = {RtpLandingPool.pack(-121, 60, 87), RtpLandingPool.pack(0, -64, -1), RtpLandingPool.pack(9, 319, -120)};
        assertEquals(-121, RtpLandingPool.x(spots[0]));
        assertEquals(87, RtpLandingPool.z(spots[0]));
        assertEquals(-64, RtpLandingPool.y(spots[1]));
        assertEquals(-1, RtpLandingPool.z(spots[1]));
        assertEquals(319, RtpLandingPool.y(spots[2]));

        RtpLandingPool pool = new RtpLandingPool(spots);
        Set<String> firstRound = new HashSet<>();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Location spot = pool.next(null);
            String key = spot.getX() + "," + spot.getY() + "," + spot.getZ();
            if (i < 3) firstRound.add(key);
            all.add(key);
        }
        assertEquals(Set.of("-120.5,60.0,87.5", "0.5,-64.0,-0.5", "9.5,319.0,-119.5"), firstRound);
        assertEquals(all.subList(0, 3), all.subList(3, 6));
        assertNull(new RtpLandingPool(new long[0]).next(null));
    }

    @Test
    void pacingReleasesAQuarterSecondShareAndCapacityCountsInboundPlayers() {
        Queue<Integer> queue = new ArrayDeque<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12));
        List<Integer> released = new ArrayList<>();
        assertEquals(5, RtpCrossServerService.drain(queue, 5, released::add));
        assertEquals(List.of(1, 2, 3, 4, 5), released);
        assertEquals(7, RtpCrossServerService.drain(queue, Integer.MAX_VALUE, released::add), "unpaced drains everything");

        // One second = four 5-tick releases. The configured rate must hold exactly, including slow and odd rates.
        for (int perSecond : new int[]{1, 3, 20, 22}) {
            double credit = 0;
            int total = 0;
            for (int tick = 0; tick < 4 * 10; tick++) {
                credit = RtpCrossServerService.refill(credit, perSecond);
                int batch = (int) credit;
                credit -= batch;
                total += batch;
            }
            assertEquals(perSecond * 10, total, perSecond + "/s over 10 seconds");
        }
        double idle = 0;
        for (int tick = 0; tick < 400; tick++) idle = RtpCrossServerService.refill(idle, 20);
        assertEquals(6, idle, "a long idle gap adds at most one extra release");

        assertEquals(1, RtpCrossServerService.waitSeconds(0, 20));
        assertEquals(25, RtpCrossServerService.waitSeconds(499, 20), "the 500th player at 20/s");
        assertEquals(0, RtpCrossServerService.waitSeconds(10, 0));

        assertFalse(RtpCrossServerService.atCapacity(0, 999, 999, 999));
        assertFalse(RtpCrossServerService.atCapacity(500, 400, 60, 39));
        assertTrue(RtpCrossServerService.atCapacity(500, 400, 60, 40));
    }
}
