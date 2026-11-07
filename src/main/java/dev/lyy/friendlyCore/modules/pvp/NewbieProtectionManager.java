package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.KitManager;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants temporary PvP immunity to newly joined players and manages their visual countdown boss bar.
 */
public class NewbieProtectionManager implements Listener {

    private final FriendlyCore plugin;
    private final Map<UUID, NewbieSession> newbieSessions = new ConcurrentHashMap<>();
    private Object tickerTask;

    public static class NewbieSession {
        private final UUID uuid;
        private final int initialDuration;
        private volatile int remainingSeconds;
        private volatile BossBar bossBar;

        public NewbieSession(UUID uuid, int initialDuration, BossBar bossBar) {
            this.uuid = uuid;
            this.initialDuration = initialDuration;
            this.remainingSeconds = initialDuration;
            this.bossBar = bossBar;
        }

        public UUID getUuid() {
            return uuid;
        }

        public int getInitialDuration() {
            return initialDuration;
        }

        public int getRemainingSeconds() {
            return remainingSeconds;
        }

        public void decrement() {
            if (this.remainingSeconds > 0) {
                this.remainingSeconds--;
            }
        }

        public BossBar getBossBar() {
            return bossBar;
        }

        public void setBossBar(BossBar bossBar) {
            this.bossBar = bossBar;
        }
    }

