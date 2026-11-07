package dev.lyy.friendlycore.modules.economy;

import java.util.UUID;

/**
 * Represents an audited monetary transaction record.
 */
public class Transaction {

    public enum Type {
        PAYMENT,
        ADMIN_GIVE,
        ADMIN_TAKE,
        ADMIN_SET,
        PVP_REWARD,
        PVP_THEFT,
        SYSTEM
    }

    private final long id;
    private final UUID senderUuid;
    private final String senderName;
    private final UUID receiverUuid;
    private final String receiverName;
    private final double amount;
    private final Type type;
    private final long timestamp;

    public Transaction(long id, UUID senderUuid, String senderName, UUID receiverUuid, String receiverName, double amount, Type type, long timestamp) {
        this.id = id;
        this.senderUuid = senderUuid;
        this.senderName = senderName != null ? senderName : "System";
        this.receiverUuid = receiverUuid;
        this.receiverName = receiverName != null ? receiverName : "System";
        this.amount = amount;
        this.type = type;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public long getId() {
        return id;
    }

    public UUID getSenderUuid() {
        return senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public UUID getReceiverUuid() {
        return receiverUuid;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public double getAmount() {
        return amount;
    }

    public Type getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
