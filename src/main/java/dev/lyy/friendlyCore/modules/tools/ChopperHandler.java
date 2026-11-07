package dev.lyy.friendlycore.modules.tools;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
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
 * Handles connected tree-chopping mechanics for custom tree chopper tools.
 * Traverses contiguous log blocks, respects region protections and Folia threading,
 * and schedules accelerated decay for orphaned leaf canopies.
 */
public class ChopperHandler implements Listener {

    private final FriendlyCore plugin;
    private final Set<UUID> choppingPlayers = Collections.synchronizedSet(new HashSet<>());

    public ChopperHandler(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (dev.lyy.friendlycore.common.hooks.ProtectionHook.isSimulating()) return;
        Player player = event.getPlayer();
        if (choppingPlayers.contains(player.getUniqueId())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        CustomToolType type = plugin.getToolManager().getToolType(item);
        if (type != CustomToolType.TREE_CHOPPER) return;

        if (!player.hasPermission("friendlycore.tools.chopper")) {
            return;
        }

        Block origin = event.getBlock();
        if (!isLog(origin.getType())) return;

        FileConfiguration config = plugin.getConfigService().getToolsConfig();
        if (!config.getBoolean("chopper.enabled", true)) return;

        int maxLogs = Math.max(1, Math.min(256, config.getInt("chopper.max-logs", 250)));
        int cooldownMs = config.getInt("chopper.cooldown-ms", 0);

        if (cooldownMs > 0 && !plugin.getCooldownManager().tryAcquire("chopper", player.getUniqueId(), cooldownMs)) {
            return;
        }

        List<Block> connectedLogs = findConnectedLogs(origin, maxLogs);
        if (connectedLogs.isEmpty()) return;

        choppingPlayers.add(player.getUniqueId());

        try {
            int brokenCount = 0;
            List<Block> validBroken = new ArrayList<>();

            for (Block log : connectedLogs) {
                if (log.equals(origin)) continue; // The origin log is already handled by the triggering event

                if (Bukkit.isOwnedByCurrentRegion(log.getLocation())) {
                    if (plugin.getHookManager().getProtectionHook().canBreak(player, log)) {
                        log.breakNaturally(item);
                        validBroken.add(log);
                        brokenCount++;
                    }
                } else {
                    plugin.getScheduler().runLocation(log.getLocation(), () -> {
                        if (plugin.getHookManager().getProtectionHook().canBreak(player, log)) {
                            log.breakNaturally(item);
                        }
                    });
                }
            }

            // Apply durability reduction for all secondary logs broken in this vein
            if (brokenCount > 0) {
                applyDurabilityDamage(player, item, brokenCount);
            }

            // Trigger accelerated leaf decay for leaves attached to the felled tree
            plugin.getLeafDecayManager().scheduleLeafDecay(validBroken);

        } finally {
            choppingPlayers.remove(player.getUniqueId());
        }
    }

    private static final int[][] CARDINAL_OFFSETS = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {1, 1, 1}, {1, 1, -1}, {-1, 1, 1}, {-1, 1, -1},
            {1, -1, 1}, {1, -1, -1}, {-1, -1, 1}, {-1, -1, -1}
    };

    private List<Block> findConnectedLogs(Block origin, int max) {
        List<Block> result = new ArrayList<>();
        Queue<Block> queue = new ArrayDeque<>();
        Set<Block> visited = new HashSet<>();

        queue.add(origin);
        visited.add(origin);

        org.bukkit.World world = origin.getWorld();

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            result.add(current);

            Location loc = current.getLocation();
            for (int[] offset : CARDINAL_OFFSETS) {
                int nx = loc.getBlockX() + offset[0];
                int ny = loc.getBlockY() + offset[1];
                int nz = loc.getBlockZ() + offset[2];

                if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue;
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) continue;
                if (plugin.getScheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(world, nx >> 4, nz >> 4)) continue;

                Block neighbor = world.getBlockAt(nx, ny, nz);
                if (isLog(neighbor.getType()) && visited.add(neighbor)) {
                    queue.add(neighbor);
                    if (result.size() + queue.size() >= max) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    private boolean isLog(Material mat) {
        String name = mat.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    private void applyDurabilityDamage(Player player, ItemStack item, int amount) {
        if (amount <= 0 || item == null || !(item.getItemMeta() instanceof Damageable damageable)) return;

        int unbreakingLevel = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        int damageToApply = 0;

        for (int i = 0; i < amount; i++) {
            if (unbreakingLevel > 0) {
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
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDamage);
                item.setItemMeta(damageable);
            }
        }
    }
}
