package dev.lyy.friendlycore.modules.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.modules.orders.enums.OrderFilter;
import dev.lyy.friendlycore.modules.orders.enums.OrderSort;
import dev.lyy.friendlycore.modules.orders.enums.OrderState;
import dev.lyy.friendlycore.modules.orders.events.*;
import dev.lyy.friendlycore.modules.orders.model.*;
import dev.lyy.friendlycore.modules.orders.redis.OrderPacketHandler;
import dev.lyy.friendlycore.modules.orders.safety.OrderSafetyValidator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class OrderManager {

    private final FriendlyCore plugin;
    private final OrderSafetyValidator safetyValidator;
    private final OrderPacketHandler packetHandler;

    private final ConcurrentHashMap<UUID, BuyOrder> activeOrders = new ConcurrentHashMap<>();
    private final Set<String> blacklistedMaterials = ConcurrentHashMap.newKeySet();
    private final Map<Material, Double> standardPrices = new ConcurrentHashMap<>();
    private final List<OrderPermissionLimit> permissionLimits = new ArrayList<>();
    private final Set<UUID> localLocks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> claimLocks = ConcurrentHashMap.newKeySet();

    private Object expirationTask;

    public OrderManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.safetyValidator = new OrderSafetyValidator(plugin);
        this.packetHandler = new OrderPacketHandler(plugin, this);
    }

    public void initialize() {
        loadConfigurations();
        loadOrdersFromDatabase();
        startExpirationTask();
    }

    public void reload() {
        loadConfigurations();
        loadOrdersFromDatabase();
    }

    public void shutdown() {
        if (expirationTask != null) {
            plugin.getScheduler().cancel(expirationTask);
        }
        activeOrders.clear();
        localLocks.clear();
    }

    public void loadConfigurations() {
        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        FileConfiguration blacklistCfg = plugin.getConfigService().get("modules/orders/blacklist.yml");
        FileConfiguration pricesCfg = plugin.getConfigService().get("modules/orders/prices.yml");

        blacklistedMaterials.clear();
        if (blacklistCfg != null) {
            List<String> list = blacklistCfg.getStringList("blacklisted_items");
            for (String s : list) {
                if (s != null && !s.isEmpty()) {
                    blacklistedMaterials.add(s.toUpperCase(Locale.ROOT));
                }
            }
        }

        standardPrices.clear();
        if (pricesCfg != null) {
            ConfigurationSection sec = pricesCfg.getConfigurationSection("prices");
            if (sec != null) {
                for (String matName : sec.getKeys(false)) {
                    Material mat = Material.matchMaterial(matName);
                    if (mat != null) {
                        standardPrices.put(mat, sec.getDouble(matName, 1.0));
                    }
                }
            }
        }

        permissionLimits.clear();
        if (config != null) {
            List<String> limits = config.getStringList("permissions.order-limits");
            for (String entry : limits) {
                if (entry != null && entry.contains(":")) {
                    String[] parts = entry.split(":");
                    try {
                        String perm = parts[0].trim();
                        int max = Integer.parseInt(parts[1].trim());
                        permissionLimits.add(new OrderPermissionLimit(perm, max));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    public void loadOrdersFromDatabase() {
        long now = System.currentTimeMillis();
        dev.lyy.friendlycore.common.database.DatabaseReadRetry.load(plugin,
            () -> plugin.getDatabaseManager().loadActiveOrders(), plugin::isEnabled).thenAccept(orders -> {
            activeOrders.clear();
            if (orders != null) {
                for (BuyOrder order : orders) {
                    if (order.getState() == OrderState.ACTIVE && !order.isCancelled() && !order.isExpired()) {
                        activeOrders.put(order.getOrderId(), order);
                    }
                }
            }
            plugin.getLogger().info("Loaded " + activeOrders.size() + " active buy orders into memory.");

            // Check and refund any orders that expired while server was offline
            plugin.getDatabaseManager().loadExpiredActiveOrders(now).thenAccept(expiredList -> {
                if (expiredList != null) {
                    for (BuyOrder exp : expiredList) {
                        expireOrder(exp);
                    }
                }
            });
        });
    }

    private void startExpirationTask() {
        if (expirationTask != null) {
            plugin.getScheduler().cancel(expirationTask);
        }
        this.expirationTask = plugin.getScheduler().runAsyncTimer(() -> {
            long now = System.currentTimeMillis();
            for (BuyOrder order : activeOrders.values()) {
                if (order.getExpirationTime() <= now && order.getState() == OrderState.ACTIVE) {
                    expireOrder(order);
                }
            }
        }, 1200L, 1200L);
    }

    public void expireOrder(BuyOrder order) {
        if (order == null) return;

        plugin.getDatabaseManager().expireOrderAtomic(order.getOrderId()).thenAccept(success -> {
            activeOrders.remove(order.getOrderId(), order);
            if (!success) return; // Another cluster server already processed expiration

            order.setState(OrderState.EXPIRED);

            double remainingRefund = order.getRemainingCost();
            if (remainingRefund > 0) {
                boolean deposited = depositMoney(order.getCreatorId(), remainingRefund, "Order Expiration Refund (" + order.getAvailableAmount() + " remaining)");
                if (!deposited) {
                    plugin.getLogger().severe("CRITICAL: Failed to refund escrow of $" + remainingRefund + " to player UUID " + order.getCreatorId() + " (" + order.getPlayerName() + ") for expired order " + order.getOrderId());
                }
            }

            OrderLogEntry log = new OrderLogEntry("EXPIRE", order.getOrderId(), order.getCreatorId(), order.getPlayerName(),
                    order.getItem() != null ? order.getItem().getType().name() : "Item", order.getTotalAmount(), order.getPricePerItem(),
                    "Order expired naturally. Refunded $" + remainingRefund, System.currentTimeMillis());
            plugin.getDatabaseManager().insertOrderLog(log);

            sendWebhook("order-expire", "Order Expired",
                    "**Player:** " + order.getPlayerName() + "\n" +
                    "**Item:** " + (order.getItem() != null ? order.getItem().getType().name() : "Item") + "\n" +
                    "**Refunded Escrow:** $" + remainingRefund, 0xFF9900);

            packetHandler.publishOrderDelete(order.getOrderId());
        });
    }

    public int getPlayerMaxOrders(Player player) {
        if (player == null) return 3;
        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        String adminPerm = config != null ? config.getString("permissions.order-admin", "friendlycore.orders.admin") : "friendlycore.orders.admin";
        if (player.hasPermission(adminPerm) || player.isOp()) {
            return 26;
        }

        int max = 3;
        for (OrderPermissionLimit limit : permissionLimits) {
            if (player.hasPermission(limit.permission())) {
                max = Math.max(max, limit.limit());
            }
        }
        return Math.min(26, max);
    }

    public long getPlayerActiveOrderCount(UUID uuid) {
        if (uuid == null) return 0;
        long count = 0;
        for (BuyOrder order : activeOrders.values()) {
            if (order.getCreatorId().equals(uuid) && order.getState() == OrderState.ACTIVE && !order.isCancelled()) {
                count++;
            }
        }
        return count;
    }

    public double getStandardPrice(Material material) {
        return standardPrices.getOrDefault(material, 10.0);
    }

    public Set<String> getBlacklistedMaterials() {
        return blacklistedMaterials;
    }

    public Map<UUID, BuyOrder> getActiveOrders() {
        return activeOrders;
    }

    public void cacheOrder(BuyOrder order) {
        if (order != null && order.getState() == OrderState.ACTIVE && !order.isCancelled()) {
            activeOrders.put(order.getOrderId(), order);
        }
    }

    public void removeCachedOrder(UUID orderId) {
        if (orderId != null) {
            activeOrders.remove(orderId);
        }
    }

    public CompletableFuture<Boolean> createOrder(Player player, ItemStack itemTemplate, long amount, double pricePerItem) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
        if (isPlayerLocked(player)) {
            plugin.getTextService().send(player, "order_locked");
            return CompletableFuture.completedFuture(false);
        }

        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        double minTotal = config != null ? config.getDouble("pricing.total.min", 1.0) : 1.0;
        double maxTotal = config != null ? config.getDouble("pricing.total.max", 1000000000.0) : 1000000000.0;
        double minUnit = config != null ? config.getDouble("pricing.per-unit.min", -1.0) : -1.0;
        double maxUnit = config != null ? config.getDouble("pricing.per-unit.max", -1.0) : -1.0;
        int publicDays = config != null ? config.getInt("duration.public", 7) : 7;
        long durationMillis = publicDays * 86400000L;

        if (itemTemplate == null || itemTemplate.getType().isAir()) {
            plugin.getTextService().send(player, "select_item");
            return CompletableFuture.completedFuture(false);
        }

        if (safetyValidator.isBlacklisted(itemTemplate.getType())) {
            plugin.getTextService().send(player, "item_blacklisted");
            return CompletableFuture.completedFuture(false);
        }

        int maxOrders = getPlayerMaxOrders(player);
        if (getPlayerActiveOrderCount(player.getUniqueId()) >= maxOrders) {
            plugin.getTextService().send(player, "max_orders_reached",
                    "{limit}", formatAmount(maxOrders),
                    "%limit%", formatAmount(maxOrders));
            return CompletableFuture.completedFuture(false);
        }

        if (amount <= 0) {
            plugin.getTextService().send(player, "invalid_amount");
            return CompletableFuture.completedFuture(false);
        }

        if (Double.isNaN(pricePerItem) || Double.isInfinite(pricePerItem) || pricePerItem <= 0) {
            plugin.getTextService().send(player, "invalid_price");
            return CompletableFuture.completedFuture(false);
        }

        if (minUnit > 0 && pricePerItem < minUnit) {
            plugin.getTextService().send(player, "pricing.per_unit_min", "{minPrice}", formatPrice(minUnit));
            return CompletableFuture.completedFuture(false);
        }
        if (maxUnit > 0 && pricePerItem > maxUnit) {
            plugin.getTextService().send(player, "pricing.per_unit_max", "{maxPrice}", formatPrice(maxUnit));
            return CompletableFuture.completedFuture(false);
        }

        double totalCost = BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(pricePerItem))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        if (totalCost < minTotal) {
            plugin.getTextService().send(player, "pricing.min", "{minPrice}", formatPrice(minTotal));
            return CompletableFuture.completedFuture(false);
        }
        if (totalCost > maxTotal) {
            plugin.getTextService().send(player, "pricing.max", "{maxPrice}", formatPrice(maxTotal));
            return CompletableFuture.completedFuture(false);
        }

        double playerBalance = getPlayerBalance(player.getUniqueId());
        if (playerBalance < totalCost) {
            plugin.getTextService().send(player, "not_enough_money");
            return CompletableFuture.completedFuture(false);
        }

        boolean withdrawn = withdrawMoney(player.getUniqueId(), totalCost, "Buy Order Escrow (" + amount + "x " + itemTemplate.getType().name() + ")");
        if (!withdrawn) {
            plugin.getTextService().send(player, "not_enough_money");
            return CompletableFuture.completedFuture(false);
        }

        BuyOrder order = new BuyOrder(
                UUID.randomUUID(),
                player.getUniqueId(),
                player.getName(),
                itemTemplate.clone(),
                amount,
                pricePerItem,
                0,
                0,
                0.0,
                System.currentTimeMillis(),
                durationMillis,
                false,
                OrderState.ACTIVE
        );

        PlayerOrderItemEvent event = new PlayerOrderItemEvent(player, order);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            boolean deposited = depositMoney(player.getUniqueId(), totalCost, "Order Cancelled Event Rollback");
            if (!deposited) {
                plugin.getLogger().severe("CRITICAL: Failed to rollback escrow of $" + totalCost + " to player UUID " + player.getUniqueId() + " (" + player.getName() + ") after OrderItemEvent was cancelled");
            }
            return CompletableFuture.completedFuture(false);
        }

        return plugin.getDatabaseManager().insertOrder(order).thenApply(v -> {
            activeOrders.put(order.getOrderId(), order);
            packetHandler.publishOrderInsert(order.getOrderId());

            announceOrder(order, player.getName(), amount, itemTemplate, pricePerItem);

            plugin.getTextService().send(player, "on_order",
                    "{amount}", formatAmount(amount),
                    "{itemType}", formatItemName(itemTemplate),
                    "{totalPrice}", formatPrice(totalCost));

            OrderLogEntry log = new OrderLogEntry("CREATE", order.getOrderId(), player.getUniqueId(), player.getName(),
                    itemTemplate.getType().name(), amount, pricePerItem, "Created buy order for " + amount + "x at $" + formatPrice(pricePerItem) + " each", System.currentTimeMillis());
            plugin.getDatabaseManager().insertOrderLog(log);

            sendWebhook("order-create", "New Order Created",
                    "**Player:** " + player.getName() + "\n" +
                    "**Item:** " + formatItemName(itemTemplate) + "\n" +
                    "**Amount:** " + formatAmount(amount) + "\n" +
                    "**Unit Price:** $" + formatPrice(pricePerItem) + "\n" +
                    "**Total Escrow:** $" + formatPrice(totalCost), 0x00FF00);

            return true;
        }).exceptionally(e -> {
            boolean deposited = depositMoney(player.getUniqueId(), totalCost, "Order Failed Rollback");
            if (!deposited) {
                plugin.getLogger().severe("CRITICAL: Failed to rollback escrow of $" + totalCost + " to player UUID " + player.getUniqueId() + " (" + player.getName() + ") after order creation failed");
            }
            plugin.getLogger().log(Level.SEVERE, "Failed to create buy order", e);
            return false;
        });
    }

    public CompletableFuture<Boolean> deliverOrder(Player seller, UUID orderId, long deliverCount) {
        if (seller == null || !seller.isOnline()) return CompletableFuture.completedFuture(false);
        if (isPlayerLocked(seller)) {
            plugin.getTextService().send(seller, "order_locked");
            return CompletableFuture.completedFuture(false);
        }

        BuyOrder order = activeOrders.get(orderId);
        if (order == null || order.isCancelled() || order.getState() != OrderState.ACTIVE) {
            plugin.getTextService().send(seller, "order_already_cancelled");
            return CompletableFuture.completedFuture(false);
        }

        if (deliverCount <= 0) {
            plugin.getTextService().send(seller, "invalid_amount");
            return CompletableFuture.completedFuture(false);
        }

        long maxNeeded = order.getAvailableAmount();
        final long effectiveDeliverCount = Math.min(deliverCount, maxNeeded);

        CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

        acquireLock(orderId).thenAccept(locked -> {
            if (!locked) {
                plugin.getTextService().send(seller, "order_locked");
                resultFuture.complete(false);
                return;
            }

            plugin.getScheduler().runEntity(seller, () -> {
                if (!seller.isOnline() || isPlayerLocked(seller)) {
                    releaseLock(orderId);
                    resultFuture.complete(false);
                    return;
                }

                if (order.isCancelled() || order.getState() != OrderState.ACTIVE) {
                    releaseLock(orderId);
                    plugin.getTextService().send(seller, "order_already_cancelled");
                    resultFuture.complete(false);
                    return;
                }

                try {
                    FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
                    boolean requireFullDurability = config != null && config.getBoolean("require-full-durability", false);

                    long matchingCount = safetyValidator.countMatchingItems(seller, order.getItem(), requireFullDurability);
                    if (matchingCount < effectiveDeliverCount) {
                        plugin.getTextService().send(seller, "no_matching_items");
                        releaseLock(orderId);
                        resultFuture.complete(false);
                        return;
                    }

                    double payout = BigDecimal.valueOf(effectiveDeliverCount)
                            .multiply(BigDecimal.valueOf(order.getPricePerItem()))
                            .setScale(2, RoundingMode.HALF_UP)
                            .doubleValue();

                    PlayerOrderDeliveryEvent event = new PlayerOrderDeliveryEvent(seller, order, effectiveDeliverCount, payout);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        releaseLock(orderId);
                        resultFuture.complete(false);
                        return;
                    }

                    // Remove items on entity thread
                    long removed = safetyValidator.removeMatchingItems(seller, order.getItem(), effectiveDeliverCount, requireFullDurability);
                    if (removed < effectiveDeliverCount) {
                        if (removed > 0) {
                            refundItemsToSeller(seller, seller.getUniqueId(), order.getItem(), removed);
                        }
                        releaseLock(orderId);
                        resultFuture.complete(false);
                        return;
                    }

                    ItemStack deliveryItem = order.getItem().clone();
                    deliveryItem.setAmount(1);

                    OrderDelivery delivery = new OrderDelivery(0, orderId, order.getCreatorId(), seller.getUniqueId(),
                            seller.getName(), deliveryItem, (int) effectiveDeliverCount, System.currentTimeMillis(), false);
                    OrderHistoryEntry history = new OrderHistoryEntry(0, orderId, order.getCreatorId(), seller.getUniqueId(),
                            seller.getName(), (int) effectiveDeliverCount, order.getPricePerItem(), payout, System.currentTimeMillis());

                    final AtomicBoolean fulfillCommitted = new AtomicBoolean(false);
                    plugin.getDatabaseManager().fulfillOrderAtomic(orderId, effectiveDeliverCount, payout, delivery, history).thenApply(success -> {
                        try {
                            if (success) {
                                fulfillCommitted.set(true);
                                boolean deposited = depositMoney(seller.getUniqueId(), payout, "Order Delivery Payout (" + effectiveDeliverCount + "x " + order.getItem().getType().name() + ")");
                                if (!deposited) {
                                    plugin.getLogger().severe("CRITICAL: Failed to deposit payout of $" + payout + " to seller UUID " + seller.getUniqueId() + " (" + seller.getName() + ") for fulfilled order " + orderId + "! Items were already transferred to buyer. Manual admin reimbursement required.");
                                }

                                order.setDeliveredAmount(order.getDeliveredAmount() + effectiveDeliverCount);
                                order.setTotalPaid(order.getTotalPaid() + payout);

                                boolean isComplete = order.isComplete();
                                if (isComplete) {
                                    order.setState(OrderState.FULFILLED);
                                    activeOrders.remove(orderId);
                                    PlayerOrderDeliveryCompleteEvent completeEvent = new PlayerOrderDeliveryCompleteEvent(order);
                                    plugin.getScheduler().runGlobal(() -> Bukkit.getPluginManager().callEvent(completeEvent));
                                }

                                plugin.getTextService().send(seller, "on_deliver",
                                        "{amount}", formatAmount(effectiveDeliverCount),
                                        "{itemType}", formatItemName(order.getItem()),
                                        "{totalPrice}", formatPrice(payout));

                                Player buyer = Bukkit.getPlayer(order.getCreatorId());
                                if (buyer != null && buyer.isOnline()) {
                                    plugin.getTextService().send(buyer, "notifications.items_delivered",
                                            "{amount}", formatAmount(effectiveDeliverCount),
                                            "{itemName}", formatItemName(order.getItem()),
                                            "{senderName}", seller.getName());
                                } else {
                                    plugin.getDatabaseManager().insertOrderNotification(order.getCreatorId(), orderId,
                                            "&7Received delivery of &f" + formatAmount(effectiveDeliverCount) + "x " + formatItemName(order.getItem()) + " &7from <gradient:#E63A35:#FF6B6B><b>" + seller.getName() + "</b></gradient>&7!");
                                }

                                // Broadcast Redis sync
                                packetHandler.publishOrderUpdate(orderId);
                                packetHandler.publishNotification(order.getCreatorId(), order.getPlayerName(),
                                        "&7You have been delivered #E63A35" + formatAmount(effectiveDeliverCount) + " " + formatItemName(order.getItem()) + " &7from #E63A35" + seller.getName());

                                OrderLogEntry log = new OrderLogEntry("DELIVER", orderId, seller.getUniqueId(), seller.getName(),
                                        order.getItem().getType().name(), effectiveDeliverCount, payout, "Delivered " + formatAmount(effectiveDeliverCount) + " items to " + order.getPlayerName(), System.currentTimeMillis());
                                plugin.getDatabaseManager().insertOrderLog(log);

                                sendWebhook("order-deliver", "Order Items Delivered",
                                        "**Seller:** " + seller.getName() + "\n" +
                                        "**Buyer:** " + order.getPlayerName() + "\n" +
                                        "**Delivered:** " + formatAmount(effectiveDeliverCount) + "x " + order.getItem().getType().name() + "\n" +
                                        "**Payout:** $" + formatPrice(payout), 0x3399FF);

                                return true;
                            } else {
                                // Rollback items to seller safely (handles offline or disconnect during fulfillment)
                                refundItemsToSeller(seller, seller.getUniqueId(), order.getItem(), effectiveDeliverCount);
                                if (seller != null && seller.isOnline()) {
                                    plugin.getTextService().send(seller, "order_completed");
                                }
                                return false;
                            }
                        } finally {
                            releaseLock(orderId);
                        }
                    }).whenComplete((res, err) -> {
                        if (err != null) {
                            releaseLock(orderId);
                            if (!fulfillCommitted.get()) {
                                plugin.getLogger().log(Level.SEVERE, "Database error delivering order " + orderId + ", rolling back items to seller UUID " + seller.getUniqueId(), err);
                                refundItemsToSeller(seller, seller.getUniqueId(), order.getItem(), effectiveDeliverCount);
                            }
                            resultFuture.completeExceptionally(err);
                        } else {
                            resultFuture.complete(res);
                        }
                    });
                } catch (Exception e) {
                    releaseLock(orderId);
                    plugin.getLogger().log(Level.SEVERE, "Error delivering order " + orderId, e);
                    resultFuture.complete(false);
                }
            }, () -> {
                releaseLock(orderId);
                resultFuture.complete(false);
            });
        }).exceptionally(ex -> {
            releaseLock(orderId);
            plugin.getLogger().log(Level.SEVERE, "Error acquiring lock for order " + orderId, ex);
            resultFuture.complete(false);
            return null;
        });

        return resultFuture;
    }

    public CompletableFuture<Boolean> cancelOrder(Player player, UUID orderId) {
        BuyOrder order = activeOrders.get(orderId);
        if (order == null || order.isCancelled() || order.getState() != OrderState.ACTIVE) {
            if (player != null) plugin.getTextService().send(player, "order_already_cancelled");
            return CompletableFuture.completedFuture(false);
        }

        if (order.getUnclaimedAmount() > 0) {
            if (player != null) plugin.getTextService().send(player, "order_cancel_has_items");
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

        acquireLock(orderId).thenAccept(locked -> {
            if (!locked) {
                if (player != null) plugin.getTextService().send(player, "order_locked");
                resultFuture.complete(false);
                return;
            }

            Runnable cancelAction = () -> {
                if (order.isCancelled() || order.getState() != OrderState.ACTIVE) {
                    releaseLock(orderId);
                    if (player != null) plugin.getTextService().send(player, "order_already_cancelled");
                    resultFuture.complete(false);
                    return;
                }

                try {
                    double refundAmount = order.getRemainingCost();

                    if (player != null) {
                        PlayerOrderCancelEvent event = new PlayerOrderCancelEvent(player, order, refundAmount);
                        Bukkit.getPluginManager().callEvent(event);
                        if (event.isCancelled()) {
                            releaseLock(orderId);
                            resultFuture.complete(false);
                            return;
                        }
                    }

                    plugin.getDatabaseManager().cancelOrderAtomic(orderId).thenApply(success -> {
                        try {
                            if (success) {
                                order.setCancelled(true);
                                order.setState(OrderState.CANCELLED);
                                activeOrders.remove(orderId);

                                // Escrow refund always goes directly to the order creator
                                if (refundAmount > 0) {
                                    boolean deposited = depositMoney(order.getCreatorId(), refundAmount, "Order Cancellation Escrow Refund");
                                    if (!deposited) {
                                        plugin.getLogger().severe("CRITICAL: Failed to refund escrow of $" + refundAmount + " to player UUID " + order.getCreatorId() + " (" + order.getPlayerName() + ") for cancelled order " + orderId);
                                    }
                                }

                                packetHandler.publishOrderDelete(orderId);

                                String executorName = player != null ? player.getName() : "Console/Admin";
                                UUID executorUuid = player != null ? player.getUniqueId() : order.getCreatorId();

                                OrderLogEntry log = new OrderLogEntry("CANCEL", orderId, executorUuid, executorName,
                                        order.getItem() != null ? order.getItem().getType().name() : "Item", order.getAvailableAmount(), refundAmount,
                                        "Order cancelled. Refunded $" + refundAmount + " to " + order.getPlayerName(), System.currentTimeMillis());
                                plugin.getDatabaseManager().insertOrderLog(log);

                                if (player != null) {
                                    plugin.getTextService().send(player, "order_cancelled");
                                }

                                sendWebhook("order-cancel", "Order Cancelled",
                                        "**Player:** " + order.getPlayerName() + "\n" +
                                        "**Cancelled By:** " + executorName + "\n" +
                                        "**Refunded:** $" + formatPrice(refundAmount), 0xFF0000);

                                return true;
                            } else {
                                if (player != null) plugin.getTextService().send(player, "order_already_cancelled");
                                return false;
                            }
                        } finally {
                            releaseLock(orderId);
                        }
                    }).whenComplete((res, err) -> {
                        if (err != null) {
                            releaseLock(orderId);
                            resultFuture.completeExceptionally(err);
                        } else {
                            resultFuture.complete(res);
                        }
                    });
                } catch (Exception e) {
                    releaseLock(orderId);
                    plugin.getLogger().log(Level.SEVERE, "Error cancelling order " + orderId, e);
                    resultFuture.complete(false);
                }
            };

            if (player != null && player.isOnline()) {
                plugin.getScheduler().runEntity(player, cancelAction, () -> {
                    releaseLock(orderId);
                    resultFuture.complete(false);
                });
            } else {
                plugin.getScheduler().runGlobal(cancelAction);
            }
        }).exceptionally(ex -> {
            releaseLock(orderId);
            plugin.getLogger().log(Level.SEVERE, "Error acquiring lock for order " + orderId, ex);
            resultFuture.complete(false);
            return null;
        });

        return resultFuture;
    }

    public CompletableFuture<Integer> claimDeliveries(Player player) {
        return claimDeliveries(player, null, false);
    }

    public CompletableFuture<Integer> claimDelivery(Player player, int deliveryId) {
        return claimDeliveries(player, deliveryId, false);
    }

    public CompletableFuture<Integer> sellDeliveries(Player player) {
        return claimDeliveries(player, null, true);
    }

    private CompletableFuture<Integer> claimDeliveries(Player player, Integer deliveryId, boolean sell) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(0);
        if (isPlayerLocked(player)) {
            plugin.getTextService().send(player, "order_locked");
            return CompletableFuture.completedFuture(0);
        }

        if (!claimLocks.add(player.getUniqueId())) {
            return CompletableFuture.completedFuture(0);
        }

        UUID playerUuid = player.getUniqueId();
        String lockKey = "friendlycore:order_claim:" + playerUuid;
        String lockValue = UUID.randomUUID().toString();

        CompletableFuture<Boolean> redisLockFuture;
        try {
            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                redisLockFuture = CompletableFuture.supplyAsync(() ->
                    plugin.getRedisService().acquireDistributedLock(lockKey, lockValue, 10),
                    plugin.getScheduler().getAsyncExecutor()
                );
            } else {
                redisLockFuture = CompletableFuture.completedFuture(true);
            }
        } catch (Exception error) {
            claimLocks.remove(playerUuid);
            plugin.getLogger().log(Level.SEVERE, "Failed to schedule order claim for " + playerUuid, error);
            return CompletableFuture.completedFuture(0);
        }

        return redisLockFuture.thenCompose(redisLocked -> {
            if (!redisLocked) {
                claimLocks.remove(playerUuid);
                plugin.getTextService().send(player, "order_locked");
                return CompletableFuture.completedFuture(0);
            }

            return plugin.getDatabaseManager().loadUnclaimedDeliveries(playerUuid).thenCompose(deliveries -> {
                if (deliveries == null || deliveries.isEmpty()) {
                    return CompletableFuture.completedFuture(0);
                }

                List<OrderDelivery> selected = deliveries.stream()
                        .filter(delivery -> deliveryId == null || delivery.getId() == deliveryId)
                        .filter(delivery -> delivery.getItem() != null && delivery.getQuantity() > 0)
                        .filter(delivery -> !sell || (Double.isFinite(getStandardPrice(delivery.getItem().getType()))
                                && getStandardPrice(delivery.getItem().getType()) > 0))
                        .toList();
                List<Integer> ids = selected.stream().map(OrderDelivery::getId).toList();
                if (ids.isEmpty()) return CompletableFuture.completedFuture(0);

                return plugin.getDatabaseManager().claimDeliveries(playerUuid, ids).thenCompose(claimedIds -> {
                    if (claimedIds == null || claimedIds.isEmpty()) return CompletableFuture.completedFuture(0);
                    Set<Integer> claimed = new HashSet<>(claimedIds);
                    List<OrderDelivery> acquired = selected.stream().filter(delivery -> claimed.contains(delivery.getId())).toList();
                    return awardClaimedDeliveries(player, acquired, sell, deliveryId != null);
                });
            }).whenComplete((res, ex) -> {
                claimLocks.remove(playerUuid);
                if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                    plugin.getScheduler().runAsync(() ->
                        plugin.getRedisService().releaseDistributedLock(lockKey, lockValue)
                    );
                }
            });
        }).exceptionally(ex -> {
            claimLocks.remove(playerUuid);
            plugin.getLogger().log(Level.SEVERE, "Error claiming deliveries for " + playerUuid, ex);
            return 0;
        });
    }

    private CompletableFuture<Integer> awardClaimedDeliveries(Player player, List<OrderDelivery> deliveries,
                                                               boolean sell, boolean single) {
        UUID playerUuid = player.getUniqueId();
        List<Integer> ids = deliveries.stream().map(OrderDelivery::getId).toList();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean();
        Runnable restore = () -> {
            try {
                plugin.getDatabaseManager().restoreClaimedDeliveries(playerUuid, ids).whenComplete((ignored, error) -> {
                    if (error == null) future.complete(0);
                    else {
                        plugin.getLogger().log(Level.SEVERE, "Failed to restore order delivery claims " + ids + " for " + playerUuid, error);
                        future.completeExceptionally(error);
                    }
                });
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to restore order delivery claims " + ids + " for " + playerUuid, error);
                future.completeExceptionally(error);
            }
        };
        try {
            plugin.getScheduler().runEntity(player, () -> {
                if (!started.compareAndSet(false, true)) return;
                boolean awardStarted = false;
                try {
                    if (!player.isOnline() || isPlayerLocked(player)) {
                        restore.run();
                        return;
                    }
                    List<ItemStack> itemsToGive = new ArrayList<>();
                    double revenue = 0;
                    for (OrderDelivery delivery : deliveries) {
                        ItemStack item = delivery.getItem();
                        if (sell) {
                            double price = getStandardPrice(item.getType());
                            if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException("Invalid sale price for delivery " + delivery.getId());
                            revenue += price * delivery.getQuantity();
                        } else {
                            int maxStack = item.getMaxStackSize();
                            if (maxStack <= 0) throw new IllegalArgumentException("Invalid stack size for delivery " + delivery.getId());
                            for (int remaining = delivery.getQuantity(); remaining > 0;) {
                                int chunk = Math.min(remaining, maxStack);
                                ItemStack stack = item.clone();
                                stack.setAmount(chunk);
                                itemsToGive.add(stack);
                                remaining -= chunk;
                            }
                        }
                    }

                    if (sell) {
                        if (!Double.isFinite(revenue) || revenue <= 0) {
                            restore.run();
                            return;
                        }
                        awardStarted = true;
                        if (!depositMoney(playerUuid, revenue, "Sold All Claimed Items")) {
                            plugin.getLogger().warning("Failed to deposit order sale revenue to " + playerUuid + "; restoring delivery claims " + ids);
                            restore.run();
                            return;
                        }
                    } else {
                        awardStarted = true;
                        for (ItemStack item : itemsToGive) {
                            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                            for (ItemStack drop : overflow.values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                            }
                        }
                        player.updateInventory();
                    }

                    List<CompletableFuture<Void>> updates = new ArrayList<>();
                    for (OrderDelivery delivery : deliveries) {
                        updates.add(plugin.getDatabaseManager().incrementClaimedAmount(delivery.getOrderId(), delivery.getQuantity())
                                .thenRun(() -> {
                                    BuyOrder cached = activeOrders.get(delivery.getOrderId());
                                    if (cached != null) cached.setClaimedAmount(cached.getClaimedAmount() + delivery.getQuantity());
                                }));
                    }
                    if (sell) {
                        plugin.getTextService().send(player, "sell_all", "{price}", formatPrice(revenue));
                    } else if (single) {
                        OrderDelivery delivery = deliveries.getFirst();
                        plugin.getTextService().send(player, "order_collected_single",
                                "{amount}", formatAmount(delivery.getQuantity()), "{itemType}", formatItemName(delivery.getItem()));
                    } else {
                        PlayerOrderCollectEvent event = new PlayerOrderCollectEvent(player, itemsToGive);
                        plugin.getScheduler().runGlobal(() -> Bukkit.getPluginManager().callEvent(event));
                        plugin.getTextService().send(player, "order_collected_all");
                        sendWebhook("order-collect", "Items Collected", "**Player:** " + player.getName() + "\n"
                                + "**Total Items Claimed:** " + itemsToGive.size(), 0x00FFCC);
                    }
                    int count = sell ? deliveries.size() : itemsToGive.size();
                    CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                        if (error == null) future.complete(count);
                        else future.completeExceptionally(error);
                    });
                } catch (Exception error) {
                    if (!awardStarted) {
                        plugin.getLogger().log(Level.WARNING, "Could not award order deliveries " + ids + " for " + playerUuid + "; restoring claims", error);
                        restore.run();
                    }
                    else {
                        // ponytail: inventory/Vault awards cannot join a SQL transaction; uncertain awards need admin recovery, never automatic replay.
                        plugin.getLogger().log(Level.SEVERE, "Order delivery award may be partial; retained claims " + ids + " for " + playerUuid, error);
                        future.completeExceptionally(error);
                    }
                }
            }, () -> {
                if (started.compareAndSet(false, true)) restore.run();
            });
        } catch (Exception error) {
            if (started.compareAndSet(false, true)) restore.run();
            else future.completeExceptionally(error);
        }
        return future;
    }

    public List<BuyOrder> getFilteredAndSortedOrders(OrderFilter filter, OrderSort sort, String searchQuery) {
        List<BuyOrder> list = new ArrayList<>();
        String query = searchQuery != null ? searchQuery.toLowerCase(Locale.ROOT).trim() : "";

        for (BuyOrder order : activeOrders.values()) {
            if (order.getState() != OrderState.ACTIVE || order.isCancelled()) continue;
            if (order.getItem() == null) continue;

            if (filter != null && !filter.matches(order.getItem().getType())) {
                continue;
            }

            if (!query.isEmpty()) {
                String typeName = order.getItem().getType().name().toLowerCase(Locale.ROOT);
                String creatorName = order.getPlayerName().toLowerCase(Locale.ROOT);
                if (!typeName.contains(query) && !creatorName.contains(query)) {
                    continue;
                }
            }

            list.add(order);
        }

        if (sort != null) {
            switch (sort) {
                case MOST_PAID -> list.sort((a, b) -> Double.compare(b.getTotalPaid(), a.getTotalPaid()));
                case MOST_DELIVERED -> list.sort((a, b) -> Long.compare(b.getDeliveredAmount(), a.getDeliveredAmount()));
                case RECENTLY_LISTED -> list.sort((a, b) -> Long.compare(b.getCreationTime(), a.getCreationTime()));
                case MOST_MONEY_PER_ITEM -> list.sort((a, b) -> Double.compare(b.getPricePerItem(), a.getPricePerItem()));
            }
        }

        return list;
    }

    private boolean isPlayerLocked(Player player) {
        return plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player);
    }

    public CompletableFuture<Boolean> acquireLock(UUID orderId) {
        if (!localLocks.add(orderId)) return CompletableFuture.completedFuture(false);
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    boolean redisLocked = plugin.getRedisService().acquireDistributedLock("friendlycore:order_lock:" + orderId, plugin.getRedisService().getServerId(), 10);
                    if (!redisLocked) {
                        localLocks.remove(orderId);
                        return false;
                    }
                    return true;
                } catch (Exception e) {
                    localLocks.remove(orderId);
                    throw e;
                }
            }, plugin.getScheduler().getAsyncExecutor());
        }
        return CompletableFuture.completedFuture(true);
    }

    private void releaseLock(UUID orderId) {
        localLocks.remove(orderId);
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            plugin.getScheduler().runAsync(() ->
                plugin.getRedisService().releaseDistributedLock("friendlycore:order_lock:" + orderId, plugin.getRedisService().getServerId())
            );
        }
    }

    public double getPlayerBalance(UUID uuid) {
        if (plugin.getEconomyManager() != null) {
            return plugin.getEconomyManager().getBalance(uuid);
        }
        if (plugin.getHookManager().getVaultEconomy() != null) {
            return plugin.getHookManager().getVaultEconomy().getBalance(Bukkit.getOfflinePlayer(uuid));
        }
        return 0.0;
    }

    public boolean withdrawMoney(UUID uuid, double amount, String reason) {
        if (plugin.getEconomyManager() != null) {
            return plugin.getEconomyManager().withdraw(uuid, amount, true);
        }
        if (plugin.getHookManager().getVaultEconomy() != null) {
            return plugin.getHookManager().getVaultEconomy().withdrawPlayer(Bukkit.getOfflinePlayer(uuid), amount).transactionSuccess();
        }
        return false;
    }

    public boolean depositMoney(UUID uuid, double amount, String reason) {
        if (plugin.getEconomyManager() != null) {
            return plugin.getEconomyManager().deposit(uuid, amount, true);
        }
        if (plugin.getHookManager().getVaultEconomy() != null) {
            var resp = plugin.getHookManager().getVaultEconomy().depositPlayer(Bukkit.getOfflinePlayer(uuid), amount);
            return resp != null && resp.transactionSuccess();
        }
        return false;
    }

    public String formatItemName(ItemStack item) {
        if (item == null) return "Unknown";
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        String[] parts = item.getType().name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Formats prices/currency cleanly in modern DonutSMP style (e.g. 1,000 or 10.5 without raw trailing .0).
     */
    public String formatPrice(double price) {
        if (Double.isNaN(price) || Double.isInfinite(price)) {
            return "0";
        }
        if (price == (long) price) {
            return String.format(Locale.US, "%,d", (long) price);
        }
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(price);
    }

    /**
     * Formats prices/currency with compact suffix scale (e.g. 1k, 64k, 1.5M).
     */
    public String formatPriceShort(double price) {
        if (Double.isNaN(price) || Double.isInfinite(price)) {
            return "0";
        }
        double abs = Math.abs(price);
        String sign = price < 0 ? "-" : "";
        if (abs >= 1_000_000_000_000.0) {
            return sign + formatShortVal(abs / 1_000_000_000_000.0) + "T";
        }
        if (abs >= 1_000_000_000.0) {
            return sign + formatShortVal(abs / 1_000_000_000.0) + "B";
        }
        if (abs >= 1_000_000.0) {
            return sign + formatShortVal(abs / 1_000_000.0) + "M";
        }
        if (abs >= 1_000.0) {
            return sign + formatShortVal(abs / 1_000.0) + "K";
        }
        return sign + formatPrice(abs);
    }

    private String formatShortVal(double val) {
        if (val == (long) val) {
            return String.valueOf((long) val);
        }
        DecimalFormat df = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(val);
    }

    /**
     * Formats integer amounts with comma groupings (e.g. 64, 1,000, 64,000).
     */
    public String formatAmount(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /**
     * Formats time remaining in sleek readable DonutSMP style (e.g. 23h 59m, 1d 4h, 45m 12s, 30s).
     */
    public String formatTimeRemaining(long millis) {
        if (millis <= 0) return "Expired";

        long total = (millis + 999L) / 1000L;
        long days = total / 86400L;
        long hours = (total % 86400L) / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;

        if (days > 0) {
            return hours > 0 ? (minutes > 0 ? days + "d " + hours + "h " + minutes + "m" : days + "d " + hours + "h") : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
        }
        return seconds + "s";
    }

    public int getExpirationDays() {
        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        return config != null ? config.getInt("duration.expiration", 30) : 30;
    }

    /**
     * Broadcasts order creation announcement across chat and actionbar according to config and player settings.
     */
    public void announceOrder(BuyOrder order, String playerName, long amount, ItemStack itemTemplate, double pricePerItem) {
        FileConfiguration cfg = plugin.getConfigService().get("modules/orders/config.yml");
        if (cfg == null) return;

        boolean announceChat = cfg.getBoolean("announce.chat", cfg.getBoolean("announce", true));
        boolean announceActionBar = cfg.getBoolean("announce.actionbar", cfg.getBoolean("announce-actionbar", true));

        if (!announceChat && !announceActionBar) return;

        String name = playerName != null ? playerName : (order != null ? order.getPlayerName() : "Unknown");
        Map<String, String> placeholders = Map.of(
                "{playerName}", name,
                "{itemAmount}", formatAmount(amount),
                "{itemType}", formatItemName(itemTemplate),
                "{itemPrice}", formatPrice(pricePerItem),
                "{priceEach}", formatPrice(pricePerItem),
                "{amount}", formatAmount(amount),
                "{player}", name
        );

        if (announceChat) {
            plugin.getTextService().broadcastMessage("order_broadcast", placeholders);
        }
        if (announceActionBar) {
            var playerListener = plugin.getOrdersModule() != null ? plugin.getOrdersModule().getPlayerListener() : null;
            plugin.getTextService().broadcastActionBar("order_broadcast_actionbar", placeholders,
                    p -> playerListener == null || playerListener.isNotificationsEnabled(p.getUniqueId()));
        }
    }

    public void refundItemsToSeller(Player seller, UUID sellerUuid, ItemStack itemTemplate, long amount) {
        if (amount <= 0 || itemTemplate == null || sellerUuid == null) return;

        if (seller != null && seller.isOnline()) {
            plugin.getScheduler().runEntity(seller, () -> {
                if (seller.isOnline()) {
                    ItemStack refund = itemTemplate.clone();
                    long remaining = amount;
                    int maxStack = Math.max(1, refund.getMaxStackSize());
                    while (remaining > 0) {
                        int chunk = (int) Math.min(remaining, maxStack);
                        ItemStack stack = refund.clone();
                        stack.setAmount(chunk);
                        HashMap<Integer, ItemStack> overflow = seller.getInventory().addItem(stack);
                        if (!overflow.isEmpty()) {
                            for (ItemStack drop : overflow.values()) {
                                seller.getWorld().dropItemNaturally(seller.getLocation(), drop);
                            }
                        }
                        remaining -= chunk;
                    }
                    seller.updateInventory();
                } else {
                    storeDeliveryRefund(sellerUuid, itemTemplate, (int) amount);
                }
            }, () -> storeDeliveryRefund(sellerUuid, itemTemplate, (int) amount));
        } else {
            storeDeliveryRefund(sellerUuid, itemTemplate, (int) amount);
        }
    }

    private void storeDeliveryRefund(UUID sellerUuid, ItemStack itemTemplate, int amount) {
        if (sellerUuid == null || itemTemplate == null || amount <= 0) return;
        ItemStack deliveryItem = itemTemplate.clone();
        deliveryItem.setAmount(1);
        OrderDelivery delivery = new OrderDelivery(0, UUID.randomUUID(), sellerUuid, sellerUuid,
                "System Refund", deliveryItem, amount, System.currentTimeMillis(), false);
        plugin.getDatabaseManager().insertDelivery(delivery).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "CRITICAL: Failed to store refund delivery for seller UUID " + sellerUuid + " (" + amount + "x " + itemTemplate.getType().name() + ")! Items may be lost!", ex);
            return null;
        });
    }

    public void sendWebhook(String eventKey, String title, String description, int colorHex) {
        FileConfiguration config = plugin.getConfigService().get("modules/orders/config.yml");
        if (config == null || !config.getBoolean("webhook.enabled", false)) return;
        if (!config.getBoolean("webhook.events." + eventKey, true)) return;

        String urlStr = config.getString("webhook.url", "");
        if (urlStr == null || urlStr.isEmpty()) return;

        String username = config.getString("webhook.username", "FriendlyCore Orders");
        String avatarUrl = config.getString("webhook.avatar-url", "");

        plugin.getScheduler().runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = URI.create(urlStr).toURL();
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "FriendlyCore-Orders");
                conn.setDoOutput(true);

                JsonObject payload = new JsonObject();
                payload.addProperty("username", username);
                payload.addProperty("avatar_url", avatarUrl);

                JsonObject embed = new JsonObject();
                embed.addProperty("title", title);
                embed.addProperty("description", description);
                embed.addProperty("color", colorHex);
                embed.addProperty("timestamp", java.time.Instant.now().toString());

                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                payload.add("embeds", embeds);

                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                conn.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public OrderSafetyValidator getSafetyValidator() {
        return safetyValidator;
    }

    public OrderPacketHandler getPacketHandler() {
        return packetHandler;
    }
}
