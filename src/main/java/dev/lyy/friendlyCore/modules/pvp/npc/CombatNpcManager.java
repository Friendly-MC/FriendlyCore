package dev.lyy.friendlycore.modules.pvp.npc;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import dev.lyy.friendlycore.modules.pvp.PvpEconomyManager;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages combat logger armor stand clones spawned upon player combat disconnection,
 * including health tracking, inventory synchronization, and offline death penalty enforcement.
 */
public class CombatNpcManager implements Listener {

    public static final String COMBAT_NPC_SLAIN_META = "friendlycore:combat-npc-slain";

    private final FriendlyCore plugin;
    private final NamespacedKey ownerKey;
    private final Map<UUID, CombatNpc> activeNpcsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, CombatNpc> activeNpcsByEntity = new ConcurrentHashMap<>();
    private final Set<UUID> pendingDeathsOnLogin = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingSpawns = new ConcurrentHashMap<>();
    private File pendingDeathsFile;
    private Object tickerTask;

    public CombatNpcManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "combat_npc_owner");
    }

    public void initialize() {
        this.pendingDeathsFile = new File(plugin.getDataFolder(), "data/pvp/pending_deaths.yml");
        loadPendingDeaths();

        // Purge any lingering or orphaned combat logger armor stands from previous abnormal stops
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntitiesByClass(ArmorStand.class)) {
                    if (entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                        entity.remove();
                    }
                }
            }
        } catch (Throwable ignored) {}

        Bukkit.getPluginManager().registerEvents(this, plugin);
        startTicker();
    }

    private void startTicker() {
        this.tickerTask = plugin.getScheduler().runGlobalTimer(this::tickNpcs, 20L, 20L);
    }

    private void tickNpcs() {
        if (activeNpcsByPlayer.isEmpty()) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        String nameFormat = config.getString("combat-tag.npc.name-format",
                "<gradient:#FF4D5A:#FF9A9A><bold>%player%</bold></gradient> <dark_gray>•</dark_gray> <white>%time%s</white>");
        boolean withParticles = config.getBoolean("combat-tag.npc.despawn-particles", true);

        for (Map.Entry<UUID, CombatNpc> entry : new ArrayList<>(activeNpcsByPlayer.entrySet())) {
            UUID playerUuid = entry.getKey();
            CombatNpc npc = entry.getValue();

            LivingEntity entity = npc.getEntity();
            if (entity == null) {
                cleanupRetiredNpc(playerUuid, npc);
                continue;
            }
            plugin.getScheduler().runEntity(entity,
                    () -> tickNpc(playerUuid, npc, entity, nameFormat, withParticles),
                    () -> cleanupRetiredNpc(playerUuid, npc));
        }
    }

    private void tickNpc(UUID playerUuid, CombatNpc npc, LivingEntity entity, String nameFormat, boolean withParticles) {
        if (!entity.isValid() || entity.isDead()) {
            if (npc.getState() == CombatNpc.NpcState.ACTIVE) {
                handleNpcSlain(npc, entity.isValid() ? entity : null, null);
            } else {
                cleanupRetiredNpc(playerUuid, npc);
            }
            return;
        }

        int remaining = npc.decrementTimer();
        if (remaining <= 0) {
            if (npc.tryTransitionState(CombatNpc.NpcState.ACTIVE, CombatNpc.NpcState.DESPAWNED)) {
                npc.despawn(withParticles);
                releaseChunkTicket(npc.getSpawnLocation());
                cleanupRetiredNpc(playerUuid, npc);
                if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                            plugin.getRedisService().getServerId(),
                            dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_DESPAWN_SYNC,
                            playerUuid, npc.getPlayerName(), 0.0, null));
                }
            }
        } else {
            npc.updateCustomName(plugin, nameFormat);
        }
    }

    private void cleanupRetiredNpc(UUID playerUuid, CombatNpc npc) {
        activeNpcsByPlayer.remove(playerUuid, npc);
        activeNpcsByEntity.remove(npc.getEntityUuid(), npc);
        releaseChunkTicket(npc.getSpawnLocation());
    }

    private void addChunkTicket(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        try {
            loc.getWorld().addPluginChunkTicket(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, plugin);
        } catch (Throwable ignored) {}
    }

    private void releaseChunkTicket(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        try {
            loc.getWorld().removePluginChunkTicket(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, plugin);
        } catch (Throwable ignored) {}
    }

    public void spawnNpc(Player player, int durationSeconds) {
        if (player == null) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.npc.enabled", true)) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null) return;

        // Keep chunk loaded while combat NPC remains alive in world
        addChunkTicket(loc);

        UUID playerUuid = player.getUniqueId();
        long combatExpiry = System.currentTimeMillis() + durationSeconds * 1000L;
        pendingSpawns.put(playerUuid, combatExpiry);
        String playerName = player.getName();
        ItemStack[] inventory = player.getInventory().getStorageContents();
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack mainHandClone = mainHand != null ? mainHand.clone() : null;
        ItemStack offHandClone = offHand != null ? offHand.clone() : null;
        int level = player.getLevel();
        float exp = player.getExp();
        double health = Math.max(1.0, player.getHealth());
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null ?
                player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;

        boolean preferSkinHead = config.getBoolean("combat-tag.npc.prefer-player-skin-head", true);
        String nameFormat = config.getString("combat-tag.npc.name-format",
                "<gradient:#FF4D5A:#FF9A9A><bold>%player%</bold></gradient> <dark_gray>•</dark_gray> <white>%time%s</white>");

        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            try {
                skullMeta.setOwningPlayer(player);
            } catch (Throwable ignored) {}
            try {
                skullMeta.setPlayerProfile(player.getPlayerProfile());
            } catch (Throwable ignored) {}
            playerHead.setItemMeta(skullMeta);
        }

        plugin.getScheduler().runLocation(loc, () -> {
            if (!pendingSpawns.remove(playerUuid, combatExpiry)) {
                // Player rejoined before spawn execution finished; cancel to prevent duplicate NPC
                releaseChunkTicket(loc);
                return;
            }

            World world = loc.getWorld();
            if (world == null) return;

            ArmorStand spawnedEntity = world.spawn(loc, ArmorStand.class, armorStand -> {
                try {
                    armorStand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerUuid.toString());
                } catch (Throwable ignored) {}

                try {
                    AttributeInstance maxHealthAttr = armorStand.getAttribute(Attribute.MAX_HEALTH);
                    if (maxHealthAttr != null) {
                        maxHealthAttr.setBaseValue(maxHealth);
                    }
                    armorStand.setHealth(Math.min(health, maxHealth));
                } catch (Throwable ignored) {}

                try {
                    armorStand.setArms(true);
                    armorStand.setBasePlate(false);
                    armorStand.setSmall(false);
                    armorStand.setVisible(true);
                    armorStand.setGravity(true);
                    armorStand.setCollidable(false);
                    armorStand.setInvulnerable(false);
                    armorStand.setRemoveWhenFarAway(false);
                    armorStand.setPersistent(false);
                } catch (Throwable ignored) {}

                try {
                    armorStand.setCanMove(false);
                } catch (Throwable ignored) {}
                try {
                    armorStand.setCanTick(true);
                } catch (Throwable ignored) {}

                try {
                    EntityEquipment equipment = armorStand.getEquipment();
                    if (equipment != null) {
                        if (armor != null && armor.length >= 4) {
                            equipment.setBoots(armor[0] != null ? armor[0].clone() : null);
                            equipment.setLeggings(armor[1] != null ? armor[1].clone() : null);
                            equipment.setChestplate(armor[2] != null ? armor[2].clone() : null);
                            if (preferSkinHead || armor[3] == null || armor[3].getType() == Material.AIR) {
                                equipment.setHelmet(playerHead);
                            } else {
                                equipment.setHelmet(armor[3] != null ? armor[3].clone() : null);
                            }
                        } else {
                            equipment.setHelmet(playerHead);
                        }
                        equipment.setItemInMainHand(mainHandClone);
                        equipment.setItemInOffHand(offHandClone);
                        equipment.setHelmetDropChance(0.0f);
                        equipment.setChestplateDropChance(0.0f);
                        equipment.setLeggingsDropChance(0.0f);
                        equipment.setBootsDropChance(0.0f);
                        equipment.setItemInMainHandDropChance(0.0f);
                        equipment.setItemInOffHandDropChance(0.0f);
                    }
                } catch (Throwable ignored) {}

                // Lock all equipment slots to prevent players stripping gear without killing the NPC
                try {
                    for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                        for (ArmorStand.LockType lock : ArmorStand.LockType.values()) {
                            armorStand.addEquipmentLock(slot, lock);
                        }
                    }
                } catch (Throwable ignored) {}

                try {
                    Map<String, String> placeholders = Map.of(
                            "%player%", playerName,
                            "%time%", String.valueOf(durationSeconds)
                    );
                    armorStand.customName(plugin.getTextService().parse(nameFormat, placeholders));
                    armorStand.setCustomNameVisible(true);
                } catch (Throwable ignored) {}
            });

            if (spawnedEntity == null) return;

            CombatNpc combatNpc = new CombatNpc(
                    playerUuid, playerName, spawnedEntity.getUniqueId(), spawnedEntity,
                    durationSeconds, inventory, armor, offHand, level, exp, health, maxHealth, loc
            );

            activeNpcsByPlayer.put(playerUuid, combatNpc);
            activeNpcsByEntity.put(spawnedEntity.getUniqueId(), combatNpc);

            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                        plugin.getRedisService().getServerId(),
                        dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_SPAWN_SYNC,
                        playerUuid,
                        playerName,
                        durationSeconds,
                        null
                ));
            }

            if (config.getBoolean("combat-tag.npc.broadcast-on-spawn", true)) {
                plugin.getTextService().broadcastMessage("combat.npc-spawned-broadcast", Map.of("%player%", playerName));
            }
            if (config.getBoolean("combat-tag.npc.sounds", true)) {
                loc.getWorld().playSound(loc, Sound.ENTITY_ARMOR_STAND_PLACE, 1.0f, 1.0f);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (isCombatNpc(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (isCombatNpc(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (isCombatNpc(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNpcDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!isCombatNpc(event.getEntity())) return;
        if (event instanceof EntityDamageByEntityEvent) return;

        CombatNpc npc = activeNpcsByEntity.get(event.getEntity().getUniqueId());
        if (npc == null) return;

        // Health is tracked here; vanilla armor-stand damage must not also apply.
        event.setCancelled(true);
        double rawDamage = event.getDamage();
        if (!Double.isFinite(rawDamage) || rawDamage <= 0.0) return;
        double damage = npc.calculateMitigatedDamage(rawDamage);
        double newHealth = npc.getHealth() - damage;
        npc.setHealth(newHealth);

        Location loc = event.getEntity().getLocation().add(0, 1, 0);
        if (loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
            loc.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, loc, 3, 0.2, 0.2, 0.2, 0.1);
        }

        if (newHealth <= 0.0) {
            event.setCancelled(true);
            handleNpcSlain(npc, (LivingEntity) event.getEntity(), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNpcDamageByEntity(EntityDamageByEntityEvent event) {
        if (!isCombatNpc(event.getEntity())) return;

        CombatNpc npc = activeNpcsByEntity.get(event.getEntity().getUniqueId());
        if (npc == null) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker != null) {
            if (isAttackBlocked(attacker, npc, event.getEntity())) {
                event.setCancelled(true);
                return;
            }

            Player victimPlayer = Bukkit.getPlayer(npc.getPlayerUuid());
            if (event.getDamage() > 0.0 && Double.isFinite(event.getDamage())) {
                plugin.getPvpManager().getCombatTagManager().tagPlayer(attacker, victimPlayer);
            }
        }

        event.setCancelled(true);
        double rawDamage = event.getDamage();
        if (!Double.isFinite(rawDamage) || rawDamage <= 0.0) return;
        double damage = npc.calculateMitigatedDamage(rawDamage);
        double newHealth = npc.getHealth() - damage;
        npc.setHealth(newHealth);

        Location loc = event.getEntity().getLocation().add(0, 1, 0);
        if (loc.getWorld() != null) {
            loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);
            loc.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, loc, 3, 0.2, 0.2, 0.2, 0.1);
        }

        if (newHealth <= 0.0) {
            event.setCancelled(true);
            handleNpcSlain(npc, (LivingEntity) event.getEntity(), event.getDamager());
        }
    }

    // Spear jabs and charges still knock back after their damage event is cancelled.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcPreAttack(PrePlayerAttackEntityEvent event) {
        CombatNpc npc = activeNpcsByEntity.get(event.getAttacked().getUniqueId());
        if (npc != null && isAttackBlocked(event.getPlayer(), npc, event.getAttacked())) event.setCancelled(true);
    }

    private boolean isAttackBlocked(Player attacker, CombatNpc npc, Entity npcEntity) {
        if (!plugin.getPvpManager().hasPvpEnabled(attacker.getUniqueId())) {
            plugin.getTextService().sendMessage(attacker, "pvp-toggle.self-pvp-disabled");
            return true;
        }
        if (!plugin.getPvpManager().hasPvpEnabled(npc.getPlayerUuid())) {
            plugin.getTextService().sendMessage(attacker, "pvp-toggle.target-pvp-disabled", Map.of("%target%", npc.getPlayerName()));
            return true;
        }

        if (plugin.getPvpManager().getNewbieProtectionManager().hasProtection(attacker.getUniqueId())) {
            plugin.getTextService().sendMessage(attacker, "newbie.blocked-attack-them");
            return true;
        }
        if (plugin.getPvpManager().getNewbieProtectionManager().hasProtection(npc.getPlayerUuid())) {
            plugin.getTextService().sendMessage(attacker, "newbie.blocked-attack-by-them", Map.of("%target%", npc.getPlayerName()));
            return true;
        }

        if (plugin.getTeamsModule() != null && plugin.getTeamsModule().getTeamsManager() != null) {
            var teamsMgr = plugin.getTeamsModule().getTeamsManager();
            var attackerTeamOpt = teamsMgr.getTeamByPlayer(attacker.getUniqueId());
            var victimTeamOpt = teamsMgr.getTeamByPlayer(npc.getPlayerUuid());
            if (attackerTeamOpt.isPresent() && victimTeamOpt.isPresent()) {
                var attackerTeam = attackerTeamOpt.get();
                var victimTeam = victimTeamOpt.get();
                if (attackerTeam.getUniqueId().equals(victimTeam.getUniqueId()) && !attackerTeam.isFriendlyFireEnabled()) {
                    Messages.send(plugin, attacker, "pvp", "friendly-fire-is-disabled-for-your");
                    return true;
                }
                if (attackerTeam.isAlly(victimTeam.getUniqueId()) && (!attackerTeam.isFriendlyFireEnabled() || !victimTeam.isFriendlyFireEnabled())) {
                    Messages.send(plugin, attacker, "pvp", "friendly-fire-is-disabled-between-allied");
                    return true;
                }
            }
        }

        List<String> customSafezones = plugin.getConfigService().getPvpConfig().getStringList("barriers.custom-safezones");
        return !plugin.getHookManager().getProtectionHook().isPvpAllowed(attacker.getLocation(), null, customSafezones)
                || !plugin.getHookManager().getProtectionHook().isPvpAllowed(npcEntity.getLocation(), null, customSafezones);
    }

    private void handleNpcSlain(CombatNpc npc, LivingEntity entity, Entity damager) {
        if (!npc.tryTransitionState(CombatNpc.NpcState.ACTIVE, CombatNpc.NpcState.SLAIN)) {
            return;
        }

        activeNpcsByPlayer.remove(npc.getPlayerUuid());
        if (entity != null) {
            activeNpcsByEntity.remove(entity.getUniqueId());
        } else {
            activeNpcsByEntity.remove(npc.getEntityUuid());
        }
        releaseChunkTicket(npc.getSpawnLocation());

        Location loc = entity != null ? entity.getLocation() : npc.getSpawnLocation();
        npc.dropLoot(loc);
        persistCombatLogDeath(npc.getPlayerUuid());

        if (entity != null) {
            EntityEquipment eq = entity.getEquipment();
            if (eq != null) {
                eq.setBoots(null);
                eq.setLeggings(null);
                eq.setChestplate(null);
                eq.setHelmet(null);
                eq.setItemInMainHand(null);
                eq.setItemInOffHand(null);
                eq.clear();
            }
            entity.remove();
        }

        // Broadcast cross-server packet to invalidate inventory if player connects to another node
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().publish(new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                    plugin.getRedisService().getServerId(),
                    dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_SLAIN_SYNC,
                    npc.getPlayerUuid(),
                    npc.getPlayerName(),
                    0.0,
                    null
            ));
        }

        PlayerStats victimStats = plugin.getStatsManager().getStats(npc.getPlayerUuid(), npc.getPlayerName());
        if (victimStats != null) {
            victimStats.recordDeath();
        }

        Player killer = resolveAttacker(damager);
        if (killer != null && !killer.getUniqueId().equals(npc.getPlayerUuid())) {
            boolean rewardKill = plugin.getPvpManager().shouldRewardKill(killer.getUniqueId(), npc.getPlayerUuid());
            PlayerStats killerStats = plugin.getStatsManager().getStats(killer.getUniqueId(), killer.getName());
            if (rewardKill && killerStats != null) {
                killerStats.recordKill();
            }

            OfflinePlayer offlineVictim = Bukkit.getOfflinePlayer(npc.getPlayerUuid());
            var economyResult = rewardKill
                    ? plugin.getPvpManager().getPvpEconomyManager().handlePlayerKill(killer, offlineVictim)
                    : java.util.concurrent.CompletableFuture.completedFuture(PvpEconomyManager.PvpEconomyResult.ZERO);

            economyResult.thenAccept(ecoResult -> plugin.getScheduler().runGlobal(() -> {
                String formattedLost = ecoResult != null && ecoResult.formattedLost() != null ? ecoResult.formattedLost() : "0";
                String formattedGained = ecoResult != null && ecoResult.formattedGained() != null ? ecoResult.formattedGained() : "0";

                plugin.getTextService().broadcastMessage("combat.npc-slain-broadcast", Map.of(
                        "%player%", npc.getPlayerName(),
                        "%victim%", npc.getPlayerName(),
                        "%killer%", killer.getName(),
                        "%money_lost%", formattedLost,
                        "%money_gained%", formattedGained,
                        "%amount%", formattedLost,
                        "%money%", formattedLost
                ));
            }));

            plugin.getPvpManager().getCombatTagManager().untagPlayer(killer, PlayerCombatUntagEvent.UntagReason.OPPONENT_DEATH);
            plugin.getPvpManager().getCombatTagManager().autoUntagOpponentsOf(npc.getPlayerUuid());
        } else {
            plugin.getPvpManager().getCombatTagManager().autoUntagOpponentsOf(npc.getPlayerUuid());
            plugin.getTextService().broadcastMessage("combat.npc-died-broadcast", Map.of(
                    "%player%", npc.getPlayerName(),
                    "%victim%", npc.getPlayerName()
            ));
        }
    }

    public void handleRemoteNpcSlain(UUID playerUuid, String playerName) {
        if (playerUuid == null) return;
        persistCombatLogDeath(playerUuid);

        Player onlinePlayer = Bukkit.getPlayer(playerUuid);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            plugin.getScheduler().runEntity(onlinePlayer, () -> enforceCombatLogDeath(onlinePlayer));
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        if (damager instanceof AreaEffectCloud aec && aec.getSource() instanceof Player shooter) return shooter;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player primer) return primer;
        if (damager instanceof EvokerFangs fangs && fangs.getOwner() instanceof Player owner) return owner;
        if (damager instanceof LightningStrike ls && ls.getCausingPlayer() != null) return ls.getCausingPlayer();
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player owner) return owner;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // If an NPC spawn task was in-flight, cancel it immediately so no duplicate clone spawns
        Long pendingExpiry = pendingSpawns.remove(uuid);
        if (pendingExpiry != null) {
            int remaining = (int) Math.ceil(Math.max(0L, pendingExpiry - System.currentTimeMillis()) / 1000.0);
            plugin.getPvpManager().getCombatTagManager().tagPlayer(player, null, remaining);
        }

        // Mark before MC-Data-Bridge (HIGHEST) applies the quit snapshot, otherwise
        // dropped NPC loot is duplicated back into the player's inventory.
        if (pendingDeathsOnLogin.contains(uuid)) {
            markCombatNpcSlain(player);
            stripCombatLogItems(player);
            if (plugin.getSpawnManager() != null) {
                plugin.getSpawnManager().markPendingRespawn(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinAfterSync(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if ((pendingDeathsOnLogin.contains(uuid) || activeNpcsByPlayer.containsKey(uuid))
                && deferUntilSyncReady(player, () -> onPlayerJoinAfterSync(event))) {
            return;
        }

        if (pendingDeathsOnLogin.contains(uuid) || player.hasMetadata(COMBAT_NPC_SLAIN_META)) {
            enforceCombatLogDeath(player);
            return;
        }

        CombatNpc npc = activeNpcsByPlayer.remove(uuid);
        if (npc != null) {
            if (npc.tryTransitionState(CombatNpc.NpcState.ACTIVE, CombatNpc.NpcState.REJOINED)) {
                activeNpcsByEntity.remove(npc.getEntityUuid());
                releaseChunkTicket(npc.getSpawnLocation());

                LivingEntity entity = npc.getEntity();
                if (entity != null) {
                    plugin.getScheduler().runEntity(entity, () -> {
                        if (!entity.isValid() || entity.isDead()) return;
                        double remainingHealth = Math.max(1.0, npc.getHealth());
                        Location currentLoc = entity.getLocation();
                        int fireTicks = entity.getFireTicks();
                        plugin.getScheduler().runEntity(player, () -> {
                            if (!player.isOnline()) return;
                            double playerMaxHealth = player.getAttribute(Attribute.MAX_HEALTH) != null ?
                                    player.getAttribute(Attribute.MAX_HEALTH).getValue() : 20.0;
                            player.setHealth(Math.min(playerMaxHealth, remainingHealth));
                            if (fireTicks > 0) {
                                player.setFireTicks(fireTicks);
                            }
                            plugin.getPvpManager().getCombatTagManager().tagPlayer(player, null, npc.getRemainingSeconds());
                            dev.lyy.friendlycore.common.SafeTeleportUtil.teleportAsync(player, currentLoc);
                        });
                        npc.despawn(false);
                    });
                }
            } else if (npc.getState() == CombatNpc.NpcState.SLAIN) {
                enforceCombatLogDeath(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof ArmorStand && entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
                if (!activeNpcsByEntity.containsKey(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        CombatNpc npc = activeNpcsByEntity.remove(entity.getUniqueId());
        if (npc == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        handleNpcSlain(npc, entity, entity.getKiller());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onNpcCombust(EntityCombustEvent event) {
        if (!activeNpcsByEntity.containsKey(event.getEntity().getUniqueId())) return;

        if (!(event instanceof EntityCombustByBlockEvent) && !(event instanceof EntityCombustByEntityEvent)) {
            event.setCancelled(true);
        }
    }

    public boolean isCombatNpc(Entity entity) {
        if (entity == null) return false;
        return activeNpcsByEntity.containsKey(entity.getUniqueId()) ||
                entity.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    public CombatNpc getNpcByPlayer(UUID playerUuid) {
        return activeNpcsByPlayer.get(playerUuid);
    }

    public boolean hasCombatLogout(UUID playerUuid) {
        return pendingSpawns.containsKey(playerUuid) || activeNpcsByPlayer.containsKey(playerUuid)
                || pendingDeathsOnLogin.contains(playerUuid);
    }

    public CombatNpc getNpcByEntity(UUID entityUuid) {
        return activeNpcsByEntity.get(entityUuid);
    }

    private void persistCombatLogDeath(UUID playerUuid) {
        pendingDeathsOnLogin.add(playerUuid);
        savePendingDeathsAsync();
        try {
            if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null) {
                plugin.getHookManager().getMcDataBridgeHook().recordOfflineDeathDrop(playerUuid);
            }
        } catch (Throwable ignored) {
            // Data-bridge is optional; pending-death flag still enforces the penalty on join.
        }
    }

    private void markCombatNpcSlain(Player player) {
        if (player == null) return;
        try {
            player.setMetadata(COMBAT_NPC_SLAIN_META, new FixedMetadataValue(plugin, true));
        } catch (Throwable ignored) {}
    }

    private void stripCombatLogItems(Player player) {
        if (player == null) return;
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setExp(0.0f);
        player.setLevel(0);
    }

    private void enforceCombatLogDeath(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        if (!pendingDeathsOnLogin.contains(uuid)) return;
        if (deferUntilSyncReady(player, () -> enforceCombatLogDeath(player))) return;
        if (!pendingDeathsOnLogin.remove(uuid)) return;
        savePendingDeathsAsync();
        markCombatNpcSlain(player);
        stripCombatLogItems(player);
        if (plugin.getSpawnManager() != null) {
            plugin.getSpawnManager().markPendingRespawn(player);
        }

        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline()) return;
            stripCombatLogItems(player);
            try {
                plugin.getPvpManager().getCombatTagManager().prepareCombatLogoutDeath(player);
                if (!player.isDead() && player.getHealth() > 0.0) {
                    player.setHealth(0.0);
                } else if (player.isDead()) {
                    plugin.getPvpManager().getCombatTagManager().respawnCombatLogger(player);
                }
            } catch (Throwable ignored) {}
        });

        plugin.getScheduler().runEntityLater(player, () -> {
            if (!player.isOnline()) return;
            if (!player.hasMetadata(COMBAT_NPC_SLAIN_META)) return;
            stripCombatLogItems(player);
        }, 2L);
    }

    private boolean deferUntilSyncReady(Player player, Runnable retry) {
        var hook = plugin.getHookManager() != null ? plugin.getHookManager().getMcDataBridgeHook() : null;
        if (hook == null || !hook.isPlayerLocked(player)) return false;
        // Async join restoration must finish before changing NPC health/location or consuming a death penalty.
        UUID uuid = player.getUniqueId();
        plugin.getScheduler().runEntityLater(player, () -> {
            if (Bukkit.getPlayer(uuid) == player && player.isOnline()) retry.run();
        }, 5L);
        return true;
    }

    private void loadPendingDeaths() {
        if (pendingDeathsFile == null || !pendingDeathsFile.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(pendingDeathsFile);
            List<String> list = yaml.getStringList("pending-deaths");
            for (String str : list) {
                try {
                    pendingDeathsOnLogin.add(UUID.fromString(str));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load pending combat NPC deaths", e);
        }
    }

    private void savePendingDeathsAsync() {
        plugin.getScheduler().runAsync(this::savePendingDeaths);
    }

    private synchronized void savePendingDeaths() {
        if (pendingDeathsFile == null) return;
        try {
            if (!pendingDeathsFile.getParentFile().exists()) {
                pendingDeathsFile.getParentFile().mkdirs();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            List<String> list = new ArrayList<>();
            for (UUID uuid : pendingDeathsOnLogin) {
                list.add(uuid.toString());
            }
            yaml.set("pending-deaths", list);
            yaml.save(pendingDeathsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save pending combat NPC deaths", e);
        }
    }

    public void shutdown() {
        if (tickerTask != null) {
            plugin.getScheduler().cancel(tickerTask);
            tickerTask = null;
        }

        for (CombatNpc npc : activeNpcsByPlayer.values()) {
            releaseChunkTicket(npc.getSpawnLocation());
            npc.despawn(false);
        }
        activeNpcsByPlayer.clear();
        activeNpcsByEntity.clear();

        savePendingDeaths();
    }
}
