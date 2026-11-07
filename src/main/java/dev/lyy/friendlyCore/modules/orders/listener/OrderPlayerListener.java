package dev.lyy.friendlycore.modules.orders.listener;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OrderPlayerListener implements Listener {

    private final FriendlyCore plugin;
    private final Set<UUID> disabledNotifications = ConcurrentHashMap.newKeySet();

    public OrderPlayerListener(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean isNotificationsEnabled(UUID uuid) {
        return !disabledNotifications.contains(uuid);
    }

    public boolean toggleNotifications(UUID uuid) {
        if (disabledNotifications.contains(uuid)) {
            disabledNotifications.remove(uuid);
            return true;
        } else {
            disabledNotifications.add(uuid);
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        if (config == null || !config.getBoolean("notifications.enabled", true)) return;
        if (!isNotificationsEnabled(player.getUniqueId())) return;

        plugin.getDatabaseManager().fetchAndClearOrderNotifications(player.getUniqueId()).thenAccept(notifs -> {
            if (notifs != null && !notifs.isEmpty()) {
                plugin.getScheduler().runEntity(player, () -> {
                    for (var notif : notifs) {
                        plugin.getTextService().send(player, notif.getMessage());
                    }
                });
            }
        });

        // Check for unclaimed orders in delivery collection box
        plugin.getDatabaseManager().loadUnclaimedDeliveries(player.getUniqueId()).thenAccept(deliveries -> {
            if (deliveries != null && !deliveries.isEmpty()) {
                plugin.getScheduler().runEntity(player, () -> {
                    plugin.getTextService().send(player, "notifications.unclaimed_order",
                            "{amount}", plugin.getOrdersModule().getOrderManager().formatAmount(deliveries.size()));
                });
            }
        });
    }
}
