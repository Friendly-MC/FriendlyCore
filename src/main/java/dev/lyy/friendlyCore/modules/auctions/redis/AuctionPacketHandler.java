package dev.lyy.friendlycore.modules.auctions.redis;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Handles Redis PubSub packet publication and receipt for multi-server auction house synchronization,
 * including item cache invalidation, cross-server payouts, and live buyer/seller notifications.
 */
public class AuctionPacketHandler {

    private final FriendlyCore plugin;
    private final AuctionManager auctionManager;
    private final String serverId;

    public AuctionPacketHandler(FriendlyCore plugin, AuctionManager auctionManager) {
        this.plugin = plugin;
        this.auctionManager = auctionManager;
        this.serverId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");
    }

    public boolean isCrossServerEnabled() {
        return plugin.getDatabaseManager() instanceof dev.lyy.friendlycore.common.database.MySQLDatabase
                && plugin.getRedisService() != null && plugin.getRedisService().isEnabled();
    }

    public void publishItemInsert(int itemId) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_ITEM_INSERT, null, null, itemId, String.valueOf(itemId));
        plugin.getRedisService().publish(packet);
    }

    public void publishItemUpdate(int itemId) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_ITEM_UPDATE, null, null, itemId, String.valueOf(itemId));
        plugin.getRedisService().publish(packet);
    }

    public void publishItemDelete(int itemId) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_ITEM_DELETE, null, null, itemId, String.valueOf(itemId));
        plugin.getRedisService().publish(packet);
    }

    public void publishPayout(UUID sellerUuid, String sellerName, double amount, String reason) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_PAYOUT, sellerUuid, sellerName, amount, reason);
        plugin.getRedisService().publish(packet);
    }

    public void publishNotification(UUID targetUuid, String targetName, String message) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_NOTIFICATION, targetUuid, targetName, 0, message);
        plugin.getRedisService().publish(packet);
    }

    public void publishUserLimit(UUID targetUuid, String targetName, int extraLimit) {
        if (!isCrossServerEnabled()) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.AUCTION_USER_LIMIT_SYNC, targetUuid, targetName, extraLimit, String.valueOf(extraLimit));
        plugin.getRedisService().publish(packet);
    }

    public void handlePacket(CrossServerPacket packet) {
        if (!isCrossServerEnabled() || packet == null || serverId.equalsIgnoreCase(packet.getSourceServer())) {
            return;
        }

        switch (packet.getAction()) {
            case AUCTION_ITEM_INSERT -> auctionManager.refreshCachedItem((int) packet.getAmount(), true);
            case AUCTION_ITEM_UPDATE -> auctionManager.refreshCachedItem((int) packet.getAmount(), false);
            case AUCTION_ITEM_DELETE -> {
                int itemId = (int) packet.getAmount();
                auctionManager.removeCachedItem(itemId);
            }
            case AUCTION_NOTIFICATION -> {
                UUID userUuid = packet.getTargetUuid();
                if (userUuid != null) {
                    plugin.getScheduler().runGlobal(() -> {
                        Player target = Bukkit.getPlayer(userUuid);
                        if (target != null) {
                            plugin.getScheduler().runEntity(target, () -> {
                                if (packet.getExtra() != null && !packet.getExtra().isBlank()) {
                                    target.sendActionBar(plugin.getTextService().parse(packet.getExtra()));
                                }
                                auctionManager.checkPendingDeliveries(target);
                            });
                        }
                    });
                }
            }
            case AUCTION_PAYOUT -> {
                UUID userUuid = packet.getTargetUuid();
                if (userUuid != null) {
                    plugin.getScheduler().runGlobal(() -> {
                        Player target = Bukkit.getPlayer(userUuid);
                        if (target != null) {
                            plugin.getScheduler().runEntity(target, () -> auctionManager.checkPendingDeliveries(target));
                        }
                    });
                }
            }
            case AUCTION_USER_LIMIT_SYNC -> {
                UUID userUuid = packet.getTargetUuid();
                if (userUuid != null) {
                    auctionManager.syncUserLimitLocally(userUuid, (int) packet.getAmount());
                }
            }
            default -> {}
        }
    }
}
