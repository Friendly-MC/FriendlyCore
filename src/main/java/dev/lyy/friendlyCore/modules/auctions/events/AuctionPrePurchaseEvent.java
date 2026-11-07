package dev.lyy.friendlycore.modules.auctions.events;

import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired immediately before a player purchases an auction listing. Can be cancelled.
 */
public class AuctionPrePurchaseEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Player buyer;
    private final AuctionItem item;
    private boolean cancelled = false;

    public AuctionPrePurchaseEvent(Player buyer, AuctionItem item) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.buyer = buyer;
        this.item = item;
    }

    public Player getBuyer() {
        return buyer;
    }

    public AuctionItem getItem() {
        return item;
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
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
