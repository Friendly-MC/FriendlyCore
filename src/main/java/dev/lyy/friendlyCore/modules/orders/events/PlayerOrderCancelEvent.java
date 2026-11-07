package dev.lyy.friendlycore.modules.orders.events;

import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerOrderCancelEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final BuyOrder order;
    private final double refundAmount;
    private boolean cancelled;

    public PlayerOrderCancelEvent(Player player, BuyOrder order, double refundAmount) {
        this.player = player;
        this.order = order;
        this.refundAmount = refundAmount;
    }

    public Player getPlayer() {
        return player;
    }

    public BuyOrder getOrder() {
        return order;
    }

    public double getRefundAmount() {
        return refundAmount;
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
