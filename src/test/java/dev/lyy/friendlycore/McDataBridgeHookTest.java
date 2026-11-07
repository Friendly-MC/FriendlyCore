package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.hooks.McDataBridgeHook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class McDataBridgeHookTest {

    @Test
    public void testHookNullSafety() {
        McDataBridgeHook hook = new McDataBridgeHook(null);
        Assertions.assertFalse(hook.isEnabled());
        Assertions.assertFalse(hook.isPlayerLocked(null));
        hook.recordOfflineDeathDrop(null);
        hook.recordOfflineDeathDrop(java.util.UUID.randomUUID());
    }

    @Test
    public void testFriendlySyncReflectionSafety() throws Exception {
        McDataBridgeHook hook = new McDataBridgeHook(null);
        java.lang.reflect.Field friendlySyncEnabledField = McDataBridgeHook.class.getDeclaredField("friendlySyncEnabled");
        friendlySyncEnabledField.setAccessible(true);
        friendlySyncEnabledField.setBoolean(hook, true);

        java.lang.reflect.Field enabledField = McDataBridgeHook.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(hook, true);

        Assertions.assertTrue(hook.isEnabled());
        // If FriendlySync is marked enabled but API is unavailable/uninitialized,
        // it should fail-safe to locked (true) to protect player data from desync.
        java.lang.reflect.Method isLockedMethod = McDataBridgeHook.class.getDeclaredMethod("isFriendlySyncLocked", java.util.UUID.class);
        isLockedMethod.setAccessible(true);
        boolean locked = (boolean) isLockedMethod.invoke(hook, java.util.UUID.randomUUID());
        Assertions.assertTrue(locked, "Should fail-safe to locked if FriendlySync is enabled but uninitialized");
    }

    @Test
    public void testFriendlySyncReadyCheck() throws Exception {
        McDataBridgeHook hook = new McDataBridgeHook(null);
        java.util.UUID playerUuid = java.util.UUID.randomUUID();

        MockApi mockApi = new MockApi();
        mockApi.targetUuid = playerUuid;

        java.lang.reflect.Field friendlySyncEnabledField = McDataBridgeHook.class.getDeclaredField("friendlySyncEnabled");
        friendlySyncEnabledField.setAccessible(true);
        friendlySyncEnabledField.setBoolean(hook, true);

        java.lang.reflect.Field enabledField = McDataBridgeHook.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(hook, true);

        java.lang.reflect.Field apiField = McDataBridgeHook.class.getDeclaredField("friendlySyncApi");
        apiField.setAccessible(true);
        apiField.set(hook, mockApi);

        java.lang.reflect.Field readyMethodField = McDataBridgeHook.class.getDeclaredField("friendlySyncIsPlayerReadyMethod");
        readyMethodField.setAccessible(true);
        readyMethodField.set(hook, MockApi.class.getMethod("isPlayerReady", java.util.UUID.class));

        // When player is ready, isLocked should be false (player is NOT locked)
        Assertions.assertFalse(hook.isLocked(playerUuid));

        // When player is NOT ready, isLocked should be true (player IS locked)
        Assertions.assertTrue(hook.isLocked(java.util.UUID.randomUUID()));
    }

    @Test
    public void testFriendlySyncJoinLockServiceFallback() throws Exception {
        McDataBridgeHook hook = new McDataBridgeHook(null);
        java.util.UUID lockedUuid = java.util.UUID.randomUUID();
        java.util.UUID unlockedUuid = java.util.UUID.randomUUID();

        MockLockService mockLockService = new MockLockService();
        mockLockService.lockedUuid = lockedUuid;

        java.lang.reflect.Field friendlySyncEnabledField = McDataBridgeHook.class.getDeclaredField("friendlySyncEnabled");
        friendlySyncEnabledField.setAccessible(true);
        friendlySyncEnabledField.setBoolean(hook, true);

        java.lang.reflect.Field enabledField = McDataBridgeHook.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(hook, true);

        java.lang.reflect.Field pluginField = McDataBridgeHook.class.getDeclaredField("friendlySyncPlugin");
        pluginField.setAccessible(true);
        Object proxyPlugin = java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.Plugin.class, TestPluginWithLock.class},
            (proxy, method, args) -> {
                if (method.getName().equals("isEnabled")) {
                    return true;
                }
                if (method.getName().equals("getJoinLockService")) {
                    return mockLockService;
                }
                return null;
            }
        );
        pluginField.set(hook, proxyPlugin);

        // Without friendlySyncApi, it falls back to joinLockService
        Assertions.assertTrue(hook.isLocked(lockedUuid));
        Assertions.assertFalse(hook.isLocked(unlockedUuid));
    }

    @Test
    public void testFriendlySyncPluginDisabledCleansUpHook() throws Exception {
        McDataBridgeHook hook = new McDataBridgeHook(null);
        java.lang.reflect.Field friendlySyncEnabledField = McDataBridgeHook.class.getDeclaredField("friendlySyncEnabled");
        friendlySyncEnabledField.setAccessible(true);
        friendlySyncEnabledField.setBoolean(hook, true);

        java.lang.reflect.Field pluginField = McDataBridgeHook.class.getDeclaredField("friendlySyncPlugin");
        pluginField.setAccessible(true);
        Object disabledPlugin = java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{org.bukkit.plugin.Plugin.class},
            (proxy, method, args) -> {
                if (method.getName().equals("isEnabled")) {
                    return false;
                }
                return null;
            }
        );
        pluginField.set(hook, disabledPlugin);

        java.lang.reflect.Method isLockedMethod = McDataBridgeHook.class.getDeclaredMethod("isFriendlySyncLocked", java.util.UUID.class);
        isLockedMethod.setAccessible(true);
        boolean locked = (boolean) isLockedMethod.invoke(hook, java.util.UUID.randomUUID());
        Assertions.assertFalse(locked, "When FriendlySync is disabled, player should not be locked");
        Assertions.assertFalse(friendlySyncEnabledField.getBoolean(hook), "friendlySyncEnabled should reset to false");
    }

    public static class MockApi {
        public java.util.UUID targetUuid;
        public boolean isPlayerReady(java.util.UUID uuid) {
            return uuid != null && uuid.equals(targetUuid);
        }
    }

    public static class MockLockService {
        public java.util.UUID lockedUuid;
        public boolean isLocked(java.util.UUID uuid) {
            return uuid != null && uuid.equals(lockedUuid);
        }
    }

    public interface TestPluginWithLock extends org.bukkit.plugin.Plugin {
        Object getJoinLockService();
    }
}
