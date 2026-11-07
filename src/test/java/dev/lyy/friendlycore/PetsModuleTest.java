package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.pets.effects.PetCondition;
import dev.lyy.friendlycore.modules.pets.effects.PetEffect;
import dev.lyy.friendlycore.modules.pets.effects.PetTrigger;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PetSpawnEggConfig;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import dev.lyy.friendlycore.modules.pets.model.WithdrawResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Unit Tests for the Pets Module.
 */
public class PetsModuleTest {

    @Test
    @DisplayName("Verify PlayerPetData state management and persistence serialization")
    void testPlayerPetData() {
        UUID uuid = UUID.randomUUID();
        PlayerPetData data = new PlayerPetData(uuid, "Steve");

        assertEquals(uuid, data.getUuid());
        assertEquals("Steve", data.getUsername());
        assertNull(data.getActivePet());
        assertFalse(data.isHidePet());
        assertTrue(data.getUnlockedPets().isEmpty());

        // Unlock pets
        assertTrue(data.unlockPet("tiger"));
        assertTrue(data.unlockPet("blaze"));
        assertFalse(data.unlockPet("tiger")); // Already unlocked
        assertTrue(data.hasPet("tiger"));
        assertTrue(data.hasPet("blaze"));
        assertEquals(2, data.getUnlockedPets().size());
        assertTrue(data.isDirty());

        // Active pet
        data.setActivePet("tiger");
        assertEquals("tiger", data.getActivePet());

        // Toggle hide
        data.setHidePet(true);
        assertTrue(data.isHidePet());

        // Lock / remove pet
        assertTrue(data.lockPet("tiger"));
        assertFalse(data.hasPet("tiger"));
        assertEquals(1, data.getUnlockedPets().size());
        assertTrue(data.getUnlockedPets().contains("blaze"));
    }

    @Test
    @DisplayName("Verify PetTrigger parsing and enum mappings")
    void testPetTriggers() {
        assertEquals(PetTrigger.MELEE_ATTACK, PetTrigger.fromString("melee_attack"));
        assertEquals(PetTrigger.MELEE_ATTACK, PetTrigger.fromString("melee"));
        assertEquals(PetTrigger.BOW_ATTACK, PetTrigger.fromString("bow_attack"));
        assertEquals(PetTrigger.BOW_ATTACK, PetTrigger.fromString("ranged"));
        assertEquals(PetTrigger.TAKE_DAMAGE, PetTrigger.fromString("take_damage"));
        assertEquals(PetTrigger.KILL, PetTrigger.fromString("kill"));
        assertEquals(PetTrigger.WIN_RAID, PetTrigger.fromString("win_raid"));
        assertEquals(PetTrigger.PASSIVE, PetTrigger.fromString("passive"));
        assertEquals(PetTrigger.PASSIVE, PetTrigger.fromString("unknown_trigger"));
    }

    @Test
    @DisplayName("Verify PetEffect argument parsing and formula evaluator")
    void testPetEffect() {
        Map<String, Object> args = new HashMap<>();
        args.put("multiplier", 1.25);
        args.put("chance", 35);
        args.put("formula", "10 * 2 + 5");

        PetEffect effect = new PetEffect("damage_multiplier", Set.of(PetTrigger.MELEE_ATTACK), List.of(), args);

        assertEquals("damage_multiplier", effect.getId());
        assertEquals(1.25, effect.getDoubleArg("multiplier", 1.0));
        assertEquals(35, effect.getIntArg("chance", 0));
        assertEquals(25.0, effect.getDoubleArg("formula", 0.0));
        assertTrue(effect.hasTrigger(PetTrigger.MELEE_ATTACK));
        assertFalse(effect.hasTrigger(PetTrigger.BOW_ATTACK));
    }

    @Test
    @DisplayName("Verify PetCondition evaluation without player context")
    void testPetConditions() {
        PetCondition cond = PetCondition.fromString("is_night");
        assertNotNull(cond);
        assertEquals("is_night", cond.getId());
        assertFalse(cond.isInverted());

        PetCondition invCond = PetCondition.fromString("!is_night");
        assertNotNull(invCond);
        assertEquals("is_night", invCond.getId());
        assertTrue(invCond.isInverted());
    }

