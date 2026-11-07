package dev.lyy.friendlycore.modules.auctions.events;

import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player completes a purchase of an auction listing.
 */
public class AuctionPurchaseEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Player buyer;
    private final AuctionItem item;

    public AuctionPurchaseEvent(Player buyer, AuctionItem item) {
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
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
