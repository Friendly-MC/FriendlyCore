package dev.lyy.friendlycore.modules.tpa.crossserver;

import dev.lyy.friendlycore.modules.tpa.TpaRequest;

import java.util.UUID;

/**
 * Data transfer object for serialized cross-server TPA packets over Redis Pub/Sub.
 */
public class TpaCrossServerPacketPayload {

    private UUID requestId;
    private UUID senderUuid;
    private String senderName;
    private UUID targetUuid;
    private String targetName;
    private String originServer;
    private String targetServer;
    private TpaRequest.Type type;
    private String reason;

    // Destination coordinates used for cross-server arrival ticket generation
    private String worldName;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public TpaCrossServerPacketPayload() {
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }

    public UUID getSenderUuid() {
        return senderUuid;
    }

    public void setSenderUuid(UUID senderUuid) {
        this.senderUuid = senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public void setTargetUuid(UUID targetUuid) {
        this.targetUuid = targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
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

    public TpaRequest.Type getType() {
        return type;
    }

    public void setType(TpaRequest.Type type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }
}
