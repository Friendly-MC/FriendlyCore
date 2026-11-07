package dev.lyy.friendlycore.modules.orders.model;

import dev.lyy.friendlycore.modules.orders.enums.OrderState;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public class BuyOrder {

    private final UUID orderId;
    private final UUID creatorId;
    private final String playerName;
    private ItemStack item;
    private long totalAmount;
    private double pricePerItem;
    private long deliveredAmount;
    private long claimedAmount;
    private double totalPaid;
    private final long creationTime;
    private final long duration;
    private boolean cancelled;
    private OrderState state;

    public BuyOrder(UUID orderId, UUID creatorId, String playerName, ItemStack item,
                    long totalAmount, double pricePerItem, long deliveredAmount, long claimedAmount,
                    double totalPaid, long creationTime, long duration, boolean cancelled, OrderState state) {
        this.orderId = orderId != null ? orderId : UUID.randomUUID();
        this.creatorId = creatorId;
        this.playerName = playerName != null ? playerName : "Unknown";
        this.item = item;
        this.totalAmount = totalAmount;
        this.pricePerItem = pricePerItem;
        this.deliveredAmount = deliveredAmount;
        this.claimedAmount = claimedAmount;
        this.totalPaid = totalPaid;
        this.creationTime = creationTime;
        this.duration = duration;
        this.cancelled = cancelled;
        this.state = state != null ? state : (cancelled ? OrderState.CANCELLED : (deliveredAmount >= totalAmount ? OrderState.FULFILLED : OrderState.ACTIVE));
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public ItemStack getItem() {
        return item != null ? item.clone() : null;
    }

    public void setItem(ItemStack item) {
        this.item = item != null ? item.clone() : null;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public double getPricePerItem() {
        return pricePerItem;
    }

    public void setPricePerItem(double pricePerItem) {
        this.pricePerItem = pricePerItem;
    }

    public long getDeliveredAmount() {
        return deliveredAmount;
    }

    public void setDeliveredAmount(long deliveredAmount) {
        this.deliveredAmount = deliveredAmount;
    }

    public long getClaimedAmount() {
        return claimedAmount;
    }

    public void setClaimedAmount(long claimedAmount) {
        this.claimedAmount = claimedAmount;
    }

    public double getTotalPaid() {
        return totalPaid;
    }

    public void setTotalPaid(double totalPaid) {
        this.totalPaid = totalPaid;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getDuration() {
        return duration;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
        if (cancelled) {
            this.state = OrderState.CANCELLED;
        }
    }

    public OrderState getState() {
        return state;
    }

    public void setState(OrderState state) {
        this.state = state;
    }

    public long getAvailableAmount() {
        return Math.max(0, totalAmount - deliveredAmount);
    }

    public long getUnclaimedAmount() {
        return Math.max(0, deliveredAmount - claimedAmount);
    }

    public long getExpirationTime() {
        return creationTime + duration;
    }

    public long getRemainingTime() {
        return Math.max(0, getExpirationTime() - System.currentTimeMillis());
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > getExpirationTime();
    }

    public boolean isComplete() {
        return deliveredAmount >= totalAmount;
    }

    public double getTotalCost() {
        return BigDecimal.valueOf(totalAmount)
                .multiply(BigDecimal.valueOf(pricePerItem))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public double getRemainingCost() {
        return BigDecimal.valueOf(getAvailableAmount())
                .multiply(BigDecimal.valueOf(pricePerItem))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuyOrder buyOrder = (BuyOrder) o;
        return Objects.equals(orderId, buyOrder.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId);
    }
}
