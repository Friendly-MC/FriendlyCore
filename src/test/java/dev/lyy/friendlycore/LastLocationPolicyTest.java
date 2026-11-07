package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.spawn.LastLocationPolicy;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class LastLocationPolicyTest {

    private static final SpawnData SPAWN = new SpawnData("spawn-eco", "spawn", 0.5, 64.0, 0.5, 0f, 0f);
    private static final SpawnData ECONOMY = new SpawnData("economy-a", "world", 120.0, 70.0, -40.0, 90f, 0f);
    private static final SpawnData SPAWN_RANDOM = new SpawnData("spawn-eco", "spawn", 80.0, 65.0, 12.0, 0f, 0f);

    @Test
    void economyLogoutRoutesOffTheHub() {
        Assertions.assertFalse(LastLocationPolicy.isSpawnLogout(ECONOMY, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertTrue(LastLocationPolicy.shouldRouteToRemote(ECONOMY, "spawn-eco"));
        Assertions.assertFalse(LastLocationPolicy.shouldRouteToRemote(ECONOMY, "economy-a"));
    }

    @Test
    void spawnLogoutUsesConfiguredSpawnPointNotExactBlock() {
        Assertions.assertTrue(LastLocationPolicy.isSpawnLogout(SPAWN_RANDOM, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertFalse(LastLocationPolicy.shouldRouteToRemote(SPAWN_RANDOM, "spawn-eco"));
    }

    @Test
    void spawnCommandOverwriteDoesNotBounceBackToEconomy() {
        Assertions.assertTrue(LastLocationPolicy.isSpawnLogout(SPAWN, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertFalse(LastLocationPolicy.shouldRouteToRemote(SPAWN, "spawn-eco"));
    }

    @Test
    void missingLastLocationStaysAtSpawn() {
        Assertions.assertTrue(LastLocationPolicy.isSpawnLogout(null, SPAWN, List.of("spawn-eco"), List.of("spawn")));
    }

    @Test
    void hubServerDetection() {
        Assertions.assertTrue(LastLocationPolicy.isHubServer("spawn-eco", SPAWN, List.of("spawn-eco")));
        Assertions.assertTrue(LastLocationPolicy.isHubServer("spawn-eco", SPAWN, List.of()));
        Assertions.assertFalse(LastLocationPolicy.isHubServer("economy-a", SPAWN, List.of("spawn-eco")));
    }

    @Test
    void economyWorldNamedSpawnStillTransfers() {
        SpawnData economySpawnWorld = new SpawnData("economy-b", "spawn", 12.0, 70.0, -8.0, 45f, 10f);
        Assertions.assertFalse(LastLocationPolicy.isSpawnLogout(economySpawnWorld, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertTrue(LastLocationPolicy.shouldRouteToRemote(economySpawnWorld, "spawn-eco"));
        Assertions.assertFalse(LastLocationPolicy.shouldRouteToRemote(economySpawnWorld, "economy-b"));
    }

    @Test
    void blankServerFallsBackToSpawnWorldName() {
        SpawnData blankServer = new SpawnData("", "spawn", 1.0, 64.0, 1.0, 0f, 0f);
        Assertions.assertTrue(LastLocationPolicy.isSpawnLogout(blankServer, SPAWN, List.of("spawn-eco"), List.of("spawn")));
    }

    @Test
    void spawnWorldIsHubEvenWhenRedisServerIdDoesNotMatch() {
        Assertions.assertTrue(LastLocationPolicy.isHubContext(
                "server-1", "spawn", null, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertFalse(LastLocationPolicy.isHubContext(
                "economy-b", "world", "economy-b", SPAWN, List.of("spawn-eco"), List.of("spawn")));
    }

    @Test
    void proxyNamePreventsBounceWhenRedisIdDiffers() {
        SpawnData economyB = new SpawnData("economy-b", "world", 10.0, 70.0, 10.0, 0f, 0f);
        Assertions.assertTrue(LastLocationPolicy.shouldRouteToRemote(economyB, "server-1", "spawn-eco"));
        Assertions.assertFalse(LastLocationPolicy.shouldRouteToRemote(economyB, "server-1", "economy-b"));
    }

    @Test
    void teleportToHubDoesNotForwardToEconomy() {
        Assertions.assertTrue(LastLocationPolicy.isHubServer("spawn-eco", SPAWN, List.of("spawn-eco")));
        Assertions.assertFalse(LastLocationPolicy.shouldForwardFromHub(true, null, "spawn-eco", "spawn-eco"));
    }

    @Test
    void spawnLogoutWinsOverMismatchedRedisId() {
        Assertions.assertTrue(LastLocationPolicy.isSpawnLogout(SPAWN, SPAWN, List.of("spawn-eco"), List.of("spawn")));
        Assertions.assertTrue(LastLocationPolicy.shouldRouteToRemote(SPAWN, "server-1", null));
        Assertions.assertTrue(LastLocationPolicy.alreadyOnSavedServer(SPAWN, "spawn-eco", null));
        Assertions.assertTrue(LastLocationPolicy.alreadyOnSavedServer(ECONOMY, "server-1", "economy-a"));
    }

    @Test
    void crossServerArrivalsNeverReuseAnotherServersCoordinates() {
        // Into the End: saved at the stronghold on economy-a; "world" also exists on the-end.
        SpawnData stronghold = new SpawnData("economy-a", "world", 1180.0, 30.0, -620.0, 0f, 0f);
        Assertions.assertFalse(LastLocationPolicy.shouldForwardFromHub(false, stronghold, "the-end", "the-end"));
        Assertions.assertFalse(LastLocationPolicy.canRestoreHere(stronghold, "the-end", "the-end"));
        // Out through the exit portal: economy-a also has a world_the_end.
        SpawnData endIsland = new SpawnData("the-end", "world_the_end", 40.0, 62.0, 12.0, 0f, 0f);
        Assertions.assertFalse(LastLocationPolicy.canRestoreHere(endIsland, "economy-a", "economy-a"));
        // Same backend (also via its proxy name) and legacy blank-server entries still restore.
        Assertions.assertTrue(LastLocationPolicy.canRestoreHere(stronghold, "server-1", "economy-a"));
        Assertions.assertTrue(LastLocationPolicy.canRestoreHere(new SpawnData("", "world", 1, 70, 1, 0f, 0f), "economy-a", null));
        Assertions.assertFalse(LastLocationPolicy.canRestoreHere(null, "economy-a", null));
    }

    @Test
    void economyJoinDoesNotBounceBackToSpawn() {
        SpawnData spawnLogout = new SpawnData("spawn-eco", "spawn", 270.0, 92.0, 7.0, 0f, 0f);
        Assertions.assertTrue(LastLocationPolicy.shouldRouteToRemote(spawnLogout, "economy-b", "economy-b"));
        Assertions.assertFalse(LastLocationPolicy.shouldForwardFromHub(false, spawnLogout, "economy-b", "economy-b"));
        Assertions.assertTrue(LastLocationPolicy.shouldForwardFromHub(true, new SpawnData("economy-b", "world", 1, 70, 1, 0f, 0f), "spawn-eco", "spawn-eco"));
    }
}
