package dev.lyy.friendlycore.modules.teams.listener;

import dev.lyy.friendlycore.modules.teams.TeamMessages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import dev.lyy.friendlycore.modules.teams.model.Team;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Optional;

/**
 * Enforces friendly fire rules between teammates and allies, and cancels in-flight teleport warmups on damage.
 */
public class TeamsDamageListener implements Listener {

    private final FriendlyCore plugin;
    private final TeamsManager teamsManager;
    private final TeamsTeleportManager teleportManager;

    public TeamsDamageListener(FriendlyCore plugin, TeamsManager teamsManager, TeamsTeleportManager teleportManager) {
        this.plugin = plugin;
        this.teamsManager = teamsManager;
        this.teleportManager = teleportManager;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        if (isFriendlyFireBlocked(attacker, victim)) event.setCancelled(true);
    }

    // Spear jabs and charges still knock back after their damage event is cancelled.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Player victim) || victim.equals(event.getPlayer())) return;
        if (isFriendlyFireBlocked(event.getPlayer(), victim)) event.setCancelled(true);
    }

    private boolean isFriendlyFireBlocked(Player attacker, Player victim) {
        Optional<Team> victimTeamOpt = teamsManager.getTeamByPlayer(victim.getUniqueId());
        Optional<Team> attackerTeamOpt = teamsManager.getTeamByPlayer(attacker.getUniqueId());

        if (victimTeamOpt.isEmpty() || attackerTeamOpt.isEmpty()) return false;

        Team victimTeam = victimTeamOpt.get();
        Team attackerTeam = attackerTeamOpt.get();

        if (victimTeam.getUniqueId().equals(attackerTeam.getUniqueId())) {
            if (!victimTeam.isFriendlyFireEnabled()) {
                TeamMessages.send(plugin, attacker, "friendly-fire-is-disabled-for-your-team");
                return true;
            }
        }

        if (victimTeam.isAlly(attackerTeam.getUniqueId())) {
            if (!victimTeam.isFriendlyFireEnabled() || !attackerTeam.isFriendlyFireEnabled()) {
                TeamMessages.send(plugin, attacker, "friendly-fire-is-disabled-between-allied-teams");
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (teleportManager.isTeleporting(player.getUniqueId())) {
                teleportManager.cancelTeleport(player.getUniqueId(), true);
            }
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
