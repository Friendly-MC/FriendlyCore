package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TransferFreezeService;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.hooks.McDataBridgeHook;
import dev.lyy.friendlycore.modules.spawn.LastLocationService;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import dev.lyy.friendlycore.modules.pvp.CombatTagManager;
import dev.lyy.friendlycore.modules.pvp.PvpManager;
import dev.lyy.friendlycore.modules.pvp.npc.CombatNpc;
import dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager;
import org.bukkit.NamespacedKey;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class AirborneRejoinSpawnTest {
    @Test
    void airborneLogoutSurvivesRestartAndRecoversBeforeLastLocationRestore() throws Exception {
        Map<NamespacedKey, Object> savedData = new HashMap<>();
        Player airborne = player(savedData, true, false);
        Runtime before = runtime();
        before.manager.onPlayerQuit(new PlayerQuitEvent(airborne, (net.kyori.adventure.text.Component) null));
        assertTrue(before.manager.hasPendingAirborneSpawn(airborne));
        assertEquals(1, before.locations.cleared);
        assertEquals(0, before.locations.saved, "Do not save an airborne Redis destination");

        // Ground state on login need not match the saved airborne position.
        Player rejoined = player(savedData, false, false);
        Runtime after = runtime();
        after.manager.onPlayerJoin(new PlayerJoinEvent(rejoined, (net.kyori.adventure.text.Component) null));
        assertTrue(after.freeze.frozen, "Protect against falling during spawn recovery");
        assertEquals(1, after.manager.teleports);
        assertTrue(after.scheduler.async.isEmpty(), "Recovery must not wait for Redis");
        assertEquals(0, after.locations.joins, "Last-location restore must not race spawn recovery");
        assertTrue(after.manager.hasPendingAirborneSpawn(rejoined), "Dispatch alone is not successful arrival");

        // Disconnecting during recovery retains the marker for the next login.
        after.manager.onPlayerQuit(new PlayerQuitEvent(rejoined, (net.kyori.adventure.text.Component) null));
        assertTrue(after.manager.hasPendingAirborneSpawn(rejoined));
    }

    @Test
    void restartWithoutQuitEventsUsesThePersistedAirborneMarkerAndLandingClearsIt() throws Exception {
        Map<NamespacedKey, Object> savedData = new HashMap<>();
        Player player = player(savedData, true, false);
        Runtime before = runtime();
        move(before, player);
        assertTrue(before.manager.hasPendingAirborneSpawn(player));
        Runtime after = runtime();
        Player rejoined = player(savedData, false, false);
        after.manager.onPlayerJoin(new PlayerJoinEvent(rejoined, (net.kyori.adventure.text.Component) null));
        assertEquals(1, after.manager.teleports);
        assertTrue(after.freeze.frozen);

        Map<NamespacedKey, Object> landedData = new HashMap<>();
        Player landed = player(landedData, false, false);
        move(before, player(landedData, true, false));
        move(before, landed);
        assertFalse(before.manager.hasPendingAirborneSpawn(landed));
    }

    @Test
    void elytraWindChargeJumpsAndFallsRecoverWithoutCreativeFlight() throws Exception {
        for (String state : new String[]{"elytra", "wind-charge", "jump", "fall"}) {
            Map<NamespacedKey, Object> savedData = new HashMap<>();
            Runtime before = runtime();
            Player airborne = player(savedData, true, false, state);
            assertFalse(airborne.isFlying(), "Survival air movement does not use /fly");
            assertEquals("elytra".equals(state), airborne.isGliding());
            move(before, airborne);
            Runtime after = runtime();
            after.manager.onPlayerJoin(new PlayerJoinEvent(player(savedData, false, false),
                    (net.kyori.adventure.text.Component) null));
            assertEquals(1, after.manager.teleports, state);
        }
    }

    @Test
    void waterLavaVehiclesAndClimbingAreNotAirborneLogouts() throws Exception {
        for (String state : new String[]{"water", "lava", "vehicle", "climbing"}) {
            Runtime runtime = runtime();
            Player supported = player(new HashMap<>(), true, false, state);
            move(runtime, supported);
            runtime.manager.onPlayerQuit(new PlayerQuitEvent(supported, (net.kyori.adventure.text.Component) null));
            assertFalse(runtime.manager.hasPendingAirborneSpawn(supported), state);
        }
    }

    @Test
    void combatNpcTagsAndPendingDeathsTakePrecedenceOverAirborneRecovery() throws Exception {
        for (String state : new String[]{"tagged", "npc", "npc-spawning", "npc-slain", "death"}) {
            Runtime runtime = runtime();
            Player player = player(new HashMap<>(), true, false);
            move(runtime, player);
            PvpManager pvp = allocate(PvpManager.class);
            CombatTagManager tags = allocate(CombatTagManager.class);
            set(tags, CombatTagManager.class, "taggedPlayers", new ConcurrentHashMap<>("tagged".equals(state)
                    ? Map.of(player.getUniqueId(), System.currentTimeMillis() + 60_000L) : Map.of()));
            CombatNpcManager npcs = allocate(CombatNpcManager.class);
            set(npcs, CombatNpcManager.class, "activeNpcsByPlayer", new ConcurrentHashMap<>("npc".equals(state)
                    ? Map.of(player.getUniqueId(), allocate(CombatNpc.class)) : Map.of()));
            set(npcs, CombatNpcManager.class, "pendingSpawns", new ConcurrentHashMap<>("npc-spawning".equals(state)
                    ? Map.of(player.getUniqueId(), System.currentTimeMillis() + 60_000L) : Map.of()));
            var deaths = ConcurrentHashMap.<UUID>newKeySet();
            if ("npc-slain".equals(state)) deaths.add(player.getUniqueId());
            set(npcs, CombatNpcManager.class, "pendingDeathsOnLogin", deaths);
            set(pvp, PvpManager.class, "combatTagManager", tags);
            set(pvp, PvpManager.class, "combatNpcManager", npcs);
            set(runtime.plugin, FriendlyCore.class, "pvpManager", pvp);
            runtime.manager.pendingRespawn = "death".equals(state);
            runtime.manager.onPlayerQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            assertEquals(1, runtime.locations.saved, "Keep the gameplay backend for combat NPC/death processing");
            assertEquals(0, runtime.locations.cleared);
            assertFalse(runtime.manager.hasPendingAirborneSpawn(player));
            move(runtime, player); // Also exercise a restart snapshot without the quit event.
            runtime.manager.onPlayerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            runtime.scheduler.async.remove().run();
            runtime.scheduler.entity.remove().run();
            assertEquals(0, runtime.manager.teleports, state);
            assertFalse(runtime.freeze.frozen, "Recovery must not untag combat or prevent the NPC death penalty");
            assertFalse(runtime.manager.hasPendingAirborneSpawn(player));
        }
    }

    @Test
    void groundedPlayersAndIntentionalTransfersKeepTheirJoinDestination() throws Exception {
        for (boolean transfer : new boolean[]{false, true}) {
            Map<NamespacedKey, Object> savedData = new HashMap<>();
            Runtime runtime = runtime();
            runtime.freeze.frozen = transfer;
            Player player = player(savedData, transfer, false);
            if (transfer) move(runtime, player);
            runtime.manager.onPlayerQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            assertFalse(runtime.manager.hasPendingAirborneSpawn(player));
            assertEquals(1, runtime.locations.saved);
            runtime.manager.onPlayerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            runtime.scheduler.async.remove().run();
            runtime.scheduler.entity.remove().run();
            assertEquals(0, runtime.manager.teleports);
            assertEquals(1, runtime.locations.joins);
        }
    }

    @Test
    void syncedMarkersNeverBounceACrossServerArrivalToSpawn() throws Exception {
        // FriendlySync carries PDC: the marker comes from a transfer snapshot taken mid-air or a stale local file.
        Map<NamespacedKey, Object> savedData = new HashMap<>();
        move(runtime(), player(savedData, true, false));
        Runtime after = runtime();
        McDataBridgeHook hook = allocate(McDataBridgeHook.class);
        set(hook, McDataBridgeHook.class, "enabled", true);
        HookManager hooks = allocate(HookManager.class);
        set(hooks, HookManager.class, "mcDataBridgeHook", hook);
        set(after.plugin, FriendlyCore.class, "hookManager", hooks);

        Player arrived = player(savedData, false, false);
        after.manager.onPlayerJoin(new PlayerJoinEvent(arrived, (net.kyori.adventure.text.Component) null));
        after.scheduler.async.remove().run();
        after.scheduler.entity.remove().run();
        assertEquals(0, after.manager.teleports, "RTP/TPA arrival must not be sent to spawn");
        assertFalse(after.freeze.frozen);
        assertEquals(1, after.locations.joins);
        assertFalse(after.manager.hasPendingAirborneSpawn(arrived));

        // Sync re-applies its pending marker after join; a grounded quit still keeps the last location.
        savedData.put(new NamespacedKey("friendlycore", "pending_airborne_spawn"), (byte) 1);
        after.manager.onPlayerQuit(new PlayerQuitEvent(arrived, (net.kyori.adventure.text.Component) null));
        assertEquals(1, after.locations.saved);
        assertEquals(0, after.locations.cleared);
    }

    @Test
    void deadPlayersKeepTheDeathRespawnPolicy() throws Exception {
        Runtime runtime = runtime();
        Player dead = player(new HashMap<>(), true, true);
        runtime.manager.onPlayerQuit(new PlayerQuitEvent(dead, (net.kyori.adventure.text.Component) null));
        assertFalse(runtime.manager.hasPendingAirborneSpawn(dead));
    }

    private static void move(Runtime runtime, Player player) {
        Location location = new Location(null, 0, 70, 0);
        runtime.manager.onPlayerMove(new PlayerMoveEvent(player, location, location));
    }

    private record Runtime(FriendlyCore plugin, SpawnProbe manager, Scheduler scheduler, Freeze freeze, Locations locations) {}

    private static Runtime runtime() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        Config config = allocate(Config.class);
        config.yaml = new YamlConfiguration();
        config.yaml.set("join-spawn.mode", "DISABLED");
        config.yaml.set("spawn.cancel-on-move", false);
        Scheduler scheduler = new Scheduler();
        Freeze freeze = new Freeze();
        set(plugin, FriendlyCore.class, "configService", config);
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        set(plugin, FriendlyCore.class, "transferFreezeService", freeze);
        SpawnProbe manager = allocate(SpawnProbe.class);
        set(manager, SpawnManager.class, "plugin", plugin);
        set(manager, SpawnManager.class, "pendingAirborneSpawnKey", new NamespacedKey("friendlycore", "pending_airborne_spawn"));
        set(manager, SpawnManager.class, "airborneKey", new NamespacedKey("friendlycore", "airborne"));
        set(manager, SpawnManager.class, "pendingTeleports", new ConcurrentHashMap<>());
        set(manager, SpawnManager.class, "arrivalTickets", new ConcurrentHashMap<>());
        set(manager, SpawnManager.class, "crossServerRespawns", ConcurrentHashMap.newKeySet());
        set(manager, SpawnManager.class, "deathRejoinQueued", ConcurrentHashMap.newKeySet());
        Locations locations = new Locations(plugin, manager);
        set(manager, SpawnManager.class, "lastLocationService", locations);
        return new Runtime(plugin, manager, scheduler, freeze, locations);
    }

    private static Player player(Map<NamespacedKey, Object> data, boolean airborne, boolean dead) {
        return player(data, airborne, dead, "air");
    }

    private static Player player(Map<NamespacedKey, Object> data, boolean airborne, boolean dead, String state) {
        PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (object, method, args) -> switch (method.getName()) {
                    case "has" -> data.containsKey(args[0]);
                    case "set" -> { data.put((NamespacedKey) args[0], args[2]); yield null; }
                    case "remove" -> { data.remove(args[0]); yield null; }
                    default -> throw new AssertionError(method.getName());
                });
        UUID uuid = new UUID(0, 1);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getPersistentDataContainer" -> pdc;
                    case "isFlying" -> false;
                    case "isGliding" -> "elytra".equals(state);
                    case "isOnGround" -> !airborne;
                    case "isInWater" -> "water".equals(state);
                    case "isInLava" -> "lava".equals(state);
                    case "isInsideVehicle" -> "vehicle".equals(state);
                    case "isClimbing" -> "climbing".equals(state);
                    case "isDead" -> dead;
                    case "isOnline", "hasPlayedBefore", "hasPermission" -> true;
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static class SpawnProbe extends SpawnManager {
        int teleports;
        boolean pendingRespawn;
        SpawnProbe() { super(null); }
        @Override public SpawnData getSpawnData() { return new SpawnData("lobby", "spawn", 10.5, 70, 20.5, 90, 0); }
        @Override public boolean hasPendingRespawn(Player player) { return pendingRespawn; }
        @Override public void dispatchSpawnTeleport(Player player, SpawnData data) {
            assertEquals(getSpawnData(), data, "Use the configured /setspawn destination");
            teleports++;
        }
    }

    private static class Config extends ConfigService {
        YamlConfiguration yaml;
        Config() { super(null); }
        @Override public FileConfiguration getSpawnConfig() { return yaml; }
    }

    private static class Locations extends LastLocationService {
        int cleared, saved, joins;
        Locations(FriendlyCore plugin, SpawnManager manager) { super(plugin, manager); }
        @Override public boolean hasSpawnRespawn(UUID uuid) { return false; }
        @Override public boolean isRouting(Player player) { return false; }
        @Override public void clearLastLocation(UUID uuid) { cleared++; }
        @Override public void saveCurrentLocation(Player player) { saved++; }
        @Override public boolean tryHandleJoin(Player player) { joins++; return true; }
    }

    private static class Freeze extends TransferFreezeService {
        boolean frozen;
        Freeze() { super(null); }
        @Override public boolean isFrozen(Player player) { return frozen; }
        @Override public void freeze(Player player) { frozen = true; }
    }

    private static class Scheduler extends FriendlyScheduler {
        final ArrayDeque<Runnable> async = new ArrayDeque<>(), entity = new ArrayDeque<>(), delayed = new ArrayDeque<>();
        Scheduler() { super(null); }
        @Override public Object runAsync(Runnable action) { async.add(action); return action; }
        @Override public Object runEntity(Entity target, Runnable action) { entity.add(action); return action; }
        @Override public Object runEntityLater(Entity target, Runnable action, long ticks) { delayed.add(action); return action; }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
