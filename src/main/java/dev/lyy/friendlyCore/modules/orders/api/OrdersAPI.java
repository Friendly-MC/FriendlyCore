package dev.lyy.friendlycore.modules.orders.api;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.orders.OrderManager;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OrdersAPI {

    private static FriendlyCore plugin;
    private static OrderManager orderManager;

    private OrdersAPI() {}

    public static void init(FriendlyCore core, OrderManager manager) {
        plugin = core;
        orderManager = manager;
    }

    public static Collection<BuyOrder> getActiveOrders() {
        return orderManager != null ? orderManager.getActiveOrders().values() : List.of();
    }

    public static BuyOrder getOrder(UUID orderId) {
        return orderManager != null ? orderManager.getActiveOrders().get(orderId) : null;
    }

    public static CompletableFuture<Boolean> createOrder(Player player, ItemStack item, long amount, double pricePerItem) {
        if (orderManager == null) return CompletableFuture.completedFuture(false);
        return orderManager.createOrder(player, item, amount, pricePerItem);
    }

    public static CompletableFuture<Boolean> deliverOrder(Player seller, UUID orderId, long quantity) {
        if (orderManager == null) return CompletableFuture.completedFuture(false);
        return orderManager.deliverOrder(seller, orderId, quantity);
    }

    public static CompletableFuture<Boolean> cancelOrder(Player player, UUID orderId) {
        if (orderManager == null) return CompletableFuture.completedFuture(false);
        return orderManager.cancelOrder(player, orderId);
    }

    public static CompletableFuture<Integer> claimDeliveries(Player player) {
        if (orderManager == null) return CompletableFuture.completedFuture(0);
        return orderManager.claimDeliveries(player);
    }

    public static int getPlayerMaxOrders(Player player) {
        return orderManager != null ? orderManager.getPlayerMaxOrders(player) : 3;
    }

    public static long getPlayerActiveOrderCount(UUID uuid) {
        return orderManager != null ? orderManager.getPlayerActiveOrderCount(uuid) : 0;
    }
}