    @Test
    @DisplayName("Verify default pet YAML configurations load accurately")
    void testLoadDefaultPetYamlFiles() throws Exception {
        String[] pets = {"king", "mvp", "mvpp", "vip"};

        for (String petId : pets) {
            String path = "modules/pets/pets/" + petId + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "Default pet file missing: " + path);
                YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                Pet pet = Pet.fromConfig(petId, config);

                assertNotNull(pet, "Failed to parse pet: " + petId);
                assertEquals(petId, pet.getId());
                assertFalse(pet.getName().isEmpty());
                assertNotNull(pet.getSpawnEggConfig());
                assertTrue(pet.getSpawnEggConfig().isEnabled());
            }
        }
    }

    @Test
    @DisplayName("Verify responsive and PvP-safe pet display defaults")
    void testPetDisplayDefaults() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("modules/pets/config.yml")) {
            assertNotNull(in, "Pets config missing");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            assertFalse(config.contains("pet-entity.update-interval-ticks"));
            assertEquals(2, config.getInt("pet-entity.item-display.teleport-duration"));
            assertEquals(2, config.getInt("pet-entity.item-display.movement-interpolation-ticks"));
            assertEquals(2.1, config.getDouble("pet-entity.pair-spacing"), 0.001);
            assertEquals("&f%pet_short%", config.getString("pet-entity.name"));
            assertEquals(0.75, config.getDouble("pet-entity.location-y-offset"), 0.001);
            assertTrue(config.getBoolean("pet-entity.combat-positioning.enabled"));
            assertTrue(config.getDouble("pet-entity.combat-positioning.back-offset") >= 1.0);
            assertEquals(2, config.getInt("pet-entity.combat-positioning.teleport-duration"));
            assertTrue(config.getBoolean("pet-entity.camera-safety.enabled"));
            assertEquals(1, config.getInt("pet-entity.camera-safety.teleport-duration"));
            assertTrue(config.getDouble("pet-entity.camera-safety.back-offset")
                    > config.getDouble("pet-entity.location-z-offset"));
            assertTrue(config.getDouble("pet-entity.camera-safety.height-boost") > 0.0);
            assertTrue(config.getDouble("pet-entity.facing-response") > 0.5);
            assertEquals(180.0, config.getDouble("pet-entity.facing-yaw-offset"));
        }
    }

    @Test
    @DisplayName("Verify WithdrawResult enum values")
    void testWithdrawResult() {
        assertEquals(5, WithdrawResult.values().length);
        assertNotNull(WithdrawResult.valueOf("OK"));
        assertNotNull(WithdrawResult.valueOf("NO_ACTIVE"));
        assertNotNull(WithdrawResult.valueOf("NOT_WITHDRAWABLE"));
        assertNotNull(WithdrawResult.valueOf("CANNOT_AFFORD"));
        assertNotNull(WithdrawResult.valueOf("INVENTORY_FULL"));
    }

    @Test
    @DisplayName("Verify PetDisplayEntity in-flight teleport state tracking and atomic debouncing")
    void testPetDisplayEntityInFlightDebouncing() {
        UUID playerUuid = UUID.randomUUID();
        Pet pet = new Pet("test_pet", "Test Pet", "Description", "player_head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity display =
                new dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity(playerUuid, pet, null);

        assertFalse(display.isInFlightTeleport());

        // First attempt succeeds
        assertTrue(display.tryStartTeleport());
        assertTrue(display.isInFlightTeleport());

        // Subsequent attempt while in-flight is atomically rejected
        assertFalse(display.tryStartTeleport());

        // Completing teleport resets state
        display.setInFlightTeleport(false);
        assertFalse(display.isInFlightTeleport());

        // Now can start again
        assertTrue(display.tryStartTeleport());
        assertTrue(display.isInFlightTeleport());

        // Despawn / remove resets in-flight state
        display.remove();
        assertTrue(display.isDespawned());
        assertFalse(display.isInFlightTeleport());
    }

    @Test
    @DisplayName("Verify PetDisplayEntity high-concurrency atomic teleport reservation")
    void testPetDisplayEntityConcurrentTeleportGate() throws InterruptedException {
        UUID playerUuid = UUID.randomUUID();
        Pet pet = new Pet("tiger", "Tiger", "Description", "player_head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity display =
                new dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity(playerUuid, pet, null);

        int threadCount = 20;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (display.tryStartTeleport()) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one thread out of 20 must have successfully acquired the in-flight lock
        assertEquals(1, winners.get(), "Only exactly ONE concurrent tick should acquire the teleport lock!");
        assertTrue(display.isInFlightTeleport());
    }

    @Test
    @DisplayName("Verify Pet angle interpolation for fast rotation damping")
    void testPetAngleInterpolation() {
        // Normal 0 to 90 degrees interpolation with alpha 0.2
        float step1 = dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.interpolateAngle(0f, 90f, 0.20f);
        assertEquals(18.0f, step1, 0.001f);

        // 350 to 10 degrees shortest path wrap-around (+20 degrees diff)
        float wrap = dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.interpolateAngle(350f, 10f, 0.20f);
        assertEquals(354.0f, wrap, 0.001f);

        // 10 to 350 degrees shortest path wrap-around (-20 degrees diff)
        float wrapBack = dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.interpolateAngle(10f, 350f, 0.20f);
        assertEquals(6.0f, wrapBack, 0.001f);

        assertEquals(10.0f, dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.normalizeAngle(370f), 0.001f);
        assertEquals(350.0f, dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.normalizeAngle(-10f), 0.001f);
        assertEquals(180.0f, dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.calculateDisplayYaw(0f, 180f), 0.001f);
        assertEquals(90.0f, dev.lyy.friendlycore.modules.pets.display.PetDisplayManager.calculateDisplayYaw(270f, 180f), 0.001f);
    }

    @Test
    @DisplayName("Verify follower offset remains behind the view direction after fast turns")
    void testPetFollowerOffsetStaysBehindPlayer() {
        float[] yaws = {0f, 90f, 180f, 270f};
        for (float yaw : yaws) {
            double[] offset = dev.lyy.friendlycore.modules.pets.display.PetDisplayManager
                    .calculateFollowerOffset(yaw, 0.75, 0.75);
            double viewX = -Math.sin(Math.toRadians(yaw));
            double viewZ = Math.cos(Math.toRadians(yaw));
            double forwardDistance = offset[0] * viewX + offset[1] * viewZ;
            assertTrue(forwardDistance < 0.0, "Pet must remain behind the player's view at yaw " + yaw);
        }
    }

    @Test
    @DisplayName("Verify camera safety activates for sharp turns but keeps jumps smooth")
    void testPetCameraSafetyMotionDetection() {
        UUID playerUuid = UUID.randomUUID();
        Pet pet = new Pet("tiger", "Tiger", "Description", "player_head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity display =
                new dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity(playerUuid, pet, null);

        assertFalse(display.updateCameraSafety(0.0f, 35.0f, 3));
        assertFalse(display.updateCameraSafety(20.0f, 35.0f, 3));
        assertTrue(display.updateCameraSafety(80.0f, 35.0f, 3),
                "A sharp turn must activate camera clearance immediately");
        assertTrue(display.updateCameraSafety(80.0f, 35.0f, 3));
        assertTrue(display.updateCameraSafety(80.0f, 35.0f, 3));
        assertFalse(display.updateCameraSafety(80.0f, 35.0f, 3));

        assertFalse(display.updateCameraSafety(80.0f, 35.0f, 3),
                "A jump without a sharp turn must preserve smooth interpolation");
    }

    @Test
    @DisplayName("Verify stale teleport callbacks cannot unlock a newer pet movement")
    void testPetTeleportTicketGeneration() {
        UUID playerUuid = UUID.randomUUID();
        Pet pet = new Pet("tiger", "Tiger", "Description", "player_head", "item:barrier", List.of(), null, List.of(), List.of(), List.of());
        dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity display =
                new dev.lyy.friendlycore.modules.pets.display.PetDisplayEntity(playerUuid, pet, null);

        long first = display.tryStartTeleportTicket();
        assertTrue(first > 0L);
        display.setInFlightTeleport(false); // Simulates timeout/cancellation.

        long second = display.tryStartTeleportTicket();
        assertTrue(second > first);
        assertFalse(display.completeTeleport(first));
        assertTrue(display.isInFlightTeleport(), "Old callback must not release the newer teleport");
        assertTrue(display.completeTeleport(second));
        assertFalse(display.isInFlightTeleport());
    }

    @Test
    @DisplayName("Verify PET_LOCK_SYNC cross-server packet handling and active pet clearance")
    void testPetLockSyncClearsActivePet() {
        UUID uuid = UUID.randomUUID();
        PlayerPetData data = new PlayerPetData(uuid, "Steve");

        data.unlockPet("tiger");
        data.unlockPet("blaze");
        data.setActivePet("tiger");

        assertEquals("tiger", data.getActivePet());
        assertTrue(data.hasPet("tiger"));
        assertTrue(data.hasPet("blaze"));

        // Simulate incoming PET_LOCK_SYNC packet for the active pet
        CrossServerPacket lockPacket = new CrossServerPacket(
                "server-2",
                CrossServerPacket.Action.PET_LOCK_SYNC,
                uuid,
                "Steve",
                0.0,
                "tiger"
        );

        assertEquals(CrossServerPacket.Action.PET_LOCK_SYNC, lockPacket.getAction());
        assertEquals(uuid, lockPacket.getTargetUuid());
        assertEquals("tiger", lockPacket.getExtra());

        // Under PET_LOCK_SYNC: check if locked pet was active
        boolean wasActive = lockPacket.getExtra().equalsIgnoreCase(data.getActivePet());
        assertTrue(wasActive, "Locked pet should match current active pet");

        data.lockPet(lockPacket.getExtra());
        assertNull(data.getActivePet(), "Active pet must be cleared when locked");
        assertFalse(data.hasPet("tiger"), "Tiger must be locked");
        assertTrue(data.hasPet("blaze"), "Blaze should remain unlocked");

        // Simulate incoming PET_LOCK_SYNC for a non-active pet
        data.setActivePet("blaze");
        CrossServerPacket lockNonActivePacket = new CrossServerPacket(
                "server-2",
                CrossServerPacket.Action.PET_LOCK_SYNC,
                uuid,
                "Steve",
                0.0,
                "blaze"
        );

        boolean wasActive2 = lockNonActivePacket.getExtra().equalsIgnoreCase(data.getActivePet());
        assertTrue(wasActive2);
        data.lockPet(lockNonActivePacket.getExtra());
        assertNull(data.getActivePet());
        assertFalse(data.hasPet("blaze"));
    }

    @Test
    @DisplayName("Verify Pet withdrawal price evaluation and egg configuration")
    void testPetWithdrawPricingAndEggConfig() {
        PetSpawnEggConfig freeConfig = new PetSpawnEggConfig(true, "chicken_spawn_egg", "Egg", List.of(),
                false, null, false, List.of(), true, false, "coins", 0.0, "");
        assertTrue(freeConfig.isEnabled());
        assertTrue(freeConfig.isWithdrawable());
        assertFalse(freeConfig.isWithdrawPriceEnabled());
        assertEquals(0.0, freeConfig.getWithdrawPriceValue());

        PetSpawnEggConfig paidConfig = new PetSpawnEggConfig(true, "tiger_spawn_egg", "Tiger Egg", List.of(),
                false, null, false, List.of(), true, true, "coins", 250.0, "$250");
        assertTrue(paidConfig.isEnabled());
        assertTrue(paidConfig.isWithdrawable());
        assertTrue(paidConfig.isWithdrawPriceEnabled());
        assertEquals(250.0, paidConfig.getWithdrawPriceValue());
        assertEquals("coins", paidConfig.getWithdrawPriceType());

        PetSpawnEggConfig nonWithdrawableConfig = new PetSpawnEggConfig(true, "egg", "Egg", List.of(),
                false, null, false, List.of(), false, true, "economy", 50.0, "$50");
        assertTrue(nonWithdrawableConfig.isEnabled());
        assertFalse(nonWithdrawableConfig.isWithdrawable());
    }

    @Test
    @DisplayName("Verify broadcastReload PET_RELOAD_SYNC cross-server packet lifecycle")
    void testBroadcastReloadPacketLifecycle() {
        String secret = "0123456789abcdef0123456789abcdef";
        com.google.gson.Gson gson = new com.google.gson.Gson();

        CrossServerPacket reloadPacket = new CrossServerPacket(
                "server-1",
                CrossServerPacket.Action.PET_RELOAD_SYNC,
                null,
                "",
                0.0,
                ""
        );

        assertEquals(CrossServerPacket.Action.PET_RELOAD_SYNC, reloadPacket.getAction());
        assertNull(reloadPacket.getTargetUuid());
        assertEquals("", reloadPacket.getTargetName());

        // Sign packet
        reloadPacket.sign(secret);
        assertTrue(reloadPacket.hasValidSignature(secret));

        // Serialize and deserialize JSON
        String json = gson.toJson(reloadPacket);
        CrossServerPacket deserialized = gson.fromJson(json, CrossServerPacket.class);

        assertNotNull(deserialized);
        assertEquals(CrossServerPacket.Action.PET_RELOAD_SYNC, deserialized.getAction());
        assertTrue(deserialized.hasValidSignature(secret));
    }

    @Test
    @DisplayName("Verify PlayerPetData dirty state tracking during unload flush")
    void testPlayerPetDataDirtyTrackingForUnloadFlush() {
        UUID uuid = UUID.randomUUID();
        PlayerPetData data = new PlayerPetData(uuid, "Alex");

        assertFalse(data.isDirty(), "Newly instantiated PlayerPetData should not be dirty");

        data.unlockPet("sea_serpent");
        assertTrue(data.isDirty(), "Unlocking a pet must mark data dirty");

        data.setDirty(false);
        assertFalse(data.isDirty());

        data.setActivePet("sea_serpent");
        assertTrue(data.isDirty(), "Setting active pet must mark data dirty");

        data.setDirty(false);
        data.setHidePet(true);
        assertTrue(data.isDirty(), "Setting hide pet must mark data dirty");

        data.setDirty(false);
        data.lockPet("sea_serpent");
        assertTrue(data.isDirty(), "Locking a pet must mark data dirty");
    }
}
