package dev.lyy.friendlycore.modules.orders.events;

import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerOrderDeliveryEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player seller;
    private final BuyOrder order;
    private final long quantity;
    private final double payout;
    private boolean cancelled;

    public PlayerOrderDeliveryEvent(Player seller, BuyOrder order, long quantity, double payout) {
        this.seller = seller;
        this.order = order;
        this.quantity = quantity;
        this.payout = payout;
    }

    public Player getSeller() {
        return seller;
    }

    public BuyOrder getOrder() {
        return order;
    }

    public long getQuantity() {
        return quantity;
    }

    public double getPayout() {
        return payout;
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
