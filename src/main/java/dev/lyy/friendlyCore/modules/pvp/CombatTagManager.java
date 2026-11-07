package dev.lyy.friendlycore.modules.pvp;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatTagEvent;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active player combat tags, boss bars, action bar countdowns,
 * safe logout handling, and combat logger NPC spawns.
 */
public class CombatTagManager implements Listener {

    private static final NamespacedKey LOGOUT_DEATH_KEY = NamespacedKey.fromString("friendlycore:combat_logout_death");

    private final FriendlyCore plugin;
    private final Map<UUID, Long> taggedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> opponentMap = new ConcurrentHashMap<>();
    private final Map<UUID, RecentOpponentInfo> recentOpponents = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> maxDurationMap = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> combatBossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastElytraNotify = new ConcurrentHashMap<>();
    private volatile boolean isShuttingDown = false;
    private Object tickerTask;

    public static class RecentOpponentInfo {
        private final UUID opponentUuid;
        private final long timestamp;

        public RecentOpponentInfo(UUID opponentUuid, long timestamp) {
            this.opponentUuid = opponentUuid;
            this.timestamp = timestamp;
        }

        public UUID getOpponentUuid() {
            return opponentUuid;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    public CombatTagManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startTicker();
    }

    private void startTicker() {
        // Player lookup and task routing must start on the global scheduler. The
        // actual player work below is handed to each player's entity scheduler.
        this.tickerTask = plugin.getScheduler().runGlobalTimer(this::tickCombat, 20L, 20L);
    }

    public boolean isShuttingDown() {
        return isShuttingDown || Bukkit.isStopping();
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.isShuttingDown = shuttingDown;
    }

    private void tickCombat() {
        if (isShuttingDown()) return;

        long now = System.currentTimeMillis();
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        FileConfiguration messages = plugin.getConfigService().getPvpMessages();

        boolean showActionbar = config.getBoolean("combat-tag.actionbar.enabled", false);
        boolean showBossbar = config.getBoolean("combat-tag.bossbar.enabled", false);
        String actionbarFormat = messages.getString("combat.actionbar", "&fCombat: #FF0000%time%s");

        for (Map.Entry<UUID, Long> entry : taggedPlayers.entrySet()) {
            UUID uuid = entry.getKey();
            long expiry = entry.getValue();

            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                // Keep tagged entry intact for onQuit lifecycle handling to avoid race conditions
                removeBossBar(uuid);
                continue;
            }

            int remainingSeconds = (int) Math.ceil(Math.max(0L, expiry - now) / 1000.0);
            UUID oppUuid = opponentMap.get(uuid);
            String oppName = "Player";
            if (oppUuid != null) {
                Player opp = Bukkit.getPlayer(oppUuid);
                if (opp != null) {
                    oppName = opp.getName();
                }
            }

            final String finalOppName = oppName;
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) return;
                Long currentExpiry = taggedPlayers.get(uuid);
                if (currentExpiry == null) return;
                if (System.currentTimeMillis() >= currentExpiry) {
                    untagPlayer(player, PlayerCombatUntagEvent.UntagReason.EXPIRED);
                    return;
                }

                stopBlockedGliding(player);

                if (showActionbar) {
                    Map<String, String> placeholders = Map.of(
                            "%time%", String.valueOf(remainingSeconds),
                            "%timer%", String.valueOf(remainingSeconds),
                            "%opponent%", finalOppName,
                            "%tagger%", finalOppName
                    );
                    plugin.getTextService().sendActionBar(player, actionbarFormat, placeholders);
                }

