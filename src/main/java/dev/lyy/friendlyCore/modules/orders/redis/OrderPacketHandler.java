package dev.lyy.friendlycore.modules.orders.redis;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.orders.OrderManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Handles Redis PubSub packet publication and receipt for multi-server buy-orders marketplace,
 * ensuring real-time order cache sync, seller payouts, and cross-server buyer alerts.
 */
public class OrderPacketHandler {

    private final FriendlyCore plugin;
    private final OrderManager orderManager;
    private final String serverId;

    public OrderPacketHandler(FriendlyCore plugin, OrderManager orderManager) {
        this.plugin = plugin;
        this.orderManager = orderManager;
        this.serverId = plugin.getConfigService().getMainConfig().getString("redis.server-id", "server-1");
    }

    public boolean isCrossServerEnabled() {
        return plugin.getRedisService() != null && plugin.getRedisService().isEnabled();
    }

    public void publishOrderInsert(UUID orderId) {
        if (!isCrossServerEnabled() || orderId == null) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.ORDER_ITEM_INSERT, null, null, 0, orderId.toString());
        plugin.getRedisService().publish(packet);
    }

    public void publishOrderUpdate(UUID orderId) {
        if (!isCrossServerEnabled() || orderId == null) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.ORDER_ITEM_UPDATE, null, null, 0, orderId.toString());
        plugin.getRedisService().publish(packet);
    }

    public void publishOrderDelete(UUID orderId) {
        if (!isCrossServerEnabled() || orderId == null) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.ORDER_ITEM_DELETE, null, null, 0, orderId.toString());
        plugin.getRedisService().publish(packet);
    }

    public void publishNotification(UUID targetUuid, String targetName, String message) {
        if (!isCrossServerEnabled() || targetUuid == null) return;
        CrossServerPacket packet = new CrossServerPacket(serverId, CrossServerPacket.Action.ORDER_NOTIFICATION, targetUuid, targetName, 0, message);
        plugin.getRedisService().publish(packet);
    }

    public void handlePacket(CrossServerPacket packet) {
        if (packet == null || packet.getSourceServer().equalsIgnoreCase(serverId)) {
            return;
        }

        switch (packet.getAction()) {
            case ORDER_ITEM_INSERT, ORDER_ITEM_UPDATE -> {
                String oidStr = packet.getExtra();
                if (oidStr != null && !oidStr.isEmpty()) {
                    try {
                        UUID orderId = UUID.fromString(oidStr);
                        plugin.getDatabaseManager().loadOrderById(orderId).thenAccept(order -> {
                            if (order != null) {
                                orderManager.cacheOrder(order);
                                if (packet.getAction() == CrossServerPacket.Action.ORDER_ITEM_INSERT) {
                                    var cfg = plugin.getConfigService().get("modules/orders/config.yml");
                                    if (cfg != null && cfg.getBoolean("announce.cross-server", true)) {
                                        orderManager.announceOrder(order, order.getPlayerName(), order.getTotalAmount(), order.getItem(), order.getPricePerItem());
                                    }
                                }
                            }
                        });
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            case ORDER_ITEM_DELETE -> {
                String oidStr = packet.getExtra();
                if (oidStr != null && !oidStr.isEmpty()) {
                    try {
                        UUID orderId = UUID.fromString(oidStr);
                        orderManager.removeCachedOrder(orderId);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            case ORDER_NOTIFICATION -> {
                UUID userUuid = packet.getTargetUuid();
                if (userUuid != null) {
                    Player target = Bukkit.getPlayer(userUuid);
                    if (target != null && target.isOnline() && packet.getExtra() != null) {
                        plugin.getTextService().send(target, packet.getExtra());
                    }
                }
            }
        }
    }
}
