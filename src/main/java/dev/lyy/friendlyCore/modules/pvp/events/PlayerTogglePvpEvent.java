package dev.lyy.friendlycore.modules.pvp.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player toggles their personal PvP combat preference.
 */
public class PlayerTogglePvpEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final boolean newState;
    private boolean cancelled = false;

    public PlayerTogglePvpEvent(@NotNull Player player, boolean newState) {
        this.player = player;
        this.newState = newState;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    public boolean getNewState() {
        return newState;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
