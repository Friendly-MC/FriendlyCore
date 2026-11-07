package dev.lyy.friendlycore.modules.auctions.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Fired immediately before an item is listed on the auction house. Can be cancelled.
 */
public class AuctionPreSellEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    private final Player player;
    private final ItemStack item;
    private double price;
    private boolean cancelled = false;

    public AuctionPreSellEvent(Player player, ItemStack item, double price) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.player = player;
        this.item = item;
        this.price = price;
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getItem() {
        return item;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
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
