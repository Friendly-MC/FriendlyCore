package dev.lyy.friendlycore.modules.joinquit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Hides vanilla join/quit broadcasts when the joinquit module is turned off
 * in {@code config.yml} ({@code modules.joinquit: false}).
 */
public final class VanillaJoinQuitSuppressListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
    }
}
