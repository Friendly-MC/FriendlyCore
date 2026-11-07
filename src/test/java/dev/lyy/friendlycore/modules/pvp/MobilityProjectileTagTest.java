package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.hooks.ProtectionHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class MobilityProjectileTagTest {
    @Test
    void directWindHitsRefreshOnlyPlayersAlreadyInCombat() throws Exception {
        var config = new YamlConfiguration();
        config.set("combat-tag.enabled", true);
        var plugin = plugin(config, new RecordingProtectionHook(), new RecordingScheduler());
        UUID attackerId = UUID.randomUUID(), victimId = UUID.randomUUID();
        Player attacker = player(attackerId), victim = player(victimId);
        DamageSource source = (DamageSource) Proxy.newProxyInstance(DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class}, (object, method, args) -> null);
        for (boolean attackerTagged : List.of(false, true)) {
            for (boolean victimTagged : List.of(false, true)) {
                var existing = new java.util.HashSet<UUID>();
                if (attackerTagged) existing.add(attackerId);
                if (victimTagged) existing.add(victimId);
                var refreshed = new java.util.HashSet<UUID>();
                var tags = new CombatTagManager(plugin) {
                    @Override public boolean isTagged(UUID id) { return existing.contains(id); }
                    @Override public void tagPlayer(Player player, Player opponent) {
                        assertNull(opponent);
                        refreshed.add(player.getUniqueId());
                    }
                };
                var hit = new EntityDamageByEntityEvent(projectile(WindCharge.class, attacker), victim,
                        DamageCause.PROJECTILE, source, 1);
                tags.onCombatDamage(hit);
                assertEquals(existing, refreshed, "A wind hit must never start combat for either player");
                assertFalse(hit.isCancelled(), "PvP-enabled wind damage remains normal");
            }
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "equals" -> object == args[0];
                    default -> throw new AssertionError(method.getName());
                });
    }

    @Test
    void mobilityProjectilesOnlyRefreshExistingCombat() throws Exception {
        EventHandler handler = CombatItemManager.class.getMethod("onMobilityProjectileLaunch", ProjectileLaunchEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());

        RecordingTagManager tags = new RecordingTagManager();
        var config = new YamlConfiguration();
        config.set("cooldowns.enabled", false);
        CombatItemManager items = new CombatItemManager(plugin(config, new RecordingProtectionHook(), new RecordingScheduler()), tags);
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> null);

        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(WindCharge.class, player)));
        assertEquals(0, tags.count);
        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(EnderPearl.class, player)));
        assertEquals(0, tags.count);
        tags.count = 1; // Existing combat from player damage.
        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(EnderPearl.class, player)));
        assertEquals(2, tags.count);
        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(WindCharge.class, player)));
        assertEquals(3, tags.count);
        assertSame(player, tags.lastPlayer);
        assertNull(tags.lastOpponent);

        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(Arrow.class, player)));
        items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(EnderPearl.class, null)));
        assertEquals(3, tags.count);
    }

    private static Projectile projectile(Class<? extends Projectile> type, Player shooter) {
        return (Projectile) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getName().equals("getShooter") ? shooter : null);
    }

    @Test
    void combatOnlyCooldownRequiresExistingCombatForBothMobilityItems() throws Exception {
        var config = new YamlConfiguration();
        config.set("cooldowns.enabled", true);
        config.set("cooldowns.combat-only", true);
        config.set("cooldowns.items.ENDER_PEARL", 15);
        config.set("cooldowns.items.WIND_CHARGE", 10);
        var scheduler = new RecordingScheduler();
        var plugin = plugin(config, new RecordingProtectionHook(), scheduler);

        for (var type : List.of(EnderPearl.class, WindCharge.class)) {
            for (boolean alreadyTagged : List.of(false, true)) {
                var tags = new RecordingTagManager();
                tags.count = alreadyTagged ? 1 : 0;
                var items = new CombatItemManager(plugin, tags);
                UUID uuid = UUID.randomUUID();
                Map<Material, Integer> overlays = new java.util.EnumMap<>(Material.class);
                Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getUniqueId" -> uuid;
                            case "getLocation" -> new Location(null, 0, 64, 0);
                            case "isOnline" -> true;
                            case "setCooldown" -> overlays.put((Material) args[0], (Integer) args[1]);
                            default -> throw new AssertionError(method.getName());
                        });
                Material material = type == EnderPearl.class ? Material.ENDER_PEARL : Material.WIND_CHARGE;
                int seconds = material == Material.ENDER_PEARL ? 15 : 10;
                var event = new ProjectileLaunchEvent(projectile(type, player));

                scheduler.pending = null;
                assertEquals(alreadyTagged, tags.isTagged(uuid));
                items.onProjectileLaunch(event);
                items.onMobilityProjectileLaunch(event);
                assertFalse(event.isCancelled());
                assertEquals(alreadyTagged ? 2 : 0, tags.count);
                if (!alreadyTagged) {
                    assertFalse(tags.isTagged(uuid));
                    assertNull(scheduler.pending, "Mobility items outside combat must not get a combat-only cooldown");
                    assertFalse(items.isOnCooldown(uuid, material));
                    assertTrue(overlays.isEmpty());
                    continue;
                }
                assertTrue(tags.isTagged(uuid));
                assertNotNull(scheduler.pending, "Launches during combat must schedule their cooldown");
                assertFalse(items.isOnCooldown(uuid, material));
                scheduler.pending.run();
                assertTrue(items.isOnCooldown(uuid, material));
                assertEquals(seconds * 20, overlays.get(material));

                scheduler.pending = null;
                var blocked = new ProjectileLaunchEvent(projectile(type, player));
                items.onProjectileLaunch(blocked);
                assertTrue(blocked.isCancelled());
                assertNull(scheduler.pending);
            }
        }
    }

    @Test
    void safeRegionLaunchDoesNotStartOrRefreshTagAndRespectsCooldownMode() throws Exception {
        var config = new YamlConfiguration();
        config.set("cooldowns.enabled", true);
        config.set("cooldowns.combat-only", true);
        config.set("cooldowns.items.ENDER_PEARL", 15);
        config.set("cooldowns.items.WIND_CHARGE", 10);
        config.set("barriers.enabled", false);
        config.set("barriers.custom-safezones", List.of("spawn"));
        var protection = new RecordingProtectionHook();
        protection.allowed = false;
        var scheduler = new RecordingScheduler();
        var plugin = plugin(config, protection, scheduler);
        Location launchLocation = new Location(null, 10, 64, 20);
        UUID uuid = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getLocation" -> launchLocation;
                    case "isOnline" -> true;
                    case "setCooldown" -> null;
                    default -> throw new AssertionError(method.getName());
                });

        for (var type : List.of(EnderPearl.class, WindCharge.class)) {
            Material material = type == EnderPearl.class ? Material.ENDER_PEARL : Material.WIND_CHARGE;
            for (boolean alreadyTagged : List.of(false, true)) {
                var tags = new RecordingTagManager();
                tags.count = alreadyTagged ? 1 : 0;
                var items = new CombatItemManager(plugin, tags);
                var event = new ProjectileLaunchEvent(projectile(type, player));
                scheduler.pending = null;
                protection.location = null;
                protection.player = null;
                protection.safezones = null;
                items.onProjectileLaunch(event);
                items.onMobilityProjectileLaunch(event);
                assertFalse(event.isCancelled(), "Safe-area launches remain usable");
                assertEquals(alreadyTagged ? 1 : 0, tags.count, "Safe-area launches must not start or refresh tags");
                if (alreadyTagged) {
                    assertSame(launchLocation, protection.location);
                    assertSame(player, protection.player);
                    assertEquals(List.of("spawn"), protection.safezones);
                } else {
                    assertNull(protection.location, "Mobility items outside combat do not need a launch-tag protection check");
                }
                assertEquals(alreadyTagged, scheduler.pending != null);
            }

            config.set("cooldowns.combat-only", false);
            var tags = new RecordingTagManager();
            var items = new CombatItemManager(plugin, tags);
            scheduler.pending = null;
            items.onMobilityProjectileLaunch(new ProjectileLaunchEvent(projectile(type, player)));
            assertEquals(0, tags.count);
            assertNotNull(scheduler.pending, "Global cooldowns still apply inside safe regions");
            scheduler.pending.run();
            assertTrue(items.isOnCooldown(uuid, material));
            config.set("cooldowns.combat-only", true);
        }
    }

    private static FriendlyCore plugin(YamlConfiguration config, ProtectionHook protection, FriendlyScheduler scheduler) throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var plugin = (FriendlyCore) unsafe.allocateInstance(FriendlyCore.class);
        var configs = (ConfigService) unsafe.allocateInstance(ConfigService.class);
        var hooks = (HookManager) unsafe.allocateInstance(HookManager.class);
        setField(configs, "aliases", Map.of());
        setField(configs, "configs", new ConcurrentHashMap<>(Map.of("modules/pvp/config.yml", config)));
        setField(hooks, "protectionHook", protection);
        setField(plugin, "configService", configs);
        setField(plugin, "hookManager", hooks);
        setField(plugin, "scheduler", scheduler);
        return plugin;
    }

    private static final class RecordingProtectionHook extends ProtectionHook {
        private boolean allowed = true;
        private Location location;
        private Player player;
        private List<String> safezones;

        @Override
        public boolean isPvpAllowed(Location location, Player player, List<String> safezones) {
            this.location = location;
            this.player = player;
            this.safezones = safezones;
            return allowed;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingScheduler extends FriendlyScheduler {
        private Runnable pending;

        private RecordingScheduler() {
            super(null);
        }

        @Override
        public Object runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
            assertEquals(1L, delayTicks);
            pending = runnable;
            return null;
        }

        @Override
        public Object runEntity(Entity entity, Runnable runnable) {
            runnable.run();
            return null;
        }
    }

    private static final class RecordingTagManager extends CombatTagManager {
        private int count;
        private Player lastPlayer;
        private Player lastOpponent;

        private RecordingTagManager() {
            super(null);
        }

        @Override
        public void tagPlayer(Player player, Player opponent) {
            count++;
            lastPlayer = player;
            lastOpponent = opponent;
        }

        @Override
        public boolean isTagged(UUID uuid) {
            return count > 0;
        }
    }
}
