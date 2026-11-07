package dev.lyy.friendlycore.modules.orders.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class OrderDelivery {

    private int id;
    private final UUID orderId;
    private final UUID buyerUuid;
    private final UUID sellerUuid;
    private final String sellerName;
    private ItemStack item;
    private int quantity;
    private final long deliveredAt;
    private boolean claimed;

    public OrderDelivery(int id, UUID orderId, UUID buyerUuid, UUID sellerUuid,
                         String sellerName, ItemStack item, int quantity,
                         long deliveredAt, boolean claimed) {
        this.id = id;
        this.orderId = orderId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName != null ? sellerName : "Unknown";
        this.item = item;
        this.quantity = quantity;
        this.deliveredAt = deliveredAt;
        this.claimed = claimed;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getBuyerUuid() {
        return buyerUuid;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public ItemStack getItem() {
        return item != null ? item.clone() : null;
    }

    public void setItem(ItemStack item) {
        this.item = item != null ? item.clone() : null;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getDeliveredAt() {
        return deliveredAt;
    }

    public boolean isClaimed() {
        return claimed;
    }

    public void setClaimed(boolean claimed) {
        this.claimed = claimed;
    }
}
