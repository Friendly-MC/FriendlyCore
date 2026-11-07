package dev.lyy.friendlycore.modules.pvp.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player's combat tag expires or is cleared.
 */
public class PlayerCombatUntagEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UntagReason reason;

    public enum UntagReason {
        EXPIRED,
        DEATH,
        OPPONENT_DEATH,
        GAMEMODE_CHANGE,
        WORLD_CHANGE,
        QUIT,
        KICKED,
        COMMAND,
        PLUGIN,
        PLUGIN_RELOAD,
        SERVER_SHUTDOWN,
        TRANSFER
    }

    public PlayerCombatUntagEvent(@NotNull Player player, @NotNull UntagReason reason) {
        this.player = player;
        this.reason = reason;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @NotNull
    public UntagReason getReason() {
        return reason;
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
