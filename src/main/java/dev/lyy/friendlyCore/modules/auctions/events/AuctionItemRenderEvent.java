package dev.lyy.friendlycore.modules.auctions.events;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** Fired after auction lore has been applied and before an item is placed in a menu. */
public final class AuctionItemRenderEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemStack item;

    public AuctionItemRenderEvent(Player player, ItemStack item) {
        super(Bukkit.getServer() != null && !Bukkit.isPrimaryThread());
        this.player = player;
        this.item = item;
    }

    public Player getPlayer() { return player; }
    public ItemStack getItem() { return item; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
