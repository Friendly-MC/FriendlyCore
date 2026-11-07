package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatTagEvent;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages client-side barrier visualization and forcefield knockback at safezone borders
 * for players currently engaged in combat.
 */
public class DisplayBarrierManager implements Listener {

    private static final int[][] NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final FriendlyCore plugin;
    private final CombatTagManager combatTagManager;

    // Track rendered fake blocks and their original BlockData per player
    private final Map<UUID, Map<Location, BlockData>> playerRenderedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    // Monotonic timestamps shared by movement and combat-tag refreshes.
    private final Map<UUID, Long> lastBarrierScans = new ConcurrentHashMap<>();
    private BlockData barrierBlockData;
    private BlockData airBlockData;

    public DisplayBarrierManager(FriendlyCore plugin, CombatTagManager combatTagManager) {
        this.plugin = plugin;
        this.combatTagManager = combatTagManager;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        loadBlockData();
    }

    public void loadBlockData() {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        String materialName = config.getString("barriers.material", "RED_STAINED_GLASS");
        Material mat = Material.matchMaterial(materialName != null ? materialName : "RED_STAINED_GLASS");
        if (mat == null || !mat.isBlock()) {
            mat = Material.RED_STAINED_GLASS;
        }
        this.barrierBlockData = mat.createBlockData();
        this.airBlockData = Material.AIR.createBlockData();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatTag(PlayerCombatTagEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("barriers.enabled", true)) return;

        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        boolean landsClaims = config.getBoolean("barriers.lands", true);
        boolean worldGuard = config.getBoolean("barriers.worldguard", true);

        refreshBarriers(player, player.getLocation(), config, customSafezones, landsClaims, worldGuard);
    }

    /**
     * Checks if a location is inside or near the boundary buffer of any safezone.
     */
    public boolean isNearSafezone(Location location, int bufferDistance, List<String> customSafezones) {
        if (location == null || location.getWorld() == null) return false;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        boolean landsClaims = config.getBoolean("barriers.lands", true);
        boolean worldGuard = config.getBoolean("barriers.worldguard", true);
        if (isBarrierSafezone(location, null, customSafezones, landsClaims, worldGuard)) {
            return true;
        }
        if (bufferDistance <= 0) return false;

        World world = location.getWorld();
        int bx = location.getBlockX();
        int by = location.getBlockY();
        int bz = location.getBlockZ();

        for (int x = -bufferDistance; x <= bufferDistance; x++) {
            for (int z = -bufferDistance; z <= bufferDistance; z++) {
                if (x == 0 && z == 0) continue;
                if ((x * x + z * z) > (bufferDistance * bufferDistance)) continue;

                Location checkLoc = new Location(world, bx + x, by, bz + z);
                if (isBarrierSafezone(checkLoc, null, customSafezones, landsClaims, worldGuard)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!combatTagManager.isTagged(uuid)) {
            if (playerRenderedBlocks.containsKey(uuid)) {
                clearBlocks(player);
            }
            return;
        }

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("barriers.enabled", true)) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Skip micro-movements and yaw/pitch changes to save CPU
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        boolean landsClaims = config.getBoolean("barriers.lands", true);
        boolean worldGuard = config.getBoolean("barriers.worldguard", true);

        boolean toIsSafezone = isBarrierSafezone(to, player, customSafezones, landsClaims, worldGuard);
        if (toIsSafezone) {
            boolean fromIsSafezone = isBarrierSafezone(from, player, customSafezones, landsClaims, worldGuard);

            if (!fromIsSafezone) {
                event.setTo(from.clone());
                applyForcefieldKnockback(player, from, to);
                return;
            }
        }

        refreshBarriers(player, to, config, customSafezones, landsClaims, worldGuard);
    }

    private void refreshBarriers(Player player, Location center, FileConfiguration config,
            List<String> customSafezones, boolean landsClaims, boolean worldGuard) {
        UUID uuid = player.getUniqueId();
        long now = System.nanoTime();
        long scanInterval = TimeUnit.MILLISECONDS.toNanos(Math.max(50L, config.getLong("barriers.scan-interval-millis", 125L)));
        Long lastScan = lastBarrierScans.get(uuid);
        if (lastScan != null && now - lastScan < scanInterval) return;

        int radius = Math.min(8, Math.max(2, config.getInt("barriers.radius", 4)));
        int height = Math.min(5, Math.max(1, config.getInt("barriers.height", 3)));

        Map<Location, BlockData> newLocations = scanSafezoneBorders(player, center, radius, height, customSafezones, landsClaims, worldGuard);
        updateClientBlocks(player, newLocations);
        lastBarrierScans.put(uuid, now);
    }

    private Vector calculateBounceVector(Player player, Location from, Location to) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        double strength = config.getDouble("barriers.knockback-strength", 1.2);
        if (strength <= 0.0 || !Double.isFinite(strength)) {
            strength = 1.2;
        }

        // Horizontal delta from 'to' back towards 'from'
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        double distSq = dx * dx + dz * dz;

        Vector bounce;
        if (distSq > 0.0001) {
            double dist = Math.sqrt(distSq);
            bounce = new Vector((dx / dist) * strength, 0.35, (dz / dist) * strength);
        } else {
            Vector look = player.getLocation().getDirection();
            double ldx = -look.getX();
            double ldz = -look.getZ();
            double lDistSq = ldx * ldx + ldz * ldz;
            if (lDistSq > 0.0001) {
                double lDist = Math.sqrt(lDistSq);
                bounce = new Vector((ldx / lDist) * strength, 0.35, (ldz / lDist) * strength);
            } else {
                bounce = new Vector(0, 0.35, -strength);
            }
        }

        // Ensure vector components are strictly finite to avoid Spigot physics crashes
        if (!Double.isFinite(bounce.getX()) || !Double.isFinite(bounce.getY()) || !Double.isFinite(bounce.getZ())) {
            bounce = new Vector(0, 0.35, 0);
        }
        return bounce;
    }

