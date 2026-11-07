package dev.lyy.friendlycore.modules.orders.model;

import java.util.UUID;

public class OrderHistoryEntry {

    private int id;
    private final UUID orderId;
    private final UUID buyerUuid;
    private final UUID sellerUuid;
    private final String sellerName;
    private final int quantity;
    private final double unitPrice;
    private final double totalPaid;
    private final long timestamp;

    public OrderHistoryEntry(int id, UUID orderId, UUID buyerUuid, UUID sellerUuid,
                             String sellerName, int quantity, double unitPrice,
                             double totalPaid, long timestamp) {
        this.id = id;
        this.orderId = orderId;
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName != null ? sellerName : "Unknown";
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPaid = totalPaid;
        this.timestamp = timestamp;
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

    public int getQuantity() {
        return quantity;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public double getTotalPaid() {
        return totalPaid;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
