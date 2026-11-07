package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.spawn.DeathRejoinSpawn;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

public class DeathRejoinSpawnTest {

    @Test
    void bedBeatsLandAndSpawn() {
        Location bed = usable("bed");
        Location land = usable("land");
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(bed, DeathRejoinSpawn.pick(bed, land, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void landBeatsConfiguredSpawnOnOverworld() {
        Location land = usable("land");
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(land, DeathRejoinSpawn.pick(null, land, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void spawnWorldUsesConfiguredSpawnWhenNoBedOrLand() {
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(configured,
                DeathRejoinSpawn.pick(null, null, configured, worldSpawn, "spawn", "spawn"));
    }

    @Test
    void overworldWithoutBedOrLandUsesWorldSpawnNotNetworkSpawn() {
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(worldSpawn,
                DeathRejoinSpawn.pick(null, null, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void alwaysModeAlwaysPicksConfiguredSpawnIgnoringBedAndLand() {
        Location bed = usable("bed");
        Location land = usable("land");
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(configured,
                DeathRejoinSpawn.pick("ALWAYS", bed, land, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void noBedModePicksConfiguredSpawnWhenBedAndLandMissingEvenOnDifferentWorld() {
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(configured,
                DeathRejoinSpawn.pick("NO_BED", null, null, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void noBedModePicksBedWhenPresent() {
        Location bed = usable("bed");
        Location land = usable("land");
        Location configured = usable("configured");
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(bed,
                DeathRejoinSpawn.pick("NO_BED", bed, land, configured, worldSpawn, "world", "spawn"));
    }

    @Test
    void ignoresLocationsWithoutAWorld() {
        Location broken = new Location(null, 0, 0, 0);
        Location worldSpawn = usable("world-spawn");

        Assertions.assertSame(worldSpawn,
                DeathRejoinSpawn.pick(broken, broken, broken, worldSpawn, "world", "spawn"));
    }

    private static Location usable(String name) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getName" -> name;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> name;
                        default -> defaultValue(method.getReturnType());
                    };
                });
        return new Location(world, 0, 64, 0);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class || type == short.class || type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