    private void applyForcefieldKnockback(Player player, Location from, Location to) {
        Vector bounce = calculateBounceVector(player, from, to);
        player.setVelocity(bounce);

        long now = System.currentTimeMillis();
        Long lastBounce = bounceCooldowns.get(player.getUniqueId());
        if (lastBounce != null && (now - lastBounce) < 800L) {
            return;
        }
        bounceCooldowns.put(player.getUniqueId(), now);

        FileConfiguration config = plugin.getConfigService().getPvpConfig();

        if (config.getBoolean("barriers.particles", true)) {
            Location loc = player.getLocation().add(0, 1, 0);
            if (loc.getWorld() != null) {
                loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1);
                loc.getWorld().spawnParticle(Particle.PORTAL, loc, 12, 0.3, 0.5, 0.3, 0.05);
            }
        }

        if (config.getBoolean("barriers.sounds", true)) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
        }

        FileConfiguration messages = plugin.getConfigService().getPvpMessages();
        String title = messages.getString("barriers.knockback-title", "<gradient:#FF4D5A:#FF9A9A><bold>⛔ sᴛᴏᴘ!</bold></gradient>");
        String subtitle = messages.getString("barriers.knockback-subtitle", "<color:#D8D0C3>ʏᴏᴜ ᴄᴀɴɴᴏᴛ ᴇɴᴛᴇʀ ᴀ sᴀғᴇᴢᴏɴᴇ ᴡʜɪʟᴇ ɪɴ ᴄᴏᴍʙᴀᴛ!</color>");
        plugin.getTextService().sendTitle(player, title, subtitle, 5, 20, 5, Map.of());
        plugin.getTextService().sendMessage(player, "barriers.knockback-message");
    }

    /**
     * Scans safezone borders around the player and returns mapping of barrier locations to original BlockData.
     * Protection lookups are memoized per horizontal column for this scan only.
     */
    private Map<Location, BlockData> scanSafezoneBorders(Player player, Location center, int radius, int height,
            List<String> customSafezones, boolean landsClaims, boolean worldGuard) {
        Map<Location, BlockData> barrierBlocks = new HashMap<>();
        World world = center.getWorld();
        if (world == null) return barrierBlocks;
        var protection = plugin.getHookManager().getProtectionHook();
        if (!protection.isLandsEnabled()
                && (!protection.isWorldGuardEnabled() || (!worldGuard && customSafezones.isEmpty()))) {
            return barrierBlocks;
        }

        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int radiusSquared = radius * radius;

        Map<Long, Boolean> localSafeMap = new HashMap<>();
        boolean isFolia = plugin.getScheduler().isFolia();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if ((x * x + z * z) > radiusSquared) continue;

                int blockX = centerX + x;
                int blockZ = centerZ + z;

                boolean isSafe = isSafezoneColumn(world, blockX, centerY, blockZ, player, customSafezones, landsClaims, worldGuard, localSafeMap);

                if (isSafe) {
                    if (isBoundaryBlock(world, blockX, centerY, blockZ, player, customSafezones, landsClaims, worldGuard, localSafeMap)) {
                        int chunkX = blockX >> 4;
                        int chunkZ = blockZ >> 4;
                        boolean chunkOwned = !isFolia || Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);

                        for (int y = -height; y <= height; y++) {
                            int blockY = centerY + y;
                            if (blockY < minY || blockY >= maxY) continue;

                            Location loc = new Location(world, blockX, blockY, blockZ);

                            if (chunkOwned) {
                                try {
                                    BlockData existingData = world.getBlockData(blockX, blockY, blockZ);
                                    if (isReplaceable(existingData.getMaterial())) {
                                        barrierBlocks.put(loc, existingData);
                                    }
                                } catch (Throwable ignored) {
                                    if (y >= 0 && y <= 2) {
                                        barrierBlocks.put(loc, airBlockData != null ? airBlockData : Material.AIR.createBlockData());
                                    }
                                }
                            } else {
                                if (y >= 0 && y <= 2) {
                                    barrierBlocks.put(loc, airBlockData != null ? airBlockData : Material.AIR.createBlockData());
                                }
                            }
                        }
                    }
                }
            }
        }
        return barrierBlocks;
    }

    private boolean isBarrierSafezone(Location location, Player player, List<String> customSafezones,
            boolean landsClaims, boolean worldGuard) {
        return plugin.getHookManager().getProtectionHook()
                .isCombatBarrierSafezone(location, player, customSafezones, landsClaims, worldGuard);
    }

    private boolean isSafezoneColumn(World world, int x, int y, int z, Player player, List<String> customSafezones,
            boolean landsClaims, boolean worldGuard, Map<Long, Boolean> localSafeMap) {
        long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        Boolean cached = localSafeMap.get(key);
        if (cached != null) {
            return cached;
        }
        boolean safe = isBarrierSafezone(new Location(world, x + 0.5, y, z + 0.5), player, customSafezones, landsClaims, worldGuard);
        localSafeMap.put(key, safe);
        return safe;
    }

    private boolean isBoundaryBlock(World world, int x, int y, int z, Player player, List<String> customSafezones,
            boolean landsClaims, boolean worldGuard, Map<Long, Boolean> localSafeMap) {
        for (int[] offset : NEIGHBORS) {
            int nx = x + offset[0];
            int nz = z + offset[1];
            boolean neighborIsSafe = isSafezoneColumn(world, nx, y, nz, player, customSafezones, landsClaims, worldGuard, localSafeMap);
            if (!neighborIsSafe) {
                return true;
            }
        }
        return false;
    }

    private boolean isReplaceable(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR ||
                material == Material.WATER || material == Material.SHORT_GRASS ||
                material == Material.TALL_GRASS || material == Material.SEAGRASS || material == Material.SNOW ||
                material == Material.VINE || material == Material.GLOW_LICHEN || material == Material.FERN ||
                material == Material.DEAD_BUSH || material == Material.HANGING_ROOTS;
    }

    private void updateClientBlocks(Player player, Map<Location, BlockData> newLocations) {
        UUID uuid = player.getUniqueId();
        Map<Location, BlockData> currentLocations = playerRenderedBlocks.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        // Revert fake blocks that are no longer adjacent to the player
        Iterator<Map.Entry<Location, BlockData>> it = currentLocations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, BlockData> entry = it.next();
            Location loc = entry.getKey();
            if (!newLocations.containsKey(loc)) {
                if (loc.getWorld() != null && loc.getWorld().equals(player.getWorld())) {
                    player.sendBlockChange(loc, entry.getValue());
                }
                it.remove();
            }
        }

        // Send fake barrier packets for newly approached safezone blocks
        for (Map.Entry<Location, BlockData> entry : newLocations.entrySet()) {
            Location loc = entry.getKey();
            if (!currentLocations.containsKey(loc)) {
                player.sendBlockChange(loc, barrierBlockData);
                currentLocations.put(loc, entry.getValue());
            }
        }
    }

    public void clearBlocks(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        Map<Location, BlockData> blocks = playerRenderedBlocks.remove(uuid);
        if (blocks != null && !blocks.isEmpty() && player.isOnline()) {
            for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
                Location loc = entry.getKey();
                if (loc.getWorld() != null && loc.getWorld().equals(player.getWorld())) {
                    player.sendBlockChange(loc, entry.getValue());
                }
            }
        }
        bounceCooldowns.remove(uuid);
        lastBarrierScans.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntag(PlayerCombatUntagEvent event) {
        clearBlocks(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearBlocks(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPearlHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player player)) return;
        if (!combatTagManager.isTagged(player.getUniqueId())) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("barriers.enabled", true)) return;
        if (!config.getBoolean("barriers.block-ender-pearls", true)) return;

        Location hit = pearl.getLocation();
        if (event.getHitBlock() != null) {
            hit = event.getHitBlock().getLocation().add(0.5, 0.0, 0.5);
        } else if (event.getHitEntity() != null) {
            hit = event.getHitEntity().getLocation();
        }

        Location from = player.getLocation();
        if (shouldBlockSafezoneEntry(player, from, hit)
                || shouldBlockSafezoneEntry(player, from, hit.clone().add(0, 1, 0))) {
            event.setCancelled(true);
            pearl.remove();
            plugin.getTextService().sendMessage(player, "barriers.knockback-message");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!combatTagManager.isTagged(player.getUniqueId())) {
            clearBlocks(player);
            return;
        }

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("barriers.enabled", true)) return;

        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;

        boolean pearlOrChorus = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT;
        if (pearlOrChorus && !config.getBoolean("barriers.block-ender-pearls", true)) {
            clearBlocks(player);
            return;
        }

        if (shouldBlockSafezoneEntry(player, from, to)) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, "barriers.knockback-message");
            return;
        }

        // Short hops keep the wall valid; this also covers the PLUGIN teleport each bounce's setTo() fires.
        if (from.getWorld() == to.getWorld() && from.distanceSquared(to) < 4.0) return;
        clearBlocks(player);
    }

    public static boolean shouldBlockSafezoneEntry(boolean tagged, boolean barriersEnabled, boolean fromSafe, boolean toSafe) {
        return tagged && barriersEnabled && toSafe && !fromSafe;
    }

    private boolean shouldBlockSafezoneEntry(Player player, Location from, Location to) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        boolean landsClaims = config.getBoolean("barriers.lands", true);
        boolean worldGuard = config.getBoolean("barriers.worldguard", true);
        boolean toSafe = to != null && isBarrierSafezone(to, player, customSafezones, landsClaims, worldGuard);
        boolean fromSafe = from != null && isBarrierSafezone(from, player, customSafezones, landsClaims, worldGuard);
        return shouldBlockSafezoneEntry(true, true, fromSafe, toSafe);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleMove(org.bukkit.event.vehicle.VehicleMoveEvent event) {
        org.bukkit.entity.Vehicle vehicle = event.getVehicle();
        if (vehicle.getPassengers().isEmpty()) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("barriers.enabled", true)) return;

        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        boolean landsClaims = config.getBoolean("barriers.lands", true);
        boolean worldGuard = config.getBoolean("barriers.worldguard", true);
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        for (org.bukkit.entity.Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player && combatTagManager.isTagged(player.getUniqueId())) {
                boolean toIsSafezone = isBarrierSafezone(to, player, customSafezones, landsClaims, worldGuard);
                if (toIsSafezone) {
                    boolean fromIsSafezone = isBarrierSafezone(from, player, customSafezones, landsClaims, worldGuard);
                    if (!fromIsSafezone) {
                        vehicle.setVelocity(calculateBounceVector(player, from, to));
                        applyForcefieldKnockback(player, from, to);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clearBlocks(event.getEntity());
    }

    public void shutdown() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            clearBlocks(online);
        }
        playerRenderedBlocks.clear();
        bounceCooldowns.clear();
        lastBarrierScans.clear();
    }
}
