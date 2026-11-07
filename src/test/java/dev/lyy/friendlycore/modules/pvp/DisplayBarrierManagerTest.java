package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.hooks.ProtectionHook;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatTagEvent;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class DisplayBarrierManagerTest {
    @Test
    void tagBurstsAndMovementShareThrottleWithoutDelayingFirstOrOtherPlayers() throws Exception {
        var runtime = new Runtime();
        runtime.tag();
        assertEquals(49, runtime.protection.calls, "Default radius scans 49 wilderness columns");
        for (int i = 0; i < 100; i++) runtime.tag();
        assertEquals(49, runtime.protection.calls, "100 tag refreshes must add no protection lookups");

        runtime.manager.onMove(new PlayerMoveEvent(runtime.player, runtime.location,
                new Location(runtime.world, -1, 64, 0)));
        assertEquals(50, runtime.protection.calls, "Movement still checks entry but shares the visual throttle");

        runtime.manager.onCombatTag(new PlayerCombatTagEvent(runtime.player(UUID.randomUUID()), null, 15));
        assertEquals(99, runtime.protection.calls, "Another player's initial scan must remain immediate");

        runtime.expireScan();
        runtime.protection.safe = location -> location.getBlockX() >= 1;
        runtime.tag();
        assertTrue(runtime.protection.calls > 99);
        assertEquals(49, runtime.packets.size(), "The next scan sees fresh protection state and draws the boundary");
    }

    @Test
    void realScannerMemoizesColumnsAndOnlyDrawsReplaceableBoundaryBlocks() throws Exception {
        var runtime = new Runtime();
        runtime.protection.safe = location -> location.getBlockX() >= 1;
        runtime.tag();
        assertTrue(runtime.protection.columns.values().stream().allMatch(count -> count == 1),
                "Neighbor checks must reuse the production scan's per-column results");
        assertEquals(49, runtime.packets.size());
        assertEquals(49, runtime.blockReads);
        assertEquals(1, runtime.minHeightReads);
        assertEquals(1, runtime.maxHeightReads);
        for (var packet : runtime.packets) {
            assertEquals(1, packet.location.getBlockX());
            assertTrue(packet.location.getBlockY() >= 61 && packet.location.getBlockY() <= 67);
            assertSame(runtime.glass, packet.data);
        }
        runtime.manager.clearBlocks(runtime.player);
        assertEquals(98, runtime.packets.size());
        assertTrue(runtime.packets.subList(49, 98).stream().allMatch(packet -> packet.data == runtime.air));

        runtime.replaceable = false;
        runtime.tag();
        assertEquals(98, runtime.packets.size(), "Solid blocks must never be replaced with fake glass");
        runtime.manager.clearBlocks(runtime.player);
        runtime.replaceable = true;
        for (var material : List.of(Material.CAVE_AIR, Material.VOID_AIR, Material.WATER)) {
            runtime.air = blockData(material);
            int before = runtime.packets.size();
            runtime.tag();
            assertEquals(before + 49, runtime.packets.size());
            runtime.manager.clearBlocks(runtime.player);
            assertTrue(runtime.packets.subList(before + 49, before + 98).stream()
                    .allMatch(packet -> packet.data == runtime.air));
        }
    }

    @Test
    void movementAndPearlOrChorusEntryStayImmediateDuringVisualThrottle() throws Exception {
        var runtime = new Runtime();
        runtime.tag();
        runtime.protection.safe = location -> location.getBlockX() >= 1;
        var destination = new Location(runtime.world, 1, 64, 0);
        var move = new PlayerMoveEvent(runtime.player, runtime.location, destination);
        runtime.manager.onMove(move);
        assertEquals(runtime.location, move.getTo());
        assertNotNull(runtime.velocity);
        assertTrue(runtime.velocity.getX() < 0);
        assertEquals(51, runtime.protection.calls, "Fresh destination and origin checks bypass the scan throttle");

        for (var cause : List.of(PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
                PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)) {
            var teleport = new PlayerTeleportEvent(runtime.player, runtime.location, destination, cause);
            runtime.manager.onTeleport(teleport);
            assertTrue(teleport.isCancelled());
        }
        assertEquals(55, runtime.protection.calls);
        assertTrue(runtime.packets.isEmpty(), "Entry checks must not run a full visual scan");
    }

    @Test
    void bounceTeleportKeepsTheWallAndTheTitleAndSoundCooldown() throws Exception {
        var runtime = new Runtime();
        runtime.protection.safe = location -> location.getBlockX() >= 1;
        runtime.tag();
        int rendered = runtime.packets.size();
        runtime.manager.onMove(new PlayerMoveEvent(runtime.player, runtime.location, new Location(runtime.world, 1, 64, 0)));
        // setTo(from) makes Paper fire a PLUGIN teleport back to the origin.
        runtime.manager.onTeleport(new PlayerTeleportEvent(runtime.player, runtime.location,
                new Location(runtime.world, 0.4, 64, 0), PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertEquals(rendered, runtime.packets.size(), "The wall the player just hit must stay drawn");
        var field = DisplayBarrierManager.class.getDeclaredField("bounceCooldowns");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(runtime.manager)).containsKey(runtime.player.getUniqueId()));
    }

    @Test
    void untagQuitAndAllowedTeleportClearBlocksAndResetThrottle() throws Exception {
        var runtime = new Runtime();
        runtime.protection.safe = location -> location.getBlockX() >= 1;
        for (int i = 0; i < 3; i++) {
            runtime.tag();
            int rendered = runtime.packets.size();
            assertEquals(49 + i * 98, rendered, "A new tag after cleanup scans immediately");
            if (i == 0) {
                runtime.manager.onUntag(new PlayerCombatUntagEvent(runtime.player,
                        PlayerCombatUntagEvent.UntagReason.EXPIRED));
            } else if (i == 1) {
                runtime.manager.onQuit(new PlayerQuitEvent(runtime.player, (net.kyori.adventure.text.Component) null));
            } else {
                var teleport = new PlayerTeleportEvent(runtime.player, runtime.location,
                        new Location(runtime.world, -10, 64, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
                runtime.manager.onTeleport(teleport);
                assertFalse(teleport.isCancelled());
            }
            assertEquals(rendered + 49, runtime.packets.size());
            assertTrue(runtime.packets.subList(rendered, rendered + 49).stream().allMatch(packet -> packet.data == runtime.air));
        }
    }

    @Test
    void inactiveProvidersSkipScanningWithoutIgnoringCustomRegionsOrLandsPvpRules() throws Exception {
        var runtime = new Runtime();
        runtime.protection.worldGuardEnabled = false;
        runtime.tag();
        assertEquals(0, runtime.protection.calls);
        assertEquals(0, runtime.minHeightReads);
        assertEquals(0, runtime.blockReads);

        runtime.protection.worldGuardEnabled = true;
        runtime.config.set("barriers.worldguard", false);
        runtime.expireScan();
        runtime.tag();
        assertEquals(0, runtime.protection.calls);

        runtime.config.set("barriers.custom-safezones", List.of("spawn"));
        runtime.expireScan();
        runtime.tag();
        assertEquals(49, runtime.protection.calls, "Custom WorldGuard regions still apply when PvP flag checks are off");

        runtime.config.set("barriers.custom-safezones", List.of());
        runtime.config.set("barriers.lands", false);
        runtime.protection.worldGuardEnabled = false;
        runtime.protection.landsEnabled = true;
        runtime.expireScan();
        runtime.tag();
        assertEquals(98, runtime.protection.calls, "Lands PvP denial still applies when all-claims barriers are off");
    }

    private record Packet(Location location, BlockData data) {}

    private static class Runtime {
        final YamlConfiguration config = new YamlConfiguration();
        final Regions protection = new Regions();
        final List<Packet> packets = new ArrayList<>();
        BlockData air = blockData(Material.AIR);
        final BlockData glass = blockData(Material.RED_STAINED_GLASS);
        final BlockData stone = blockData(Material.STONE);
        int blockReads, minHeightReads, maxHeightReads;
        boolean replaceable = true;
        Vector velocity;
        final World world = proxy(World.class, (object, method, args) -> switch (method.getName()) {
            case "getMinHeight" -> { minHeightReads++; yield -64; }
            case "getMaxHeight" -> { maxHeightReads++; yield 320; }
            case "getBlockData" -> { blockReads++; yield replaceable ? air : stone; }
            case "equals" -> object == args[0];
            case "hashCode" -> System.identityHashCode(object);
            default -> throw new AssertionError(method.getName());
        });
        final Location location = new Location(world, 0, 64, 0);
        final Player player = player(UUID.randomUUID());
        final DisplayBarrierManager manager;

        Runtime() throws Exception {
            // A long interval makes burst assertions deterministic without sleeping.
            config.set("barriers.scan-interval-millis", 60_000L);
            config.set("barriers.particles", false);
            config.set("barriers.sounds", false);
            var plugin = allocate(FriendlyCore.class);
            var configs = allocate(ConfigService.class);
            set(configs, "aliases", Map.of());
            set(configs, "configs", new ConcurrentHashMap<>(Map.of(
                    "modules/pvp/config.yml", config, "modules/pvp/messages.yml", new YamlConfiguration())));
            var hooks = allocate(HookManager.class);
            set(hooks, "protectionHook", protection);
            set(plugin, "configService", configs);
            set(plugin, "hookManager", hooks);
            var scheduler = allocate(FriendlyScheduler.class);
            set(scheduler, "isFolia", false);
            set(plugin, "scheduler", scheduler);
            set(plugin, "textService", new TextService() {
                @Override public void sendMessage(CommandSender sender, String message) {}
                @Override public void sendTitle(Player player, String title, String subtitle,
                        int fadeIn, int stay, int fadeOut, Map<String, String> placeholders) {}
            });
            manager = new DisplayBarrierManager(plugin, new CombatTagManager(plugin) {
                @Override public boolean isTagged(UUID uuid) { return true; }
            });
            set(manager, "barrierBlockData", glass);
            set(manager, "airBlockData", air);
        }

        Player player(UUID uuid) {
            return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uuid;
                case "getLocation" -> location.clone();
                case "getWorld" -> world;
                case "isOnline" -> true;
                case "sendBlockChange" -> { packets.add(new Packet(((Location) args[0]).clone(), (BlockData) args[1])); yield null; }
                case "setVelocity" -> { velocity = (Vector) args[0]; yield null; }
                default -> throw new AssertionError(method.getName());
            });
        }

        void tag() { manager.onCombatTag(new PlayerCombatTagEvent(player, null, 15)); }

        @SuppressWarnings("unchecked")
        void expireScan() throws Exception {
            var field = DisplayBarrierManager.class.getDeclaredField("lastBarrierScans");
            field.setAccessible(true);
            ((Map<UUID, Long>) field.get(manager)).put(player.getUniqueId(), System.nanoTime() - TimeUnit.SECONDS.toNanos(61));
        }
    }

    private static class Regions extends ProtectionHook {
        boolean worldGuardEnabled = true, landsEnabled;
        int calls;
        Predicate<Location> safe = location -> false;
        final Map<String, Integer> columns = new HashMap<>();
        @Override public boolean isWorldGuardEnabled() { return worldGuardEnabled; }
        @Override public boolean isLandsEnabled() { return landsEnabled; }
        @Override public boolean isCombatBarrierSafezone(Location location, Player player,
                List<String> safezones, boolean landsClaims, boolean worldGuard) {
            calls++;
            columns.merge(location.getBlockX() + "," + location.getBlockZ(), 1, Integer::sum);
            return safe.test(location);
        }
    }

    private static BlockData blockData(Material material) {
        return proxy(BlockData.class, (object, method, args) -> {
            if (method.getName().equals("getMaterial")) return material;
            throw new AssertionError(method.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
