package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class NetworkOptimizationAndSafetyTest {

    @Test
    void shortInputGapsKeepMotionAndBobbingSettlesGradually() {
        PetDisplayEntity entity = new PetDisplayEntity(UUID.randomUUID(), null, null);
        Location owner = new Location(null, 0, 64, 0);
        assertTrue(entity.updateOwnerMotion(owner));
        assertTrue(entity.updateOwnerMotion(owner));
        assertTrue(entity.updateOwnerMotion(owner));
        owner.add(0.06, 0, 0);
        assertTrue(entity.updateOwnerMotion(owner));
        assertTrue(entity.updateOwnerMotion(owner));
        assertTrue(entity.updateOwnerMotion(owner));
        assertFalse(entity.updateOwnerMotion(owner));
        double movingBob = entity.smoothBobOffset(0.15);
        double stoppedBob = entity.smoothBobOffset(0);
        assertTrue(stoppedBob > 0 && stoppedBob < movingBob);
        for (int tick = 0; tick < 20; tick++) stoppedBob = entity.smoothBobOffset(0);
        assertEquals(0, stoppedBob, 0.0001);
        org.bukkit.World world = createDummyWorld("world");
        entity.recordTarget(new Location(world, 0, 64, 0));
        assertTrue(entity.shouldTeleport(new Location(world, 0.03, 64, 0), true));
        assertFalse(entity.shouldTeleport(new Location(world, 0.03, 64, 0), false));
    }

    @Test
    @DisplayName("PetDisplayEntity: walking motion excludes camera-only turns")
    void testPetOwnerMovedDetection() {
        UUID uuid = UUID.randomUUID();
        Pet pet = new Pet("cat", "Cat", "Desc", "head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        PetDisplayEntity entity = new PetDisplayEntity(uuid, pet, null);

        Location startLoc = new Location(null, 100.0, 64.0, 100.0, 0.0f, 0.0f);

        // First call initializes baseline and returns true
        assertTrue(entity.hasOwnerMoved(startLoc));

        // Stationary call with exact same position returns false
        assertFalse(entity.hasOwnerMoved(startLoc));

        // Micro-twitch < 4cm without rotation returns false
        Location tinyJitter = new Location(null, 100.01, 64.0, 100.01, 0.0f, 0.0f);
        assertFalse(entity.hasOwnerMoved(tinyJitter));

        // Linear movement > 4cm (dx=0.1) triggers movement
        Location movedLoc = new Location(null, 100.1, 64.0, 100.0, 0.0f, 0.0f);
        assertTrue(entity.hasOwnerMoved(movedLoc));

        // Record new position
        entity.recordOwnerLocation(movedLoc);
        assertFalse(entity.hasOwnerMoved(movedLoc));

        // Camera-only turns must not trigger walking bobbing.
        Location turnedHead = new Location(null, 100.1, 64.0, 100.0, 5.0f, 0.0f);
        assertFalse(entity.hasOwnerMoved(turnedHead));
        turnedHead.setYaw(180.0f);
        assertFalse(entity.hasOwnerMoved(turnedHead));
    }

    @Test
    @DisplayName("PetDisplayEntity: shouldTeleport accurately handles moving and settled states")
    void testPetTeleportThresholds() {
        UUID uuid = UUID.randomUUID();
        Pet pet = new Pet("dog", "Dog", "Desc", "head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        PetDisplayEntity entity = new PetDisplayEntity(uuid, pet, null);

        org.bukkit.World world = createDummyWorld("world");
        Location restingTarget = new Location(world, 50.0, 70.0, 50.0);

        // Record initial target
        entity.recordTarget(restingTarget);

        // When stationary and target has not moved, shouldTeleport is false
        assertFalse(entity.shouldTeleport(restingTarget, false));

        // When stationary and target is slightly offset (> 8cm), settles to resting target
        Location slightlyOff = new Location(world, 50.15, 70.0, 50.0);
        assertTrue(entity.shouldTeleport(slightlyOff, false));

        // When moving, delta > 8cm triggers teleport
        Location movingOff = new Location(world, 50.1, 70.0, 50.0);
        assertTrue(entity.shouldTeleport(movingOff, true));
    }

    private static org.bukkit.World createDummyWorld(String name) {
        return (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return name;
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return name.hashCode();
                    return null;
                });
    }

    @Test
    @DisplayName("PetDisplayEntity: shouldRotate uses appropriate thresholds for moving vs stationary")
    void testPetRotationThresholds() {
        UUID uuid = UUID.randomUUID();
        Pet pet = new Pet("bird", "Bird", "Desc", "head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        PetDisplayEntity entity = new PetDisplayEntity(uuid, pet, null);

        Location loc = new Location(null, 0.0, 0.0, 0.0, 0.0f, 0.0f);
        entity.recordTarget(loc);

        // Small turn of 5 degrees: when moving (> 2 deg) triggers rotate, but when stationary (<= 15 deg) does not
        assertTrue(entity.shouldRotate(5.0f, true), "5 degree delta should rotate when moving");
        assertFalse(entity.shouldRotate(5.0f, false), "5 degree delta should not rotate when stationary");

        // Large turn of 25 degrees: triggers rotate in both states
        assertTrue(entity.shouldRotate(25.0f, true));
        assertTrue(entity.shouldRotate(25.0f, false));
    }

    @Test
    @DisplayName("LeafDecayManager: Chunk-local indices distinguish scanned block coordinates")
    void testLeafIndex() throws Exception {
        java.lang.reflect.Method packMethod =
                dev.lyy.friendlycore.modules.tools.LeafDecayManager.class.getDeclaredMethod("leafIndex", int.class, int.class, int.class, int.class);
        packMethod.setAccessible(true);

        int p1 = (int) packMethod.invoke(null, 100, 64, -200, -64);
        int p2 = (int) packMethod.invoke(null, 100, 64, -200, -64);
        int p3 = (int) packMethod.invoke(null, 101, 64, -200, -64);

        assertEquals(p1, p2, "Identical coordinates must have the same bit index");
        assertNotEquals(p1, p3, "Different blocks in a chunk must have different indices");
    }

    @Test
    @DisplayName("ChopperHandler: isLog correctly identifies all log and wood types")
    void testIsLog() throws Exception {
        java.lang.reflect.Method isLogMethod =
                dev.lyy.friendlycore.modules.tools.ChopperHandler.class.getDeclaredMethod("isLog", org.bukkit.Material.class);
        isLogMethod.setAccessible(true);
        var chopper = new dev.lyy.friendlycore.modules.tools.ChopperHandler(null);

        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.OAK_LOG));
        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.BIRCH_LOG));
        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.STRIPPED_OAK_LOG));
        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.OAK_WOOD));
        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.CRIMSON_STEM));
        assertTrue((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.WARPED_HYPHAE));

        assertFalse((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.OAK_LEAVES));
        assertFalse((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.DIRT));
        assertFalse((boolean) isLogMethod.invoke(chopper, org.bukkit.Material.AIR));
    }
}
