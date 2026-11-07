package dev.lyy.friendlycore.common;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freezes a player during spawn warmup and proxy server transfers so combat,
 * mob hits, and item drops cannot race the cross-server snapshot.
 */
public class TransferFreezeService implements Listener {

    public static final String META_KEY = "friendlycore:transfer-freeze";
    private static final long FAILSAFE_TICKS = 200L;

    private final FriendlyCore plugin;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> failsafeTasks = new ConcurrentHashMap<>();

    public TransferFreezeService(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (plugin != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
    }

    public void shutdown() {
        for (Object task : failsafeTasks.values()) {
            if (plugin != null && task != null) {
                plugin.getScheduler().cancel(task);
            }
        }
        failsafeTasks.clear();
        frozen.clear();
    }

    public boolean isFrozen(UUID uuid) {
        return uuid != null && frozen.contains(uuid);
    }

    public boolean isFrozen(Player player) {
        if (player == null) {
            return false;
        }
        if (frozen.contains(player.getUniqueId())) {
            return true;
        }
        try {
            return player.hasMetadata(META_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void freeze(UUID uuid) {
        if (uuid != null) {
            frozen.add(uuid);
        }
    }

    public void freeze(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        freeze(uuid);
        try {
            player.setMetadata(META_KEY, new FixedMetadataValue(plugin, true));
        } catch (Throwable ignored) {}
        try {
            player.setInvulnerable(true);
        } catch (Throwable ignored) {}
        try {
            player.closeInventory();
        } catch (Throwable ignored) {}
        untagCombat(player);
        scheduleFailsafe(player);
    }

    public void unfreeze(UUID uuid) {
        if (uuid == null) {
            return;
        }
        frozen.remove(uuid);
        cancelFailsafe(uuid);
        if (plugin == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            clearPlayerFreeze(player);
        }
    }

    public void unfreeze(Player player) {
        if (player == null) {
            return;
        }
        frozen.remove(player.getUniqueId());
        cancelFailsafe(player.getUniqueId());
        clearPlayerFreeze(player);
    }

    private void clearPlayerFreeze(Player player) {
        try {
            player.removeMetadata(META_KEY, plugin);
        } catch (Throwable ignored) {}
        if (shouldKeepInvulnerable(player)) {
            return;
        }
        try {
            player.setInvulnerable(false);
        } catch (Throwable ignored) {}
    }

    private boolean shouldKeepInvulnerable(Player player) {
        try {
            if (plugin.getEssentialsManager() != null
                    && plugin.getEssentialsManager().isGodMode(player.getUniqueId())) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void untagCombat(Player player) {
        try {
            if (plugin.getPvpManager() != null && plugin.getPvpManager().getCombatTagManager() != null) {
                plugin.getPvpManager().getCombatTagManager()
                        .untagPlayer(player, PlayerCombatUntagEvent.UntagReason.TRANSFER);
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleFailsafe(Player player) {
        if (plugin == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        cancelFailsafe(uuid);
        Object task = plugin.getScheduler().runEntityLater(player, () -> {
            failsafeTasks.remove(uuid);
            if (player.isOnline() && isFrozen(uuid)) {
                unfreeze(player);
            }
        }, FAILSAFE_TICKS);
        if (task != null) {
            failsafeTasks.put(uuid, task);
        }
    }

    private void cancelFailsafe(UUID uuid) {
        Object task = failsafeTasks.remove(uuid);
        if (plugin != null && task != null) {
            plugin.getScheduler().cancel(task);
        }
    }

    public static boolean isFrozenSource(Entity entity, java.util.function.Predicate<UUID> frozenCheck) {
        Player source = resolvePlayer(entity);
        return source != null && frozenCheck.test(source.getUniqueId());
    }

    public static Player resolvePlayer(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source) {
            return source;
        }
        if (entity instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && isFrozen(victim)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity
                && isFrozenSource(byEntity.getDamager(), this::isFrozen)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        Player shooter = event.getPotion().getShooter() instanceof Player player ? player : null;
        boolean shooterFrozen = shooter != null && isFrozen(shooter);
        for (org.bukkit.entity.LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && (isFrozen(victim) || shooterFrozen)) {
                event.setIntensity(victim, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!isFrozen(event.getEntity())) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        frozen.remove(uuid);
        cancelFailsafe(uuid);
        try {
            event.getPlayer().removeMetadata(META_KEY, plugin);
        } catch (Throwable ignored) {}
    }
}
