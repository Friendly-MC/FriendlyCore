package dev.lyy.friendlycore.modules.tools;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles area-of-effect block breaking for 3x3 mining drill picks and shovels.
 * Supports Folia region scheduling, custom break filters, and durability tracking.
 */
public class DrillHandler implements Listener {

    private final FriendlyCore plugin;
    private final Set<UUID> breakingPlayers = Collections.synchronizedSet(new HashSet<>());

    public DrillHandler(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (dev.lyy.friendlycore.common.hooks.ProtectionHook.isSimulating()) return;
        Player player = event.getPlayer();
        if (breakingPlayers.contains(player.getUniqueId())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        CustomToolType type = plugin.getToolManager().getToolType(item);
        if (type == null || !type.isDrill()) return;

        if (!player.hasPermission("friendlycore.tools.drill")) {
            return;
        }

        FileConfiguration config = plugin.getConfigService().getToolsConfig();
        if (!config.getBoolean("drill.enabled", true)) return;

        // Skip AoE logic if the central targeted block itself is protected/blacklisted
        List<String> blacklist = config.getStringList("drill.blacklisted-materials");
        Block origin = event.getBlock();
        if (isBlacklisted(origin.getType(), blacklist)) return;

        int radius = Math.max(0, Math.min(2, config.getInt("drill.radius", 1)));
        int budget = Math.max(1, Math.min(26, config.getInt("drill.blocks-per-tick", 9)));
        int cooldownMs = config.getInt("drill.cooldown-ms", 0);
        if (cooldownMs > 0 && !plugin.getCooldownManager().tryAcquire("drill", player.getUniqueId(), cooldownMs)) return;
        if (!breakingPlayers.add(player.getUniqueId())) return;

        DrillBatch batch = new DrillBatch(event, item.clone(), type, blacklist,
                getSurroundingLocations(origin, radius).iterator(), budget);
        batch.schedule();
    }

    /** One operation per player; all inventory and protection access stays on their entity thread. */
    private final class DrillBatch implements Runnable {
        private final BlockBreakEvent event;
        private final Player player;
        private final int slot;
        private final CustomToolType type;
        private final List<String> blacklist;
        private final Iterator<Location> locations;
        private final int budget;
        private ItemStack expected;
        private boolean first = true;

        private DrillBatch(BlockBreakEvent event, ItemStack expected, CustomToolType type,
                           List<String> blacklist, Iterator<Location> locations, int budget) {
            this.event = event;
            this.player = event.getPlayer();
            this.slot = player.getInventory().getHeldItemSlot();
            this.expected = expected;
            this.type = type;
            this.blacklist = blacklist;
            this.locations = locations;
            this.budget = budget;
        }

        private void finish() {
            breakingPlayers.remove(player.getUniqueId());
        }

        private void schedule() {
            try {
                if (plugin.getScheduler().runEntityLater(player, this, this::finish, 1L) == null) finish();
            } catch (RuntimeException error) {
                finish();
                throw error;
            }
        }

        @Override
        public void run() {
            boolean continueLater = false;
            try {
                if (event.isCancelled() || !player.isOnline() || !player.hasPermission("friendlycore.tools.drill")
                        || player.getInventory().getHeldItemSlot() != slot) return;
                ItemStack item = player.getInventory().getItemInMainHand();
                if (first) {
                    // The triggering vanilla break may have used one durability point.
                    if (expected.getItemMeta() instanceof Damageable oldMeta
                            && item.getItemMeta() instanceof Damageable currentMeta) {
                        int delta = currentMeta.getDamage() - oldMeta.getDamage();
                        if (delta < 0 || delta > 1) return;
                        oldMeta.setDamage(currentMeta.getDamage());
                        expected.setItemMeta(oldMeta);
                    }
                    first = false;
                    if (!plugin.getScheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(event.getBlock().getLocation())) {
                        playDrillEffects(event.getBlock().getLocation(), plugin.getConfigService().getToolsConfig());
                    }
                }
                if (!item.isSimilar(expected) || plugin.getToolManager().getToolType(item) != type) return;
                for (int attempted = 0; attempted < budget && locations.hasNext(); attempted++) {
                    Location location = locations.next();
                    if (location.getWorld() == null || location.getBlockY() < location.getWorld().getMinHeight()
                            || location.getBlockY() >= location.getWorld().getMaxHeight()
                            || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
                    // Protection hooks inspect the player as well as the block. Never send
                    // that mutable player/inventory to an unrelated Folia region.
                    if (plugin.getScheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(location)) continue;
                    Block target = location.getBlock();
                    Material material = target.getType();
                    if ((material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) || isBlacklisted(material, blacklist)) continue;
                    if (type == CustomToolType.DRILL_SHOVEL && !isShovelMaterial(material)) continue;
                    if (type == CustomToolType.DRILL_PICKAXE && isShovelMaterial(material)) continue;
                    if (!plugin.getHookManager().getProtectionHook().canBreak(player, target)) continue;
                    if (player.getInventory().getHeldItemSlot() != slot
                            || !player.getInventory().getItemInMainHand().isSimilar(item)) return;
                    if (target.breakNaturally(item)) {
                        applyDurabilityDamage(player, item, 1);
                        if (player.getInventory().getItemInMainHand().getType() == Material.AIR) return;
                    }
                }
                plugin.getToolManager().updateDrillMeta(item, player.getName());
                expected = item.clone();
                continueLater = locations.hasNext();
            } finally {
                if (continueLater) schedule();
                else finish();
            }
        }
    }

    private List<Location> getSurroundingLocations(Block origin, int radius) {
        radius = Math.max(0, Math.min(2, radius));
        List<Location> list = new ArrayList<>();
        Location loc = origin.getLocation();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    list.add(loc.clone().add(x, y, z));
                }
            }
        }
        return list;
    }

    private boolean isBlacklisted(Material mat, List<String> blacklist) {
        if (mat == Material.BEDROCK || mat == Material.BARRIER || mat == Material.END_PORTAL || mat == Material.END_PORTAL_FRAME || mat == Material.COMMAND_BLOCK) {
            return true;
        }
        return blacklist.contains(mat.name());
    }

    private boolean isShovelMaterial(Material mat) {
        if (mat == null) return false;
        String name = mat.name();
        return name.contains("DIRT") || name.contains("SAND") || name.contains("GRAVEL") ||
                name.contains("SOUL") || name.contains("CLAY") || name.contains("MUD") ||
                name.contains("MUDDY") || name.contains("GRASS_BLOCK") || name.contains("MYCELIUM") ||
                name.contains("PODZOL") || name.contains("SNOW") || name.contains("FARMLAND");
    }

    private void applyDurabilityDamage(Player player, ItemStack item, int amount) {
        if (amount <= 0 || item == null || !(item.getItemMeta() instanceof Damageable damageable) || damageable.isUnbreakable()) return;

        int unbreakingLevel = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        int damageToApply = 0;

        for (int i = 0; i < amount; i++) {
            if (unbreakingLevel > 0) {
                // Vanilla unbreaking formula: 100 / (unbreaking + 1)% chance of taking damage
                if (ThreadLocalRandom.current().nextInt(100) < (100 / (unbreakingLevel + 1))) {
                    damageToApply++;
                }
            } else {
                damageToApply++;
            }
        }

        if (damageToApply > 0) {
            int newDamage = damageable.getDamage() + damageToApply;
            if (newDamage >= item.getType().getMaxDurability()) {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand != null && mainHand.isSimilar(item)) {
                    player.getInventory().setItemInMainHand(null);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDamage);
                item.setItemMeta(damageable);
            }
        }
    }

    private void playDrillEffects(Location loc, FileConfiguration config) {
        if (config.getBoolean("drill.effects.sound.enabled", true)) {
            String soundStr = config.getString("drill.effects.sound.type", "BLOCK_AMETHYST_BLOCK_HIT");
            float vol = (float) config.getDouble("drill.effects.sound.volume", 0.8);
            float pitch = (float) config.getDouble("drill.effects.sound.pitch", 1.4);
            dev.lyy.friendlycore.common.TextService.playSound(loc, soundStr, vol, pitch);
        }

        if (config.getBoolean("drill.effects.particle.enabled", true)) {
            String partStr = config.getString("drill.effects.particle.type", "ENCHANTED_HIT");
            int count = config.getInt("drill.effects.particle.count", 10);
            Particle particle = dev.lyy.friendlycore.common.EffectUtil.parseParticle(partStr, Particle.ENCHANTED_HIT);
            if (particle != null && loc.getWorld() != null) {
                dev.lyy.friendlycore.common.EffectUtil.spawnParticle(loc.getWorld(), particle, loc.clone().add(0.5, 0.5, 0.5), count, 0.4, 0.4, 0.4, 0.05);
            }
        }
    }
}
