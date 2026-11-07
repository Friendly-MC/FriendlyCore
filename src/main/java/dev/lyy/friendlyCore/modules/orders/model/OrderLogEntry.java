package dev.lyy.friendlycore.modules.orders.model;

import java.util.UUID;

public class OrderLogEntry {

    private final String action;
    private final UUID orderId;
    private final UUID playerId;
    private final String playerName;
    private final String itemType;
    private final long amount;
    private final double price;
    private final String details;
    private final long timestamp;

    public OrderLogEntry(String action, UUID orderId, UUID playerId, String playerName,
                         String itemType, long amount, double price, String details, long timestamp) {
        this.action = action;
        this.orderId = orderId;
        this.playerId = playerId;
        this.playerName = playerName != null ? playerName : "Unknown";
        this.itemType = itemType != null ? itemType : "Unknown";
        this.amount = amount;
        this.price = price;
        this.details = details != null ? details : "";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public String getAction() {
        return action;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getItemType() {
        return itemType;
    }

    public long getAmount() {
        return amount;
    }

    public double getPrice() {
        return price;
    }

    public String getDetails() {
        return details;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
