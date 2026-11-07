package dev.lyy.friendlycore.modules.pvp.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Called when a player enters combat with another player or hostile explosive source.
 */
public class PlayerCombatTagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Player opponent;
    private int durationSeconds;
    private boolean cancelled = false;

    public PlayerCombatTagEvent(@NotNull Player player, @Nullable Player opponent, int durationSeconds) {
        this.player = player;
        this.opponent = opponent;
        this.durationSeconds = durationSeconds;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @Nullable
    public Player getOpponent() {
        return opponent;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
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
