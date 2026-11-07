package dev.lyy.friendlycore.modules.essentials.vanish;

import dev.lyy.friendlycore.common.Messages;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.lyy.friendlycore.FriendlyCore;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.EnderChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

import java.util.Iterator;
import java.util.UUID;

/**
 * Event listener handling total cloaking, silent container inspection,
 * mob targeting immunity, sculk vibration suppression, and broadcast dampening.
 */
public class VanishListener implements Listener {

    private final FriendlyCore plugin;
    private final VanishManager vanishManager;

    public VanishListener(FriendlyCore plugin, VanishManager vanishManager) {
        this.plugin = plugin;
        this.vanishManager = vanishManager;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        if (!vanishManager.isVanished(player.getUniqueId())) return;

        boolean silentChest = plugin.getConfigService().get("essentials.yml").getBoolean("vanish.silent-container-open", true);
        if (!silentChest) return;

        Material type = block.getType();
        if (isContainerBlock(type)) {
            event.setCancelled(true);

            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;

                if (type == Material.ENDER_CHEST) {
                    player.openInventory(player.getEnderChest());
                } else {
                    BlockState state = block.getState();
                    if (state instanceof Container container) {
                        player.openInventory(container.getInventory());
                    }
                }
            });
        }
    }

    private boolean isContainerBlock(Material mat) {
        if (mat == Material.CHEST || mat == Material.TRAPPED_CHEST || mat == Material.BARREL || mat == Material.ENDER_CHEST) {
            return true;
        }
        return mat.name().endsWith("_SHULKER_BOX") || mat == Material.SHULKER_BOX;
    }

    // Also receives EntityTargetLivingEntityEvent, which shares this event's handler list.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetEvent event) {
        Entity target = event.getTarget();
        if (target instanceof Player player && vanishManager.isVanished(player.getUniqueId())
                && plugin.getConfigService().get("essentials.yml").getBoolean("vanish.prevent-mob-target", true)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    // Fires for every vibration near any sculk sensor: do the cheap vanish check before the config read.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockReceiveGameEvent(BlockReceiveGameEvent event) {
        if (event.getEntity() instanceof Player player && vanishManager.isVanished(player.getUniqueId())
                && plugin.getConfigService().get("essentials.yml").getBoolean("vanish.prevent-sculk-sensors", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && vanishManager.isVanished(player.getUniqueId())) {
            if (!vanishManager.canPickupItems(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerAttemptPickupItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        if (vanishManager.isVanished(player.getUniqueId())) {
            if (!vanishManager.canPickupItems(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        if (vanishManager.isVanished(player.getUniqueId())) {
            if (!vanishManager.canPickupItems(player)) {
                event.setAmount(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && vanishManager.isVanished(player.getUniqueId())) {
            if (!player.hasPermission("friendlycore.vanish.damage")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Player attacker = null;

        if (damager instanceof Player p) {
            attacker = p;
        } else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker != null && isAttackBlocked(attacker)) {
            event.setCancelled(true);
        }
    }

    // Spear jabs and charges still knock back after their damage event is cancelled.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        // Living targets only: boats, minecarts and hangings never went through the damage rule above.
        if (event.getAttacked() instanceof LivingEntity && isAttackBlocked(event.getPlayer())) event.setCancelled(true);
    }

    private boolean isAttackBlocked(Player attacker) {
        return vanishManager.isVanished(attacker.getUniqueId())
                && !attacker.hasPermission("friendlycore.vanish.pvp") && !attacker.hasPermission("friendlycore.vanish.damage");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (vanishManager.isVanished(player.getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (vanishManager.isVanished(player.getUniqueId())) {
            event.deathMessage(null);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(PaperServerListPingEvent event) {
        if (!plugin.getConfigService().get("essentials.yml").getBoolean("vanish.hide-from-server-ping", true)) {
            return;
        }

        int vanishedCount = vanishManager.getVanishedCount();
        if (vanishedCount > 0) {
            event.setNumPlayers(Math.max(0, event.getNumPlayers() - vanishedCount));

            if (event.getPlayerSample() != null) {
                event.getPlayerSample().removeIf(profile -> profile.getId() != null && vanishManager.isVanished(profile.getId()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            vanishManager.loadTransferArrival(event.getUniqueId());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not load vanish transfer state", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Messages.component(plugin, "essentials", "vanish-load-failed-kick"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            vanishManager.consumeTransferArrival(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginResult(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            vanishManager.consumeTransferArrival(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean transferredVanished = vanishManager.consumeTransferArrival(player.getUniqueId());
        vanishManager.invalidateTier(player.getUniqueId());
        vanishManager.setVanished(player, transferredVanished, true);

        if (transferredVanished || player.hasPermission("friendlycore.vanish.silentjoin")) {
            event.joinMessage(null);
        }

        vanishManager.updateAllForViewer(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (vanishManager.isVanished(player.getUniqueId())) {
            event.quitMessage(null);
        }

        vanishManager.invalidateTier(player.getUniqueId());
    }
}
