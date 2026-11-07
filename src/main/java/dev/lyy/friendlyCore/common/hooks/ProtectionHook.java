package dev.lyy.friendlycore.common.hooks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Universal protection hook supporting WorldGuard, Lands, and event-based protection plugins.
 * Uses cached reflection handles to ensure zero hard runtime coupling.
 */
public class ProtectionHook {

    private boolean worldGuardEnabled = false;
    private boolean landsEnabled = false;

    private Object landsIntegration = null;
    private Method landsGetAreaMethod = null;
    private Method landsGetAreaByLocMethod = null;
    private Method landsGetUnloadedAreaMethod = null;
    private Method landsGetAreaUnloadedMethod = null;
    private Method landsGetLandMethod = null;
    private Method landsGetLandByChunkMethod = null;
    private Method landsGetLandByUnloadedChunkMethod = null;
    private Method landsGetLandWorldMethod = null;
    private Method landsGetWorldMethod = null;
    private Method landsLandGetDefaultAreaMethod = null;
    private Method landsLandWorldGetAreaMethod = null;
    private Method landsLandWorldGetAreaXyzMethod = null;
    private Method landsLandWorldGetAreaByUnloadedMethod = null;
    private Method landsLandWorldGetLandByChunkMethod = null;
    private Method landsLandWorldGetLandByUnloadedChunkMethod = null;
    private Method landsCanPvPMethod = null;
    private Method landsAreaHasRoleFlagUuidMethod = null;
    private Method landsAreaHasNaturalFlagMethod = null;
    private Method landsAreaGetVisitorRoleMethod = null;
    private Method landsAreaGetNameMethod = null;
    private Method landsAreaGetLandMethod = null;
    private Method landsLandGetNameMethod = null;
    private Method landsAreaIsBannedMethod = null;
    private Method landsRoleHasFlagMethod = null;
    private Class<?> landsRoleFlagClass = null;
    private Class<?> landsNaturalFlagClass = null;
    private Class<?> landsParentFlagClass = null;
    private Object landsAttackPlayerFlag = null;
    private Object landsBlockBreakFlag = null;
    private Object landsPeacefulFlag = null;
    private Object landsLandEnterFlag = null;
    private Object landsNoDamageFlag = null;

    private Object wgRegionContainer = null;
    private Object wgCachedQuery = null;
    private Method wgCreateQueryMethod = null;
    private Method wgQueryStateMethod = null;
    private Method wgGetApplicableRegionsMethod = null;
    private Method weAdaptLocationMethod = null;
    private Method wgInstMethod = null;
    private Method wgWrapPlayerMethod = null;
    private Object wgPvpFlagsArray = null;

    // Short-lived coordinate cache: worldHash ^ posHash ^ playerHash ^ safezoneHash -> allowed
    private final java.util.Map<Long, CachedPvpState> pvpCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static class CachedPvpState {
        final boolean allowed;
        final long expiry;

        CachedPvpState(boolean allowed, long expiry) {
            this.allowed = allowed;
            this.expiry = expiry;
        }
    }

    public ProtectionHook() {
        checkHooks();
    }

