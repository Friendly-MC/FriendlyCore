package dev.lyy.friendlycore.modules.nightvision;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * Event listener for NightVision player lifecycle events.
 */
public class NightVisionListener implements Listener {

    private final FriendlyCore plugin;
    private final NightVisionManager manager;

    public NightVisionListener(FriendlyCore plugin, NightVisionManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        manager.loadPlayerAsync(player.getUniqueId(), player.getName()).thenAccept(profile -> {
            if (profile != null && profile.isEnabled()) {
                manager.applyNightVision(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        java.util.UUID uuid = player.getUniqueId();
        manager.cancelPendingLoad(uuid);
        NightVisionProfile profile = manager.getCachedProfile(uuid);
        if (profile != null) {
            if (profile.isDirty()) {
                long version = profile.getVersion();
                if (plugin.getDatabaseManager() != null) {
                    plugin.getDatabaseManager().saveNightVision(profile.getUuid(), profile.getUsername(), profile.isEnabled())
                            .thenRun(() -> {
                                profile.markSaved(version);
                                plugin.getScheduler().runGlobal(() -> {
                                    Player p = org.bukkit.Bukkit.getPlayer(uuid);
                                    if (p == null || !p.isOnline()) manager.uncacheProfile(uuid);
                                });
                            });
                } else {
                    manager.uncacheProfile(uuid);
                }
            } else {
                manager.uncacheProfile(uuid);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!manager.isReapplyOnRespawn()) return;

        Player player = event.getPlayer();
        NightVisionProfile profile = manager.getCachedProfile(player.getUniqueId());
        if (profile != null && profile.isEnabled()) {
            plugin.getScheduler().runEntityLater(player, () -> {
                if (player.isOnline() && profile.isEnabled()) {
                    manager.applyNightVision(player);
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        NightVisionProfile profile = manager.getCachedProfile(player.getUniqueId());
        if (profile == null || !profile.isEnabled()) return;

        if (manager.isWorldDisabled(player.getWorld())) {
            manager.removeNightVision(player);
        } else {
            manager.applyNightVision(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!manager.isKeepOnMilk()) return;

        ItemStack item = event.getItem();
        if (item == null) return;
        if (item.getType() == Material.MILK_BUCKET) {
            Player player = event.getPlayer();
            NightVisionProfile profile = manager.getCachedProfile(player.getUniqueId());
            if (profile != null && profile.isEnabled()) {
                // Reapply after milk removes all effects
                plugin.getScheduler().runEntityLater(player, () -> {
                    if (player.isOnline() && profile.isEnabled()) {
                        manager.applyNightVision(player);
                    }
                }, 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!manager.isKeepOnMilk()) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (event.getModifiedType().equals(PotionEffectType.NIGHT_VISION)) {
            EntityPotionEffectEvent.Action action = event.getAction();
            if (action == EntityPotionEffectEvent.Action.CLEARED || action == EntityPotionEffectEvent.Action.REMOVED) {
                NightVisionProfile profile = manager.getCachedProfile(player.getUniqueId());
                if (profile != null && profile.isEnabled()) {
                    // Check if removed by plugin manager disable or external clear
                    if (event.getCause() == EntityPotionEffectEvent.Cause.MILK || event.getCause() == EntityPotionEffectEvent.Cause.BEACON) {
                        plugin.getScheduler().runEntityLater(player, () -> {
                            if (player.isOnline() && profile.isEnabled()) {
                                manager.applyNightVision(player);
                            }
                        }, 2L);
                    }
                }
            }
        }
    }
}
