package dev.lyy.friendlycore.modules.auctions.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired one second after the auction module has initialized. */
public final class AuctionLoadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    public AuctionLoadEvent() {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
    }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