    public void checkHooks() {
        pvpCache.clear();
        try {
            if (Bukkit.getServer() != null && Bukkit.getPluginManager() != null) {
                this.worldGuardEnabled = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
                this.landsEnabled = Bukkit.getPluginManager().isPluginEnabled("Lands");
            } else {
                this.worldGuardEnabled = false;
                this.landsEnabled = false;
            }
        } catch (Throwable ignored) {
            this.worldGuardEnabled = false;
            this.landsEnabled = false;
        }

        if (this.worldGuardEnabled) {
            try {
                Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Object wgInst = wgClass.getMethod("getInstance").invoke(null);
                Object platform = wgClass.getMethod("getPlatform").invoke(wgInst);
                this.wgRegionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                this.weAdaptLocationMethod = bukkitAdapter.getMethod("adapt", Location.class);

                Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
                Object pvpFlag = flagsClass.getField("PVP").get(null);

                Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
                this.wgPvpFlagsArray = Array.newInstance(stateFlagClass, 1);
                Array.set(this.wgPvpFlagsArray, 0, pvpFlag);

                Class<?> regionContainerClass = this.wgRegionContainer.getClass();
                this.wgCreateQueryMethod = regionContainerClass.getMethod("createQuery");
                this.wgCachedQuery = this.wgCreateQueryMethod.invoke(this.wgRegionContainer);

                Class<?> weLocationClass = Class.forName("com.sk89q.worldedit.util.Location");
                Class<?> regionAssociableClass = Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable");
                Class<?> flagsArrayClass = this.wgPvpFlagsArray.getClass();

                Class<?> queryClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery");
                this.wgQueryStateMethod = queryClass.getMethod("queryState", weLocationClass, regionAssociableClass, flagsArrayClass);
                this.wgGetApplicableRegionsMethod = queryClass.getMethod("getApplicableRegions", weLocationClass);

                Class<?> wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
                this.wgInstMethod = wgPluginClass.getMethod("inst");
                this.wgWrapPlayerMethod = wgPluginClass.getMethod("wrapPlayer", Player.class);
            } catch (Throwable ignored) {
                // If reflection setup fails partially, methods remain null and fallback safely
            }
        }

        if (this.landsEnabled) {
            try {
                Class<?> landsClass = Class.forName("me.angeschossen.lands.api.LandsIntegration");
                Object plugin = (Bukkit.getServer() != null && Bukkit.getPluginManager() != null)
                        ? Bukkit.getPluginManager().getPlugin("FriendlyCore") : null;
                
                Method ofMethod = landsClass.getMethod("of", org.bukkit.plugin.Plugin.class);
                this.landsIntegration = ofMethod.invoke(null, plugin);

                try {
                    this.landsGetAreaMethod = landsClass.getMethod("getArea", Location.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetAreaByLocMethod = landsClass.getMethod("getAreaByLoc", Location.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetUnloadedAreaMethod = landsClass.getMethod("getUnloadedArea", Location.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetAreaUnloadedMethod = landsClass.getMethod("getAreaUnloaded", Location.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetLandMethod = landsClass.getMethod("getLand", Location.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetLandByChunkMethod = landsClass.getMethod("getLandByChunk", org.bukkit.World.class, int.class, int.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetLandByUnloadedChunkMethod = landsClass.getMethod("getLandByUnloadedChunk", org.bukkit.World.class, int.class, int.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetLandWorldMethod = landsClass.getMethod("getLandWorld", org.bukkit.World.class);
                } catch (Throwable ignored) {}
                try {
                    this.landsGetWorldMethod = landsClass.getMethod("getWorld", org.bukkit.World.class);
                } catch (Throwable ignored) {}

                try {
                    this.landsCanPvPMethod = landsClass.getMethod("canPvP", Player.class, Player.class, Location.class, boolean.class, boolean.class);
                } catch (Throwable ignored) {}

                try {
                    Class<?> landClass = Class.forName("me.angeschossen.lands.api.land.Land");
                    this.landsLandGetDefaultAreaMethod = landClass.getMethod("getDefaultArea");
                } catch (Throwable ignored) {}

                try {
                    Class<?> landWorldClass = Class.forName("me.angeschossen.lands.api.land.LandWorld");
                    try {
                        this.landsLandWorldGetAreaMethod = landWorldClass.getMethod("getArea", Location.class);
                    } catch (Throwable ignored) {}
                    try {
                        this.landsLandWorldGetAreaXyzMethod = landWorldClass.getMethod("getArea", int.class, int.class, int.class);
                    } catch (Throwable ignored) {}
                    try {
                        this.landsLandWorldGetAreaByUnloadedMethod = landWorldClass.getMethod("getAreaByUnloaded", int.class, int.class, int.class);
                    } catch (Throwable ignored) {}
                    try {
                        this.landsLandWorldGetLandByChunkMethod = landWorldClass.getMethod("getLandByChunk", int.class, int.class);
                    } catch (Throwable ignored) {}
                    try {
                        this.landsLandWorldGetLandByUnloadedChunkMethod = landWorldClass.getMethod("getLandByUnloadedChunk", int.class, int.class);
                    } catch (Throwable ignored) {}
                } catch (Throwable ignored) {}

                try {
                    this.landsRoleFlagClass = Class.forName("me.angeschossen.lands.api.flags.type.RoleFlag");
                } catch (ClassNotFoundException e) {
                    try {
                        this.landsRoleFlagClass = Class.forName("me.angeschossen.lands.api.flags.types.RoleFlag");
                    } catch (Throwable ignored) {}
                }

                try {
                    this.landsNaturalFlagClass = Class.forName("me.angeschossen.lands.api.flags.type.NaturalFlag");
                } catch (ClassNotFoundException e) {
                    try {
                        this.landsNaturalFlagClass = Class.forName("me.angeschossen.lands.api.flags.types.LandFlag");
                    } catch (Throwable ignored) {}
                }

                try {
                    this.landsParentFlagClass = Class.forName("me.angeschossen.lands.api.flags.type.parent.Flag");
                } catch (Throwable ignored) {
                    this.landsParentFlagClass = this.landsRoleFlagClass;
                }

                resolveLandsFlags();
            } catch (Throwable t) {
                this.landsEnabled = false;
                this.landsIntegration = null;
            }
        }
    }

    public Object getLandsArea(Location location) {
        if (!landsEnabled || landsIntegration == null || location == null) return null;
        try {
            if (landsGetAreaMethod != null) {
                Object area = landsGetAreaMethod.invoke(landsIntegration, location);
                if (area != null) return area;
            }
            if (landsGetAreaByLocMethod != null) {
                Object area = landsGetAreaByLocMethod.invoke(landsIntegration, location);
                if (area != null) return area;
            }
            if (landsGetUnloadedAreaMethod != null) {
                Object area = landsGetUnloadedAreaMethod.invoke(landsIntegration, location);
                if (area != null) return area;
            }
            if (landsGetAreaUnloadedMethod != null) {
                Object area = landsGetAreaUnloadedMethod.invoke(landsIntegration, location);
                if (area != null) return area;
            }
            Object landWorld = getLandsWorld(location.getWorld());
            if (landWorld != null) {
                int x = location.getBlockX();
                int y = location.getBlockY();
                int z = location.getBlockZ();
                if (landsLandWorldGetAreaMethod != null) {
                    Object area = landsLandWorldGetAreaMethod.invoke(landWorld, location);
                    if (area != null) return area;
                }
                if (landsLandWorldGetAreaXyzMethod != null) {
                    Object area = landsLandWorldGetAreaXyzMethod.invoke(landWorld, x, y, z);
                    if (area != null) return area;
                }
                if (landsLandWorldGetAreaByUnloadedMethod != null) {
                    Object area = landsLandWorldGetAreaByUnloadedMethod.invoke(landWorld, x, y, z);
                    if (area != null) return area;
                }
            }
            if (landsGetLandMethod != null) {
                Object land = landsGetLandMethod.invoke(landsIntegration, location);
                if (land != null) {
                    if (landsLandGetDefaultAreaMethod != null) {
                        Object area = landsLandGetDefaultAreaMethod.invoke(land);
                        if (area != null) return area;
                    }
                    return land;
                }
            }
            Object land = getLandsLandForChunk(location);
            if (land != null) {
                if (landsLandGetDefaultAreaMethod != null) {
                    Object area = landsLandGetDefaultAreaMethod.invoke(land);
                    if (area != null) return area;
                }
                return land;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object getLandsWorld(org.bukkit.World world) {
        if (world == null || landsIntegration == null) {
            return null;
        }
        try {
            if (landsGetWorldMethod != null) {
                Object landWorld = landsGetWorldMethod.invoke(landsIntegration, world);
                if (landWorld != null) {
                    return landWorld;
                }
            }
            if (landsGetLandWorldMethod != null) {
                return landsGetLandWorldMethod.invoke(landsIntegration, world);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object getLandsLandForChunk(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        try {
            if (landsGetLandByUnloadedChunkMethod != null) {
                Object land = landsGetLandByUnloadedChunkMethod.invoke(landsIntegration, location.getWorld(), chunkX, chunkZ);
                if (land != null) {
                    return land;
                }
            }
            if (landsGetLandByChunkMethod != null) {
                Object land = landsGetLandByChunkMethod.invoke(landsIntegration, location.getWorld(), chunkX, chunkZ);
                if (land != null) {
                    return land;
                }
            }
            Object landWorld = getLandsWorld(location.getWorld());
            if (landWorld != null) {
                if (landsLandWorldGetLandByUnloadedChunkMethod != null) {
                    Object land = landsLandWorldGetLandByUnloadedChunkMethod.invoke(landWorld, chunkX, chunkZ);
                    if (land != null) {
                        return land;
                    }
                }
                if (landsLandWorldGetLandByChunkMethod != null) {
                    Object land = landsLandWorldGetLandByChunkMethod.invoke(landWorld, chunkX, chunkZ);
                    if (land != null) {
                        return land;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private synchronized void resolveLandsFlags() {
        if (!this.landsEnabled || this.landsIntegration == null) return;

        try {
            Method getFlagRegistryMethod = this.landsIntegration.getClass().getMethod("getFlagRegistry");
            Object flagRegistry = getFlagRegistryMethod.invoke(this.landsIntegration);
            if (flagRegistry != null) {
                Method getRoleMethod = null;
                try {
                    getRoleMethod = flagRegistry.getClass().getMethod("getRole", String.class);
                } catch (Throwable ignored) {}

                Method getNaturalMethod = null;
                try {
                    getNaturalMethod = flagRegistry.getClass().getMethod("getNatural", String.class);
                } catch (Throwable ignored) {}

                Method getGenericMethod = null;
                try {
                    getGenericMethod = flagRegistry.getClass().getMethod("get", String.class);
                } catch (Throwable ignored) {}

                if (this.landsAttackPlayerFlag == null) {
                    if (getRoleMethod != null) {
                        this.landsAttackPlayerFlag = getRoleMethod.invoke(flagRegistry, "ATTACK_PLAYER");
                        if (this.landsAttackPlayerFlag == null) {
                            this.landsAttackPlayerFlag = getRoleMethod.invoke(flagRegistry, "attack_player");
                        }
                    }
                    if (this.landsAttackPlayerFlag == null && getGenericMethod != null) {
                        this.landsAttackPlayerFlag = getGenericMethod.invoke(flagRegistry, "ATTACK_PLAYER");
                    }
                }

                if (this.landsBlockBreakFlag == null) {
                    if (getRoleMethod != null) {
                        this.landsBlockBreakFlag = getRoleMethod.invoke(flagRegistry, "BLOCK_BREAK");
                        if (this.landsBlockBreakFlag == null) {
                            this.landsBlockBreakFlag = getRoleMethod.invoke(flagRegistry, "block_break");
                        }
                    }
                    if (this.landsBlockBreakFlag == null && getGenericMethod != null) {
                        this.landsBlockBreakFlag = getGenericMethod.invoke(flagRegistry, "BLOCK_BREAK");
                    }
                }

                if (this.landsLandEnterFlag == null) {
                    if (getRoleMethod != null) {
                        this.landsLandEnterFlag = getRoleMethod.invoke(flagRegistry, "LAND_ENTER");
                        if (this.landsLandEnterFlag == null) {
                            this.landsLandEnterFlag = getRoleMethod.invoke(flagRegistry, "land_enter");
                        }
                    }
                    if (this.landsLandEnterFlag == null && getGenericMethod != null) {
                        this.landsLandEnterFlag = getGenericMethod.invoke(flagRegistry, "LAND_ENTER");
                    }
                }

                if (this.landsPeacefulFlag == null) {
                    if (getNaturalMethod != null) {
                        this.landsPeacefulFlag = getNaturalMethod.invoke(flagRegistry, "PEACEFUL");
                        if (this.landsPeacefulFlag == null) {
                            this.landsPeacefulFlag = getNaturalMethod.invoke(flagRegistry, "peaceful");
                        }
                    }
                    if (this.landsPeacefulFlag == null && getGenericMethod != null) {
                        this.landsPeacefulFlag = getGenericMethod.invoke(flagRegistry, "PEACEFUL");
                    }
                }

                if (this.landsNoDamageFlag == null) {
                    if (getRoleMethod != null) {
                        this.landsNoDamageFlag = getRoleMethod.invoke(flagRegistry, "NO_DAMAGE");
                        if (this.landsNoDamageFlag == null) {
                            this.landsNoDamageFlag = getRoleMethod.invoke(flagRegistry, "no_damage");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (this.landsAttackPlayerFlag == null || this.landsBlockBreakFlag == null || this.landsPeacefulFlag == null) {
            try {
                Class<?> flagsClass = Class.forName("me.angeschossen.lands.api.flags.type.Flags");
                if (this.landsAttackPlayerFlag == null) {
                    try {
                        this.landsAttackPlayerFlag = flagsClass.getField("ATTACK_PLAYER").get(null);
                    } catch (Throwable ignored) {}
                }
                if (this.landsBlockBreakFlag == null) {
                    try {
                        this.landsBlockBreakFlag = flagsClass.getField("BLOCK_BREAK").get(null);
                    } catch (Throwable ignored) {}
                }
                if (this.landsPeacefulFlag == null) {
                    try {
                        this.landsPeacefulFlag = flagsClass.getField("PEACEFUL").get(null);
                    } catch (Throwable ignored) {}
                }
                if (this.landsLandEnterFlag == null) {
                    try {
                        this.landsLandEnterFlag = flagsClass.getField("LAND_ENTER").get(null);
                    } catch (Throwable ignored) {}
                }
                if (this.landsNoDamageFlag == null) {
                    try {
                        this.landsNoDamageFlag = flagsClass.getField("NO_DAMAGE").get(null);
                    } catch (Throwable ignored) {}
                }

                if (this.landsAttackPlayerFlag == null || this.landsBlockBreakFlag == null) {
                    Method getFlagMethod = flagsClass.getMethod("get", String.class);
                    if (this.landsAttackPlayerFlag == null) {
                        this.landsAttackPlayerFlag = getFlagMethod.invoke(null, "ATTACK_PLAYER");
                    }
                    if (this.landsBlockBreakFlag == null) {
                        this.landsBlockBreakFlag = getFlagMethod.invoke(null, "BLOCK_BREAK");
                    }
                    if (this.landsPeacefulFlag == null) {
                        this.landsPeacefulFlag = getFlagMethod.invoke(null, "PEACEFUL");
                    }
                    if (this.landsLandEnterFlag == null) {
                        this.landsLandEnterFlag = getFlagMethod.invoke(null, "LAND_ENTER");
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: legacy Flags class
        if (this.landsAttackPlayerFlag == null || this.landsBlockBreakFlag == null) {
            try {
                Class<?> legacyFlagsClass = Class.forName("me.angeschossen.lands.api.flags.Flags");
                if (this.landsAttackPlayerFlag == null) {
                    this.landsAttackPlayerFlag = legacyFlagsClass.getField("ATTACK_PLAYER").get(null);
                }
                if (this.landsBlockBreakFlag == null) {
                    this.landsBlockBreakFlag = legacyFlagsClass.getField("BLOCK_BREAK").get(null);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static final ThreadLocal<Boolean> IS_SIMULATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean isSimulating() {
        return Boolean.TRUE.equals(IS_SIMULATING.get());
    }

    /**
     * Checks if a player has permission to break a block at the given location.
     */
    public boolean canBreak(Player player, Block block) {
        if (player == null || block == null) return false;
        if (player.isOp()) return true;

        if (landsEnabled && landsIntegration != null) {
            if (landsBlockBreakFlag == null) {
                resolveLandsFlags();
            }
            if (landsBlockBreakFlag != null && landsRoleFlagClass != null) {
                try {
                    Object area = getLandsArea(block.getLocation());
                    if (area != null) {
                        if (landsAreaHasRoleFlagUuidMethod == null) {
                            try {
                                landsAreaHasRoleFlagUuidMethod = area.getClass().getMethod("hasRoleFlag", UUID.class, landsRoleFlagClass);
                            } catch (NoSuchMethodException e) {
                                landsAreaHasRoleFlagUuidMethod = area.getClass().getMethod("hasRoleFlag", UUID.class, landsBlockBreakFlag.getClass());
                            }
                        }
                        Boolean allowed = (Boolean) landsAreaHasRoleFlagUuidMethod.invoke(area, player.getUniqueId(), landsBlockBreakFlag);
                        if (allowed != null && !allowed) {
                            return false;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Fallback: simulate BlockBreakEvent for universal plugin compatibility (WorldGuard, Towny, PlotSquared, GriefPrevention)
        try {
            IS_SIMULATING.set(Boolean.TRUE);
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        } finally {
            IS_SIMULATING.set(Boolean.FALSE);
        }
    }

    /**
     * Checks if a location is inside any blacklisted WorldGuard regions or Lands claims.
     */
    public boolean isRegionBlocked(Location location, List<String> blacklistedRegions) {
        if (location == null || blacklistedRegions == null || blacklistedRegions.isEmpty()) {
            return false;
        }

        if (worldGuardEnabled && weAdaptLocationMethod != null && wgGetApplicableRegionsMethod != null) {
            try {
                Object query = wgCachedQuery != null ? wgCachedQuery : (wgCreateQueryMethod != null && wgRegionContainer != null ? wgCreateQueryMethod.invoke(wgRegionContainer) : null);
                if (query != null) {
                    Object weLocation = weAdaptLocationMethod.invoke(null, location);
                    Object applicableSet = wgGetApplicableRegionsMethod.invoke(query, weLocation);

                    Iterable<?> regions = (Iterable<?>) applicableSet.getClass().getMethod("getRegions").invoke(applicableSet);
                    if (regions != null) {
                        for (Object reg : regions) {
                            String id = (String) reg.getClass().getMethod("getId").invoke(reg);
                            for (String blacklisted : blacklistedRegions) {
                                if (id.equalsIgnoreCase(blacklisted)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (landsEnabled && landsIntegration != null) {
            try {
                Object area = getLandsArea(location);
                if (area != null) {
                    if (landsAreaGetNameMethod == null) {
                        landsAreaGetNameMethod = area.getClass().getMethod("getName");
                    }
                    String areaName = (String) landsAreaGetNameMethod.invoke(area);
                    if (areaName != null) {
                        for (String blacklisted : blacklistedRegions) {
                            if (areaName.equalsIgnoreCase(blacklisted)) {
                                return true;
                            }
                        }
                    }

                    if (landsAreaGetLandMethod == null) {
                        landsAreaGetLandMethod = area.getClass().getMethod("getLand");
                    }
                    Object land = landsAreaGetLandMethod.invoke(area);
                    if (land != null) {
                        if (landsLandGetNameMethod == null) {
                            landsLandGetNameMethod = land.getClass().getMethod("getName");
                        }
                        String landName = (String) landsLandGetNameMethod.invoke(land);
                        if (landName != null) {
                            for (String blacklisted : blacklistedRegions) {
                                if (landName.equalsIgnoreCase(blacklisted)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return false;
    }

    /**
     * True when this location is inside a claimed Lands land (not wilderness).
     * Uses unloaded-chunk lookups so ender-pearl destinations still resolve.
     */
    public boolean isInsideLandsClaim(Location location) {
        if (!landsEnabled || landsIntegration == null || location == null || location.getWorld() == null) {
            return false;
        }
        try {
            Object land = getLandsLandForChunk(location);
            if (land != null) {
                return true;
            }
            if (landsGetLandMethod != null) {
                land = landsGetLandMethod.invoke(landsIntegration, location);
                if (land != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        Object area = getLandsArea(location);
        if (area == null || isWildernessArea(area)) {
            return false;
        }
        return true;
    }

    private boolean isWildernessArea(Object area) {
        if (area == null) {
            return true;
        }
        try {
            Method method = area.getClass().getMethod("isWilderness");
            return Boolean.TRUE.equals(method.invoke(area));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object resolveLandFromArea(Object area) throws Exception {
        if (area == null) {
            return null;
        }
        if (landsAreaGetLandMethod != null) {
            Object land = landsAreaGetLandMethod.invoke(area);
            if (land != null) {
                return land;
            }
        }
        for (String methodName : new String[] {"getLand", "getOwningLand", "getOwnerLand"}) {
            try {
                Object land = area.getClass().getMethod(methodName).invoke(area);
                if (land != null) {
                    return land;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * Combat-tag forcefield check. Unlike {@link #isPvpAllowed}, Lands claims are treated
     * as safezones when {@code treatLandsClaimsAsSafe} is true so tagged players cannot
     * run from wilderness into a land even if that land allows visitor PvP.
     */
    public boolean isCombatBarrierSafezone(Location location, Player player, List<String> customSafezones,
                                          boolean treatLandsClaimsAsSafe, boolean worldGuardPvpDeny) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (customSafezones != null && !customSafezones.isEmpty() && isRegionBlocked(location, customSafezones)) {
            return true;
        }
        if (treatLandsClaimsAsSafe && isInsideLandsClaim(location)) {
            return true;
        }
        if (worldGuardPvpDeny && isWorldGuardPvpDenied(location, player)) {
            return true;
        }
        if (!treatLandsClaimsAsSafe && isLandsPvpDenied(location, player)) {
            return true;
        }
        return false;
    }

    private boolean isWorldGuardPvpDenied(Location location, Player player) {
        if (!worldGuardEnabled || wgPvpFlagsArray == null || weAdaptLocationMethod == null || wgQueryStateMethod == null) {
            return false;
        }
        try {
            Object query = wgCachedQuery != null ? wgCachedQuery : (wgCreateQueryMethod != null && wgRegionContainer != null ? wgCreateQueryMethod.invoke(wgRegionContainer) : null);
            if (query == null) {
                return false;
            }
            Object weLocation = weAdaptLocationMethod.invoke(null, location);
            Object localPlayer = null;
            if (player != null && wgInstMethod != null && wgWrapPlayerMethod != null) {
                Object wgPluginInst = wgInstMethod.invoke(null);
                localPlayer = wgWrapPlayerMethod.invoke(wgPluginInst, player);
            }
            Object state = wgQueryStateMethod.invoke(query, weLocation, localPlayer, wgPvpFlagsArray);
            return state != null && state.toString().equalsIgnoreCase("DENY");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isLandsPvpDenied(Location location, Player player) {
        if (!landsEnabled || landsIntegration == null) {
            return false;
        }
        return !computeLandsPvpAllowed(location, player);
    }

    /**
     * Checks if PvP is permitted at a specific location for WorldGuard, Lands, and custom safezones.
     * Returns false if location is a safezone (PVP DENIED).
     */
    public boolean isPvpAllowed(Location location, Player player, List<String> customSafezones) {
        if (location == null || location.getWorld() == null) return true;

        long now = System.currentTimeMillis();
        long worldHash = location.getWorld().getUID().getMostSignificantBits();
        long playerHash = player != null ? player.getUniqueId().getMostSignificantBits() : 0L;
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();
        long posHash = (((long) (bx & 0x3FFFFFF)) << 38) | (((long) (bz & 0x3FFFFFF)) << 12) | ((long) (by & 0xFFF));
        long safezoneHash = (customSafezones != null && !customSafezones.isEmpty()) ? ((long) customSafezones.hashCode() * 31L) : 0L;
        long cacheKey = worldHash ^ posHash ^ playerHash ^ safezoneHash;

        CachedPvpState cached = pvpCache.get(cacheKey);
        if (cached != null && now < cached.expiry) {
            return cached.allowed;
        }

        boolean allowed = computePvpAllowed(location, player, customSafezones);

        if (pvpCache.size() > 10000) {
            long cutoff = now;
            pvpCache.entrySet().removeIf(e -> e.getValue().expiry < cutoff);
            if (pvpCache.size() > 20000) {
                pvpCache.clear();
            }
        }
        pvpCache.put(cacheKey, new CachedPvpState(allowed, now + 2000L));

        return allowed;
    }

    private boolean computePvpAllowed(Location location, Player player, List<String> customSafezones) {
        if (customSafezones != null && !customSafezones.isEmpty() && isRegionBlocked(location, customSafezones)) {
            return false;
        }
        if (isWorldGuardPvpDenied(location, player)) {
            return false;
        }
        return computeLandsPvpAllowed(location, player);
    }

    private boolean computeLandsPvpAllowed(Location location, Player player) {
        if (!landsEnabled || landsIntegration == null) {
            return true;
        }
        if (landsAttackPlayerFlag == null || landsPeacefulFlag == null) {
            resolveLandsFlags();
        }
        try {
            Object area = getLandsArea(location);
            if (area == null) {
                return true;
            }
            if (player != null) {
                if (landsAreaIsBannedMethod == null) {
                    try {
                        landsAreaIsBannedMethod = area.getClass().getMethod("isBanned", UUID.class);
                    } catch (Throwable ignored) {}
                }
                if (landsAreaIsBannedMethod != null) {
                    Boolean isBanned = (Boolean) landsAreaIsBannedMethod.invoke(area, player.getUniqueId());
                    if (Boolean.TRUE.equals(isBanned)) {
                        return false;
                    }
                }
            }

            if (landsPeacefulFlag != null && landsNaturalFlagClass != null) {
                if (landsAreaHasNaturalFlagMethod == null) {
                    try {
                        landsAreaHasNaturalFlagMethod = area.getClass().getMethod("hasNaturalFlag", landsNaturalFlagClass);
                    } catch (Throwable ignored) {}
                }
                if (landsAreaHasNaturalFlagMethod != null) {
                    Boolean isPeaceful = (Boolean) landsAreaHasNaturalFlagMethod.invoke(area, landsPeacefulFlag);
                    if (Boolean.TRUE.equals(isPeaceful)) {
                        return false;
                    }
                }
            }

            if (player != null && landsNoDamageFlag != null) {
                if (landsAreaHasRoleFlagUuidMethod == null && landsRoleFlagClass != null) {
                    try {
                        landsAreaHasRoleFlagUuidMethod = area.getClass().getMethod("hasRoleFlag", UUID.class, landsRoleFlagClass);
                    } catch (Throwable ignored) {}
                }
                if (landsAreaHasRoleFlagUuidMethod != null) {
                    Boolean noDamage = (Boolean) landsAreaHasRoleFlagUuidMethod.invoke(area, player.getUniqueId(), landsNoDamageFlag);
                    if (Boolean.TRUE.equals(noDamage)) {
                        return false;
                    }
                }
            }

            if (landsAttackPlayerFlag != null) {
                if (player != null) {
                    if (landsAreaHasRoleFlagUuidMethod == null && landsRoleFlagClass != null) {
                        try {
                            landsAreaHasRoleFlagUuidMethod = area.getClass().getMethod("hasRoleFlag", UUID.class, landsRoleFlagClass);
                        } catch (Throwable ignored) {}
                    }
                    if (landsAreaHasRoleFlagUuidMethod != null) {
                        try {
                            Boolean playerCanPvp = (Boolean) landsAreaHasRoleFlagUuidMethod.invoke(area, player.getUniqueId(), landsAttackPlayerFlag);
                            if (Boolean.TRUE.equals(playerCanPvp)) {
                                return true;
                            }
                            if (Boolean.FALSE.equals(playerCanPvp)) {
                                return false;
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                if (landsAreaGetVisitorRoleMethod == null) {
                    try {
                        landsAreaGetVisitorRoleMethod = area.getClass().getMethod("getVisitorRole");
                    } catch (Throwable ignored) {}
                }
                if (landsAreaGetVisitorRoleMethod != null) {
                    Object visitorRole = landsAreaGetVisitorRoleMethod.invoke(area);
                    if (visitorRole != null) {
                        if (landsRoleHasFlagMethod == null) {
                            try {
                                landsRoleHasFlagMethod = visitorRole.getClass().getMethod("hasFlag", landsRoleFlagClass != null ? landsRoleFlagClass : landsAttackPlayerFlag.getClass());
                            } catch (NoSuchMethodException e) {
                                if (landsParentFlagClass != null) {
                                    try {
                                        landsRoleHasFlagMethod = visitorRole.getClass().getMethod("hasFlag", landsParentFlagClass);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                        if (landsRoleHasFlagMethod != null) {
                            Boolean visitorCanPvp = (Boolean) landsRoleHasFlagMethod.invoke(visitorRole, landsAttackPlayerFlag);
                            if (Boolean.FALSE.equals(visitorCanPvp)) {
                                return false;
                            }
                            if (Boolean.TRUE.equals(visitorCanPvp)) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * Lands land/area spawn if the player is standing in a claimed land.
     * Used so a death-then-rejoin on the overworld lands at the land spawn, not the corpse.
     */
    public Location getLandsSpawn(Player player) {
        if (player == null) {
            return null;
        }
        return getLandsSpawn(player.getLocation());
    }

    public Location getLandsSpawn(Location location) {
        if (!landsEnabled || landsIntegration == null || location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Object land = getLandsLandForChunk(location);
            if (land == null && landsGetLandMethod != null) {
                land = landsGetLandMethod.invoke(landsIntegration, location);
            }
            Location spawn = invokeSpawnLocation(land);
            if (spawn != null) {
                return spawn;
            }
            Object area = getLandsArea(location);
            if (area != null && !isWildernessArea(area)) {
                spawn = invokeSpawnLocation(area);
                if (spawn != null) {
                    return spawn;
                }
                spawn = invokeSpawnLocation(resolveLandFromArea(area));
                if (spawn != null) {
                    return spawn;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Location invokeSpawnLocation(Object holder) {
        if (holder == null) {
            return null;
        }
        for (String methodName : new String[] {"getSpawn", "getSpawnLocation", "getSafeSpawn"}) {
            try {
                Object result = holder.getClass().getMethod(methodName).invoke(holder);
                if (result instanceof Location loc && loc.getWorld() != null) {
                    return loc;
                }
                if (result instanceof java.util.Optional<?> optional && optional.isPresent()
                        && optional.get() instanceof Location loc && loc.getWorld() != null) {
                    return loc;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Checks if PvP is allowed between an attacker and victim, respecting Lands wars, alliances, and combat tags.
     */
    public boolean canPvpFight(Player attacker, Player target, Location location) {
        if (attacker == null || target == null) return true;
        if (location == null) location = target.getLocation();

        // Check Lands combat settings (wars, mutual enemies, role flags)
        if (landsEnabled && landsIntegration != null && landsCanPvPMethod != null) {
            try {
                Boolean canFight = (Boolean) landsCanPvPMethod.invoke(landsIntegration, attacker, target, location, false, false);
                if (canFight != null) {
                    return canFight;
                }
            } catch (Throwable ignored) {}
        }

        // Verify attacker and target are not in safezones
        if (!isPvpAllowed(attacker.getLocation(), attacker, null) || !isPvpAllowed(target.getLocation(), target, null)) {
            return false;
        }

        return true;
    }

    public boolean isWorldGuardEnabled() {
        return worldGuardEnabled;
    }

    public boolean isLandsEnabled() {
        return landsEnabled;
    }
}
