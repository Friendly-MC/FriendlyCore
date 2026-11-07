package dev.lyy.friendlycore.modules.teams.listener;

import dev.lyy.friendlycore.modules.teams.manager.TeamsChatManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsManager;
import dev.lyy.friendlycore.modules.teams.manager.TeamsTeleportManager;
import dev.lyy.friendlycore.modules.teams.model.TeamPlayerStats;
import org.bukkit.Location;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Tracks member statistics (kills, deaths, block interactions) and manages session cleanup on join and quit.
 */
public class TeamsPlayerListener implements Listener {

    private final TeamsManager teamsManager;
    private final TeamsTeleportManager teleportManager;
    private final TeamsChatManager chatManager;

    public TeamsPlayerListener(TeamsManager teamsManager, TeamsTeleportManager teleportManager, TeamsChatManager chatManager) {
        this.teamsManager = teamsManager;
        this.teleportManager = teleportManager;
        this.chatManager = chatManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        teamsManager.getOrCreatePlayerStats(player.getUniqueId(), player.getName());
        teamsManager.deliverPendingPayments(player);

        Location pendingLoc = teleportManager.pollPendingTeleport(player.getUniqueId());
        if (pendingLoc != null) {
            teleportManager.teleportArrivingPlayer(player, pendingLoc);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        teleportManager.cancelTeleport(player.getUniqueId(), false);
        chatManager.setPlayerChannel(player.getUniqueId(), TeamsChatManager.ChatChannel.PUBLIC);
        chatManager.clearQuestion(player.getUniqueId());
        dev.lyy.friendlycore.common.SafeTeleportUtil.clearInFlight(player.getUniqueId());

        // Save and release distributed vault lease if player disconnected while viewing
        if (player.getOpenInventory().getTopInventory().getHolder()
                instanceof dev.lyy.friendlycore.modules.teams.model.TeamEnderChest chest) {
            teamsManager.saveAndReleaseTeamVault(chest.getTeam(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (dev.lyy.friendlycore.common.hooks.ProtectionHook.isSimulating()) return;
        Player player = event.getPlayer();
        TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(player.getUniqueId(), player.getName());
        stats.incrementBlocksBroken();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(player.getUniqueId(), player.getName());
        stats.incrementBlocksPlaced();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            teleportManager.cancelTeleport(player.getUniqueId(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        TeamPlayerStats victimStats = teamsManager.getOrCreatePlayerStats(victim.getUniqueId(), victim.getName());
        victimStats.incrementDeaths();

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            TeamPlayerStats killerStats = teamsManager.getOrCreatePlayerStats(killer.getUniqueId(), killer.getName());
            killerStats.incrementKills();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKill(EntityDeathEvent event) {
        if (event.getEntity() instanceof Monster) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                TeamPlayerStats stats = teamsManager.getOrCreatePlayerStats(killer.getUniqueId(), killer.getName());
                stats.incrementMobKills();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVaultClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof dev.lyy.friendlycore.modules.teams.model.TeamEnderChest chest
                && !teamsManager.isTeamVaultOpen(chest.getTeam(), event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVaultDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof dev.lyy.friendlycore.modules.teams.model.TeamEnderChest chest
                && !teamsManager.isTeamVaultOpen(chest.getTeam(), event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof dev.lyy.friendlycore.modules.teams.model.TeamEnderChest chest) {
            chest.setDirty(true);
            if (chest.getTeam() != null) {
                chest.getTeam().setDirty(true);
                if (event.getPlayer() instanceof Player player) {
                    teamsManager.saveAndReleaseTeamVault(chest.getTeam(), player.getUniqueId());
                }
            }
        }
    }
}
