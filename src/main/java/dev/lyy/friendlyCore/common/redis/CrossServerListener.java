package dev.lyy.friendlycore.common.redis;

import com.google.gson.Gson;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.economy.AccountData;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import redis.clients.jedis.JedisPubSub;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Redis PubSub subscription listener handling packet authentication, HMAC verification,
 * replay attack mitigation, and event dispatch across FriendlyCore modules.
 */
public class CrossServerListener extends JedisPubSub {

    private final FriendlyCore plugin;
    private final Gson gson;
    private final String localServerId;
    private final String expectedChannel;
    private final String sharedSecret;
    private final long maxClockSkewMillis;
    private final int maxMessageBytes;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public CrossServerListener(FriendlyCore plugin, String localServerId, String expectedChannel,
                               String sharedSecret, int maxClockSkewSeconds, int maxMessageBytes) {
        this.plugin = plugin;
        this.gson = new Gson();
        this.localServerId = localServerId;
        this.expectedChannel = expectedChannel;
        this.sharedSecret = sharedSecret;
        this.maxClockSkewMillis = maxClockSkewSeconds * 1000L;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public void onPMessage(String pattern, String channel, String message) {
        onMessage(channel, message);
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            if (plugin == null || !plugin.isEnabled()) {
                return;
            }
            // Discard deliveries arriving on a stale subscription after plugin reload
            if (plugin.getRedisService() == null || plugin.getRedisService().getListener() != this) {
                return;
            }
            if (!expectedChannel.equals(channel) || message == null
                    || message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxMessageBytes) {
                return;
            }
            CrossServerPacket packet = gson.fromJson(message, CrossServerPacket.class);
            if (packet == null || packet.getAction() == null || packet.getSourceServer() == null
                    || packet.getSourceServer().isBlank() || !packet.hasValidSignature(sharedSecret)) {
                plugin.getLogger().warning("Rejected unauthenticated Redis packet on " + channel);
                return;
            }

            if (localServerId.equalsIgnoreCase(packet.getSourceServer())) {
                return;
            }

            long now = System.currentTimeMillis();
            if (Math.abs(now - packet.getTimestamp()) > maxClockSkewMillis
                    || packet.getNonce() == null || packet.getNonce().isBlank()) {
                plugin.getLogger().warning("Rejected expired or invalid Redis packet from " + packet.getSourceServer()
                        + " (clock skew " + (now - packet.getTimestamp()) + "ms, max " + maxClockSkewMillis + "ms)");
                return;
            }

            Long existingTimestamp = seenNonces.putIfAbsent(packet.getNonce(), now);
            if (existingTimestamp != null) {
                plugin.getLogger().warning("Rejected replayed Redis packet (nonce collision) from " + packet.getSourceServer());
                return;
            }

            // Prune nonces older than the maximum allowed clock skew window
            if (seenNonces.size() > 10000) {
                long cutoff = now - maxClockSkewMillis;
                seenNonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
                if (seenNonces.size() > 50000) {
                    seenNonces.clear();
                }
            }

            // Network-wide replay protection: claim nonce atomically in Redis with TTL matching clock skew
            RedisService redisService = plugin.getRedisService();
            if (redisService != null && redisService.isEnabled()
                    && !redisService.claimNonce(packet.getNonce(), (int) Math.max(1, maxClockSkewMillis / 1000))) {
                plugin.getLogger().warning("Rejected Redis packet (duplicate nonce or Redis unavailable) from " + packet.getSourceServer());
                return;
            }

            if (!plugin.isEnabled()) {
                return;
            }

            plugin.getScheduler().runGlobal(() -> {
                if (plugin.isEnabled()) {
                    handleVerifiedPacket(packet);
                }
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // Plugin is shutting down; ignore scheduler registration race
        } catch (Exception e) {
            if (plugin != null && plugin.isEnabled()) {
                plugin.getLogger().log(Level.WARNING, "Failed to process Redis PubSub message on channel " + channel, e);
            }
        }
    }

    public void clearSeenNonces() {
        seenNonces.clear();
    }

    public Map<String, Long> getSeenNonces() {
        return seenNonces;
    }

    private void handleVerifiedPacket(CrossServerPacket packet) {
        try {
            switch (packet.getAction()) {
                case BALANCE_UPDATE -> {
                    if (packet.getTargetUuid() != null && plugin.getEconomyManager() != null) {
                        AccountData localAcc = plugin.getEconomyManager().getCachedAccount(packet.getTargetUuid());
                        // Adopt remote balance only when local cache has no pending dirty state
                        if (localAcc != null && !localAcc.isDirty()) {
                            localAcc.setBalance(packet.getAmount());
                            localAcc.markSaved(localAcc.getCurrentVersion());
                        }
                    }
                }
                case CROSS_SERVER_DEPOSIT -> {
                    if (packet.getTargetUuid() != null && plugin.getEconomyManager() != null) {
                        Player online = Bukkit.getPlayer(packet.getTargetUuid());
                        if (online != null && online.isOnline()) {
                            AccountData localAcc = plugin.getEconomyManager().getCachedAccount(packet.getTargetUuid());
                            if (localAcc != null) {
                                double delta = packet.getAmount();
                                if (delta > 0) {
                                    localAcc.deposit(delta);
                                }
                            }
                        }
                    }
                }
                case CROSS_SERVER_WITHDRAW -> {
                    if (packet.getTargetUuid() != null && plugin.getEconomyManager() != null) {
                        Player online = Bukkit.getPlayer(packet.getTargetUuid());
                        if (online != null && online.isOnline()) {
                            AccountData localAcc = plugin.getEconomyManager().getCachedAccount(packet.getTargetUuid());
                            if (localAcc != null) {
                                double delta = packet.getAmount();
                                if (delta > 0) {
                                    localAcc.withdraw(delta);
                                }
                            }
                        }
                    }
                }
                case BALANCE_INVALIDATE -> {
                    if (packet.getTargetUuid() != null && plugin.getEconomyManager() != null) {
                        Player online = Bukkit.getPlayer(packet.getTargetUuid());
                        if ((online != null && online.isOnline()) || plugin.getEconomyManager().isOnlineLocally(packet.getTargetUuid())) {
                            plugin.getEconomyManager().syncOnlineAccountFromDatabase(packet.getTargetUuid());
                        } else {
                            plugin.getEconomyManager().invalidateAccountCache(packet.getTargetUuid());
                        }
                    }
                }
                case CROSS_SERVER_PAY -> {
                    if (packet.getTargetUuid() != null && plugin.getEconomyManager() != null) {
                        AccountData localAcc = plugin.getEconomyManager().getCachedAccount(packet.getTargetUuid());
                        if (localAcc != null) {
                            double delta = packet.getTransactionAmount();
                            if (localAcc.isDirty()) {
                                // Apply delta over uncommitted local changes to avoid clobbering concurrent transactions
                                if (delta > 0) {
                                    localAcc.deposit(delta);
                                }
                            } else if (delta > 0) {
                                localAcc.setBalance(packet.getAmount());
                                localAcc.markSaved(localAcc.getCurrentVersion());
                            }
                        }
                        Player receiver = Bukkit.getPlayer(packet.getTargetUuid());
                        if (receiver != null && receiver.isOnline()) {
                            String senderName = packet.getExtra();
                            plugin.getScheduler().runEntity(receiver, () -> {
                                boolean alertsEnabled = plugin.getSettingsManager() == null ||
                                        plugin.getSettingsManager().getSetting(receiver.getUniqueId(), dev.lyy.friendlycore.modules.settings.SettingType.PAY_NOTIFICATIONS);

                                if (alertsEnabled) {
                                    String formattedAmount = plugin.getNumberFormatter().format(packet.getTransactionAmount());
                                    plugin.getTextService().sendMessage(receiver,
                                            plugin.getConfigService().getMessages().getString("economy.payment-received",
                                                    "&#63ed2dYou received $%amount% from %sender%!"),
                                            Map.of("%amount%", formattedAmount,
                                                    "%sender%", senderName != null ? senderName : "A player"));

                                    boolean sendActionbar = plugin.getConfigService().getEconomyConfig().getBoolean("payment.actionbar.enabled", true);
                                    if (sendActionbar) {
                                        String recvBar = plugin.getConfigService().getMessages().getString("economy.actionbar-received",
                                                "&#63ed2dYou received $%amount% from %sender%!");
                                        plugin.getTextService().sendActionBar(receiver, recvBar,
                                                Map.of("%amount%", formattedAmount, "%sender%", senderName != null ? senderName : "A player"));
                                    }

                                    String recvSoundStr = plugin.getConfigService().getEconomyConfig().getString("payment.sound.receive", "ENTITY_PLAYER_LEVELUP");
                                    TextService.playSound(receiver, recvSoundStr, 1.0f, 1.0f);
                                }
                            });
                        }
                    }
                }
                case PLAYER_JOIN, NETWORK_FIRST_JOIN -> {
                    if (packet.getTargetName() != null) {
                        plugin.getRedisService().getNetworkOnlinePlayers().add(packet.getTargetName());
                        if (packet.getTargetUuid() != null) {
                            plugin.getRedisService().getNetworkPlayerUuids().put(packet.getTargetName().toLowerCase(Locale.ROOT), packet.getTargetUuid());
                            plugin.getRedisService().getNetworkOnlineUuids().add(packet.getTargetUuid());
                        }
                    }
                }
                case NETWORK_JOIN -> {
                    if (packet.getTargetName() != null) {
                        plugin.getRedisService().getNetworkOnlinePlayers().add(packet.getTargetName());
                        if (packet.getTargetUuid() != null) {
                            plugin.getRedisService().getNetworkPlayerUuids().put(packet.getTargetName().toLowerCase(Locale.ROOT), packet.getTargetUuid());
                            plugin.getRedisService().getNetworkOnlineUuids().add(packet.getTargetUuid());
                        }
                    }
                    if (plugin.getJoinQuitManager() != null && plugin.getJoinQuitManager().getCrossServerService() != null) {
                        plugin.getJoinQuitManager().getCrossServerService().handleIncomingPacket(packet);
                    }
                }
                case PLAYER_QUIT -> {
                    if (packet.getTargetName() != null) {
                        plugin.getRedisService().getNetworkOnlinePlayers().remove(packet.getTargetName());
                        plugin.getRedisService().getNetworkPlayerUuids().remove(packet.getTargetName().toLowerCase(Locale.ROOT));
                    }
                    if (packet.getTargetUuid() != null) {
                        plugin.getRedisService().getNetworkOnlineUuids().remove(packet.getTargetUuid());
                    }
                }
                case NETWORK_QUIT -> {
                    if (packet.getTargetName() != null) {
                        plugin.getRedisService().getNetworkOnlinePlayers().remove(packet.getTargetName());
                        plugin.getRedisService().getNetworkPlayerUuids().remove(packet.getTargetName().toLowerCase(Locale.ROOT));
                    }
                    if (packet.getTargetUuid() != null) {
                        plugin.getRedisService().getNetworkOnlineUuids().remove(packet.getTargetUuid());
                    }
                    if (plugin.getJoinQuitManager() != null && plugin.getJoinQuitManager().getCrossServerService() != null) {
                        plugin.getJoinQuitManager().getCrossServerService().handleIncomingPacket(packet);
                    }
                }
                case BALTOP_INVALIDATE -> {
                    if (plugin.getEconomyManager() != null) plugin.getEconomyManager().invalidateBaltopCache();
                }
                case GLOBAL_RESET -> {
                    if (plugin.getEconomyManager() != null) plugin.getEconomyManager().clearLocalCache();
                }
                case RTP_REQUEST -> {
                    if (plugin.getRtpManager() != null && plugin.getRtpManager().getCrossServerService() != null) {
                        plugin.getRtpManager().getCrossServerService().handleIncomingRequest(packet);
                    }
                }
                case RTP_LOCATION_REPLY -> {
                    if (plugin.getRtpManager() != null && plugin.getRtpManager().getCrossServerService() != null) {
                        plugin.getRtpManager().getCrossServerService().handleIncomingReply(packet);
                    }
                }
                case RTP_FAILED -> {
                    if (plugin.getRtpManager() != null && plugin.getRtpManager().getCrossServerService() != null) {
                        plugin.getRtpManager().getCrossServerService().handleIncomingFailure(packet);
                    }
                }
                case RTP_QUEUED -> {
                    if (plugin.getRtpManager() != null && plugin.getRtpManager().getCrossServerService() != null) {
                        plugin.getRtpManager().getCrossServerService().handleIncomingQueued(packet);
                    }
                }
                case RTP_COOLDOWN_SYNC -> {
                    if (plugin.getRtpManager() != null && packet.getTargetUuid() != null) {
                        if (Double.isFinite(packet.getAmount()) && "expiry".equals(packet.getExtra())) {
                            plugin.getRtpManager().handleRemoteCooldown(packet.getTargetUuid(), (long) packet.getAmount());
                        }
                    }
                }
                case RTP_DISCONNECT_REFUND -> {
                    // Legacy unaffiliated refund packets cannot mint money. RTP refunds are collected from Redis state.
                }
                case SPAWN_SYNC -> {
                    if (plugin.getSpawnManager() != null) {
                        plugin.getSpawnManager().handleIncomingSpawnSync(packet);
                    }
                }
                case SPAWN_TICKET -> {
                    if (plugin.getSpawnManager() != null) {
                        plugin.getSpawnManager().handleIncomingSpawnTicket(packet);
                    }
                }
                case LAST_LOCATION_TICKET -> {
                    if (plugin.getSpawnManager() != null && plugin.getSpawnManager().getLastLocationService() != null) {
                        plugin.getSpawnManager().getLastLocationService().handleIncomingTicket(packet);
                    }
                }
                case KIT_CLAIM_SYNC, KIT_RESET_SYNC, KIT_COOLDOWN_SYNC, KIT_GIVE_REMOTE, KIT_RELOAD_SYNC -> {
                    if (plugin.getKitManager() != null && plugin.getKitManager().getCrossServerService() != null) {
                        plugin.getKitManager().getCrossServerService().handleIncomingPacket(packet);
                    }
                }
                case TEAM_CREATE_SYNC, TEAM_DISBAND_SYNC, TEAM_MEMBER_ADD_SYNC, TEAM_MEMBER_REMOVE_SYNC,
                     TEAM_MEMBER_ROLE_SYNC, TEAM_UPDATE_SYNC, TEAM_INVITE_SYNC, TEAM_CHAT_SYNC, ALLY_CHAT_SYNC,
                     TEAM_ENDERCHEST_SYNC, TEAM_HOME_SYNC -> {
                    if (plugin.getTeamsModule() != null) {
                        plugin.getTeamsModule().handleIncomingCrossServerPacket(packet);
                    }
                }
                case HOME_CROSS_TELEPORT -> {
                    if (packet.getExtra() != null) {
                        try {
                            dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket homePacket =
                                    gson.fromJson(packet.getExtra(), dev.lyy.friendlycore.modules.homes.crossserver.HomeTeleportPacket.class);
                            String localServer = plugin.getRedisService() != null && plugin.getRedisService().getServerId() != null
                                    ? plugin.getRedisService().getServerId()
                                    : (plugin.getHomesManager() != null ? plugin.getHomesManager().getServerId() : "");

                            if (homePacket != null && (localServer.isEmpty() || homePacket.getTargetServer().equalsIgnoreCase(localServer))) {
                                org.bukkit.Location loc = homePacket.toLocation();
                                if (loc != null) {
                                    if (homePacket.isTeamHome() && plugin.getTeamsModule() != null) {
                                        plugin.getTeamsModule().getTeleportManager().registerPendingTeleport(homePacket.getPlayerUuid(), loc);
                                    } else if (plugin.getHomesManager() != null) {
                                        plugin.getHomesManager().registerPendingTeleport(homePacket.getPlayerUuid(), loc);
                                    } else if (plugin.getTeamsModule() != null) {
                                        plugin.getTeamsModule().getTeleportManager().registerPendingTeleport(homePacket.getPlayerUuid(), loc);
                                    }
                                } else {
                                    plugin.getLogger().warning("Dropped cross-server home teleport: world '" + homePacket.getWorldName()
                                            + "' is not loaded on this server (refusing random-world fallback)");
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to parse cross-server home packet: " + e.getMessage());
                        }
                    }
                }
                case TPA_REQUEST, TPA_ACCEPT, TPA_DENY, TPA_CANCEL, TPA_EXECUTE -> {
                    if (plugin.getTpaManager() != null && plugin.getTpaManager().getCrossServerService() != null) {
                        plugin.getTpaManager().getCrossServerService().handleIncomingPacket(packet);
                    }
                }
                case ADMIN_TP_REQUEST, ADMIN_TP_TICKET, ADMIN_TPHERE, ADMIN_GAMEMODE_SET, ADMIN_FLY_SET,
                     ADMIN_GOD_SET, ADMIN_HEAL, ADMIN_FEED, ADMIN_SPEED_SET, ADMIN_INVSEE_REQ,
                     ADMIN_INVSEE_RESP, ADMIN_INVSEE_SAVE, ADMIN_EC_REQ, ADMIN_EC_RESP, ADMIN_EC_SAVE,
                     ADMIN_TIME_SYNC, ADMIN_WEATHER_SYNC, ADMIN_CLEAR_INVENTORY -> {
                    if (plugin.getEssentialsManager() != null && plugin.getEssentialsManager().getCrossServerService() != null) {
                        plugin.getEssentialsManager().getCrossServerService().handleIncomingPacket(packet);
                    }
                }
                case PET_ACTIVATE_SYNC, PET_DEACTIVATE_SYNC, PET_UNLOCK_SYNC, PET_LOCK_SYNC,
                     PET_HIDE_TOGGLE_SYNC, PET_RELOAD_SYNC -> {
                    if (plugin.getPetManager() != null) {
                        plugin.getPetManager().handleCrossServerPacket(packet);
                    }
                }
                case CHAT_PUBLIC -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingPublicChat(packet.getExtra());
                    }
                }
                case CHAT_STAFF -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingStaffChat(packet.getTargetName(), packet.getExtra());
                    }
                }
                case CHAT_PRIVATE -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingPrivateMessage(packet.getTargetUuid(), packet.getExtra());
                    }
                }
                case CHAT_SPY -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingSocialSpy(packet.getExtra());
                    }
                }
                case CHAT_MUTE_SYNC -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingMuteSync(packet.getAmount() > 0.5, packet.getTargetName());
                    }
                }
                case CHAT_CLEAR_SYNC -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingClearSync(packet.getTargetName());
                    }
                }
                case SETTINGS_SYNC -> {
                    if (plugin.getSettingsManager() != null) {
                        plugin.getSettingsManager().handleRemoteSync(packet.getTargetUuid(), packet.getTargetName(), packet.getExtra());
                    }
                }
                case CHAT_BROADCAST -> {
                    if (plugin.getChatModule() != null) {
                        plugin.getChatModule().handleIncomingBroadcast(packet.getExtra());
                    }
                }
                case PVP_NPC_SLAIN_SYNC -> {
                    if (plugin.getPvpManager() != null && plugin.getPvpManager().getCombatNpcManager() != null) {
                        plugin.getPvpManager().getCombatNpcManager().handleRemoteNpcSlain(packet.getTargetUuid(), packet.getTargetName());
                    }
                }
                case AUCTION_ITEM_INSERT, AUCTION_ITEM_UPDATE, AUCTION_ITEM_DELETE, AUCTION_NOTIFICATION, AUCTION_PAYOUT, AUCTION_USER_LIMIT_SYNC -> {
                    if (plugin.getAuctionsModule() != null && plugin.getAuctionsModule().getPacketHandler() != null) {
                        plugin.getAuctionsModule().getPacketHandler().handlePacket(packet);
                    }
                }
                case ORDER_ITEM_INSERT, ORDER_ITEM_UPDATE, ORDER_ITEM_DELETE, ORDER_NOTIFICATION -> {
                    if (plugin.getOrdersModule() != null && plugin.getOrdersModule().getPacketHandler() != null) {
                        plugin.getOrdersModule().getPacketHandler().handlePacket(packet);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to apply verified Redis packet", e);
        }
    }
}
