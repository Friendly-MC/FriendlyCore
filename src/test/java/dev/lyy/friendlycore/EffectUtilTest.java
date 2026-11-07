package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.EffectUtil;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EffectUtilTest {

    @Test
    @DisplayName("parseParticle should correctly parse known and fallback particles")
    public void testParseParticle() {
        Particle p1 = EffectUtil.parseParticle("PORTAL", Particle.DRAGON_BREATH);
        assertEquals(Particle.PORTAL, p1);

        Particle p2 = EffectUtil.parseParticle("non_existent_particle_xyz", Particle.PORTAL);
        assertEquals(Particle.PORTAL, p2);

        Particle p3 = EffectUtil.parseParticle(null, Particle.DRAGON_BREATH);
        assertEquals(Particle.DRAGON_BREATH, p3);

        Particle p4 = EffectUtil.parseParticle("   ", Particle.DRAGON_BREATH);
        assertEquals(Particle.DRAGON_BREATH, p4);
    }

    @Test
    @DisplayName("getDefaultParticleData should handle data classes safely")
    public void testGetDefaultParticleData() {
        Object portalData = EffectUtil.getDefaultParticleData(Particle.PORTAL, null, 0.0);
        assertNull(portalData);

        // Test with all Particle enum values
        for (Particle particle : Particle.values()) {
            Class<?> dataType = particle.getDataType();
            Object data = EffectUtil.getDefaultParticleData(particle, null, 0.5);
            if (dataType == Void.class) {
                assertNull(data);
            } else if (dataType == Float.class) {
                assertNotNull(data);
                assertTrue(data instanceof Float);
            } else if (dataType == Integer.class) {
                assertNotNull(data);
                assertTrue(data instanceof Integer);
            } else if (dataType == Particle.DustOptions.class) {
                assertNotNull(data);
                assertTrue(data instanceof Particle.DustOptions);
            }
        }
    }

    @Test
    @DisplayName("spawnParticle should handle null entities gracefully without throwing exceptions")
    public void testSafeNullCalls() {
        assertDoesNotThrow(() -> EffectUtil.spawnParticle((Player) null, Particle.PORTAL, null, 1, 0, 0, 0, 0));
        assertDoesNotThrow(() -> EffectUtil.spawnParticle((Player) null, (Particle) null, 0, 0, 0, 1, 0, 0, 0, 0));
        assertDoesNotThrow(() -> EffectUtil.spawnParticle((World) null, Particle.PORTAL, null, 1, 0, 0, 0, 0));
        assertDoesNotThrow(() -> EffectUtil.spawnParticle((World) null, (Particle) null, 0, 0, 0, 1, 0, 0, 0, 0));
    }
}
