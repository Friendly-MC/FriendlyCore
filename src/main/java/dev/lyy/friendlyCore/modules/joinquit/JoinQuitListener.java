package dev.lyy.friendlycore.modules.joinquit;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player join and quit events, applying group-based formatting,
 * unique player tracking, silent staff notifications, and cross-server Redis dispatch.
 */
public class JoinQuitListener implements Listener {

    private final JoinQuitManager manager;

    public JoinQuitListener(FriendlyCore plugin, JoinQuitManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        boolean crossServer = manager.getCrossServerService().isCrossServerActive();
        if (crossServer || shouldHideVanilla(manager.isSuppressVanilla(), manager.isEnabled(), manager.isJoinEnabled())) {
            event.joinMessage(null);
        }

        if (!manager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        if (manager.isJoinEnabled() && !player.hasPlayedBefore()) {
            manager.incrementUniqueJoins();
        }

        JoinQuitGroup group = manager.getMatchingGroup(player);
        boolean isSilent = manager.isSilent(player);

        if (crossServer) {
            manager.getCrossServerService().handlePlayerJoin(player, group, isSilent);
            return;
        }
        if (!manager.isJoinEnabled()) return;

        if (isSilent) {
            manager.notifyStaffSilentJoin(player);
        } else {
            manager.broadcastLocalJoin(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        boolean crossServer = manager.getCrossServerService().isCrossServerActive();
        if (crossServer || shouldHideVanilla(manager.isSuppressVanilla(), manager.isEnabled(), manager.isQuitEnabled())) {
            event.quitMessage(null);
        }

        if (!manager.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();

        JoinQuitGroup group = manager.getMatchingGroup(player);
        boolean isSilent = manager.isSilent(player);

        if (crossServer) {
            manager.getCrossServerService().handlePlayerQuit(player, group, isSilent);
            return;
        }
        if (!manager.isQuitEnabled()) return;

        if (isSilent) {
            manager.notifyStaffSilentQuit(player);
        } else {
            manager.broadcastLocalQuit(player);
        }
    }

    static boolean shouldHideVanilla(boolean suppressVanilla, boolean moduleEnabled, boolean typeEnabled) {
        return suppressVanilla || !moduleEnabled || !typeEnabled;
    }
}
