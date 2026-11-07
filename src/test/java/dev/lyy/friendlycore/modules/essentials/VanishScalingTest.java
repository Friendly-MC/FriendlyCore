package dev.lyy.friendlycore.modules.essentials;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.modules.essentials.vanish.VanishManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VanishScalingTest {

    @Test
    void vanishTierIsScannedOncePerCacheWindow() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        Permissible holder = (Permissible) Proxy.newProxyInstance(Permissible.class.getClassLoader(),
                new Class<?>[]{Permissible.class}, (proxy, method, args) -> null);
        PermissionAttachmentInfo tier5 = new PermissionAttachmentInfo(holder, "friendlycore.vanish.tier.5", null, true);
        Player player = player(UUID.randomUUID(), scans, Set.of(tier5));
        VanishManager manager = new VanishManager(plugin(), null);

        for (int i = 0; i < 100; i++) assertEquals(5, manager.getVanishTier(player));
        assertEquals(1, scans.get(), "effective permissions must not be rescanned on every canSee");

        manager.invalidateTier(player.getUniqueId());
        assertEquals(5, manager.getVanishTier(player));
        assertEquals(2, scans.get());
    }

    @Test
    void joiningWhileVisibleSkipsTheServerWideVisibilityPass() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        VanishManager manager = new VanishManager(plugin(), null) {
            @Override public void updateAllVisibilities(Player target) { passes.incrementAndGet(); }
        };
        Player player = player(UUID.randomUUID(), new AtomicInteger(), Set.of());

        manager.setVanished(player, false, true);
        assertEquals(0, passes.get(), "a normal join must not touch every online player");

        manager.setVanished(player, true, true);
        manager.setVanished(player, false, true);
        assertEquals(2, passes.get(), "real vanish transitions still update everyone");
    }

    private static Player player(UUID uuid, AtomicInteger scans, Set<PermissionAttachmentInfo> perms) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnline" -> true;
                    case "isOp", "hasPermission" -> false;
                    case "getEffectivePermissions" -> {
                        scans.incrementAndGet();
                        yield perms;
                    }
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static FriendlyCore plugin() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        var configField = FriendlyCore.class.getDeclaredField("configService");
        configField.setAccessible(true);
        configField.set(plugin, allocate(ConfigProbe.class));
        var schedulerField = FriendlyCore.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(plugin, new FriendlyScheduler(null) {
            @Override public Object runEntity(Entity entity, Runnable action) { return null; }
        });
        return plugin;
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