                if (showBossbar) {
                    int maxDur = maxDurationMap.getOrDefault(uuid, config.getInt("combat-tag.duration", 15));
                    updateBossBar(player, remainingSeconds, maxDur, finalOppName);
                }
            });
        }

        // Prune crystal and anchor detonator caches older than 10 seconds
        if (!crystalDetonators.isEmpty()) {
            crystalDetonators.entrySet().removeIf(e -> (now - e.getValue().getTimestamp()) > 10000L);
        }
        if (!anchorDetonators.isEmpty()) {
            anchorDetonators.entrySet().removeIf(e -> (now - e.getValue().getTimestamp()) > 10000L);
        }
        if (!recentOpponents.isEmpty()) {
            recentOpponents.entrySet().removeIf(e -> (now - e.getValue().getTimestamp()) > 15000L);
        }
    }

    public boolean isTagged(UUID uuid) {
        if (uuid == null) return false;
        Long expiry = taggedPlayers.get(uuid);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public int getRemainingSeconds(UUID uuid) {
        Long expiry = taggedPlayers.get(uuid);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    public UUID getOpponent(UUID uuid) {
        return opponentMap.get(uuid);
    }

    public UUID getRecentOpponent(UUID uuid) {
        if (uuid == null) return null;
        UUID activeOpponent = opponentMap.get(uuid);
        if (activeOpponent != null) return activeOpponent;
        RecentOpponentInfo info = recentOpponents.get(uuid);
        if (info != null && (System.currentTimeMillis() - info.getTimestamp()) < 10000L) {
            return info.getOpponentUuid();
        }
        return null;
    }

    public boolean isBypassing(Player player) {
        if (player == null) return false;
        if (plugin.getPvpManager().hasAdminBypass(player.getUniqueId())) {
            return true;
        }
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        boolean ignoreOp = config.getBoolean("combat-tag.ignore-op", true);
        if (ignoreOp && player.isOp()) {
            return false;
        }
        return player.hasPermission("friendlycore.pvp.bypass");
    }

    public void tagPlayer(Player player, Player opponent) {
        tagPlayer(player, opponent, plugin.getConfigService().getPvpConfig().getInt("combat-tag.duration", 15));
    }

    public void tagPlayer(Player player, Player opponent, int duration) {
        if (player == null || !player.isOnline()) return;
        if (plugin.getScheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(player)) {
            plugin.getScheduler().runEntity(player, () -> tagPlayer(player, opponent, duration));
            return;
        }
        if (isShuttingDown()) return;
        if (isTransferFrozen(player)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.enabled", true)) return;

        List<String> blacklistedWorlds = config.getStringList("combat-tag.blacklisted-worlds");
        if (blacklistedWorlds.contains(player.getWorld().getName())) return;

        if (isBypassing(player)) return;

        if (duration <= 0) return;
        PlayerCombatTagEvent event = new PlayerCombatTagEvent(player, opponent, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        boolean wasTagged = isTagged(player.getUniqueId());
        long expiry = System.currentTimeMillis() + (event.getDurationSeconds() * 1000L);
        taggedPlayers.put(player.getUniqueId(), expiry);
        maxDurationMap.put(player.getUniqueId(), event.getDurationSeconds());

        // tagPlayer already runs on the owning region. Stop an existing glide now,
        // before a rocket boost can slip through while deferred tasks are pending.
        stopBlockedGliding(player);

        String oppName = opponent != null ? opponent.getName() : "Player";
        if (opponent != null) {
            opponentMap.put(player.getUniqueId(), opponent.getUniqueId());
            recentOpponents.put(player.getUniqueId(), new RecentOpponentInfo(opponent.getUniqueId(), System.currentTimeMillis()));
        }

        sendInstantActionbar(player, event.getDurationSeconds(), oppName);
        if (config.getBoolean("combat-tag.bossbar.enabled", false)) {
            updateBossBar(player, event.getDurationSeconds(), event.getDurationSeconds(), oppName);
        }

        plugin.getScheduler().runEntity(player, () -> {
            if (!player.isOnline() || !isTagged(player.getUniqueId()) || isShuttingDown()) return;
            dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(player.getUniqueId());

            if (config.getBoolean("combat-tag.disable-flight", true) && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
                if (player.isFlying() || player.getAllowFlight()) {
                    player.setAllowFlight(false);
                    player.setFlying(false);
                    plugin.getTextService().sendMessage(player, "combat.fly-disabled");
                }
            }
        });

        if (!wasTagged) {
            if (config.getBoolean("sounds.tag.enabled", true)) {
                String soundType = config.getString("sounds.tag.type", "ENTITY_PLAYER_ATTACK_CRIT");
                float vol = (float) config.getDouble("sounds.tag.volume", 1.0);
                float pitch = (float) config.getDouble("sounds.tag.pitch", 1.0);
                TextService.playSound(player, soundType, vol, pitch);
            }

            plugin.getTextService().sendMessage(player, "combat.tagged", Map.of("%opponent%", oppName));
        }
    }

    private void sendInstantActionbar(Player player, int remainingSeconds, String opponentName) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.actionbar.enabled", false)) return;

        FileConfiguration messages = plugin.getConfigService().getPvpMessages();
        String actionbarFormat = messages.getString("combat.actionbar", "&fCombat: #FF0000%time%s");

        Map<String, String> placeholders = Map.of(
                "%time%", String.valueOf(remainingSeconds),
                "%timer%", String.valueOf(remainingSeconds),
                "%opponent%", opponentName,
                "%tagger%", opponentName
        );
        plugin.getTextService().sendActionBar(player, actionbarFormat, placeholders);
    }

    private void updateBossBar(Player player, int remainingSeconds, int maxDuration, String opponentName) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        BossBar bossBar = combatBossBars.computeIfAbsent(player.getUniqueId(), uuid -> {
            String colorStr = config.getString("combat-tag.bossbar.color", "RED");
            BarColor color;
            try {
                color = BarColor.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                color = BarColor.RED;
            }

            String styleStr = config.getString("combat-tag.bossbar.style", "SOLID");
            BarStyle style;
            try {
                style = BarStyle.valueOf(styleStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                style = BarStyle.SOLID;
            }

            BossBar bar = Bukkit.createBossBar("Combat", color, style);
            bar.addPlayer(player);
            bar.setVisible(true);
            return bar;
        });

        double progress = (double) remainingSeconds / Math.max(1, maxDuration);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

        FileConfiguration messages = plugin.getConfigService().getPvpMessages();
        String defaultFormat = "<gradient:#FF4D5A:#FF9A9A><bold>⚔ ᴄᴏᴍʙᴀᴛ</bold></gradient> <dark_gray>•</dark_gray> <white>%time%s</white> <dark_gray>(vs <color:#E7B85C>%opponent%</color>)</dark_gray>";
        String format = messages.getString("combat.bossbar", config.getString("combat-tag.bossbar.text", defaultFormat));

        String legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(plugin.getTextService().parse(format, Map.of("%time%", String.valueOf(remainingSeconds), "%opponent%", opponentName)));
        bossBar.setTitle(legacy);
    }

    private void removeBossBar(UUID uuid) {
        BossBar bar = combatBossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void untagPlayer(Player player, PlayerCombatUntagEvent.UntagReason reason) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (taggedPlayers.remove(uuid) != null) {
            opponentMap.remove(uuid);
            maxDurationMap.remove(uuid);
            removeBossBar(uuid);

            plugin.getScheduler().runEntity(player, () -> {
                Bukkit.getPluginManager().callEvent(new PlayerCombatUntagEvent(player, reason));

                if ((reason == PlayerCombatUntagEvent.UntagReason.EXPIRED 
                        || reason == PlayerCombatUntagEvent.UntagReason.OPPONENT_DEATH
                        || reason == PlayerCombatUntagEvent.UntagReason.COMMAND) && player.isOnline()) {
                    plugin.getTextService().sendMessage(player, "combat.untagged");
                }
            });
        }
    }

    public void autoUntagOpponentsOf(UUID victimUuid) {
        if (victimUuid == null) return;
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.auto-untag.on-opponent-death", true)) return;

        for (Map.Entry<UUID, UUID> entry : opponentMap.entrySet()) {
            if (victimUuid.equals(entry.getValue())) {
                UUID oppUuid = entry.getKey();
                Player oppPlayer = Bukkit.getPlayer(oppUuid);
                if (oppPlayer != null && oppPlayer.isOnline() && isTagged(oppUuid)) {
                    untagPlayer(oppPlayer, PlayerCombatUntagEvent.UntagReason.OPPONENT_DEATH);
                }
            }
        }
    }

    public static class DetonatorInfo {
        private final UUID playerUuid;
        private final Location location;
        private final long timestamp;

        public DetonatorInfo(UUID playerUuid, Location location, long timestamp) {
            this.playerUuid = playerUuid;
            this.location = location;
            this.timestamp = timestamp;
        }

        public UUID getPlayerUuid() {
            return playerUuid;
        }

        public Location getLocation() {
            return location;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    private final Map<UUID, DetonatorInfo> crystalDetonators = new ConcurrentHashMap<>();
    private final Map<Location, DetonatorInfo> anchorDetonators = new ConcurrentHashMap<>();

    public void recordCrystalDetonation(UUID crystalUuid, Player player) {
        if (crystalUuid == null || player == null) return;
        crystalDetonators.put(crystalUuid, new DetonatorInfo(player.getUniqueId(), player.getLocation().clone(), System.currentTimeMillis()));
    }

    public void recordAnchorDetonation(Location blockLoc, Player player) {
        if (blockLoc == null || player == null) return;
        anchorDetonators.put(blockLoc.getBlock().getLocation(), new DetonatorInfo(player.getUniqueId(), player.getLocation().clone(), System.currentTimeMillis()));
    }

    public Player resolveCrystalDetonator(UUID crystalUuid) {
        if (crystalUuid == null) return null;
        DetonatorInfo info = crystalDetonators.get(crystalUuid);
        if (info != null && (System.currentTimeMillis() - info.getTimestamp()) < 6000L) {
            return Bukkit.getPlayer(info.getPlayerUuid());
        }
        return null;
    }

    public Player resolveAnchorDetonator(Location damageLoc) {
        if (damageLoc == null || damageLoc.getWorld() == null) return null;
        long now = System.currentTimeMillis();
        for (Map.Entry<Location, DetonatorInfo> entry : anchorDetonators.entrySet()) {
            Location loc = entry.getKey();
            DetonatorInfo info = entry.getValue();
            if (now - info.getTimestamp() > 6000L) {
                anchorDetonators.remove(loc);
                continue;
            }
            if (loc.getWorld() != null && loc.getWorld().equals(damageLoc.getWorld()) && loc.distanceSquared(damageLoc) <= 64.0) {
                return Bukkit.getPlayer(info.getPlayerUuid());
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        int bufferRadius = Math.max(0, config.getInt("explosions.safezone-buffer-radius", 4));

        if (config.getBoolean("explosions.prevent-safezone-bombing", true)) {
            if (!plugin.getHookManager().getProtectionHook().isPvpAllowed(attacker.getLocation(), attacker, customSafezones)
                    || plugin.getPvpManager().getDisplayBarrierManager().isNearSafezone(crystal.getLocation(), bufferRadius, customSafezones)) {
                event.setCancelled(true);
                plugin.getTextService().sendMessage(attacker, "barriers.crystal-anchor-blocked-safezone");
                return;
            }
        }

        recordCrystalDetonation(crystal.getUniqueId(), attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof EnderCrystal crystal)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        List<String> customSafezones = config.getStringList("barriers.custom-safezones");
        Player detonator = resolveCrystalDetonator(crystal.getUniqueId());

        if (config.getBoolean("explosions.prevent-safezone-bombing", true)) {
            if (!plugin.getHookManager().getProtectionHook().isPvpAllowed(victim.getLocation(), victim, customSafezones)
                    || !plugin.getHookManager().getProtectionHook().isPvpAllowed(crystal.getLocation(), null, customSafezones)
                    || (detonator != null && !plugin.getHookManager().getProtectionHook().isPvpAllowed(detonator.getLocation(), detonator, customSafezones))) {
                event.setCancelled(true);
                return;
            }
        }

        if (detonator != null && (!plugin.getPvpManager().hasPvpEnabled(victim.getUniqueId())
                || !plugin.getPvpManager().hasPvpEnabled(detonator.getUniqueId())
                || plugin.getPvpManager().getNewbieProtectionManager().hasProtection(victim.getUniqueId())
                || plugin.getPvpManager().getNewbieProtectionManager().hasProtection(detonator.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getFinalDamage() <= 0.0) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.enabled", true)) return;

        Entity damager = event.getDamager();
        Player attacker = damager instanceof EnderCrystal crystal
                ? resolveCrystalDetonator(crystal.getUniqueId()) : resolveAttacker(damager);

        if (damager instanceof AbstractWindCharge) {
            if (isTagged(victim.getUniqueId())) tagPlayer(victim, null);
            if (attacker != null && !attacker.equals(victim) && isTagged(attacker.getUniqueId())) {
                tagPlayer(attacker, null);
            }
            return;
        }

        // Direct player attack or resolved projectile/minion combat tagging
        if (attacker != null && !attacker.equals(victim)) {
            if (event.getDamager() instanceof Projectile && !config.getBoolean("combat-tag.tag-for-projectiles", true)) {
                return;
            }

            tagPlayer(victim, attacker);
            tagPlayer(attacker, victim);
            return;
        }

        if (config.getBoolean("combat-tag.tag-for-explosions", true)) {
            if (damager instanceof TNTPrimed || damager instanceof EnderCrystal || damager instanceof Explosive || damager instanceof org.bukkit.entity.minecart.ExplosiveMinecart) {
                tagPlayer(victim, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosionProtection(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        List<String> customSafezones = config.getStringList("barriers.custom-safezones");

        // Protect players inside safezones from outside block explosions (anchors, beds)
        if (config.getBoolean("explosions.prevent-safezone-bombing", true)) {
            if (!plugin.getHookManager().getProtectionHook().isPvpAllowed(victim.getLocation(), victim, customSafezones)) {
                event.setCancelled(true);
                return;
            }
        }

        // Attribute anchor detonation damage to the triggering player
        Player detonator = resolveAnchorDetonator(victim.getLocation());
        if (detonator != null && !detonator.equals(victim)) {
            if (config.getBoolean("explosions.prevent-safezone-bombing", true)) {
                if (!plugin.getHookManager().getProtectionHook().isPvpAllowed(detonator.getLocation(), detonator, customSafezones)) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (!plugin.getPvpManager().hasPvpEnabled(victim.getUniqueId()) || !plugin.getPvpManager().hasPvpEnabled(detonator.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (plugin.getPvpManager().getNewbieProtectionManager().hasProtection(victim.getUniqueId()) || plugin.getPvpManager().getNewbieProtectionManager().hasProtection(detonator.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (event.getFinalDamage() <= 0.0) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("combat-tag.enabled", true)) return;

        Player detonator = resolveAnchorDetonator(victim.getLocation());
        if (detonator != null && !detonator.equals(victim)) {
            tagPlayer(victim, detonator);
            tagPlayer(detonator, victim);
        } else if (config.getBoolean("combat-tag.tag-for-explosions", true)) {
            tagPlayer(victim, null);
        }
    }

    private boolean isTransferFrozen(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (plugin.getTransferFreezeService() != null && plugin.getTransferFreezeService().isFrozen(player)) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                    && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player)) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public Player resolveAttacker(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof AreaEffectCloud aec && aec.getSource() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player primer) {
            return primer;
        }
        if (damager instanceof EvokerFangs fangs && fangs.getOwner() instanceof Player owner) {
            return owner;
        }
        if (damager instanceof LightningStrike ls && ls.getCausingPlayer() != null) {
            return ls.getCausingPlayer();
        }
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player owner) {
            return owner;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isTagged(player.getUniqueId())) return;
        if (isBypassing(player)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        List<String> whitelist = config.getStringList("combat-tag.whitelisted-commands");

        String fullMessage = event.getMessage().substring(1).trim();
        String command = fullMessage.split(" ")[0].toLowerCase(Locale.ROOT);
        if (command.contains(":")) {
            command = command.substring(command.indexOf(":") + 1);
        }

        boolean allowed = false;
        for (String whitelisted : whitelist) {
            if (whitelisted.equalsIgnoreCase(command)) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, "combat.command-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!event.isGliding()) return;

        if (isElytraBlocked(player)) {
            event.setCancelled(true);
            notifyElytraBlocked(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        if (!isElytraBlocked(player)) return;
        event.setCancelled(true);
        event.setShouldConsume(false);
        notifyElytraBlocked(player);
    }

    private boolean isElytraBlocked(Player player) {
        if (!isTagged(player.getUniqueId())) return false;
        if (plugin.getConfigService().getPvpConfig().getBoolean("combat-tag.disable-elytra", true)) return true;
        CombatItemManager items = plugin.getPvpManager().getCombatItemManager();
        return items != null && items.isItemBlocked(Material.ELYTRA, player);
    }

    private void stopBlockedGliding(Player player) {
        if (player.isGliding() && isElytraBlocked(player)) {
            player.setGliding(false);
            notifyElytraBlocked(player);
        }
    }

    private void notifyElytraBlocked(Player player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        Long last = lastElytraNotify.get(player.getUniqueId());
        if (last != null && (now - last) < 2000L) {
            return;
        }
        lastElytraNotify.put(player.getUniqueId(), now);
        plugin.getTextService().sendMessage(player, "combat.elytra-disabled");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        lastElytraNotify.remove(uuid);
        if (!isTagged(uuid)) {
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.QUIT);
            removeBossBar(uuid);
            return;
        }

        if (isShuttingDown()) {
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.SERVER_SHUTDOWN);
            return;
        }

        if (isTransferFrozen(player)) {
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.TRANSFER);
            return;
        }

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        int remainingSeconds = getRemainingSeconds(uuid);

        // Spawn a Combat Logger NPC clone at logout position
        if (config.getBoolean("combat-tag.npc.enabled", true)) {
            plugin.getPvpManager().getCombatNpcManager().spawnNpc(player, remainingSeconds);
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.QUIT);
            return;
        }

        // Direct kill-on-leave punishment when NPCs are disabled
        punishCombatLogout(player, config);

        untagPlayer(player, PlayerCombatUntagEvent.UntagReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!isTagged(uuid)) return;

        if (isShuttingDown() || event.getCause() == PlayerKickEvent.Cause.RESTART_COMMAND) {
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.SERVER_SHUTDOWN);
            return;
        }

        if (isTransferFrozen(player)) {
            untagPlayer(player, PlayerCombatUntagEvent.UntagReason.TRANSFER);
            return;
        }
        // Leave the tag for onQuit's NPC path: a plugin kick right before a stop
        // (restart scripts) must not drop items, and shutdown despawns NPCs harmlessly.
    }

    private void punishCombatLogout(Player player, FileConfiguration config) {
        if (!config.getBoolean("combat-tag.kill-on-leave", true) || player.isDead() || player.getHealth() <= 0) return;

        if (config.getBoolean("combat-tag.drop-inventory-on-leave", true)) {
            Location loc = player.getLocation();
            if (loc.getWorld() != null) {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && item.getType() != Material.AIR) {
                        loc.getWorld().dropItemNaturally(loc, item.clone());
                    }
                }
                player.getInventory().clear();
            }
        }

        prepareCombatLogoutDeath(player);
        player.setHealth(0.0);
        if (config.getBoolean("combat-tag.broadcast-on-leave", true)) {
            plugin.getTextService().broadcastMessage("combat.combat-logged-broadcast", Map.of("%player%", player.getName()));
        }
    }

    public void prepareCombatLogoutDeath(Player player) {
        player.getPersistentDataContainer().set(LOGOUT_DEATH_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isCombatLogoutDeath(Player player) {
        return player.getPersistentDataContainer().has(LOGOUT_DEATH_KEY, PersistentDataType.BYTE);
    }

    public void respawnCombatLogger(Player player) {
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
            // Folia respawns asynchronously and may not fire Bukkit respawn events.
            Object[] completion = new Object[1];
            completion[0] = plugin.getScheduler().runEntityTimer(player, () -> {
                if (!player.isOnline() || (!player.isDead() && player.isValid())) {
                    if (player.isOnline()) clearCombatLogoutDeath(player);
                    plugin.getScheduler().cancel(completion[0]);
                }
            }, 1L, 1L);
        }, 1L);
    }

    private void clearCombatLogoutDeath(Player player) {
        player.getPersistentDataContainer().remove(LOGOUT_DEATH_KEY);
        player.removeMetadata(dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager.COMBAT_NPC_SLAIN_META, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombatLogoutDeath(PlayerDeathEvent event) {
        if (!isCombatLogoutDeath(event.getEntity())) return;
        event.deathMessage(null);
        respawnCombatLogger(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCombatLoggerJoin(PlayerJoinEvent event) {
        if (isCombatLogoutDeath(event.getPlayer())) {
            respawnCombatLogger(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID victimUuid = player.getUniqueId();
        untagPlayer(player, PlayerCombatUntagEvent.UntagReason.DEATH);
        if (player.hasMetadata(dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager.COMBAT_NPC_SLAIN_META)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (config.getBoolean("combat-tag.auto-untag.on-opponent-death", true)) {
            Player killer = plugin.getPvpManager().resolveKiller(player);
            if (killer != null && !killer.getUniqueId().equals(victimUuid) && isTagged(killer.getUniqueId())) {
                untagPlayer(killer, PlayerCombatUntagEvent.UntagReason.OPPONENT_DEATH);
            }
            autoUntagOpponentsOf(victimUuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clearCombatLogoutDeath(event.getPlayer());
        untagPlayer(event.getPlayer(), PlayerCombatUntagEvent.UntagReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR) {
            FileConfiguration config = plugin.getConfigService().getPvpConfig();
            if (config.getBoolean("combat-tag.auto-untag.on-gamemode-change", true)) {
                if (isTagged(event.getPlayer().getUniqueId())) {
                    untagPlayer(event.getPlayer(), PlayerCombatUntagEvent.UntagReason.GAMEMODE_CHANGE);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (config.getBoolean("combat-tag.auto-untag.on-world-change", true)) {
            List<String> blacklistedWorlds = config.getStringList("combat-tag.blacklisted-worlds");
            if (blacklistedWorlds.contains(event.getPlayer().getWorld().getName())) {
                if (isTagged(event.getPlayer().getUniqueId())) {
                    untagPlayer(event.getPlayer(), PlayerCombatUntagEvent.UntagReason.WORLD_CHANGE);
                }
            }
        }
    }

    public void shutdown() {
        this.isShuttingDown = true;
        if (tickerTask != null) {
            plugin.getScheduler().cancel(tickerTask);
            tickerTask = null;
        }
        for (BossBar bar : combatBossBars.values()) {
            if (bar != null) {
                bar.removeAll();
            }
        }
        combatBossBars.clear();
        taggedPlayers.clear();
        opponentMap.clear();
        recentOpponents.clear();
        maxDurationMap.clear();
        crystalDetonators.clear();
        anchorDetonators.clear();
        lastElytraNotify.clear();
    }
}
