package dev.lyy.friendlycore.modules.pets.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.display.PetDisplayManager;
import dev.lyy.friendlycore.modules.pets.effects.PetEffect;
import dev.lyy.friendlycore.modules.pets.effects.PetTrigger;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance event listener for executing all pet effect triggers.
 */
public class PetEventListener implements Listener {

    private final FriendlyCore plugin;
    private final PetManager petManager;
    private final Random random = new Random();
    private final Map<UUID, Map<PetEffect, Long>> lifestealCooldowns = new ConcurrentHashMap<>();

    public PetEventListener(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        petManager.loadPlayerData(player.getUniqueId(), player.getName()).thenAccept(data -> {
            if (data != null) {
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) petManager.applyPetAttributes(player);
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        petManager.getDisplayManager().despawn(uuid);
        petManager.removePetAttributes(event.getPlayer());
        petManager.unloadPlayerData(uuid);
        lifestealCooldowns.remove(uuid);
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        petManager.getDisplayManager().despawn(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        petManager.getDisplayManager().despawn(player.getUniqueId());
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline()) {
                petManager.getDisplayManager().refreshPlayerPet(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        petManager.getDisplayManager().despawn(player.getUniqueId());
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline()) {
                petManager.getDisplayManager().refreshPlayerPet(player);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Player player) {
                petManager.getDisplayManager().despawn(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity()) || PetDisplayManager.isPetEntity(event.getDamager())
                || (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Entity shooter && PetDisplayManager.isPetEntity(shooter))) {
            event.setCancelled(true);
            return;
        }
        // Melee or projectile attack dispatched by a player
        Player attacker = null;
        boolean isBow = false;

        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
            isBow = true;
        }

        if (attacker != null) {
            handleOutgoingAttack(event, attacker, isBow);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            handleIncomingDamage(event, victim);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetInteract(PlayerInteractEntityEvent event) {
        if (PetDisplayManager.isPetEntity(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetInteractAt(PlayerInteractAtEntityEvent event) {
        onPetInteract(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetPickup(EntityPickupItemEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetTarget(EntityTargetEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity()) || PetDisplayManager.isPetEntity(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetPortal(EntityPortalEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPetMount(EntityMountEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity()) || PetDisplayManager.isPetEntity(event.getMount())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPetDeath(EntityDeathEvent event) {
        if (PetDisplayManager.isPetEntity(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    private void handleOutgoingAttack(EntityDamageByEntityEvent event, Player attacker, boolean isBow) {
        PetTrigger trigger = isBow ? PetTrigger.BOW_ATTACK : PetTrigger.MELEE_ATTACK;

        for (PetEffect effect : petManager.getActiveEffects(attacker)) {
            if (!effect.hasTrigger(trigger) && !effect.hasTrigger(PetTrigger.PASSIVE)) continue;

            String id = effect.getId();

            if (id.equals("damage_multiplier") || id.equals("melee_damage") || id.equals("bow_damage")) {
                double multiplier = effect.getDoubleArg("multiplier", 1.0);
                if (multiplier > 0) {
                    event.setDamage(event.getDamage() * multiplier);
                }
            } else if (id.equals("ignite") || id.equals("fire")) {
                double chance = effect.getDoubleArg("chance", 100.0);
                if (random.nextDouble() * 100.0 <= chance) {
                    int ticks = effect.getIntArg("ticks", effect.getIntArg("duration", 100));
                    event.getEntity().setFireTicks(ticks);
                }
            } else if (id.equals("give_health") || id.equals("lifesteal")) {
                double chance = effect.getDoubleArg("chance", 100.0);
                if (random.nextDouble() * 100.0 <= chance) {
                    double cooldownSec = effect.getDoubleArg("cooldown", 0.0);
                    long now = System.currentTimeMillis();
                    Map<PetEffect, Long> cooldowns = lifestealCooldowns.computeIfAbsent(attacker.getUniqueId(), ignored -> new ConcurrentHashMap<>());
                    long lastHeal = cooldowns.getOrDefault(effect, 0L);

                    if (now - lastHeal >= (long) (cooldownSec * 1000L)) {
                        cooldowns.put(effect, now);
                        double amount = effect.getDoubleArg("amount", effect.getDoubleArg("heal", 2.0));
                        AttributeInstance maxHealth = attacker.getAttribute(Attribute.MAX_HEALTH);
                        double max = maxHealth != null ? maxHealth.getValue() : 20.0;
                        attacker.setHealth(Math.min(max, attacker.getHealth() + amount));
                    }
                }
            } else if (id.equals("bleed")) {
                double chance = effect.getDoubleArg("chance", 100.0);
                if (random.nextDouble() * 100.0 <= chance && event.getEntity() instanceof LivingEntity living) {
                    double bleedDmg = effect.getDoubleArg("damage", 1.5);
                    int interval = effect.getIntArg("interval", 15);
                    int pulses = effect.getIntArg("amount", 2);
                    applyBleed(living, bleedDmg, interval, pulses);
                }
            }
        }
    }

    private void handleIncomingDamage(EntityDamageEvent event, Player victim) {
        for (PetEffect effect : petManager.getActiveEffects(victim)) {
            if (!effect.hasTrigger(PetTrigger.TAKE_DAMAGE)) continue;

            String id = effect.getId();
            if (id.equals("damage_reduction") || id.equals("resistance")) {
                double mult = effect.getDoubleArg("multiplier", 0.8);
                event.setDamage(event.getDamage() * mult);
            }
        }
    }

    private void applyBleed(LivingEntity target, double damage, int intervalTicks, int totalPulses) {
        if (target == null || !target.isValid() || totalPulses <= 0) return;

        for (int i = 1; i <= totalPulses; i++) {
            long delay = (long) intervalTicks * i;
            plugin.getScheduler().runEntityLater(target, () -> {
                if (target.isValid() && !target.isDead()) {
                    target.damage(damage);
                }
            }, delay);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        for (PetEffect effect : petManager.getActiveEffects(player)) {
            if (effect.hasTrigger(PetTrigger.PASSIVE) && (effect.getId().equals("xp_multiplier") || effect.getId().equals("exp_multiplier"))) {
                double mult = effect.getDoubleArg("multiplier", 1.0);
                if (mult > 0 && event.getAmount() > 0) {
                    event.setAmount((int) Math.round(event.getAmount() * mult));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaidFinish(RaidFinishEvent event) {
        for (Player winner : event.getWinners()) {
            plugin.getScheduler().runEntity(winner, () -> {
                if (winner.isOnline()) petManager.applyPetAttributes(winner);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getScheduler().runEntityLater(player, () -> {
            if (!player.isOnline()) return;
            petManager.applyPetAttributes(player);
            petManager.getDisplayManager().refreshPlayerPet(player);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            Player player = event.getPlayer();
            plugin.getScheduler().runEntityLater(player, () -> {
                if (!player.isOnline()) return;
                petManager.applyPetAttributes(player);
            }, 1L);
        }
    }
}
