package dev.lyy.friendlycore.modules.tpa;

import org.bukkit.Location;

import java.util.UUID;

/**
 * Model representing a pending or active teleport request between two players.
 * Supports cross-server identification and lifecycle status tracking.
 */
public class TpaRequest {

    public enum Type {
        TPA,
        TPHERE,
        TPAALL
    }

    public enum Status {
        PENDING,
        ACCEPTED,
        DECLINED,
        CANCELLED,
        EXPIRED
    }

    private final UUID id;
    private final UUID senderUuid;
    private final String senderName;
    private final UUID targetUuid;
    private final String targetName;
    private final Type type;
    private final long createdAt;
    private Status status;
    private Location initialLocation;

    private String originServer;
    private String targetServer;

    public TpaRequest(UUID senderUuid, String senderName, UUID targetUuid, String targetName, Type type) {
        this.id = UUID.randomUUID();
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
        this.status = Status.PENDING;
    }

    public TpaRequest(UUID id, UUID senderUuid, String senderName, UUID targetUuid, String targetName, Type type, long createdAt, Status status) {
        this.id = id != null ? id : UUID.randomUUID();
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.type = type;
        this.createdAt = createdAt;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSenderUuid() {
        return senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public Type getType() {
        return type;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Location getInitialLocation() {
        return initialLocation;
    }

    public void setInitialLocation(Location initialLocation) {
        this.initialLocation = initialLocation;
    }

    public String getOriginServer() {
        return originServer;
    }

    public void setOriginServer(String originServer) {
        this.originServer = originServer;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public void setTargetServer(String targetServer) {
        this.targetServer = targetServer;
    }

    public boolean isCrossServer() {
        return originServer != null && targetServer != null && !originServer.equalsIgnoreCase(targetServer);
    }

    public boolean isExpired(long timeoutSeconds) {
        if (status != Status.PENDING) return false;
        return System.currentTimeMillis() - createdAt > (timeoutSeconds * 1000L);
    }
}
