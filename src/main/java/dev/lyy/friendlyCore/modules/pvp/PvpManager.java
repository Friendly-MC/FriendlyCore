package dev.lyy.friendlycore.modules.pvp;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.pvp.events.PlayerTogglePvpEvent;
import dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Master manager coordinating combat tagging, safezone barriers, newbie protection,
 * item blockers, PvP economy rewards, and death attribution.
 */
public class PvpManager implements Listener {

    private final FriendlyCore plugin;
    private final CombatTagManager combatTagManager;
    private final NewbieProtectionManager newbieProtectionManager;
    private final DisplayBarrierManager displayBarrierManager;
    private final CombatItemManager combatItemManager;
    private final PvpEconomyManager pvpEconomyManager;
    private final KillRewardLimiter killRewardLimiter = new KillRewardLimiter();
    private final dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager combatNpcManager;

    private final Map<UUID, Boolean> pvpToggledOff = new ConcurrentHashMap<>();
    private final Map<UUID, Long> toggleCooldowns = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> adminBypassSet = ConcurrentHashMap.newKeySet();

    public PvpManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.combatNpcManager = new dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager(plugin);
        this.combatTagManager = new CombatTagManager(plugin);
        this.newbieProtectionManager = new NewbieProtectionManager(plugin);
        this.displayBarrierManager = new DisplayBarrierManager(plugin, combatTagManager);
        this.combatItemManager = new CombatItemManager(plugin, combatTagManager);
        this.pvpEconomyManager = new PvpEconomyManager(plugin);
    }

    public void initialize() {
        combatNpcManager.initialize();
        combatTagManager.initialize();
        newbieProtectionManager.initialize();
        displayBarrierManager.initialize();
        combatItemManager.initialize();
        pvpEconomyManager.initialize();

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public CombatNpcManager getCombatNpcManager() {
        return combatNpcManager;
    }

    public CombatTagManager getCombatTagManager() {
        return combatTagManager;
    }

    public NewbieProtectionManager getNewbieProtectionManager() {
        return newbieProtectionManager;
    }

    public DisplayBarrierManager getDisplayBarrierManager() {
        return displayBarrierManager;
    }

    public CombatItemManager getCombatItemManager() {
        return combatItemManager;
    }

    public PvpEconomyManager getPvpEconomyManager() {
        return pvpEconomyManager;
    }

    public boolean shouldRewardKill(UUID killer, UUID victim) {
        int cooldown = plugin.getConfigService().getPvpConfig().getInt("player-kills.anti-farm-cooldown-seconds", 300);
        return killRewardLimiter.allow(killer, victim, System.currentTimeMillis(), cooldown);
    }

    public boolean hasAdminBypass(UUID uuid) {
        if (uuid == null) return false;
        return adminBypassSet.contains(uuid);
    }

    public void setAdminBypass(UUID uuid, boolean bypass) {
        if (uuid == null) return;
        if (bypass) {
            adminBypassSet.add(uuid);
        } else {
            adminBypassSet.remove(uuid);
        }
    }

    public boolean toggleAdminBypass(UUID uuid) {
        if (uuid == null) return false;
        if (adminBypassSet.contains(uuid)) {
            adminBypassSet.remove(uuid);
            return false;
        } else {
            adminBypassSet.add(uuid);
            return true;
        }
    }

    public boolean hasPvpEnabled(UUID uuid) {
        return !pvpToggledOff.getOrDefault(uuid, false);
    }

    public boolean togglePvp(Player player) {
        UUID uuid = player.getUniqueId();
        if (hasPvpEnabled(uuid) && combatTagManager.isTagged(uuid)) {
            plugin.getTextService().sendMessage(player, "pvp-toggle.combat-blocked");
            return true;
        }
        long now = System.currentTimeMillis();
        Long lastToggle = toggleCooldowns.get(uuid);

        if (lastToggle != null && now - lastToggle < 5000L) {
            int remaining = (int) Math.ceil((5000L - (now - lastToggle)) / 1000.0);
            plugin.getTextService().sendMessage(player, "pvp-toggle.cooldown", Map.of("%time%", String.valueOf(remaining)));
            return hasPvpEnabled(uuid);
        }

        boolean current = hasPvpEnabled(uuid);
        boolean newState = !current;

        PlayerTogglePvpEvent event = new PlayerTogglePvpEvent(player, newState);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return current;
        }

        toggleCooldowns.put(uuid, now);

        if (newState) {
            pvpToggledOff.remove(uuid);
            plugin.getTextService().sendMessage(player, "pvp-toggle.enabled");
        } else {
            pvpToggledOff.put(uuid, true);
            plugin.getTextService().sendMessage(player, "pvp-toggle.disabled");
        }

        return newState;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvpToggleDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (isPvpBlocked(attacker, victim)) event.setCancelled(true);
    }

    // Spear jabs and charges still knock back and dismount after their damage event is
    // cancelled, so safezone and toggle rules must stop the attack before it starts.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Player victim) || victim.equals(event.getPlayer())) return;
        if (isPvpBlocked(event.getPlayer(), victim)) event.setCancelled(true);
    }

    private boolean isPvpBlocked(Player attacker, Player victim) {
        var safezones = plugin.getConfigService().getPvpConfig().getStringList("barriers.custom-safezones");
        var protection = plugin.getHookManager().getProtectionHook();
        if (!protection.isPvpAllowed(attacker.getLocation(), attacker, safezones)
                || !protection.isPvpAllowed(victim.getLocation(), victim, safezones)) {
            return true;
        }

        if (!hasPvpEnabled(victim.getUniqueId())) {
            plugin.getTextService().sendMessage(attacker, "pvp-toggle.target-pvp-disabled", Map.of("%target%", victim.getName()));
            return true;
        }

        if (!hasPvpEnabled(attacker.getUniqueId())) {
            plugin.getTextService().sendMessage(attacker, "pvp-toggle.self-pvp-disabled");
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(org.bukkit.event.entity.PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player shooter)) return;

        for (org.bukkit.entity.LivingEntity affected : event.getAffectedEntities()) {
            if (!(affected instanceof Player victim)) continue;
            if (victim.equals(shooter)) continue;

            if (!hasPvpEnabled(victim.getUniqueId()) || !hasPvpEnabled(shooter.getUniqueId())) {
                event.setIntensity(victim, 0.0);
            }
        }
    }

    public Player resolveAttacker(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player p) return p;
        if (damager instanceof EnderCrystal crystal) {
            return combatTagManager.resolveCrystalDetonator(crystal.getUniqueId());
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        if (damager instanceof AreaEffectCloud aec && aec.getSource() instanceof Player shooter) return shooter;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player primer) return primer;
        if (damager instanceof EvokerFangs fangs && fangs.getOwner() instanceof Player owner) return owner;
        if (damager instanceof LightningStrike ls && ls.getCausingPlayer() != null) return ls.getCausingPlayer();
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player owner) return owner;
        return null;
    }

    public Player resolveKiller(Player victim) {
        if (victim == null) return null;
        if (victim.getKiller() != null && !victim.getKiller().equals(victim)) {
            return victim.getKiller();
        }

        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (lastDamage instanceof EntityDamageByEntityEvent edbe) {
            Player attacker = resolveAttacker(edbe.getDamager());
            if (attacker != null && !attacker.equals(victim)) {
                return attacker;
            }
        } else if (lastDamage != null && lastDamage.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            Player detonator = combatTagManager.resolveAnchorDetonator(victim.getLocation());
            if (detonator != null && !detonator.equals(victim)) {
                return detonator;
            }
        }

        // Fallback to active or recent combat tag opponent
        UUID oppUuid = combatTagManager.getOpponent(victim.getUniqueId());
        if (oppUuid == null) {
            oppUuid = combatTagManager.getRecentOpponent(victim.getUniqueId());
        }
        if (oppUuid != null && !oppUuid.equals(victim.getUniqueId())) {
            Player opp = Bukkit.getPlayer(oppUuid);
            if (opp != null && opp.isOnline()) {
                return opp;
            }
        }

        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        // The offline NPC death already recorded stats and settled the reward.
        if (victim.hasMetadata(dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager.COMBAT_NPC_SLAIN_META)) return;
        Player killer = resolveKiller(victim);
        if (killer == null || killer.equals(victim)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();

        PlayerStats killerStats = plugin.getStatsManager().getStats(killer.getUniqueId(), killer.getName());
        PlayerStats victimStats = plugin.getStatsManager().getStats(victim.getUniqueId(), victim.getName());
        victimStats.recordDeath();
        if (!shouldRewardKill(killer.getUniqueId(), victim.getUniqueId())) return;
        killerStats.recordKill();

        pvpEconomyManager.handlePlayerKill(killer, victim).thenAccept(ecoResult -> {
            String formattedLost = ecoResult.formattedLost() != null && !ecoResult.formattedLost().isEmpty() ? ecoResult.formattedLost() : "0";
            String formattedGained = ecoResult.formattedGained() != null && !ecoResult.formattedGained().isEmpty() ? ecoResult.formattedGained() : "0";

            Map<String, String> placeholders = Map.ofEntries(
                    Map.entry("%victim%", victim.getName()),
                    Map.entry("%killer%", killer.getName()),
                    Map.entry("%kills%", String.valueOf(killerStats.getKills())),
                    Map.entry("%deaths%", String.valueOf(victimStats.getDeaths())),
                    Map.entry("%streak%", String.valueOf(killerStats.getCurrentStreak())),
                    Map.entry("%highest_streak%", String.valueOf(killerStats.getHighestStreak())),
                    Map.entry("%kd%", String.format(Locale.ROOT, "%.2f", killerStats.getKDRatio())),
                    Map.entry("%money_lost%", formattedLost),
                    Map.entry("%money_gained%", formattedGained),
                    Map.entry("%money%", formattedLost),
                    Map.entry("%amount%", formattedLost),
                    Map.entry("%lost%", formattedLost),
                    Map.entry("%stolen%", formattedGained)
            );

            // Schedule killer title and audio cues on their entity thread
            plugin.getScheduler().runEntity(killer, () -> {
                if (config.getBoolean("titles.enabled", true)) {
                    int fadeIn = config.getInt("titles.fade-in", 5);
                    int stay = config.getInt("titles.stay", 40);
                    int fadeOut = config.getInt("titles.fade-out", 5);

                    String killTitle = config.getString("titles.kill-title", "<gradient:#00e676:#00b0ff><bold>KILL!</bold></gradient>");
                    String killSubtitle = config.getString("titles.kill-subtitle", "<gray>Defeated <white>%victim%</white> <dark_gray>(Streak: <#00e676>%streak%</#00e676>)</dark_gray></gray>");
                    plugin.getTextService().sendTitle(killer, killTitle, killSubtitle, fadeIn, stay, fadeOut, placeholders);
                }

                if (config.getBoolean("sounds.kill.enabled", true)) {
                    String killSound = config.getString("sounds.kill.type", "ENTITY_PLAYER_LEVELUP");
                    float vol = (float) config.getDouble("sounds.kill.volume", 1.0);
                    float pitch = (float) config.getDouble("sounds.kill.pitch", 1.2);
                    TextService.playSound(killer, killSound, vol, pitch);
                }
            });

            // Schedule victim death screen and audio cues on their entity thread
            plugin.getScheduler().runEntity(victim, () -> {
                if (config.getBoolean("titles.enabled", true)) {
                    int fadeIn = config.getInt("titles.fade-in", 5);
                    int stay = config.getInt("titles.stay", 40);
                    int fadeOut = config.getInt("titles.fade-out", 5);

                    String deathTitle = config.getString("titles.death-title", "<gradient:#ff5252:#ff1744><bold>YOU DIED</bold></gradient>");
                    String deathSubtitle = config.getString("titles.death-subtitle", "<gray>Slain by <white>%killer%</white> <dark_gray>•</dark_gray> <red>-$%money_lost%</red></gray>");
                    plugin.getTextService().sendTitle(victim, deathTitle, deathSubtitle, fadeIn, stay, fadeOut, placeholders);
                }

                if (config.getBoolean("sounds.death.enabled", true)) {
                    String deathSound = config.getString("sounds.death.type", "ENTITY_VILLAGER_DEATH");
                    float vol = (float) config.getDouble("sounds.death.volume", 1.0);
                    float pitch = (float) config.getDouble("sounds.death.pitch", 0.8);
                    TextService.playSound(victim, deathSound, vol, pitch);
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        toggleCooldowns.remove(uuid);
        adminBypassSet.remove(uuid);
    }

    public void reload() {
        displayBarrierManager.loadBlockData();
    }

    public void shutdown() {
        combatNpcManager.shutdown();
        combatTagManager.shutdown();
        newbieProtectionManager.shutdown();
        displayBarrierManager.shutdown();
        combatItemManager.shutdown();
        pvpToggledOff.clear();
        toggleCooldowns.clear();
        adminBypassSet.clear();
    }

    static final class KillRewardLimiter {
        private record Pair(UUID killer, UUID victim) {}

        private final Cache<Pair, Long> lastKills = CacheBuilder.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();

        synchronized boolean allow(UUID killer, UUID victim, long nowMillis, int cooldownSeconds) {
            if (cooldownSeconds <= 0) return true;
            Pair pair = new Pair(killer, victim);
            Long last = lastKills.getIfPresent(pair);
            if (last != null && nowMillis - last < Math.min(cooldownSeconds, 3600) * 1000L) return false;
            lastKills.put(pair, nowMillis);
            return true;
        }
    }
}
