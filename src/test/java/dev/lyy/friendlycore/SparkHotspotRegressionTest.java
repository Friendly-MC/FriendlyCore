package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import dev.lyy.friendlycore.modules.tpa.crossserver.TpaCrossServerService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SparkHotspotRegressionTest {
    @Test
    void quittingCancelsBothDirectionsWithoutReloadingAndStillSavesDirtyPreferences() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        TpaManager manager = allocate(TpaManager.class);
        Map<UUID, TpaProfile> cache = new ConcurrentHashMap<>();
        Map<UUID, Object> tokens = new ConcurrentHashMap<>();
        var crossServer = allocate(CrossServerProbe.class);
        set(manager, TpaManager.class, "plugin", plugin);
        set(manager, TpaManager.class, "profileCache", cache);
        set(manager, TpaManager.class, "loadTokens", tokens);
        set(manager, TpaManager.class, "activeCountdowns", new ConcurrentHashMap<>());
        set(manager, TpaManager.class, "inFlightSaves", ConcurrentHashMap.newKeySet());
        set(manager, TpaManager.class, "crossServerService", crossServer);
        UUID sender = UUID.randomUUID(), target = UUID.randomUUID(), requester = UUID.randomUUID();
        TpaProfile profile = new TpaProfile(sender, "Sender");
        profile.setTpaEnabled(false);
        TpaProfile targetProfile = new TpaProfile(target, "Target");
        TpaProfile requesterProfile = new TpaProfile(requester, "Requester");
        TpaRequest outgoing = new TpaRequest(sender, "Sender", target, "Target", TpaRequest.Type.TPA);
        TpaRequest remote = new TpaRequest(sender, "Sender", UUID.randomUUID(), "Remote", TpaRequest.Type.TPA);
        remote.setTargetServer("remote");
        TpaRequest incoming = new TpaRequest(requester, "Requester", sender, "Sender", TpaRequest.Type.TPA);
        profile.addOutgoingRequest(outgoing);
        profile.addOutgoingRequest(remote);
        targetProfile.addIncomingRequest(outgoing);
        profile.addIncomingRequest(incoming);
        requesterProfile.addOutgoingRequest(incoming);
        cache.put(sender, profile);
        cache.put(target, targetProfile);
        cache.put(requester, requesterProfile);
        tokens.put(sender, new Object());
        int[] saves = {0};
        DatabaseManager database = (DatabaseManager) Proxy.newProxyInstance(DatabaseManager.class.getClassLoader(),
                new Class<?>[]{DatabaseManager.class}, (p, method, args) -> {
                    assertEquals("saveTpaProfile", method.getName(), "Cleanup must never load a profile");
                    assertSame(profile, args[0]);
                    assertFalse(profile.isTpaEnabled());
                    assertTrue(profile.getIncomingRequests().isEmpty());
                    assertTrue(profile.getOutgoingRequests().isEmpty());
                    saves[0]++;
                    return CompletableFuture.completedFuture(null);
                });
        set(plugin, FriendlyCore.class, "databaseManager", database);

        manager.unloadProfile(player(sender));
        manager.unloadProfile(player(sender));
        manager.cancelOutgoingRequests(player(sender), false);

        assertFalse(cache.containsKey(sender));
        assertFalse(tokens.containsKey(sender));
        assertTrue(targetProfile.getIncomingRequests().isEmpty());
        assertTrue(requesterProfile.getOutgoingRequests().isEmpty());
        for (TpaRequest request : List.of(outgoing, remote, incoming)) {
            assertEquals(TpaRequest.Status.CANCELLED, request.getStatus());
        }
        assertEquals(1, crossServer.cancellations);
        assertEquals(1, saves[0]);
    }

    @Test
    void vanishSavesOnlyChangesOffThreadInOrderAndFlushesDuringShutdown(@TempDir Path directory) throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        set(plugin, FriendlyCore.class, "configService", allocate(ConfigProbe.class));
        var executor = new QueuedExecutor();
        var scheduler = new FriendlyScheduler(null) {
            @Override public ExecutorService getAsyncExecutor() { return executor; }
            @Override public Object runEntity(Entity entity, Runnable action) { return null; }
        };
        set(plugin, FriendlyCore.class, "scheduler", scheduler);
        VanishManager manager = new VanishManager(plugin, null) {
            @Override public void updateAllVisibilities(Player target) {}
        };
        File file = directory.resolve("nested/vanished_players.yml").toFile();
        set(manager, VanishManager.class, "storageFile", file);
        UUID uuid = UUID.randomUUID();
        Player player = player(uuid);
        manager.setVanished(player, false, true);
        assertTrue(executor.tasks.isEmpty(), "Visible joins must not schedule persistence");
        manager.setVanished(player, true, true);
        manager.setVanished(player, true, true);
        manager.setVanished(player, false, true);
        manager.setVanished(player, true, true);
        assertFalse(file.exists(), "Player-thread changes must not write files");
        assertEquals(1, executor.tasks.size(), "Only the first serialized save is submitted");
        executor.runNext();
        assertEquals(List.of(uuid.toString()), YamlConfiguration.loadConfiguration(file).getStringList("vanished-uuids"));
        executor.runNext();
        assertTrue(YamlConfiguration.loadConfiguration(file).getStringList("vanished-uuids").isEmpty());
        executor.runNext();
        assertEquals(List.of(uuid.toString()), YamlConfiguration.loadConfiguration(file).getStringList("vanished-uuids"));
        manager.setVanished(player, false, true);

        var shutdown = CompletableFuture.runAsync(manager::shutdown);
        executor.runNext();
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(YamlConfiguration.loadConfiguration(file).getStringList("vanished-uuids").isEmpty());
        assertTrue(executor.tasks.isEmpty());
        manager.setVanished(player, true, true);
        assertFalse(manager.isVanished(uuid), "Shutdown must not allow a late save to overwrite the flush");
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (p, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Sender";
                    default -> throw new AssertionError(method.getName());
                });
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static class ConfigProbe extends ConfigService {
        ConfigProbe() { super(null); }
        @Override public FileConfiguration get(String name) { return new YamlConfiguration(); }
    }

    private static class CrossServerProbe extends TpaCrossServerService {
        int cancellations;
        CrossServerProbe() { super(null, null); }
        @Override public String getLocalServerId() { return "local"; }
        @Override public void cancelCrossServerRequest(Player sender, TpaRequest request) { cancellations++; }
    }

    private static class QueuedExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() { Objects.requireNonNull(tasks.poll()).run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
