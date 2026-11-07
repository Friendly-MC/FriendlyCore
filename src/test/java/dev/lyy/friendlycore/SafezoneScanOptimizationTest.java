package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.hooks.ProtectionHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates ProtectionHook fallbacks and combat safezone entry rules.
 */
class SafezoneScanOptimizationTest {

    @Test
    @DisplayName("ProtectionHook null safety and default fallbacks work reliably")
    void testProtectionHookNullSafety() {
        ProtectionHook hook = new ProtectionHook();

        // Null location must safely return true
        assertTrue(hook.isPvpAllowed(null, null, null));
        assertTrue(hook.isPvpAllowed(null, null, List.of("spawn", "safezone")));
        assertFalse(hook.isRegionBlocked(null, List.of("spawn")));
        assertFalse(hook.isRegionBlocked(null, null));
        assertFalse(hook.isInsideLandsClaim(null));
        assertFalse(hook.isCombatBarrierSafezone(null, null, List.of("spawn"), true, true));
    }

    @Test
    @DisplayName("Combat pearls are blocked when entering a safezone from wilderness")
    void testPearlBlockedEnteringSafezone() {
        assertTrue(dev.lyy.friendlycore.modules.pvp.DisplayBarrierManager.shouldBlockSafezoneEntry(true, true, false, true));
        assertFalse(dev.lyy.friendlycore.modules.pvp.DisplayBarrierManager.shouldBlockSafezoneEntry(true, true, true, true));
        assertFalse(dev.lyy.friendlycore.modules.pvp.DisplayBarrierManager.shouldBlockSafezoneEntry(true, true, false, false));
        assertFalse(dev.lyy.friendlycore.modules.pvp.DisplayBarrierManager.shouldBlockSafezoneEntry(false, true, false, true));
        assertFalse(dev.lyy.friendlycore.modules.pvp.DisplayBarrierManager.shouldBlockSafezoneEntry(true, false, false, true));
    }

    @Test
    @DisplayName("ProtectionHook hook detection initializes without errors")
    void testProtectionHookInitialization() {
        ProtectionHook hook = new ProtectionHook();
        assertNotNull(hook);
        // In unit test environment without WorldGuard/Lands jars, both should be false gracefully
        assertFalse(hook.isWorldGuardEnabled());
        assertFalse(hook.isLandsEnabled());
        assertNull(hook.getLandsArea(null));
        assertTrue(hook.canPvpFight(null, null, null));
    }

}
