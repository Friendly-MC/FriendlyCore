package dev.lyy.friendlycore.common;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTeleportUtilTest {
    @Test
    void worldsEntryPermissionControlsCrossWorldTeleportsOnlyWhenWorldsIsEnabled() {
        World current = world("lobby");
        World destination = world("survival");
        Player denied = player(current, false);
        Player allowed = player(current, true);

        assertTrue(SafeTeleportUtil.canEnterWorld(denied, destination, false));
        assertTrue(SafeTeleportUtil.canEnterWorld(denied, current, true));
        assertFalse(SafeTeleportUtil.canEnterWorld(denied, destination, true));
        assertTrue(SafeTeleportUtil.canEnterWorld(allowed, destination, true));
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getKey")) return new NamespacedKey("example", name);
                    throw new AssertionError(method.getName());
                });
    }

    private static Player player(World current, boolean allowed) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> current;
                    case "hasPermission" -> allowed && "worlds.enter.example:survival".equals(args[0]);
                    default -> throw new AssertionError(method.getName());
                });
    }
}