    public NewbieProtectionManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.tickerTask = plugin.getScheduler().runGlobalTimer(this::tickNewbieProtection, 20L, 20L);
    }

    public boolean hasProtection(UUID uuid) {
        if (uuid == null) return false;
        NewbieSession session = newbieSessions.get(uuid);
        return session != null && session.getRemainingSeconds() > 0;
    }

    public int getRemainingSeconds(UUID uuid) {
        NewbieSession session = newbieSessions.get(uuid);
        return session != null ? session.getRemainingSeconds() : 0;
    }

    public void grantProtection(Player player, int durationSeconds) {
        if (player == null || !player.isOnline()) return;

        removeProtection(player.getUniqueId(), false);

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        BossBar bossBar = null;

        if (config.getBoolean("newbie-protection.bossbar.enabled", true)) {
            String colorStr = config.getString("newbie-protection.bossbar.color", "GREEN");
            BarColor color;
            try {
                color = BarColor.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                color = BarColor.GREEN;
            }

            bossBar = Bukkit.createBossBar("Newbie Protection", color, BarStyle.SOLID);
            bossBar.addPlayer(player);
            bossBar.setVisible(true);
        }

        NewbieSession session = new NewbieSession(player.getUniqueId(), durationSeconds, bossBar);
        newbieSessions.put(player.getUniqueId(), session);

        plugin.getTextService().sendMessage(player, "newbie.granted", Map.of("%time%", String.valueOf(durationSeconds)));
        updateBossBar(player, session);
    }

    public void removeProtection(UUID uuid, boolean notify) {
        NewbieSession session = newbieSessions.remove(uuid);
        if (session != null) {
            if (session.getBossBar() != null) {
                session.getBossBar().removeAll();
            }
            if (notify) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    plugin.getTextService().sendMessage(player, "newbie.disabled-self");
                }
            }
        }
    }

    private void tickNewbieProtection() {
        for (Map.Entry<UUID, NewbieSession> entry : newbieSessions.entrySet()) {
            UUID uuid = entry.getKey();
            NewbieSession session = entry.getValue();

            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                if (session.getBossBar() != null) {
                    session.getBossBar().removeAll();
                }
                newbieSessions.remove(uuid, session);
                continue;
            }

            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline() || newbieSessions.get(uuid) != session) {
                    if (newbieSessions.remove(uuid, session) && session.getBossBar() != null) {
                        session.getBossBar().removeAll();
                    }
                    return;
                }

                session.decrement();
                if (session.getRemainingSeconds() <= 0) {
                    if (session.getBossBar() != null) {
                        session.getBossBar().removeAll();
                    }
                    newbieSessions.remove(uuid, session);
                    plugin.getTextService().sendMessage(player, "newbie.expired");
                } else {
                    updateBossBar(player, session);
                }
            }, () -> newbieSessions.remove(uuid, session));
        }
    }

    private void updateBossBar(Player player, NewbieSession session) {
        if (session.getBossBar() == null) return;

        double progress = (double) session.getRemainingSeconds() / Math.max(1, session.getInitialDuration());
        session.getBossBar().setProgress(Math.max(0.0, Math.min(1.0, progress)));

        FileConfiguration messages = plugin.getConfigService().getPvpMessages();
        String format = messages.getString("newbie.bossbar", "<gradient:#7BD88F:#B9FBC0><bold>✦ ɴᴇᴡʙɪᴇ ᴘʀᴏᴛᴇᴄᴛɪᴏɴ</bold></gradient> <dark_gray>•</dark_gray> <white>%time% remaining</white>");

        String legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(plugin.getTextService().parse(formatBossBarTitle(format, session.getRemainingSeconds())));
        session.getBossBar().setTitle(legacy);
    }

    static String formatBossBarTitle(String format, int remainingSeconds) {
        String time = remainingSeconds > 0 ? KitManager.formatDurationDetailed(remainingSeconds) : "0s";
        // Existing server templates include the seconds suffix after the placeholder.
        return format.replace("%time%s", "%time%").replace("%time%", time);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfigService().getPvpConfig();

        if (!config.getBoolean("newbie-protection.enabled", true)) return;

        if (!player.hasPlayedBefore()) {
            int duration = config.getInt("newbie-protection.duration", 120);
            grantProtection(player, duration);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        NewbieSession session = newbieSessions.remove(uuid);
        if (session != null && session.getBossBar() != null) {
            session.getBossBar().removeAll();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("newbie-protection.enabled", true)) return;

        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;

        if (isAttackBlocked(config, attacker, victim)) {
            event.setCancelled(true);
        } else if (hasProtection(attacker.getUniqueId())) {
            removeProtection(attacker.getUniqueId(), false);
            plugin.getTextService().sendMessage(attacker, "newbie.disabled-attack");
        }
    }

    // Spear jabs and charges still knock back after their damage event is cancelled.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("newbie-protection.enabled", true)) return;
        if (!(event.getAttacked() instanceof Player victim) || victim.equals(event.getPlayer())) return;
        if (isAttackBlocked(config, event.getPlayer(), victim)) event.setCancelled(true);
    }

    private boolean isAttackBlocked(FileConfiguration config, Player attacker, Player victim) {
        if (hasProtection(victim.getUniqueId())) {
            plugin.getTextService().sendMessage(attacker, "newbie.blocked-attack-by-them", Map.of("%target%", victim.getName()));
            return true;
        }
        if (hasProtection(attacker.getUniqueId()) && !config.getBoolean("newbie-protection.remove-on-attack", true)) {
            plugin.getTextService().sendMessage(attacker, "newbie.blocked-attack-them");
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionSplash(org.bukkit.event.entity.PotionSplashEvent event) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("newbie-protection.enabled", true)) return;

        if (!(event.getPotion().getShooter() instanceof Player shooter)) return;

        for (org.bukkit.entity.LivingEntity affected : event.getAffectedEntities()) {
            if (!(affected instanceof Player victim)) continue;
            if (victim.equals(shooter)) continue;

            if (hasProtection(victim.getUniqueId()) || hasProtection(shooter.getUniqueId())) {
                event.setIntensity(victim, 0.0);
            }
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

    public void shutdown() {
        if (tickerTask != null) {
            plugin.getScheduler().cancel(tickerTask);
        }
        for (NewbieSession session : newbieSessions.values()) {
            if (session.getBossBar() != null) {
                session.getBossBar().removeAll();
            }
        }
        newbieSessions.clear();
    }
}
