package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishListener;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VanishJoinTest {
    @Test
    void joiningClearsVanishAndItsEffectsRegardlessOfPermissionsOrPreviousState() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        var configField = FriendlyCore.class.getDeclaredField("configService");
        configField.setAccessible(true);
        configField.set(plugin, allocate(ConfigProbe.class));
        var schedulerField = FriendlyCore.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var entityTasks = new ArrayList<Runnable>();
        schedulerField.set(plugin, new FriendlyScheduler(null) {
            @Override public Object runEntity(Entity entity, Runnable action) {
                entityTasks.add(action);
                return null;
            }
        });

        for (boolean previouslyVanished : new boolean[]{false, true}) {
            for (boolean staffPermissions : new boolean[]{false, true}) {
                UUID uuid = UUID.randomUUID();
                Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                        new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getUniqueId" -> uuid;
                            case "isOnline", "hasPermission" -> method.getName().equals("isOnline") || staffPermissions;
                            default -> throw new AssertionError(method.getName());
                        });
                boolean[] viewerUpdated = {false};
                VanishManager manager = new VanishManager(plugin, null) {
                    @Override public void updateAllVisibilities(Player target) {}
                    @Override public void updateAllForViewer(Player viewer) {
                        assertFalse(isVanished(viewer));
                        viewerUpdated[0] = true;
                    }
                };
                if (previouslyVanished) manager.setVanished(player, true, true);
                var nightVisionField = VanishManager.class.getDeclaredField("nightVisionPlayers");
                nightVisionField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Set<UUID> nightVisionPlayers = (Set<UUID>) nightVisionField.get(manager);
                if (previouslyVanished) nightVisionPlayers.add(uuid);
                entityTasks.clear();

                Component joinMessage = Component.text("joined");
                PlayerJoinEvent event = new PlayerJoinEvent(player, joinMessage);
                new VanishListener(plugin, manager).onPlayerJoin(event);

                assertFalse(manager.isVanished(uuid));
                assertEquals(0, manager.getVanishDuration(uuid));
                assertEquals(previouslyVanished, nightVisionPlayers.contains(uuid),
                        "Keep effect ownership until the entity restoration task can remove night vision");
                assertEquals(1, entityTasks.size(), "Joining schedules player state restoration");
                assertTrue(viewerUpdated[0]);
                assertEquals(staffPermissions ? null : joinMessage, event.joinMessage());
            }
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static class ConfigProbe extends ConfigService {
        ConfigProbe() { super(null); }
        @Override public FileConfiguration get(String name) { return new YamlConfiguration(); }
    }
}
