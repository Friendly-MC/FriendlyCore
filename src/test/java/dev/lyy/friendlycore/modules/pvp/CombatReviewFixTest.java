package dev.lyy.friendlycore.modules.pvp;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.NumberFormatter;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.hooks.HookManager;
import dev.lyy.friendlycore.common.hooks.ProtectionHook;
import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.EconomyManager;
import dev.lyy.friendlycore.modules.economy.FriendlyVaultEconomy;
import dev.lyy.friendlycore.modules.pvp.npc.CombatNpc;
import dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CombatReviewFixTest {
    @Test
    void blockedMouseAndKeyboardEquipPreserveCursorAndArmorAndResyncAfterTransaction() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.tags.tagged = true;
            runtime.config.set("combat-tag.disable-elytra", true);
            var items = new CombatItemManager(runtime.plugin, runtime.tags);
            for (ClickType click : List.of(ClickType.LEFT, ClickType.RIGHT, ClickType.NUMBER_KEY, ClickType.SHIFT_LEFT)) {
                var state = new EquipmentPlayer();
                var scheduler = new Scheduler();
                set(runtime.plugin, "scheduler", scheduler);
                if (click == ClickType.SHIFT_LEFT) state.chestplate = null;
                ItemStack chestplate = state.chestplate;
                var action = click == ClickType.NUMBER_KEY ? InventoryAction.HOTBAR_SWAP
                        : click == ClickType.SHIFT_LEFT ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.SWAP_WITH_CURSOR;
                int slot = click == ClickType.SHIFT_LEFT ? 9 : 6;
                var event = new InventoryClickEvent(state.view,
                        click == ClickType.SHIFT_LEFT ? InventoryType.SlotType.CONTAINER : InventoryType.SlotType.ARMOR,
                        slot, click, action, 0);
                items.onInventoryClick(event);
                assertTrue(event.isCancelled(), click.toString());
                assertSame(state.elytra, state.cursor);
                assertSame(chestplate, state.chestplate);
                assertSame(state.elytra, state.inventory.getItem(0));
                assertEquals(0, state.updates, "Do not resend the inventory during the active click transaction");
                assertEquals(List.of(1L), scheduler.laterDelays);
                scheduler.delayedTask.run();
                assertEquals(1, state.updates);
                assertSame(state.elytra, state.cursor);
                assertSame(chestplate, state.chestplate);
                assertSame(state.elytra, state.inventory.getItem(0));
            }
        }
    }

    @Test
    void blockedDragAndRightClickEquipResyncWithoutRewritingItems() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.tags.tagged = true;
            runtime.config.set("combat-tag.disable-elytra", false);
            runtime.config.set("blocker.enabled", true);
            runtime.config.set("blocker.blocked-items", List.of("ELYTRA"));
            var items = new CombatItemManager(runtime.plugin, runtime.tags);
            var state = new EquipmentPlayer();
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            var drag = new InventoryDragEvent(state.view, null, state.elytra, false, Map.of(6, state.elytra));
            items.onInventoryDrag(drag);
            assertTrue(drag.isCancelled());
            assertEquals(1, scheduler.laterTasks.size());
            scheduler.delayedTask.run();
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                var interact = new PlayerInteractEvent(state.player, Action.RIGHT_CLICK_AIR, state.elytra,
                        null, org.bukkit.block.BlockFace.SELF, hand);
                items.onInteract(interact);
                assertTrue(interact.isCancelled());
                assertEquals(org.bukkit.event.Event.Result.DENY, interact.useItemInHand());
                assertSame(state.elytra, state.cursor);
                scheduler.delayedTask.run();
            }
            assertEquals(3, state.updates);
            assertSame(state.elytra, state.cursor);
            assertEquals(Material.DIAMOND_CHESTPLATE, state.chestplate.getType());
        }
    }

    @Test
    void inventoryResyncUsesCurrentStateAndSkipsOfflinePlayersWithoutBlockingArmorRemoval() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.tags.tagged = true;
            runtime.config.set("combat-tag.disable-elytra", true);
            var items = new CombatItemManager(runtime.plugin, runtime.tags);
            var state = new EquipmentPlayer();
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            var blocked = new InventoryClickEvent(state.view, InventoryType.SlotType.ARMOR, 6,
                    ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
            items.onInventoryClick(blocked);
            ItemStack replacement = state.chestplate;
            state.cursor = replacement;
            state.chestplate = state.elytra;
            scheduler.delayedTask.run();
            assertSame(replacement, state.cursor, "A deferred refresh must not restore the old cursor over a later action");
            assertSame(state.elytra, state.chestplate);
            state.online = false;
            scheduler.delayedTask.run();
            assertEquals(1, state.updates);
            state.online = true;
            state.cursor = null;
            var remove = new InventoryClickEvent(state.view, InventoryType.SlotType.ARMOR, 6,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL);
            items.onInventoryClick(remove);
            assertFalse(remove.isCancelled(), "Players may remove their elytra during combat");
            assertEquals(1, scheduler.laterTasks.size());
            runtime.tags.tagged = false;
            state.cursor = state.elytra;
            var outsideCombat = new InventoryClickEvent(state.view, InventoryType.SlotType.ARMOR, 6,
                    ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
            items.onInventoryClick(outsideCombat);
            assertFalse(outsideCombat.isCancelled());
            assertEquals(1, scheduler.laterTasks.size());
        }
    }

    private static class EquipmentPlayer {
        final ItemStack elytra = stack(Material.ELYTRA);
        ItemStack chestplate = stack(Material.DIAMOND_CHESTPLATE);
        ItemStack cursor = elytra;
        boolean online = true;
        int updates;
        final UUID uuid = UUID.randomUUID();
        // Every storage slot is occupied. No inventory mutation is permitted.
        final PlayerInventory inventory = proxy(PlayerInventory.class, (object, method, args) -> switch (method.getName()) {
            case "getItem" -> (int) args[0] == 0 ? elytra : stack(Material.STONE);
            case "getChestplate" -> chestplate;
            default -> throw new AssertionError(method.getName());
        });
        final Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getInventory" -> inventory;
            case "isOnline" -> online;
            case "updateInventory" -> { updates++; yield null; }
            default -> throw new AssertionError(method.getName());
        });
        final InventoryView view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
            case "getPlayer" -> player;
            case "getCursor" -> cursor;
            case "getItem" -> (int) args[0] == 6 ? chestplate : elytra;
            case "convertSlot" -> args[0];
            case "getSlotType" -> (int) args[0] == 6 ? InventoryType.SlotType.ARMOR : InventoryType.SlotType.CONTAINER;
            default -> throw new AssertionError(method.getName());
        });
    }

    private static class EquipmentStack extends ItemStack {
        Material material;
        @Override public Material getType() { return material; }
        @Override public ItemStack clone() { return this; }
    }

    private static ItemStack stack(Material material) {
        try {
            var stack = allocate(EquipmentStack.class);
            stack.material = material;
            return stack;
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void glideAndBoostRestrictionsRespectBothElytraSettingsAndTagExpiry() throws Exception {
        try (var runtime = new Runtime()) {
            var tags = glideTags(runtime);
            var state = new GlidePlayer();
            var deadlines = deadlines(tags);
            for (boolean tagged : new boolean[]{false, true}) {
                for (boolean disableElytra : new boolean[]{false, true}) {
                    for (boolean blockerEnabled : new boolean[]{false, true}) {
                        for (boolean listed : new boolean[]{false, true}) {
                            runtime.config.set("combat-tag.disable-elytra", disableElytra);
                            runtime.config.set("blocker.enabled", blockerEnabled);
                            runtime.config.set("blocker.blocked-items", listed ? List.of("elytra") : List.of());
                            deadlines.put(state.player.getUniqueId(), tagged ? System.currentTimeMillis() + 60000 : 0L);
                            boolean blocked = tagged && (disableElytra || (blockerEnabled && listed));
                            var start = new EntityToggleGlideEvent(state.player, true);
                            tags.onGlide(start);
                            assertEquals(blocked, start.isCancelled());
                            assertEquals(0, state.glideWrites, "Cancel the transition without changing gliding inside its own event");
                            var stop = new EntityToggleGlideEvent(state.player, false);
                            tags.onGlide(stop);
                            assertFalse(stop.isCancelled(), "Stopping gliding must always be allowed");
                            var boost = new PlayerElytraBoostEvent(state.player, allocate(ItemStack.class),
                                    proxy(Firework.class, (object, method, args) -> null), EquipmentSlot.HAND);
                            tags.onElytraBoost(boost);
                            assertEquals(blocked, boost.isCancelled());
                            assertEquals(!blocked, boost.shouldConsume());
                        }
                    }
                }
            }
            assertEquals(1, Collections.frequency(runtime.text.messages, "combat.elytra-disabled"), "Repeated starts and boosts share the existing message throttle");
            var mob = new EntityToggleGlideEvent(proxy(ArmorStand.class, (object, method, args) -> null), true);
            tags.onGlide(mob);
            assertFalse(mob.isCancelled());
        }
    }

    @Test
    void taggingStopsExistingGlideBeforeDeferredTasksOnPaperAndFolia() throws Exception {
        for (boolean folia : new boolean[]{false, true}) {
            try (var runtime = new Runtime()) {
                var tags = glideTags(runtime);
                var state = new GlidePlayer();
                var queued = new ArrayList<Runnable>();
                set(runtime.plugin, "scheduler", new Scheduler() {
                    @Override public boolean isFolia() { return folia; }
                    @Override public Object runEntity(Entity entity, Runnable task) { queued.add(task); return task; }
                });
                runtime.config.set("combat-tag.disable-elytra", true);
                runtime.ownsPlayer = !folia;
                tags.tagPlayer(state.player, null);
                if (folia) {
                    assertTrue(state.gliding, "Foreign regions must wait for the player's scheduler");
                    assertFalse(tags.isTagged(state.player.getUniqueId()));
                    runtime.ownsPlayer = true;
                    queued.removeFirst().run();
                }
                assertTrue(tags.isTagged(state.player.getUniqueId()));
                assertFalse(state.gliding, "An accepted tag must stop gliding before deferred restriction tasks run");
                assertEquals(1, state.glideWrites);
            }
        }
    }

    @Test
    void rejectedTagsAndAllowedElytraDoNotInterruptGliding() throws Exception {
        try (var runtime = new Runtime()) {
            var tags = glideTags(runtime);
            var state = new GlidePlayer();
            runtime.config.set("combat-tag.disable-elytra", true);
            runtime.events = event -> {
                if (event instanceof dev.lyy.friendlycore.modules.pvp.events.PlayerCombatTagEvent tag) tag.setCancelled(true);
            };
            tags.tagPlayer(state.player, null);
            assertFalse(tags.isTagged(state.player.getUniqueId()));
            assertTrue(state.gliding);
            runtime.events = event -> {};
            runtime.config.set("combat-tag.disable-elytra", false);
            tags.tagPlayer(state.player, null);
            assertTrue(tags.isTagged(state.player.getUniqueId()));
            assertTrue(state.gliding);
            assertEquals(0, state.glideWrites);
        }
    }

    @Test
    void combatTickerRecoversGlidingAndRechecksExpiryOnEntityScheduler() throws Exception {
        try (var runtime = new Runtime()) {
            var tags = glideTags(runtime);
            var state = new GlidePlayer();
            runtime.players.put(state.player.getUniqueId(), state.player);
            runtime.config.set("combat-tag.disable-elytra", false);
            runtime.config.set("blocker.enabled", true);
            runtime.config.set("blocker.blocked-items", List.of("ELYTRA"));
            var queued = new ArrayList<Runnable>();
            set(runtime.plugin, "scheduler", new Scheduler() {
                @Override public Object runEntity(Entity entity, Runnable task) {
                    assertSame(state.player, entity);
                    queued.add(task); return task;
                }
            });
            deadlines(tags).put(state.player.getUniqueId(), System.currentTimeMillis() + 60000);
            var tick = CombatTagManager.class.getDeclaredMethod("tickCombat");
            tick.setAccessible(true);
            tick.invoke(tags);
            assertTrue(state.gliding, "The global ticker must not mutate player state");
            queued.removeFirst().run();
            assertFalse(state.gliding, "Ticker must catch gliding restored while combat restrictions apply");
            state.gliding = true;
            tick.invoke(tags);
            deadlines(tags).put(state.player.getUniqueId(), 0L);
            queued.removeFirst().run();
            assertTrue(state.gliding, "Expired combat must not interrupt gliding");
            assertEquals(1, state.glideWrites);
        }
    }

    private static CombatTagManager glideTags(Runtime runtime) throws Exception {
        runtime.config.set("combat-tag.disable-flight", false);
        runtime.config.set("combat-tag.disable-elytra", false);
        runtime.config.set("blocker.enabled", false);
        runtime.config.set("sounds.tag.enabled", false);
        var tags = new CombatTagManager(runtime.plugin);
        set(runtime.pvp, "combatTagManager", tags);
        set(runtime.pvp, "combatItemManager", new CombatItemManager(runtime.plugin, tags));
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Long> deadlines(CombatTagManager tags) throws Exception {
        var field = CombatTagManager.class.getDeclaredField("taggedPlayers");
        field.setAccessible(true);
        return (Map<UUID, Long>) field.get(tags);
    }

    private static class GlidePlayer {
        boolean gliding = true;
        int glideWrites;
        final UUID uuid = UUID.randomUUID();
        final org.bukkit.World world = proxy(org.bukkit.World.class, (object, method, args) -> "world");
        final Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> "Glider";
            case "getWorld" -> world;
            case "isOnline" -> true;
            case "isGliding" -> gliding;
            case "setGliding" -> { glideWrites++; gliding = (boolean) args[0]; yield null; }
            case "hasPermission", "isOp" -> false;
            default -> throw new AssertionError(method.getName());
        });
    }

    @Test
    void fastRejoinRestoresUnexpiredCombatAndExpiredTagsAreNotRecreated() throws Exception {
        try (var runtime = new Runtime()) {
            var manager = allocate(CombatNpcManager.class);
            set(manager, "plugin", runtime.plugin);
            Player player = player(1);
            var pending = new ConcurrentHashMap<UUID, Long>();
            set(manager, "pendingSpawns", pending);
            set(manager, "pendingDeathsOnLogin", ConcurrentHashMap.newKeySet());
            for (int remaining : new int[]{7, -1}) {
                pending.put(player.getUniqueId(), System.currentTimeMillis() + remaining * 1000L);
                manager.onPlayerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
                assertEquals(Math.max(0, remaining), runtime.tags.restoredDuration);
                assertTrue(pending.isEmpty());
            }
        }
    }

    @Test
    void restoredTagsKeepTheRemainingDurationAndExpiredQuitClearsOpponent() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.config.set("combat-tag.disable-flight", false);
            runtime.config.set("combat-tag.disable-elytra", true);
            runtime.config.set("sounds.tag.enabled", false);
            var tags = new CombatTagManager(runtime.plugin);
            var world = proxy(org.bukkit.World.class, (object, method, args) -> "world");
            UUID id = UUID.randomUUID();
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Rejoined";
                case "getWorld" -> world;
                case "isOnline" -> true;
                case "hasPermission", "isOp", "isGliding" -> false;
                default -> throw new AssertionError(method.getName());
            });
            var foreignTasks = new ArrayList<Runnable>();
            set(runtime.plugin, "scheduler", new Scheduler() {
                @Override public boolean isFolia() { return true; }
                @Override public Object runEntity(Entity entity, Runnable task) {
                    if (!runtime.ownsPlayer) { foreignTasks.add(task); return null; }
                    return super.runEntity(entity, task);
                }
            });
            runtime.ownsPlayer = false;
            tags.tagPlayer(player, player(1), 7);
            assertFalse(tags.isTagged(id), "Cross-region tagging must wait for the owning scheduler");
            assertEquals(1, foreignTasks.size());
            runtime.ownsPlayer = true;
            foreignTasks.getFirst().run();
            assertTrue(tags.isTagged(id));
            assertEquals(7, tags.getRemainingSeconds(id));
            assertNotNull(tags.getOpponent(id));
            var expiry = CombatTagManager.class.getDeclaredField("taggedPlayers");
            expiry.setAccessible(true);
            @SuppressWarnings("unchecked") var deadlines = (Map<UUID, Long>) expiry.get(tags);
            deadlines.put(id, 0L);
            tags.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            assertFalse(tags.isTagged(id));
            assertNull(tags.getOpponent(id));
            assertFalse(deadlines.containsKey(id));
        }
    }

    @Test
    void pluginKickBeforeStopLeavesInventoryForQuitNpcPath() throws Exception {
        try (var runtime = new Runtime()) {
            var tags = new CombatTagManager(runtime.plugin);
            UUID id = UUID.randomUUID();
            // Any inventory, health, or drop call hits the default branch and fails the test.
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                default -> throw new AssertionError(method.getName());
            });
            deadlines(tags).put(id, System.currentTimeMillis() + 10_000L);
            tags.onKick(new org.bukkit.event.player.PlayerKickEvent(player, net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.text.Component.empty(), org.bukkit.event.player.PlayerKickEvent.Cause.PLUGIN));
            assertTrue(tags.isTagged(id), "Tag must survive the kick so onQuit spawns the NPC");
        }
    }

    @Test
    void npcHitsUseCustomHealthOnlyAndZeroDamageCannotHurtOrTag() throws Exception {
        try (var runtime = new Runtime()) {
            var manager = allocate(CombatNpcManager.class);
            set(manager, "plugin", runtime.plugin);
            UUID id = UUID.randomUUID();
            ArmorStand entity = proxy(ArmorStand.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getLocation" -> new Location(null, 1, 64, 0);
                default -> throw new AssertionError(method.getName());
            });
            var npc = new CombatNpc(UUID.randomUUID(), "Offline", id, entity, 7,
                    null, null, null, 0, 0, 20, 20, new Location(null, 1, 64, 0));
            set(manager, "activeNpcsByEntity", new ConcurrentHashMap<>(Map.of(id, npc)));
            for (double amount : new double[]{0, 4}) {
                var environmental = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FALL, damageSource(), amount);
                manager.onNpcDamage(environmental);
                assertTrue(environmental.isCancelled(), "Vanilla health must never also change");
                assertEquals(amount == 0 ? 20 : 16, npc.getHealth());
                var hit = new EntityDamageByEntityEvent(player(1), entity,
                        EntityDamageEvent.DamageCause.ENTITY_ATTACK, damageSource(), amount);
                manager.onNpcDamageByEntity(hit);
                assertTrue(hit.isCancelled());
                assertEquals(amount == 0 ? 20 : 12, npc.getHealth());
            }
            assertEquals(1, runtime.tags.tagCalls);
            var denied = new EntityDamageByEntityEvent(player(-1), entity,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, damageSource(), 4);
            manager.onNpcDamageByEntity(denied);
            assertTrue(denied.isCancelled());
            assertEquals(12, npc.getHealth());
            assertEquals(1, runtime.tags.tagCalls);
        }
    }

    @Test
    void harmlessPlayerHitsDoNotStartCombat() throws Exception {
        try (var runtime = new Runtime()) {
            var hit = new EntityDamageByEntityEvent(player(1), player(1),
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, damageSource(), 0);
            runtime.tags.onCombatDamage(hit);
            var explosion = new EntityDamageEvent(player(1), EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, damageSource(), 0);
            runtime.tags.onBlockExplosionDamage(explosion);
            assertEquals(0, runtime.tags.tagCalls);
        }
    }

    @Test
    void logoutDeathIsSilentAutoRespawnsAndDoesNotReplayNpcRewards() throws Exception {
        try (var runtime = new Runtime()) {
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            var persistent = new HashSet<NamespacedKey>();
            var data = proxy(PersistentDataContainer.class, (object, method, args) -> switch (method.getName()) {
                case "set" -> { persistent.add((NamespacedKey) args[0]); yield null; }
                case "has" -> persistent.contains(args[0]);
                case "remove" -> { persistent.remove(args[0]); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            boolean[] dead = {true}, npcMarker = {true};
            int[] respawns = {0};
            Player.Spigot spigot = new Player.Spigot() {
                @Override public void respawn() { respawns[0]++; dead[0] = false; }
            };
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getPersistentDataContainer" -> data;
                case "hasMetadata" -> npcMarker[0];
                case "removeMetadata" -> { npcMarker[0] = false; yield null; }
                case "isOnline", "isValid" -> true;
                case "isDead" -> dead[0];
                case "spigot" -> spigot;
                default -> throw new AssertionError(method.getName());
            });
            var event = allocate(PlayerDeathEvent.class);
            set(event, "entity", player);
            event.deathMessage(net.kyori.adventure.text.Component.text("died"));
            runtime.tags.prepareCombatLogoutDeath(player);
            runtime.tags.onCombatLogoutDeath(event);
            assertNull(event.deathMessage());
            assertEquals(1, scheduler.delay);
            assertEquals(0, respawns[0], "Respawn must wait until after the death event");
            runtime.pvp.onPlayerDeath(event); // Any killer/stats lookup would fail this proxy.
            assertTrue(runtime.text.messages.isEmpty());
            scheduler.delayedTask.run();
            assertEquals(1, respawns[0]);
            assertFalse(persistent.isEmpty(), "Keep marker until async respawn completes");
            scheduler.timerTask.run();
            assertTrue(persistent.isEmpty());
            assertFalse(npcMarker[0], "Future ordinary deaths must still receive rewards");
            assertTrue(scheduler.cancelled);

            // A direct logout can disconnect before its delayed respawn executes.
            dead[0] = true;
            runtime.tags.prepareCombatLogoutDeath(player);
            runtime.tags.onCombatLoggerJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            scheduler.delayedTask.run();
            assertEquals(2, respawns[0]);
            scheduler.timerTask.run();
            assertTrue(persistent.isEmpty());
        }
    }

    @Test
    void offlineNpcPenaltyWaitsForSyncRestorationAndDoesNotRunOnAReplacementSession() throws Exception {
        try (var runtime = new Runtime()) {
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            boolean[] locked = {true};
            var hook = new dev.lyy.friendlycore.common.hooks.McDataBridgeHook(null) {
                @Override public boolean isPlayerLocked(Player player) { return locked[0]; }
            };
            set(runtime.plugin.getHookManager(), "mcDataBridgeHook", hook);
            var manager = allocate(CombatNpcManager.class);
            set(manager, "plugin", runtime.plugin);
            UUID id = UUID.randomUUID();
            Set<UUID> pending = new HashSet<>(Set.of(id));
            set(manager, "pendingDeathsOnLogin", pending);
            int[] clears = {0}, kills = {0};
            var data = proxy(PersistentDataContainer.class, (object, method, args) -> null);
            var inventory = proxy(org.bukkit.inventory.PlayerInventory.class, (object, method, args) -> {
                if (method.getName().equals("clear")) clears[0]++;
                return null;
            });
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getInventory" -> inventory;
                case "getPersistentDataContainer" -> data;
                case "isOnline", "isValid" -> true;
                case "isDead", "hasMetadata" -> false;
                case "getHealth" -> 20.0;
                case "setHealth" -> { kills[0]++; yield null; }
                case "setMetadata", "setExp", "setLevel" -> null;
                default -> throw new AssertionError(method.getName());
            });
            runtime.players.put(id, player);
            var enforce = CombatNpcManager.class.getDeclaredMethod("enforceCombatLogDeath", Player.class);
            enforce.setAccessible(true);
            enforce.invoke(manager, player);
            assertTrue(pending.contains(id));
            assertEquals(0, clears[0]);
            assertEquals(0, kills[0]);
            assertEquals(5L, scheduler.delay);
            Runnable firstRetry = scheduler.delayedTask;
            firstRetry.run();
            assertTrue(pending.contains(id));
            assertEquals(0, clears[0]);

            // A disconnected CraftPlayer can still report online for a new connection with the same UUID.
            Runnable oldSessionRetry = scheduler.delayedTask;
            Player replacement = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> true;
                default -> throw new AssertionError("Stale callback accessed replacement: " + method.getName());
            });
            runtime.players.put(id, replacement);
            locked[0] = false;
            oldSessionRetry.run();
            assertTrue(pending.contains(id));
            assertSame(replacement, runtime.players.get(id));
            assertEquals(0, clears[0]);
            assertEquals(0, kills[0]);
            runtime.players.put(id, player);
            oldSessionRetry.run();
            assertFalse(pending.contains(id));
            assertTrue(clears[0] >= 1);
            assertEquals(1, kills[0]);
            firstRetry.run();
            assertEquals(1, kills[0], "Duplicate readiness callbacks must not consume a penalty twice");
        }
    }

    @Test
    void survivingCombatNpcIsNotConsumedWhileSyncRestoresThePlayer() throws Exception {
        try (var runtime = new Runtime()) {
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            boolean[] locked = {true};
            var hook = new dev.lyy.friendlycore.common.hooks.McDataBridgeHook(null) {
                @Override public boolean isPlayerLocked(Player player) { return locked[0]; }
            };
            set(runtime.plugin.getHookManager(), "mcDataBridgeHook", hook);
            Player player = player(1);
            UUID id = player.getUniqueId();
            runtime.players.put(id, player);
            var npc = new CombatNpc(id, "Player", UUID.randomUUID(), null, 20,
                    null, null, null, 0, 0, 7, 20, null);
            var manager = allocate(CombatNpcManager.class);
            set(manager, "plugin", runtime.plugin);
            Map<UUID, CombatNpc> npcs = new HashMap<>(Map.of(id, npc));
            set(manager, "activeNpcsByPlayer", npcs);
            set(manager, "activeNpcsByEntity", new HashMap<>());
            set(manager, "pendingDeathsOnLogin", new HashSet<>());
            manager.onPlayerJoinAfterSync(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            assertSame(npc, npcs.get(id));
            assertEquals(CombatNpc.NpcState.ACTIVE, npc.getState());
            scheduler.delayedTask.run();
            assertSame(npc, npcs.get(id));
            locked[0] = false;
            scheduler.delayedTask.run();
            assertFalse(npcs.containsKey(id));
            assertEquals(CombatNpc.NpcState.REJOINED, npc.getState());
        }
    }

    @Test
    void offlineNpcPenaltyDoesNotKillAgainAfterAutomaticRespawn() throws Exception {
        try (var runtime = new Runtime()) {
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            var manager = allocate(CombatNpcManager.class);
            set(manager, "plugin", runtime.plugin);
            UUID id = UUID.randomUUID();
            set(manager, "pendingDeathsOnLogin", new HashSet<>(Set.of(id)));
            boolean[] dead = {false}, marker = {false};
            int[] kills = {0}, clears = {0};
            var data = proxy(PersistentDataContainer.class, (object, method, args) -> null);
            var inventory = proxy(org.bukkit.inventory.PlayerInventory.class, (object, method, args) -> {
                if (method.getName().equals("clear")) clears[0]++;
                return null;
            });
            Player.Spigot spigot = new Player.Spigot() {
                @Override public void respawn() { dead[0] = false; }
            };
            Player player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getInventory" -> inventory;
                case "getPersistentDataContainer" -> data;
                case "isOnline", "isValid" -> true;
                case "isDead" -> dead[0];
                case "getHealth" -> dead[0] ? 0.0 : 20.0;
                case "setHealth" -> {
                    dead[0] = true; kills[0]++;
                    runtime.tags.respawnCombatLogger((Player) object);
                    yield null;
                }
                case "setMetadata" -> { marker[0] = true; yield null; }
                case "hasMetadata" -> marker[0];
                case "removeMetadata" -> { marker[0] = false; yield null; }
                case "setExp", "setLevel" -> null;
                case "spigot" -> spigot;
                default -> throw new AssertionError(method.getName());
            });
            var enforce = CombatNpcManager.class.getDeclaredMethod("enforceCombatLogDeath", Player.class);
            enforce.setAccessible(true);
            enforce.invoke(manager, player);
            assertEquals(List.of(1L, 2L), scheduler.laterDelays);
            scheduler.laterTasks.get(0).run();
            assertFalse(dead[0]);
            scheduler.laterTasks.get(1).run();
            assertEquals(1, kills[0], "Delayed inventory enforcement must not re-kill a respawned player");
            assertTrue(clears[0] >= 2, "Retain inventory penalty");
            assertTrue(runtime.text.messages.isEmpty());
            scheduler.timerTask.run();
            assertFalse(marker[0]);
        }
    }

    @Test
    void meleeAndArrowsRequireBothPlayersOutsideSafezonesEvenWithoutTagsOrBarriers() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.config.set("barriers.enabled", false);
            runtime.config.set("barriers.custom-safezones", List.of("spawn"));
            for (boolean tagging : List.of(false, true)) {
                runtime.config.set("combat-tag.enabled", tagging);
                for (boolean attackerSafe : List.of(false, true)) {
                    for (boolean victimSafe : List.of(false, true)) {
                        Player attacker = player(attackerSafe ? -1 : 1);
                        Player victim = player(victimSafe ? -1 : 1);
                        Entity arrow = proxy(Arrow.class, (object, method, args) -> method.getName().equals("getShooter") ? attacker : null);
                        for (Entity damager : List.of(attacker, arrow)) {
                            runtime.tags.tagCalls = 0;
                            var event = damage(damager, victim);
                            runtime.pvp.onPvpToggleDamage(event);
                            if (!event.isCancelled()) runtime.tags.onCombatDamage(event);
                            assertEquals(attackerSafe || victimSafe, event.isCancelled());
                            assertEquals(!attackerSafe && !victimSafe && tagging ? 2 : 0, runtime.tags.tagCalls);
                            assertEquals(List.of("spawn"), runtime.protection.safezones);
                        }
                    }
                }
            }
        }
    }

    @Test
    void spearAttacksAreStoppedBeforeKnockbackWhenSafezoneOrToggleDeniesPvp() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.config.set("barriers.custom-safezones", List.of("spawn"));
            for (boolean attackerSafe : List.of(false, true)) {
                for (boolean victimSafe : List.of(false, true)) {
                    var event = new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(
                            player(attackerSafe ? -1 : 1), player(victimSafe ? -1 : 1), true);
                    runtime.pvp.onPreAttack(event);
                    assertEquals(attackerSafe || victimSafe, event.isCancelled());
                }
            }
            Player victim = player(1);
            runtime.toggledOff.put(victim.getUniqueId(), true);
            var toggled = new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(player(1), victim, true);
            runtime.pvp.onPreAttack(toggled);
            assertTrue(toggled.isCancelled());
            assertEquals(List.of("pvp-toggle.target-pvp-disabled"), runtime.text.messages);
        }
    }

    @Test
    void combatBlocksDisablingPvpButAllowsEnablingIt() throws Exception {
        try (var runtime = new Runtime()) {
            Player player = player(1);
            runtime.tags.tagged = true;
            assertTrue(runtime.pvp.togglePvp(player));
            assertTrue(runtime.pvp.hasPvpEnabled(player.getUniqueId()));
            assertEquals(List.of("pvp-toggle.combat-blocked"), runtime.text.messages);
            runtime.toggledOff.put(player.getUniqueId(), true);
            assertTrue(runtime.pvp.togglePvp(player));
            assertTrue(runtime.pvp.hasPvpEnabled(player.getUniqueId()));
        }
    }

    @Test
    void potionRestrictionsAreOnlyConsultedDuringCombat() throws Exception {
        try (var runtime = new Runtime()) {
            var event = allocate(ObservedPotionEvent.class);
            set(event, "entity", player(1));
            var items = new CombatItemManager(runtime.plugin, runtime.tags);
            items.onPotionEffect(event);
            assertEquals(0, event.effectReads);
            assertFalse(event.isCancelled());
            runtime.tags.tagged = true;
            items.onPotionEffect(event);
            assertEquals(1, event.effectReads);
        }
    }

    @Test
    void explosionProtectionRunsBeforeMonitorsAndWithTaggingDisabled() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.config.set("combat-tag.enabled", false);
            Player victim = player(-1);
            UUID crystalId = UUID.randomUUID();
            EnderCrystal crystal = proxy(EnderCrystal.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> crystalId;
                case "getLocation" -> new Location(null, 1, 64, 0);
                default -> throw new AssertionError(method.getName());
            });
            var crystalDamage = damage(crystal, victim);
            runtime.tags.onExplosionDamage(crystalDamage);
            assertTrue(crystalDamage.isCancelled());
            var blockDamage = new EntityDamageEvent(victim, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, damageSource(), 4);
            runtime.tags.onBlockExplosionProtection(blockDamage);
            assertTrue(blockDamage.isCancelled());
            assertEquals(0, runtime.tags.tagCalls);
            for (String method : List.of("onExplosionDamage", "onBlockExplosionProtection", "onCombatDamage", "onBlockExplosionDamage")) {
                Class<?> type = method.equals("onExplosionDamage") || method.equals("onCombatDamage")
                        ? EntityDamageByEntityEvent.class : EntityDamageEvent.class;
                var handler = CombatTagManager.class.getMethod(method, type).getAnnotation(EventHandler.class);
                assertTrue(handler.ignoreCancelled());
                assertEquals(method.equals("onCombatDamage") || method.equals("onBlockExplosionDamage")
                        ? EventPriority.MONITOR : EventPriority.HIGHEST, handler.priority());
            }
            var allowed = damage(crystal, player(1));
            runtime.tags.onCombatDamage(allowed);
            assertFalse(allowed.isCancelled(), "Monitor handlers must not change cancellation");
        }
    }

    @Test
    void vaultFailuresDoNotMintRewardsAndFailedCreditsRefundTheVictim() throws Exception {
        try (var runtime = new Runtime()) {
            var internal = allocate(PendingEconomy.class);
            set(runtime.plugin, "economyManager", internal);
            for (String failure : List.of("withdraw", "credit", "refund", "none", "exception")) {
                var balances = new HashMap<UUID, Double>();
                var calls = new ArrayList<String>();
                Player killer = player(1), victim = player(1);
                balances.put(killer.getUniqueId(), 0.0);
                balances.put(victim.getUniqueId(), 1000.0);
                Economy vault = proxy(Economy.class, (object, method, args) -> {
                    UUID uuid = ((org.bukkit.OfflinePlayer) args[0]).getUniqueId();
                    if (method.getName().equals("getBalance")) return balances.get(uuid);
                    double amount = (double) args[1];
                    boolean withdrawal = method.getName().equals("withdrawPlayer");
                    boolean credit = !withdrawal && uuid.equals(killer.getUniqueId());
                    String operation = withdrawal ? "withdraw" : credit ? "credit" : "refund";
                    calls.add(operation);
                    if (failure.equals("exception")) throw new IllegalStateException("Provider rejected the operation");
                    boolean success = !failure.equals(operation) && !(failure.equals("refund") && credit);
                    if (success) balances.compute(uuid, (key, balance) -> balance + (withdrawal ? -amount : amount));
                    return new EconomyResponse(success ? amount : 0, balances.get(uuid),
                            success ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, null);
                });
                var economy = new PvpEconomyManager(runtime.plugin);
                set(economy, "vaultEconomy", vault);
                runtime.text.messages.clear();
                var result = economy.handlePlayerKill(killer, victim).join();
                boolean success = failure.equals("none");
                boolean refundFailed = failure.equals("refund");
                assertEquals(success ? 700 : 0, result.killerGained());
                assertEquals(success || refundFailed ? 700 : 0, result.victimLost());
                assertEquals(success ? 700 : 0, balances.get(killer.getUniqueId()));
                assertEquals(success || refundFailed ? 300 : 1000, balances.get(victim.getUniqueId()));
                assertEquals(result.killerGained(), economy.getLastRecord(killer.getUniqueId()).moneyGained());
                assertEquals(result.victimLost(), economy.getLastRecord(victim.getUniqueId()).moneyLost());
                if (failure.equals("withdraw") || failure.equals("exception")) assertEquals(List.of("withdraw"), calls);
                if (failure.equals("credit") || refundFailed) assertEquals(List.of("withdraw", "credit", "refund"), calls);
                if (!success) assertFalse(runtime.text.messages.contains("economy.kill-steal-killer"));
                if (refundFailed) assertTrue(runtime.text.messages.contains("economy.refund-failed"));
            }
            assertEquals(0, internal.withdrawals, "Vault failures must not fall back to another wallet");
            assertEquals(0, internal.deposits);
        }
    }

    @Test
    void failedIndependentRewardAndPenaltyReportZeroAndInvalidAmountsNeverReachVault() throws Exception {
        try (var runtime = new Runtime()) {
            var calls = new ArrayList<String>();
            var economy = new PvpEconomyManager(runtime.plugin);
            set(economy, "vaultEconomy", proxy(Economy.class, (object, method, args) -> {
                if (method.getName().equals("getBalance")) return 1000.0;
                calls.add(method.getName());
                return new EconomyResponse(0, 1000, EconomyResponse.ResponseType.FAILURE, null);
            }));
            runtime.config.set("player-kills.money-steal", false);
            var result = economy.handlePlayerKill(player(1), player(1)).join();
            assertEquals(0, result.killerGained());
            assertEquals(0, result.victimLost());
            assertEquals(List.of("withdrawPlayer", "depositPlayer"), calls);
            assertTrue(runtime.text.messages.isEmpty());
            calls.clear();
            runtime.config.set("player-kills.money-reward", Double.NaN);
            runtime.config.set("player-kills.money-penalty", Double.POSITIVE_INFINITY);
            economy.handlePlayerKill(player(1), player(1)).join();
            assertTrue(calls.isEmpty());
        }
    }

    @Test
    void internalStealWaitsForAccountLoadingAndTransferCompletionIncludingFriendlyVault() throws Exception {
        try (var runtime = new Runtime()) {
            for (boolean friendlyVault : List.of(false, true)) {
                for (boolean success : List.of(false, true)) {
                    var internal = allocate(PendingEconomy.class);
                    internal.load = new CompletableFuture<>();
                    internal.transfer = new CompletableFuture<>();
                    set(runtime.plugin, "economyManager", internal);
                    var economy = new PvpEconomyManager(runtime.plugin);
                    if (friendlyVault) set(economy, "vaultEconomy", new FriendlyVaultEconomy(runtime.plugin, internal));
                    Player killer = player(1), victim = player(1);
                    runtime.text.messages.clear();
                    var result = economy.handlePlayerKill(killer, victim);
                    assertFalse(result.isDone());
                    assertEquals(0, internal.transfers);
                    internal.load.complete(new AccountData(victim.getUniqueId(), victim.getName(), 1000));
                    assertEquals(1, internal.transfers);
                    assertEquals(700, internal.amount);
                    assertEquals(victim.getUniqueId(), internal.sender);
                    assertEquals(killer.getUniqueId(), internal.receiver);
                    assertFalse(result.isDone());
                    assertTrue(runtime.text.messages.isEmpty());
                    internal.transfer.complete(success);
                    assertEquals(success ? 700 : 0, result.join().killerGained());
                    assertEquals(success ? 700 : 0, result.join().victimLost());
                    assertEquals(success ? 2 : 0, runtime.text.messages.size());
                }
            }
        }
    }

    @Test
    void internalIndependentRewardsRunAsynchronouslyAndRespectRejectedAdjustments() throws Exception {
        try (var runtime = new Runtime()) {
            runtime.config.set("player-kills.money-steal", false);
            var internal = allocate(PendingEconomy.class);
            internal.load = CompletableFuture.completedFuture(new AccountData(UUID.randomUUID(), "Victim", 1000));
            set(runtime.plugin, "economyManager", internal);
            var scheduler = new Scheduler();
            set(runtime.plugin, "scheduler", scheduler);
            var result = new PvpEconomyManager(runtime.plugin).handlePlayerKill(player(1), player(1));
            assertFalse(result.isDone());
            assertEquals(0, internal.withdrawals);
            assertEquals(0, internal.deposits);
            assertNotNull(scheduler.asyncTask);
            scheduler.asyncTask.run();
            assertEquals(1, internal.withdrawals);
            assertEquals(1, internal.deposits);
            assertEquals(0, result.join().victimLost());
            assertEquals(0, result.join().killerGained());
            assertTrue(runtime.text.messages.isEmpty());
        }
    }

    private static Player player(int x) {
        UUID uuid = UUID.randomUUID();
        return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> uuid.toString();
            case "getLocation" -> new Location(null, x, 64, 0);
            case "isOnline" -> true;
            case "getPlayer" -> object;
            case "hasPermission" -> false;
            case "hasMetadata" -> false;
            case "equals" -> object == args[0];
            case "hashCode" -> uuid.hashCode();
            default -> throw new AssertionError(method.getName());
        });
    }

    private static DamageSource damageSource() {
        return proxy(DamageSource.class, (object, method, args) -> null);
    }

    private static EntityDamageByEntityEvent damage(Entity damager, Player victim) {
        return new EntityDamageByEntityEvent(damager, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damageSource(), 4);
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
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static class Regions extends ProtectionHook {
        List<String> safezones;
        @Override public boolean isPvpAllowed(Location location, Player player, List<String> safezones) {
            this.safezones = safezones;
            return location.getX() >= 0;
        }
    }

    private static class Tags extends CombatTagManager {
        boolean tagged;
        int tagCalls;
        int restoredDuration;
        Tags(FriendlyCore plugin) { super(plugin); }
        @Override public boolean isTagged(UUID uuid) { return tagged; }
        @Override public void tagPlayer(Player player, Player opponent) { tagCalls++; }
        @Override public void tagPlayer(Player player, Player opponent, int duration) { restoredDuration = duration; }
        @Override public Player resolveCrystalDetonator(UUID uuid) { return null; }
        @Override public Player resolveAnchorDetonator(Location location) { return null; }
    }

    private static class Messages extends TextService {
        final List<String> messages = new ArrayList<>();
        @Override public void sendMessage(CommandSender sender, String message) { messages.add(message); }
        @Override public void sendMessage(CommandSender sender, String message, Map<String, String> placeholders) { messages.add(message); }
    }

    private static class Scheduler extends FriendlyScheduler {
        Runnable asyncTask;
        Runnable delayedTask, timerTask;
        final List<Runnable> laterTasks = new ArrayList<>();
        final List<Long> laterDelays = new ArrayList<>();
        long delay;
        boolean cancelled;
        Scheduler() { super(null); }
        @Override public Object runEntity(Entity entity, Runnable task) { task.run(); return null; }
        @Override public Object runAsync(Runnable task) { asyncTask = task; return null; }
        @Override public Object runEntityLater(Entity entity, Runnable task, long delay) {
            laterTasks.add(task); laterDelays.add(delay);
            delayedTask = task; this.delay = delay; return null;
        }
        @Override public Object runEntityTimer(Entity entity, Runnable task, long delay, long period) {
            timerTask = task; return task;
        }
        @Override public void cancel(Object task) { cancelled = true; }
    }

    private static class ObservedPotionEvent extends EntityPotionEffectEvent {
        int effectReads;
        private ObservedPotionEvent() { super(null, null, null, Cause.UNKNOWN, Action.ADDED, false); }
        @Override public PotionEffect getNewEffect() { effectReads++; return null; }
    }

    private static class PendingEconomy extends EconomyManager {
        CompletableFuture<AccountData> load;
        CompletableFuture<Boolean> transfer;
        int transfers;
        int withdrawals, deposits;
        double amount;
        UUID sender, receiver;
        private PendingEconomy() { super(null); }
        @Override public CompletableFuture<AccountData> getAccountAsync(UUID uuid, String name) { return load; }
        @Override public boolean withdraw(UUID uuid, double amount, boolean broadcast) { withdrawals++; return false; }
        @Override public boolean deposit(UUID uuid, double amount, boolean broadcast) { deposits++; return false; }
        @Override public CompletableFuture<Boolean> transfer(UUID sender, String senderName, UUID receiver, String receiverName, double amount) {
            transfers++;
            this.amount = amount;
            this.sender = sender;
            this.receiver = receiver;
            return transfer;
        }
    }

    private static class Runtime implements AutoCloseable {
        final Object previousServer;
        final FriendlyCore plugin = allocate(FriendlyCore.class);
        final YamlConfiguration config = new YamlConfiguration();
        final Regions protection = new Regions();
        final Messages text = new Messages();
        final Tags tags = new Tags(plugin);
        final PvpManager pvp = allocate(PvpManager.class);
        final Map<UUID, Boolean> toggledOff = new ConcurrentHashMap<>();
        final Map<UUID, Player> players = new HashMap<>();
        java.util.function.Consumer<org.bukkit.event.Event> events = event -> {};
        boolean ownsPlayer = true;

        Runtime() throws Exception {
            var server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            previousServer = server.get(null);
            PluginManager manager = proxy(PluginManager.class, (object, method, args) -> switch (method.getName()) {
                case "isPluginEnabled" -> false;
                case "getPlugin" -> null;
                case "callEvent" -> { events.accept((org.bukkit.event.Event) args[0]); yield null; }
                default -> throw new AssertionError(method.getName());
            });
            server.set(null, proxy(Server.class, (object, method, args) -> switch (method.getName()) {
                case "getPluginManager" -> manager;
                case "isStopping" -> false;
                case "isOwnedByCurrentRegion" -> ownsPlayer;
                case "getPlayer" -> players.get(args[0]);
                default -> null;
            }));
            var service = allocate(ConfigService.class);
            set(service, "aliases", Map.of());
            set(service, "configs", new ConcurrentHashMap<>(Map.of("modules/pvp/config.yml", config,
                    "modules/pvp/messages.yml", new YamlConfiguration())));
            var hooks = allocate(HookManager.class);
            set(hooks, "protectionHook", protection);
            set(plugin, "configService", service);
            set(plugin, "hookManager", hooks);
            set(plugin, "numberFormatter", new NumberFormatter());
            set(plugin, "textService", text);
            set(plugin, "scheduler", new Scheduler());
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            set(plugin, "logger", logger);
            set(plugin, "pvpManager", pvp);
            set(pvp, "plugin", plugin);
            set(pvp, "combatTagManager", tags);
            set(pvp, "pvpToggledOff", toggledOff);
            set(pvp, "toggleCooldowns", new ConcurrentHashMap<UUID, Long>());
            set(pvp, "adminBypassSet", ConcurrentHashMap.newKeySet());
            set(pvp, "newbieProtectionManager", new NewbieProtectionManager(plugin));
        }

        @Override public void close() throws Exception {
            var server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, previousServer);
        }
    }
}
