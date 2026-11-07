package dev.lyy.friendlycore.modules.orders.events;

import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class PlayerOrderDeliveryCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BuyOrder order;

    public PlayerOrderDeliveryCompleteEvent(BuyOrder order) {
        this.order = order;
    }

    public BuyOrder getOrder() {
        return order;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
