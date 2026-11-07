package dev.lyy.friendlycore.modules.orders.model;

import java.util.UUID;

public class OrderNotification {

    private final UUID userUuid;
    private final UUID orderId;
    private final String message;
    private final long timestamp;

    public OrderNotification(UUID userUuid, UUID orderId, String message, long timestamp) {
        this.userUuid = userUuid;
        this.orderId = orderId;
        this.message = message;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public UUID getUserUuid() {
        return userUuid;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
