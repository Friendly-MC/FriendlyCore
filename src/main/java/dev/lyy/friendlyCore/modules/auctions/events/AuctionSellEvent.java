package dev.lyy.friendlycore.modules.auctions.events;

import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after an item has been successfully published to the auction house.
 */
public class AuctionSellEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Player player;
    private final AuctionItem item;

    public AuctionSellEvent(Player player, AuctionItem item) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.player = player;
        this.item = item;
    }

    public Player getPlayer() {
        return player;
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
