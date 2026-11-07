package dev.lyy.friendlycore;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import dev.lyy.friendlycore.modules.kits.commands.FriendlyCoreCommand;
import dev.lyy.friendlycore.modules.spawn.SpawnData;
import dev.lyy.friendlycore.modules.spawn.SpawnManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.io.File;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpawnRespawnLifecycleTest {
    @Test
    void foliaDeathWithoutRespawnEventsWaitsForRespawnThenTransfersOnce() throws Exception {
        FoliaDeath death = foliaDeath("NO_BED", false);
        death.tick();
        assertEquals(0, death.manager.transfers);
        assertFalse(death.cancelled[0], "Keep waiting while the death screen is open");
        death.dead[0] = false;
        death.valid[0] = false;
        death.tick();
        assertEquals(0, death.manager.transfers, "Health resets before Folia finishes placement");
        assertFalse(death.cancelled[0]);
        death.valid[0] = true;
        death.tick();
        assertEquals(1, death.manager.transfers);
        assertTrue(death.cancelled[0]);
        assertFalse(death.manager.pending);
        death.tick();
        assertEquals(1, death.manager.transfers);
    }

    @Test
    void foliaCompletionRespectsSavedBedsAnchorsAndModes() throws Exception {
        for (String mode : new String[]{"NO_BED", "ALWAYS", "DISABLED"}) {
            FoliaDeath death = foliaDeath(mode, true);
            death.dead[0] = false;
            death.tick();
            assertEquals("ALWAYS".equals(mode) ? 1 : 0, death.manager.transfers, mode);
            assertTrue(death.cancelled[0]);
        }
        FoliaDeath invalidBed = foliaDeath("NO_BED", true);
        invalidBed.saved[0] = null; // Vanilla clears a broken bed / invalid anchor during respawn.
        invalidBed.dead[0] = false;
        invalidBed.tick();
        assertEquals(1, invalidBed.manager.transfers);

        FoliaDeath eventsSupported = foliaDeath("NO_BED", false);
        eventsSupported.dead[0] = false;
        eventsSupported.manager.onPlayerPostRespawn(new PlayerPostRespawnEvent(eventsSupported.player,
                eventsSupported.player.getWorld().getSpawnLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));
        eventsSupported.tick();
        assertEquals(1, eventsSupported.manager.transfers, "A fork firing post-respawn must not transfer twice");
    }

    private record FoliaDeath(SpawnProbe manager, Player player, boolean[] dead, boolean[] valid, Location[] saved,
                             boolean[] cancelled, Consumer<ScheduledTask> callback, ScheduledTask task) {
        void tick() { callback.accept(task); }
    }

    private static FoliaDeath foliaDeath(String mode, boolean savedSpawn) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("respawn.mode", mode);
        SpawnProbe manager = manager(config);
        var pluginField = SpawnManager.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        FriendlyCore plugin = (FriendlyCore) pluginField.get(manager);
        FriendlyScheduler scheduler = allocate(FriendlyScheduler.class);
        set(scheduler, FriendlyScheduler.class, "isFolia", true);
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        boolean[] cancelled = {false};
        ScheduledTask task = (ScheduledTask) Proxy.newProxyInstance(ScheduledTask.class.getClassLoader(),
                new Class<?>[]{ScheduledTask.class}, (object, method, args) -> {
                    if (!"cancel".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
                    cancelled[0] = true;
                    return ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
                });
        AtomicReference<Consumer<ScheduledTask>> callback = new AtomicReference<>();
        EntityScheduler entityScheduler = (EntityScheduler) Proxy.newProxyInstance(EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class}, (object, method, args) -> {
                    if (!"runAtFixedRate".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
                    @SuppressWarnings("unchecked") Consumer<ScheduledTask> action = (Consumer<ScheduledTask>) args[1];
                    callback.set(action);
                    return task;
                });
        boolean[] dead = {true};
        boolean[] valid = {true};
        Location[] saved = {savedSpawn ? new Location(null, 10, 80, 10) : null};
        Player player = player(dead, entityScheduler, saved, valid);
        PlayerDeathEvent event = allocate(PlayerDeathEvent.class);
        set(event, EntityEvent.class, "entity", player);
        manager.onPlayerDeath(event);
        assertNotNull(callback.get(), "A Folia death must schedule completion even if no respawn event fires");
        return new FoliaDeath(manager, player, dead, valid, saved, cancelled, callback.get(), task);
    }

    @Test
    void statusReportsRegisteredListenersAndTraceWithoutConsoleOutput() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("debug", true);
        SpawnProbe manager = manager(config);
        var pluginField = SpawnManager.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        FriendlyCore plugin = (FriendlyCore) pluginField.get(manager);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        set(plugin, JavaPlugin.class, "logger", logger);
        set(plugin, JavaPlugin.class, "dataFolder", new File("target/spawn-test"));
        set(plugin, FriendlyCore.class, "spawnManager", manager);
        assertTrue(manager.getLastRespawnTrace().contains("No respawn event observed"));

        Player player = player(new boolean[]{false});
        manager.onPlayerPostRespawn(new PlayerPostRespawnEvent(player, player.getWorld().getSpawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        var messages = new ArrayList<String>();
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (object, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> true;
                    case "sendMessage" -> { messages.add((String) args[0]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RegisteredListener listener = new RegisteredListener(manager, (ignored, event) -> {},
                EventPriority.HIGHEST, plugin, false);
        PlayerRespawnEvent.getHandlerList().register(listener);
        PlayerPostRespawnEvent.getHandlerList().register(listener);
        try {
            Command command = new Command("friendlycore") {
                @Override public boolean execute(CommandSender source, String label, String[] args) { return true; }
            };
            assertTrue(new FriendlyCoreCommand(plugin).onCommand(sender, command, "friendlycore", new String[]{"spawnstatus"}));
            assertEquals(3, messages.size());
            assertTrue(messages.get(0).contains("Diagnostics active; local=lobby target=spawn-eco mode=NO_BED debug=true"));
            assertTrue(messages.get(1).contains("before=1 after=1"));
            assertTrue(messages.get(2).contains("PlayerPostRespawnEvent player=Steve reason=DEATH"));
            assertTrue(messages.get(2).contains("dead=false valid=true"));
            assertTrue(messages.get(2).contains("route=proxy"));
        } finally {
            PlayerRespawnEvent.getHandlerList().unregister(listener);
            PlayerPostRespawnEvent.getHandlerList().unregister(listener);
        }
    }

    @Test
    void noBedDeathTransfersOnlyAfterThePlayerIsAlive() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        boolean[] dead = {true};
        Player player = player(dead);
        SpawnProbe manager = manager(config);
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(player, player.getWorld().getSpawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH);

        manager.onPlayerRespawn(respawn);
        assertEquals(0, manager.transfers, "The dead entity cannot schedule the proxy transfer");
        assertEquals(player.getWorld().getSpawnLocation(), respawn.getRespawnLocation());

        dead[0] = false;
        manager.onPlayerPostRespawn(new PlayerPostRespawnEvent(player, respawn.getRespawnLocation(),
                false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        assertEquals(1, manager.transfers);
    }

    @Test
    void postRespawnPreservesBedsAnchorsModesAndEndPortalPolicy() throws Exception {
        assertEquals(0, transfers("NO_BED", true, false, PlayerRespawnEvent.RespawnReason.DEATH, false));
        assertEquals(0, transfers("NO_BED", false, true, PlayerRespawnEvent.RespawnReason.DEATH, false));
        assertEquals(1, transfers("ALWAYS", true, false, PlayerRespawnEvent.RespawnReason.DEATH, false));
        assertEquals(0, transfers("DISABLED", false, false, PlayerRespawnEvent.RespawnReason.DEATH, false));
        assertEquals(0, transfers("ALWAYS", false, false, PlayerRespawnEvent.RespawnReason.END_PORTAL, false));
        assertEquals(1, transfers("NO_BED", false, false, PlayerRespawnEvent.RespawnReason.END_PORTAL, true));
        assertEquals(0, transfers("NO_BED", true, false, PlayerRespawnEvent.RespawnReason.END_PORTAL, true));
    }

    @Test
    void pluginTriggeredDeathRespawnsUseTheSameSpawnPolicy() throws Exception {
        assertEquals(1, transfers("NO_BED", false, false, PlayerRespawnEvent.RespawnReason.PLUGIN, false));
        assertEquals(0, transfers("NO_BED", true, false, PlayerRespawnEvent.RespawnReason.PLUGIN, false));
        assertEquals(0, transfers("NO_BED", false, true, PlayerRespawnEvent.RespawnReason.PLUGIN, false));
        assertEquals(1, transfers("ALWAYS", true, false, PlayerRespawnEvent.RespawnReason.PLUGIN, false));
        assertEquals(0, transfers("DISABLED", false, false, PlayerRespawnEvent.RespawnReason.PLUGIN, false));
    }

    private static int transfers(String mode, boolean bed, boolean anchor,
                                 PlayerRespawnEvent.RespawnReason reason, boolean overrideEnd) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("respawn.mode", mode);
        config.set("respawn.override-end-portal", overrideEnd);
        SpawnProbe manager = manager(config);
        boolean[] dead = {true};
        Player player = player(dead);
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(player, player.getWorld().getSpawnLocation(),
                bed, anchor, false, reason);
        manager.onPlayerRespawn(respawn);
        assertEquals(0, manager.transfers);
        dead[0] = false;
        manager.onPlayerPostRespawn(new PlayerPostRespawnEvent(player, respawn.getRespawnLocation(),
                bed, anchor, false, reason));
        return manager.transfers;
    }

    private static Player player(boolean[] dead) {
        return player(dead, null, null, null);
    }

    private static Player player(boolean[] dead, EntityScheduler scheduler, Location[] saved, boolean[] valid) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getSpawnLocation" -> new Location((World) object, 0.5, 70, 0.5);
                    case "getName" -> "world";
                    case "equals" -> object == args[0];
                    case "hashCode" -> System.identityHashCode(object);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (object, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "isOnline" -> true;
                    case "isDead" -> dead[0];
                    case "isValid" -> valid != null ? valid[0] : !dead[0];
                    case "getName" -> "Steve";
                    case "getScheduler" -> scheduler;
                    case "getRespawnLocation" -> {
                        assertEquals(false, args[0], "Do not validate blocks across Folia regions");
                        yield saved[0];
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SpawnProbe manager(YamlConfiguration config) throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        ConfigProbe configs = allocate(ConfigProbe.class);
        configs.config = config;
        set(plugin, FriendlyCore.class, "configService", configs);
        SpawnProbe manager = allocate(SpawnProbe.class);
        set(manager, SpawnManager.class, "plugin", plugin);
        return manager;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class ConfigProbe extends ConfigService {
        FileConfiguration config;
        ConfigProbe() { super(null); }
        @Override public FileConfiguration getSpawnConfig() { return config; }
        @Override public FileConfiguration getMainConfig() { return config; }
    }

    private static class SpawnProbe extends SpawnManager {
        int transfers;
        boolean pending;
        SpawnProbe() { super(null); }
        @Override public SpawnData getSpawnData() {
            return new SpawnData("spawn-eco", "spawn", 0.5, 64, 0.5, 0, 0);
        }
        @Override public boolean isLocalServer(String server) { return false; }
        @Override public void markPendingRespawn(Player player) { pending = true; }
        @Override public boolean hasPendingRespawn(Player player) { return pending; }
        @Override public void clearPendingRespawn(Player player) { pending = false; }
        @Override public void dispatchCrossServerRespawn(Player player, SpawnData spawn) {
            assertFalse(player.isDead(), "Transfer scheduling must wait for post-respawn");
            assertEquals("spawn-eco", spawn.server());
            transfers++;
        }
    }
}
