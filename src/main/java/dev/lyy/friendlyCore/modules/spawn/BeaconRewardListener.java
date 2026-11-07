package dev.lyy.friendlycore.modules.spawn;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

/**
 * Handles interactive spawn beacon reward mechanics including visual lightning effects,
 * console kit dispatch, and automatic block regeneration.
 */
public class BeaconRewardListener implements Listener {

    private final FriendlyCore plugin;

    public BeaconRewardListener(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    private boolean isEligibleBeacon(Block block, FileConfiguration config) {
        if (block == null) return false;
        World world = block.getWorld();
        if (world == null) return false;

        String targetWorld = config.getString("beacon-reward.world", "spawn");
        if (targetWorld != null && !targetWorld.isEmpty() && !targetWorld.equalsIgnoreCase("all") && !targetWorld.equalsIgnoreCase("*")) {
            if (!world.getName().equalsIgnoreCase(targetWorld)) {
                return false;
            }
        }

        String targetMatName = config.getString("beacon-reward.target-material", "BEACON");
        Material targetMat = Material.matchMaterial(targetMatName);
        if (targetMat == null) {
            targetMat = Material.BEACON;
        }

        if (block.getType() != targetMat) {
            return false;
        }

        boolean requireCoords = config.getBoolean("beacon-reward.require-coordinates", true);
        if (requireCoords) {
            boolean hasCoordinates = config.contains("beacon-reward.x")
                    && config.contains("beacon-reward.y")
                    && config.contains("beacon-reward.z");
            if (!hasCoordinates) {
                return false;
            }
            int tx = config.getInt("beacon-reward.x");
            int ty = config.getInt("beacon-reward.y");
            int tz = config.getInt("beacon-reward.z");
            if (block.getX() != tx || block.getY() != ty || block.getZ() != tz) {
                return false;
            }
        }

        return true;
    }

    private void processReward(Player player, Block block, FileConfiguration config) {
        Location loc = block.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        long cdSeconds = config.getLong("beacon-reward.cooldown-seconds", 0L);
        boolean hasBypass = player.hasPermission("friendlycore.bypass.cooldown") || player.hasPermission("friendlycore.beacon.bypass");
        if (cdSeconds > 0 && !hasBypass && plugin.getCooldownManager().isOnCooldown("beacon_reward", playerUuid, cdSeconds * 1000L)) {
            String msg = config.getString("beacon-reward.cooldown-message", "<red>You have already claimed the spawn beacon reward!");
            plugin.getTextService().sendMessage(player, msg, null);
            return;
        }

        // Cache block type for regeneration before breaking
        final Material respawnType = block.getType();
        block.setType(Material.AIR);

        if (config.getBoolean("beacon-reward.strike-lightning", true)) {
            world.strikeLightningEffect(loc);
        }

        if (cdSeconds > 0 && !hasBypass) {
            plugin.getCooldownManager().setCooldown("beacon_reward", playerUuid);
        }

        String template = config.getString("beacon-reward.dispatch-command", "fc givekit beacon %player%");
        String cmd = template.replace("%player%", playerName);
        plugin.getScheduler().runGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));

        long respawnSeconds = config.getLong("beacon-reward.respawn-seconds", 0L);
        if (respawnSeconds > 0) {
            plugin.getScheduler().runLocationLater(loc, () -> {
                Block current = loc.getBlock();
                if (current.getType().isAir()) {
                    current.setType(respawnType);
                }
            }, respawnSeconds * 20L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (dev.lyy.friendlycore.common.hooks.ProtectionHook.isSimulating()) return;
        FileConfiguration config = plugin.getConfigService().getSpawnConfig();
        if (!config.getBoolean("beacon-reward.enabled", true)) return;

        Block block = event.getBlock();
        if (!isEligibleBeacon(block, config)) return;

        Player player = event.getPlayer();
        long cdSeconds = config.getLong("beacon-reward.cooldown-seconds", 0L);
        boolean hasBypass = player.hasPermission("friendlycore.bypass.cooldown") || player.hasPermission("friendlycore.beacon.bypass");
        if (cdSeconds > 0 && !hasBypass && plugin.getCooldownManager().isOnCooldown("beacon_reward", player.getUniqueId(), cdSeconds * 1000L)) {
            String msg = config.getString("beacon-reward.cooldown-message", "<red>You have already claimed the spawn beacon reward!");
            plugin.getTextService().sendMessage(player, msg, null);
            event.setCancelled(true);
            return;
        }

        // Uncancel if cancelled by general spawn protection so beacon is minable
        event.setCancelled(false);
        event.setDropItems(false);
        event.setExpToDrop(0);
        processReward(player, block, config);
    }
}
