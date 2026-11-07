package dev.lyy.friendlycore.common;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Utility for cross-version particle spawning.
 * Automatically resolves required particle data types (DustOptions, BlockData, ItemStack, etc.)
 * to prevent {@link IllegalArgumentException} across different Paper and Folia versions.
 */
public final class EffectUtil {

    private EffectUtil() {}

    /**
     * Attempts to resolve a particle by enum name or registry key, returning the fallback on failure.
     */
    public static Particle parseParticle(String name, Particle fallback) {
        if (name == null || name.isBlank()) return fallback;
        String clean = name.trim().toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(clean);
        } catch (Throwable ignored) {}

        try {
            if (Bukkit.getServer() != null) {
                Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
                if (particle != null) return particle;
            }
        } catch (Throwable ignored) {}

        return fallback;
    }

    /**
     * Supplies sensible default parameters for particles requiring specialized data types.
     */
    public static Object getDefaultParticleData(Particle particle, Location loc, double extra) {
        if (particle == null) return null;
        try {
            Class<?> dataType = particle.getDataType();
            if (dataType == null || dataType == Void.class) {
                return null;
            }

            if (dataType == Float.class) {
                return (float) (extra > 0 ? extra : 1.0f);
            }
            if (dataType == Integer.class) {
                return (int) (extra > 0 ? extra : 0);
            }
            if (dataType == Particle.DustOptions.class) {
                return new Particle.DustOptions(Color.fromRGB(0, 210, 255), 1.0f);
            }
            if (dataType == Particle.DustTransition.class) {
                return new Particle.DustTransition(Color.fromRGB(0, 210, 255), Color.fromRGB(0, 230, 118), 1.0f);
            }
            if (dataType == Color.class) {
                return Color.fromRGB(0, 210, 255);
            }

            if (Bukkit.getServer() != null) {
                if (dataType == org.bukkit.inventory.ItemStack.class) {
                    return new org.bukkit.inventory.ItemStack(Material.ENDER_PEARL);
                }
                if (dataType == org.bukkit.block.data.BlockData.class) {
                    return Material.PURPLE_CONCRETE.createBlockData();
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Spawns particles for a specific player, attaching appropriate data types where necessary.
     */
    public static void spawnParticle(Player player, Particle particle, Location loc, int count, double ox, double oy, double oz, double extra) {
        if (player == null || !player.isOnline() || particle == null || loc == null) return;
        try {
            Class<?> dataType = particle.getDataType();
            if (dataType == null || dataType == Void.class) {
                player.spawnParticle(particle, loc, count, ox, oy, oz, extra);
            } else {
                Object data = getDefaultParticleData(particle, loc, extra);
                if (data != null) {
                    player.spawnParticle(particle, loc, count, ox, oy, oz, extra, data);
                } else {
                    player.spawnParticle(particle, loc, count, ox, oy, oz, extra);
                }
            }
        } catch (Throwable t) {
            try {
                // Fallback to portal particles if the requested effect fails
                player.spawnParticle(Particle.PORTAL, loc, count, ox, oy, oz, extra);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Spawns particles for a specific player at explicit coordinate offsets.
     */
    public static void spawnParticle(Player player, Particle particle, double x, double y, double z, int count, double ox, double oy, double oz, double extra) {
        if (player == null || !player.isOnline() || particle == null) return;
        try {
            Class<?> dataType = particle.getDataType();
            if (dataType == null || dataType == Void.class) {
                player.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra);
            } else {
                Object data = getDefaultParticleData(particle, player.getLocation(), extra);
                if (data != null) {
                    player.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra, data);
                } else {
                    player.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra);
                }
            }
        } catch (Throwable t) {
            try {
                player.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, extra);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Spawns particles in the target world, attaching appropriate data types where necessary.
     */
    public static void spawnParticle(World world, Particle particle, Location loc, int count, double ox, double oy, double oz, double extra) {
        if (world == null || particle == null || loc == null) return;
        try {
            Class<?> dataType = particle.getDataType();
            if (dataType == null || dataType == Void.class) {
                world.spawnParticle(particle, loc, count, ox, oy, oz, extra);
            } else {
                Object data = getDefaultParticleData(particle, loc, extra);
                if (data != null) {
                    world.spawnParticle(particle, loc, count, ox, oy, oz, extra, data);
                } else {
                    world.spawnParticle(particle, loc, count, ox, oy, oz, extra);
                }
            }
        } catch (Throwable t) {
            try {
                world.spawnParticle(Particle.PORTAL, loc, count, ox, oy, oz, extra);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Spawns particles in the target world at explicit coordinates.
     */
    public static void spawnParticle(World world, Particle particle, double x, double y, double z, int count, double ox, double oy, double oz, double extra) {
        if (world == null || particle == null) return;
        try {
            Class<?> dataType = particle.getDataType();
            if (dataType == null || dataType == Void.class) {
                world.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra);
            } else {
                Object data = getDefaultParticleData(particle, null, extra);
                if (data != null) {
                    world.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra, data);
                } else {
                    world.spawnParticle(particle, x, y, z, count, ox, oy, oz, extra);
                }
            }
        } catch (Throwable t) {
            try {
                world.spawnParticle(Particle.PORTAL, x, y, z, count, ox, oy, oz, extra);
            } catch (Throwable ignored) {}
        }
    }
}
