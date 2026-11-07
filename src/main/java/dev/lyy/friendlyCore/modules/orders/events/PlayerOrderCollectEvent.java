package dev.lyy.friendlycore.modules.orders.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PlayerOrderCollectEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final List<ItemStack> items;

    public PlayerOrderCollectEvent(Player player, List<ItemStack> items) {
        this.player = player;
        this.items = items;
    }

    public Player getPlayer() {
        return player;
    }

    public List<ItemStack> getItems() {
        return items;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
