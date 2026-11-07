package dev.lyy.friendlycore.modules.pets.display;

import dev.lyy.friendlycore.modules.pets.listeners.PetEventListener;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Allay;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Display;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PetNativeEntityTest {
    @Test
    void turningIsResponsiveAcrossYawWrapAndSettlesAfterInputStops() {
        PetDisplayEntity tracked = new PetDisplayEntity(UUID.randomUUID(), null, null);
        assertFalse(tracked.updateOwnerTurning(359));
        assertTrue(tracked.updateOwnerTurning(1));
        assertTrue(tracked.updateOwnerTurning(181));
        assertTrue(tracked.updateOwnerTurning(181));
        assertFalse(tracked.updateOwnerTurning(181));
        assertFalse(tracked.updateOwnerTurning(541));
        assertFalse(tracked.updateOwnerTurning(181.1f));
    }

    @Test
    void sharpTurnClearanceRisesAndSettlesWithoutHeightOrDistanceSteps() {
        PetDisplayEntity tracked = new PetDisplayEntity(UUID.randomUUID(), null, null);
        double previous = 0.0;
        for (int tick = 0; tick < 4; tick++) {
            double blend = tracked.smoothCameraSafety(true);
            assertTrue(blend > previous && blend < 1.0);
            assertTrue(blend - previous <= 0.35);
            previous = blend;
        }
        for (int tick = 0; tick < 24; tick++) {
            double blend = tracked.smoothCameraSafety(false);
            assertTrue(blend < previous && blend > 0.0);
            assertTrue(previous - blend <= 0.35);
            previous = blend;
        }
        assertTrue(previous < 0.0001);
    }

    @Test
    void configuredNamesKeepLegacyHexAndMiniMessageColorsWithoutAnOverride() {
        var text = new dev.lyy.friendlycore.common.TextService();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        String[] formats = {"&6Tiger", "§x§1§2§A§B§3§4Tiger", "<#12ab34>Tiger</#12ab34>"};
        for (String name : formats) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("name", name);
            Pet pet = Pet.fromConfig("tiger", config);
            assertEquals(name, pet.getDisplayName());
            var component = text.toComponent(pet.getDisplayName());
            assertEquals("Tiger", plain.serialize(component));
            assertEquals(name.equals(formats[0]) ? net.kyori.adventure.text.format.NamedTextColor.GOLD
                    : net.kyori.adventure.text.format.TextColor.color(0x12ab34), component.color());
            config.set("display-name", "&bCub");
            assertEquals(net.kyori.adventure.text.format.NamedTextColor.AQUA,
                    text.toComponent(Pet.fromConfig("tiger", config).getDisplayName()).color());
        }
    }

    @Test
    void nativeDefinitionsUseSmallLowModelsAndLegacyDefinitionsKeepGlobalSettings() {
        YamlConfiguration config = new YamlConfiguration();
        Pet legacy = Pet.fromConfig("legacy", config);
        assertNull(legacy.getEntityType());
        assertEquals(1.25, legacy.getEntityScale(1.25));
        assertEquals(0.75, legacy.getEntityYOffset(0.75));
        for (String type : new String[]{" allay ", "BLAZE"}) {
            config.set("entity-type", type);
            Pet pet = Pet.fromConfig("native", config);
            assertEquals(EntityType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT)), pet.getEntityType());
            assertEquals(0.5, pet.getEntityScale(1.25));
            assertEquals(-1.35, pet.getEntityYOffset(0.75));
        }
        config.set("entity-scale", 0.65);
        config.set("entity-y-offset", -1.0);
        Pet custom = Pet.fromConfig("custom", config);
        assertEquals(0.65, custom.getEntityScale(1.0));
        assertEquals(-1.0, custom.getEntityYOffset(0.75));
        config.set("entity-type", "DISPLAY");
        assertEquals(0.65, Pet.fromConfig("display", config).getEntityScale(1.0));
        config.set("entity-type", "WITHER");
        assertThrows(IllegalArgumentException.class, () -> Pet.fromConfig("unsafe", config));
        config.set("entity-type", "ALLAY");
        for (Object value : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, 0.0, 17.0, "bad"}) {
            config.set("entity-scale", value);
            assertThrows(IllegalArgumentException.class, () -> Pet.fromConfig("invalid", config));
        }
        config.set("entity-scale", 0.5);
        config.set("entity-y-offset", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> Pet.fromConfig("invalid", config));
    }

    @Test
    void mirroredFollowersStaySeparatedAndCachedTargetsHandleTeleportsAcrossRegions() {
        for (float yaw : new float[]{0, 45, 90, 180, 270, 359}) {
            double[] left = PetDisplayManager.calculateFollowerOffset(yaw, 1.05, 0.75);
            double[] right = PetDisplayManager.calculateFollowerOffset(yaw, -1.05, 0.75);
            assertEquals(2.1, Math.hypot(left[0] - right[0], left[1] - right[1]), 1e-9);
        }
        World world = proxy(World.class, false);
        World other = proxy(World.class, false);
        PetDisplayEntity display = new PetDisplayEntity(UUID.randomUUID(), Pet.fromConfig("allay", new YamlConfiguration()), null);
        display.recordTarget(new Location(world, 0, 64, 0));
        assertFalse(display.isFarFrom(new Location(world, 1, 64, 1), 576));
        assertTrue(display.isFarFrom(new Location(world, 25, 64, 0), 576));
        assertTrue(display.isFarFrom(new Location(other, 0, 64, 0), 576));
    }

    @Test
    void cosmeticMobsCannotBeDamagedGivenItemsOrPickUpDrops() {
        PetEventListener listener = new PetEventListener(null, null);
        Allay companion = proxy(Allay.class, true);
        Allay normal = proxy(Allay.class, false);
        Player player = proxy(Player.class, false);
        var damage = new EntityDamageEvent(companion, EntityDamageEvent.DamageCause.VOID, proxy(DamageSource.class, false), 10);
        listener.onEntityDamage(damage);
        assertTrue(damage.isCancelled());
        var interact = new PlayerInteractEntityEvent(player, companion);
        listener.onPetInteract(interact);
        assertTrue(interact.isCancelled());
        var pickup = new EntityPickupItemEvent(companion, null, 0);
        listener.onPetPickup(pickup);
        assertTrue(pickup.isCancelled());
        interact = new PlayerInteractEntityEvent(player, normal);
        listener.onPetInteract(interact);
        assertFalse(interact.isCancelled());
    }

    @Test
    void aRegionSpawnCompletingAfterDespawnCannotLeakTheCompanion() {
        PetDisplayEntity pending = new PetDisplayEntity(UUID.randomUUID(), Pet.fromConfig("pet", new YamlConfiguration()), null);
        int[] removed = {0};
        Entity spawned = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (object, method, args) -> {
                    if (method.getName().equals("remove")) removed[0]++;
                    return null;
                });
        pending.remove();
        pending.setEntity(spawned);
        assertNull(pending.getEntity());
        assertEquals(1, removed[0]);
    }

    @Test
    void nightLightingUsesClientMetadataAndRestoresDaylightWithoutRepeatedWrites() {
        PetDisplayEntity tracked = new PetDisplayEntity(UUID.randomUUID(), Pet.fromConfig("pet", new YamlConfiguration()), null);
        assertTrue(tracked.shouldUpdateLighting(21));
        assertFalse(tracked.shouldUpdateLighting(40));
        assertTrue(tracked.shouldUpdateLighting(42));
        for (long time : new long[]{0, 12299, 23850, 23999, 24000}) {
            assertFalse(PetDisplayManager.isNightTime(time));
        }
        for (long time : new long[]{12300, 18000, 23849, 42000}) {
            assertTrue(PetDisplayManager.isNightTime(time));
        }
        for (Class<? extends Entity> type : java.util.List.of(Allay.class, ItemDisplay.class)) {
            Object[] lighting = {null, false};
            int[] writes = {0};
            Entity entity = (Entity) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (object, method, args) -> switch (method.getName()) {
                        case "getBrightness" -> lighting[0];
                        case "isGlowing" -> lighting[1];
                        case "setBrightness", "setGlowing" -> {
                            lighting[method.getName().equals("setBrightness") ? 0 : 1] = args[0];
                            writes[0]++;
                            yield null;
                        }
                        default -> throw new AssertionError("Lighting must only touch visual metadata: " + method.getName());
                    });
            PetDisplayManager.applyNightLighting(entity, true);
            PetDisplayManager.applyNightLighting(entity, true);
            assertEquals(1, writes[0]);
            if (entity instanceof Display) assertEquals(new Display.Brightness(15, 15), lighting[0]);
            else assertEquals(true, lighting[1]);
            PetDisplayManager.applyNightLighting(entity, false);
            PetDisplayManager.applyNightLighting(entity, false);
            assertEquals(2, writes[0]);
            assertNull(lighting[0]);
            assertEquals(false, lighting[1]);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, boolean companion) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> proxy(PersistentDataContainer.class, companion);
            case "has" -> companion;
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            case "toString" -> type.getSimpleName();
            default -> null;
        });
    }
}
