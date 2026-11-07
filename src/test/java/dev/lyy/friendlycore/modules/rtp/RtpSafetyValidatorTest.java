package dev.lyy.friendlycore.modules.rtp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ConfigService;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class RtpSafetyValidatorTest {
    @Test
    void rejectsCoveredOverworldCavitiesAndNonPassableFeetOrHead() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var plugin = (FriendlyCore) unsafe.allocateInstance(FriendlyCore.class);
        var config = (Config) unsafe.allocateInstance(Config.class);
        var pluginConfig = FriendlyCore.class.getDeclaredField("configService");
        pluginConfig.setAccessible(true);
        pluginConfig.set(plugin, config);
        var validator = new RtpSafetyValidator(plugin);

        WorldBorder border = proxy(WorldBorder.class, (p, m, a) -> switch (m.getName()) {
            case "getSize" -> 10000.0;
            case "getCenter" -> new Location(null, 0, 0, 0);
            default -> throw new AssertionError(m.getName());
        });
        int[] surface = {70};
        World world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getWorldBorder" -> border;
            case "getHighestBlockYAt" -> {
                assertEquals(HeightMap.MOTION_BLOCKING_NO_LEAVES, a[2]);
                yield surface[0];
            }
            default -> throw new AssertionError(m.getName());
        });
        boolean[] feetClear = {true}, headClear = {true};
        Chunk chunk = proxy(Chunk.class, (p, m, a) -> {
            assertEquals("getBlock", m.getName());
            int y = (int) a[1];
            return proxy(Block.class, (b, operation, args) -> {
                if (operation.getName().equals("isPassable")) return y == 64 ? feetClear[0] : headClear[0];
                throw new AssertionError("Unsafe candidate reached material checks: " + operation.getName());
            });
        });
        Location destination = new Location(world, 0.5, 64, 0.5);
        assertFalse(validator.isLocationSafe(destination, chunk, RtpWorldConfig.WorldType.NORMAL));
        surface[0] = 63;
        feetClear[0] = false;
        assertFalse(validator.isLocationSafe(destination, chunk, RtpWorldConfig.WorldType.NORMAL));
        feetClear[0] = true;
        headClear[0] = false;
        assertFalse(validator.isLocationSafe(destination, chunk, RtpWorldConfig.WorldType.NORMAL));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static class Config extends ConfigService {
        Config() { super(null); }
        @Override public org.bukkit.configuration.file.FileConfiguration getRtpConfig() {
            return new YamlConfiguration();
        }
    }
}
