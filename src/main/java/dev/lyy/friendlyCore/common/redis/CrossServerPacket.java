package dev.lyy.friendlycore.common.redis;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Cross-server messaging packet encapsulating action types, payload data,
 * timestamps, unique nonces, and HMAC-SHA256 signatures.
 */
public class CrossServerPacket {

    public enum Action {
        BALANCE_UPDATE,
        CROSS_SERVER_PAY,
        CROSS_SERVER_DEPOSIT,
        CROSS_SERVER_WITHDRAW,
        BALANCE_INVALIDATE,
        BALTOP_INVALIDATE,
        GLOBAL_RESET,
        PLAYER_JOIN,
        PLAYER_QUIT,
        NETWORK_JOIN,
        NETWORK_QUIT,
        NETWORK_FIRST_JOIN,
        TPA_REQUEST,
        TPA_ACCEPT,
        TPA_DENY,
        TPA_CANCEL,
        TPA_EXECUTE,
        RTP_REQUEST,
        RTP_LOCATION_REPLY,
        RTP_FAILED,
        RTP_QUEUED,
        RTP_COOLDOWN_SYNC,
        RTP_DISCONNECT_REFUND,
        SPAWN_SYNC,
        SPAWN_TICKET,
        LAST_LOCATION_TICKET,
        KIT_CLAIM_SYNC,
        KIT_RESET_SYNC,
        KIT_COOLDOWN_SYNC,
        KIT_GIVE_REMOTE,
        KIT_RELOAD_SYNC,
        TEAM_CREATE_SYNC,
        TEAM_DISBAND_SYNC,
        TEAM_MEMBER_ADD_SYNC,
        TEAM_MEMBER_REMOVE_SYNC,
        TEAM_MEMBER_ROLE_SYNC,
        TEAM_UPDATE_SYNC,
        TEAM_INVITE_SYNC,
        TEAM_CHAT_SYNC,
        ALLY_CHAT_SYNC,
        TEAM_ENDERCHEST_SYNC,
        TEAM_HOME_SYNC,
        HOME_CROSS_TELEPORT,
        ADMIN_TP_REQUEST,
        ADMIN_TP_TICKET,
        ADMIN_TPHERE,
        ADMIN_GAMEMODE_SET,
        ADMIN_FLY_SET,
        ADMIN_GOD_SET,
        ADMIN_HEAL,
        ADMIN_FEED,
        ADMIN_SPEED_SET,
        ADMIN_INVSEE_REQ,
        ADMIN_INVSEE_RESP,
        ADMIN_INVSEE_SAVE,
        ADMIN_EC_REQ,
        ADMIN_EC_RESP,
        ADMIN_EC_SAVE,
        ADMIN_TIME_SYNC,
        ADMIN_WEATHER_SYNC,
        ADMIN_CLEAR_INVENTORY,
        PET_ACTIVATE_SYNC,
        PET_DEACTIVATE_SYNC,
        PET_UNLOCK_SYNC,
        PET_LOCK_SYNC,
        PET_HIDE_TOGGLE_SYNC,
        PET_RELOAD_SYNC,
        CHAT_PUBLIC,
        CHAT_STAFF,
        CHAT_PRIVATE,
        CHAT_SPY,
        CHAT_MUTE_SYNC,
        CHAT_CLEAR_SYNC,
        CHAT_BROADCAST,
        SETTINGS_SYNC,
        PVP_NPC_SPAWN_SYNC,
        PVP_NPC_DESPAWN_SYNC,
        PVP_NPC_SLAIN_SYNC,
        AUCTION_ITEM_INSERT,
        AUCTION_ITEM_UPDATE,
        AUCTION_ITEM_DELETE,
        AUCTION_NOTIFICATION,
        AUCTION_PAYOUT,
        AUCTION_USER_LIMIT_SYNC,
        ORDER_ITEM_INSERT,
        ORDER_ITEM_UPDATE,
        ORDER_ITEM_DELETE,
        ORDER_NOTIFICATION
    }

    private final String sourceServer;
    private final Action action;
    private final UUID targetUuid;
    private final String targetName;
    private final double amount;
    private final String extra;
    private double transactionAmount;
    private long timestamp;
    private String nonce;
    private String signature;

    public CrossServerPacket(String sourceServer, Action action, UUID targetUuid, String targetName, double amount, String extra) {
        this.sourceServer = sourceServer;
        this.action = action;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.amount = amount;
        this.extra = extra;
    }

    public CrossServerPacket(String sourceServer, Action action, UUID targetUuid, String targetName,
                             double amount, double transactionAmount, String extra) {
        this(sourceServer, action, targetUuid, targetName, amount, extra);
        this.transactionAmount = transactionAmount;
    }

    public String getSourceServer() {
        return sourceServer;
    }

    public Action getAction() {
        return action;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public double getAmount() {
        return amount;
    }

    public String getExtra() {
        return extra;
    }

    public double getTransactionAmount() {
        return transactionAmount;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getNonce() {
        return nonce;
    }

    public String getSignature() {
        return signature;
    }

    public void sign(String sharedSecret) {
        this.timestamp = System.currentTimeMillis();
        this.nonce = UUID.randomUUID().toString();
        this.signature = computeSignature(sharedSecret);
    }

    public boolean hasValidSignature(String sharedSecret) {
        if (sharedSecret == null || sharedSecret.isBlank() || timestamp <= 0 || nonce == null || signature == null) {
            return false;
        }
        byte[] expected = computeSignature(sharedSecret).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    private String computeSignature(String sharedSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(signingPayload().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign cross-server packet", e);
        }
    }

    private String signingPayload() {
        return String.join("\n",
                sourceServer == null ? "" : sourceServer,
                action == null ? "" : action.name(),
                targetUuid == null ? "" : targetUuid.toString(),
                targetName == null ? "" : targetName,
                Double.toHexString(amount),
                Double.toHexString(transactionAmount),
                extra == null ? "" : extra,
                Long.toString(timestamp),
                nonce == null ? "" : nonce);
    }
}
