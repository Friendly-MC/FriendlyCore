package dev.lyy.friendlycore.modules.settings.events;

import dev.lyy.friendlycore.modules.settings.SettingType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Event fired whenever a player changes one of their settings.
 */
public class PlayerSettingChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SettingType setting;
    private final boolean oldValue;
    private boolean newValue;
    private final boolean crossServer;
    private boolean cancelled = false;

    public PlayerSettingChangeEvent(Player player, SettingType setting, boolean oldValue, boolean newValue, boolean crossServer) {
        this.player = player;
        this.setting = setting;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.crossServer = crossServer;
    }

    public Player getPlayer() {
        return player;
    }

    public SettingType getSetting() {
        return setting;
    }

    public boolean getOldValue() {
        return oldValue;
    }

    public boolean getNewValue() {
        return newValue;
    }

    public void setNewValue(boolean newValue) {
        this.newValue = newValue;
    }

    public boolean isCrossServer() {
        return crossServer;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
