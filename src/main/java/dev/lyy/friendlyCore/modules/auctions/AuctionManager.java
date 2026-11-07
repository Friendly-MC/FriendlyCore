package dev.lyy.friendlycore.modules.auctions;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.category.AuctionCategoryManager;
import dev.lyy.friendlycore.modules.auctions.converters.AuctionConverterManager;
import dev.lyy.friendlycore.modules.auctions.economy.AuctionEconomyService;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionSorting;
import dev.lyy.friendlycore.modules.auctions.input.AuctionSignInputManager;
import dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry;
import dev.lyy.friendlycore.modules.auctions.model.AuctionPayout;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import dev.lyy.friendlycore.modules.auctions.redis.AuctionPacketHandler;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine;
import dev.lyy.friendlycore.modules.auctions.webhooks.AuctionDiscordWebhook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Core manager responsible for auction lifecycles, active item caching,
 * transactional purchases/cancellations, safety locks, and offline deliveries.
 */
public class AuctionManager {

    private final FriendlyCore plugin;
    private final Object cacheLock = new Object();
    private long cacheVersion;
    private final java.util.concurrent.atomic.AtomicBoolean cacheRefreshInFlight = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong itemRefreshSequence = new java.util.concurrent.atomic.AtomicLong();
    private final Map<Integer, Long> itemRefreshVersions = new ConcurrentHashMap<>();
    private final Map<Integer, AuctionItem> activeItems = new ConcurrentHashMap<>();
    private final Map<UUID, AuctionUser> userCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<AuctionUser>> userLoads = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loadedUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Consumer<String>> pendingSearchInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Object> deliveryChecksInFlight = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> deliveryRechecks = ConcurrentHashMap.newKeySet();
    private final java.util.Set<dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase> activeMenus = ConcurrentHashMap.newKeySet();
    private final java.util.Set<dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase> pendingMenuRefreshes = ConcurrentHashMap.newKeySet();
    private static final int REFRESH_SPREAD_TICKS = 4;

    private AuctionEconomyService economyService;
    private AuctionCategoryManager categoryManager;
    private AuctionSafetyValidator safetyValidator;
    private AuctionDiscordWebhook discordWebhook;
    private AuctionPacketHandler packetHandler;
    private AuctionConverterManager converterManager;
    private AuctionSignInputManager signInputManager;

    private final java.util.Set<Integer> archivingItems = ConcurrentHashMap.newKeySet();
    private final Map<UUID, dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics> statistics = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> statisticsLoads = ConcurrentHashMap.newKeySet();
    private volatile dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics globalStatistics =
            dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics.EMPTY;
    private final java.util.concurrent.atomic.AtomicBoolean globalStatisticsLoad = new java.util.concurrent.atomic.AtomicBoolean();
    private Object expiryTask;
    private Object syncTask;
    private long syncCycleCounter = 0L;
    private volatile java.time.format.DateTimeFormatter dateFormatter;

    public AuctionManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        this.economyService = new AuctionEconomyService(plugin);
        this.categoryManager = new AuctionCategoryManager(plugin);
        this.safetyValidator = new AuctionSafetyValidator(plugin);
        this.discordWebhook = new AuctionDiscordWebhook(plugin, economyService);
        this.packetHandler = new AuctionPacketHandler(plugin, this);
        this.converterManager = new AuctionConverterManager(plugin, this);
        this.signInputManager = new AuctionSignInputManager(plugin, this);

        loadInitialData();
        refreshGlobalStatistics();
        reloadDateFormat();
        startTasks();
    }

    public void reload() {
        if (categoryManager != null) categoryManager.reload();
        if (safetyValidator != null) safetyValidator.reload();
        if (discordWebhook != null) discordWebhook.reload();
        reloadDateFormat();
        loadInitialData();
    }

    public void shutdown() {
        if (expiryTask != null) {
            plugin.getScheduler().cancel(expiryTask);
            expiryTask = null;
        }
        if (syncTask != null) {
            plugin.getScheduler().cancel(syncTask);
            syncTask = null;
        }
        if (signInputManager != null) {
            signInputManager.cleanupAll();
        }
        activeItems.clear();
        itemRefreshVersions.clear();
        userLoads.clear();
        loadedUsers.clear();
        userCache.clear();
        statistics.clear();
        statisticsLoads.clear();
        deliveryChecksInFlight.clear();
        deliveryRechecks.clear();
        pendingSearchInputs.clear();
    }

    private void loadInitialData() {
        if (!cacheRefreshInFlight.compareAndSet(false, true)) return;
        final long version;
        synchronized (cacheLock) { version = cacheVersion; }
        try {
            plugin.getDatabaseManager().loadActiveAuctions().whenComplete((items, failure) -> {
                try {
                    if (failure != null || !plugin.isEnabled()) return;
                    boolean itemsChanged = false;
                    synchronized (cacheLock) {
                        // A newer local operation or Redis invalidation wins over an older query.
                        if (version != cacheVersion) return;
                        long expiry = getExpireDurationMillis();
                        Map<Integer, AuctionItem> refreshed = new java.util.HashMap<>();
                        for (AuctionItem item : items) {
                            if (item.isExpired(expiry)) item.setItemState(AuctionItemState.EXPIRED);
                            refreshed.put(item.getId(), item);
                        }
                        if (!refreshed.keySet().equals(activeItems.keySet())) {
                            itemsChanged = true;
                        } else {
                            for (Map.Entry<Integer, AuctionItem> entry : refreshed.entrySet()) {
                                AuctionItem existing = activeItems.get(entry.getKey());
                                if (existing == null || existing.getItemState() != entry.getValue().getItemState()
                                        || Math.abs(existing.getPrice() - entry.getValue().getPrice()) > 0.0001) {
                                    itemsChanged = true;
                                    break;
                                }
                            }
                        }
                        activeItems.clear();
                        activeItems.putAll(refreshed);
                        cacheVersion++;
                    }
                    if (itemsChanged) {
                        refreshOpenGuis();
                    }
                } finally {
                    cacheRefreshInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException saturated) {
            cacheRefreshInFlight.set(false);
        }
    }

    private void startTasks() {
        this.expiryTask = plugin.getScheduler().runAsyncTimer(() -> {
            long expireDuration = getExpireDurationMillis();
            long deletionDuration = getDeletionDurationMillis();

            for (AuctionItem item : activeItems.values()) {
                if (item.isRemoved()) continue;

                // Move active listings past their expiry window to expired state
                if (item.getItemState() == AuctionItemState.ACTIVE && item.isExpired(expireDuration)) {
                    item.setItemState(AuctionItemState.EXPIRED);
                    notifySellerExpired(item);
                }

                // Permanently archive expired listings once deletion retention passes
                if (item.isDeleted(expireDuration, deletionDuration)) {
                    if (!archivingItems.add(item.getId())) continue;
                    try {
                        plugin.getDatabaseManager().archiveAuctionItem(item.getId()).whenComplete((success, failure) -> {
                            archivingItems.remove(item.getId());
                            if (failure != null) {
                                plugin.getLogger().log(Level.WARNING, "Failed to archive auction #" + item.getId(), failure);
                                return;
                            }
                            removeCachedItem(item.getId());
                            if (Boolean.TRUE.equals(success)) {
                                audit("archived unclaimed #" + item.getId() + " " + describe(item.getItemStack())
                                        + " seller=" + item.getSellerName() + " (" + item.getSellerUuid() + ")");
                                packetHandler.publishItemDelete(item.getId());
                                notifySellerDeleted(item);
                            }
                        });
                    } catch (RejectedExecutionException saturated) {
                        archivingItems.remove(item.getId());
                    }
                }
            }
        }, 20L, 20L);

        // Sync auction listings and global statistics periodically
        this.syncTask = plugin.getScheduler().runGlobalTimer(() -> {
            syncCycleCounter++;
            int pollInterval = (packetHandler != null && packetHandler.isCrossServerEnabled()) ? 10 : 2;
            if (syncCycleCounter % pollInterval == 0) {
                loadInitialData();
            }
            refreshGlobalStatistics();
        }, 600L, 600L);
    }

    public void checkPendingDeliveries(Player player) {
        if (player == null || !player.isOnline() || isPlayerDataLocked(player)) return;
        UUID playerUuid = player.getUniqueId();
        refreshStatistics(playerUuid);
        refreshUserLimit(player);
        Object deliveryToken = new Object();
        if (deliveryChecksInFlight.putIfAbsent(playerUuid, deliveryToken) != null) {
            // A notification or payout committed after the running check read the queue; run once more after it.
            deliveryRechecks.add(playerUuid);
            return;
        }

        CompletableFuture<Void> notificationsFuture = plugin.getDatabaseManager()
                .fetchAndClearAuctionNotifications(playerUuid).thenAccept(notifications -> {
            if (notifications != null && !notifications.isEmpty()) {
                plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline()) {
                        requeueNotifications(playerUuid, notifications);
                        return;
                    }
                    for (String msg : notifications) {
                        plugin.getTextService().sendMessage(player, msg);
                    }
                }, () -> requeueNotifications(playerUuid, notifications));
            }
        });

        // Claim after the provider-load delay; a claimed row belongs to this server alone.
        CompletableFuture<Void> payoutsFuture = !economyService.hasEconomy() ? CompletableFuture.completedFuture(null)
                : payoutDelay().thenCompose(ignored -> plugin.getDatabaseManager().fetchAndClearAuctionPayouts(playerUuid))
                .thenCompose(payouts -> {
            if (payouts == null || payouts.isEmpty()) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> delivered = new CompletableFuture<>();
            plugin.getScheduler().runEntity(player, () -> {
                depositClaimedPayouts(playerUuid, player.getName(), player, payouts);
                delivered.complete(null);
            }, () -> {
                requeuePayouts(playerUuid, payouts);
                delivered.complete(null);
            });
            return delivered;
        });

        CompletableFuture.allOf(notificationsFuture, payoutsFuture).whenComplete((ignored, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException
                    || failure.getCause() instanceof java.util.concurrent.CancellationException)) {
                plugin.getLogger().log(Level.WARNING, "Auction delivery check failed for " + playerUuid, failure);
            }
            deliveryChecksInFlight.remove(playerUuid, deliveryToken);
            if (deliveryRechecks.remove(playerUuid) && player.isOnline()) {
                plugin.getScheduler().runEntity(player, () -> checkPendingDeliveries(player));
            }
        });
    }

    private CompletableFuture<Void> payoutDelay() {
        long delayTicks = getPayoutDelaySeconds() * 20L;
        if (delayTicks <= 0) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> delay = new CompletableFuture<>();
        // Disabled plugin: leave the payouts queued in SQL instead of claiming them during shutdown.
        if (plugin.getScheduler().runAsyncLater(() -> delay.complete(null), delayTicks) == null) delay.cancel(false);
        return delay;
    }

    /** Settle a durable payout through Vault when the provider supports offline accounts. */
    private void checkPendingPayoutsOffline(UUID playerUuid, String playerName) {
        if (playerUuid == null || onlyGiveMoneyOnJoin() || !economyService.hasEconomy()) return;
        // The seller's own server claims it after the AUCTION_PAYOUT packet, or on their next join.
        if (isOnlineOnAnotherServer(playerUuid)) return;
        try {
            plugin.getDatabaseManager().fetchAndClearAuctionPayouts(playerUuid).thenAccept(payouts -> {
                if (payouts == null || payouts.isEmpty()) return;
                Object scheduled = plugin.getScheduler().runGlobal(
                        () -> depositClaimedPayouts(playerUuid, playerName, null, payouts));
                if (scheduled == null) requeuePayouts(playerUuid, payouts);
            });
        } catch (RejectedExecutionException saturated) {
            // The rows stay queued for the seller's next join.
        }
    }

    /**
     * Deposit payouts this server already claimed from SQL. Anything Vault refuses goes back into the queue.
     * ponytail: a hard crash between the claim and the deposit loses that payout; the "claimed" audit line
     * is the reconciliation record. Add a claimed_at lease column if that window must be closed.
     */
    private void depositClaimedPayouts(UUID uuid, String name, Player online, List<AuctionPayout> payouts) {
        // Each payout stays finite; adding all outstanding amounts could overflow a double.
        for (AuctionPayout payout : payouts) {
            double amount = payout.getAmount();
            audit("payout #" + payout.getId() + " claimed for " + name + " (" + uuid + ") amount=" + amount
                    + " reason=\"" + payout.getReason() + "\"");
            if (online == null && isOnlineOnAnotherServer(uuid)) {
                // FriendlyCore forwards such a deposit over Redis without an acknowledgement, and it is dropped
                // if the seller is mid-transfer. Hand it back to the queue for the seller's own server instead.
                audit("payout #" + payout.getId() + " deferred: " + uuid + " is online on another server");
                requeuePayout(uuid, amount, payout.getReason()).thenAccept(queued -> {
                    if (Boolean.TRUE.equals(queued)) packetHandler.publishPayout(uuid, name, amount, payout.getReason());
                });
                continue;
            }
            boolean deposited = false;
            try {
                deposited = online != null
                        ? online.isOnline() && !isPlayerDataLocked(online) && economyService.deposit(online, amount)
                        : economyService.deposit(uuid, amount);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Vault threw on auction payout #" + payout.getId() + " for " + uuid, failure);
            }
            if (!deposited) {
                audit("payout #" + payout.getId() + " not deposited for " + uuid + "; requeued amount=" + amount);
                requeuePayout(uuid, amount, payout.getReason());
                continue;
            }
            audit("payout #" + payout.getId() + " deposited to " + name + " (" + uuid + ") amount=" + amount
                    + (online != null ? "" : " (offline)"));
            logAction(uuid, name, AuctionActionType.PAYOUT, name + "|" + payout.getReason() + "|" + amount);
            if (online != null) {
                try {
                    sendLang(online, "buy.payout-received", "%price%", formatPrice(amount),
                            "%reason%", payout.getReason() != null ? payout.getReason() : "");
                } catch (RuntimeException notificationFailure) {
                    // The money is already deposited. A chat failure must not stop later payouts.
                    plugin.getLogger().log(Level.WARNING,
                            "Auction payout deposited but its receipt could not be shown to " + uuid, notificationFailure);
                }
            }
        }
    }

    private boolean isOnlineOnAnotherServer(UUID uuid) {
        var redis = plugin.getRedisService();
        return redis != null && redis.isEnabled() && Bukkit.getPlayer(uuid) == null && redis.isPlayerOnlineOnNetwork(uuid);
    }

    private void requeuePayouts(UUID uuid, List<AuctionPayout> payouts) {
        for (AuctionPayout payout : payouts) requeuePayout(uuid, payout.getAmount(), payout.getReason());
    }

    private void requeueNotifications(UUID playerUuid, List<String> notifications) {
        for (String message : notifications) {
            persistNotification(playerUuid, 0, message);
        }
    }

    public void persistNotification(UUID playerUuid, int itemId, String message) {
        try {
            plugin.getDatabaseManager().insertAuctionNotification(playerUuid, itemId, message)
                    .exceptionally(error -> {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to persist auction notification for " + playerUuid, error);
                        return null;
                    });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().log(Level.WARNING,
                    "Auction notification submission was rejected for " + playerUuid, saturated);
        }
    }

    public void notifySeller(UUID sellerUuid, String sellerName, int itemId, String formattedMessage) {
        notifySeller(sellerUuid, sellerName, itemId, formattedMessage, "");
    }

    public void notifySeller(UUID sellerUuid, String sellerName, int itemId, String formattedMessage, String actionBarMessage) {
        if (sellerUuid == null) return;
        if (formattedMessage != null && !formattedMessage.isEmpty()) {
            // Persist once; Redis only wakes the owning server to claim the durable notification.
            plugin.getDatabaseManager().insertAuctionNotification(sellerUuid, itemId, formattedMessage).thenRun(() -> {
                packetHandler.publishNotification(sellerUuid, sellerName != null ? sellerName : "", actionBarMessage != null ? actionBarMessage : "");
                plugin.getScheduler().runGlobal(() -> {
                    Player seller = Bukkit.getPlayer(sellerUuid);
                    if (seller != null) {
                        plugin.getScheduler().runEntity(seller, () -> {
                            if (actionBarMessage != null && !actionBarMessage.isBlank() && seller.isOnline()) {
                                seller.sendActionBar(plugin.getTextService().parse(actionBarMessage));
                            }
                            checkPendingDeliveries(seller);
                        });
                    }
                });
            });
        } else if (actionBarMessage != null && !actionBarMessage.isBlank()) {
            notifySellerActionBarOnly(sellerUuid, sellerName, actionBarMessage);
        }
    }

    public void notifySellerActionBarOnly(UUID sellerUuid, String sellerName, String actionBarMessage) {
        if (sellerUuid == null || actionBarMessage == null || actionBarMessage.isBlank()) return;
        packetHandler.publishNotification(sellerUuid, sellerName != null ? sellerName : "", actionBarMessage);
        plugin.getScheduler().runGlobal(() -> {
            Player seller = Bukkit.getPlayer(sellerUuid);
            if (seller != null) {
                plugin.getScheduler().runEntity(seller, () -> {
                    if (seller.isOnline()) {
                        seller.sendActionBar(plugin.getTextService().parse(actionBarMessage));
                    }
                });
            }
        });
    }

    private CompletableFuture<Boolean> requeuePayout(UUID playerUuid, double amount, String reason) {
        String safeReason = reason != null ? reason : "Deferred auction payout";
        try {
            return plugin.getDatabaseManager().insertAuctionPayout(playerUuid, amount, safeReason).thenApply(ignored -> true)
                    .exceptionally(error -> {
                        // Money is only recoverable from this line now; keep every detail.
                        plugin.getLogger().log(Level.SEVERE, "[Auctions] LOST PAYOUT, credit manually: " + playerUuid
                                + " amount=" + amount + " reason=\"" + safeReason + "\"", error);
                        return false;
                    });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().log(Level.WARNING, "Auction payout queue saturated for " + playerUuid
                    + " amount=" + amount + "; retrying in 1s", saturated);
            CompletableFuture<Boolean> retried = new CompletableFuture<>();
            Object scheduled = plugin.getScheduler().runAsyncLater(
                    () -> requeuePayout(playerUuid, amount, safeReason).thenAccept(retried::complete), 20L);
            if (scheduled == null) {
                plugin.getLogger().severe("[Auctions] LOST PAYOUT, credit manually: " + playerUuid
                        + " amount=" + amount + " reason=\"" + safeReason + "\"");
                retried.complete(false);
            }
            return retried;
        }
    }

    public void persistRecoveryItem(UUID ownerUuid, String ownerName, ItemStack item) {
        if (ownerUuid == null || item == null || item.getType() == Material.AIR) return;
        AuctionItem recovery = new AuctionItem(0, item, ownerUuid,
                ownerName != null ? ownerName : "Unknown", 0.0, System.currentTimeMillis());
        try {
            plugin.getDatabaseManager().insertAuctionDeleted(recovery)
                    .exceptionally(error -> {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to persist an auction recovery item for " + ownerUuid, error);
                        return null;
                    });
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().log(Level.SEVERE,
                    "Async queue saturated while persisting an auction recovery item for " + ownerUuid,
                    saturated);
            plugin.getScheduler().runAsyncLater(() -> persistRecoveryItem(ownerUuid, ownerName, item), 20L);
        }
    }

    private void refundPurchase(Player buyer, int itemId, double amount) {
        // Vault providers may be region-bound; never call them from a SQL completion thread.
        UUID buyerUuid = buyer.getUniqueId();
        audit("refund queued for " + buyer.getName() + " (" + buyerUuid + ") amount=" + amount + " item=#" + itemId);
        requeuePayout(buyerUuid, amount, "Refund for auction #" + itemId).thenAccept(queued -> {
            if (!Boolean.TRUE.equals(queued)) return;
            plugin.getScheduler().runEntity(buyer, () -> {
                sendLang(buyer, "buy.refunded", "%price%", formatPrice(amount));
                // Deliver now instead of leaving the buyer's money queued until the next join.
                checkPendingDeliveries(buyer);
            });
        });
    }

    /** One grep-able console line per item or money movement, for resolving player disputes. */
    private void audit(String message) {
        plugin.getLogger().info("[Auctions] " + message);
    }

    private static String describe(ItemStack item) {
        return item == null ? "nothing" : item.getAmount() + "x " + item.getType().name();
    }

    public void createAuction(Player seller, ItemStack item, double price) {
        if (seller == null || !seller.isOnline() || item == null || item.getType() == Material.AIR) return;
        if (!economyService.hasEconomy()) {
            sendLang(seller, "economy-unavailable");
            return;
        }

        if (plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(seller)) {
            sendLang(seller, "safety");
            return;
        }

        UUID sellerUuid = seller.getUniqueId();
        if (!loadedUsers.contains(sellerUuid)) {
            double requestedPrice = price;
            getUserAsync(sellerUuid, seller.getName()).thenAccept(user ->
                    plugin.getScheduler().runEntity(seller, () -> {
                        if (seller.isOnline()) createAuction(seller, item, requestedPrice);
                    })).exceptionally(error -> {
                plugin.getScheduler().runEntity(seller,
                        () -> sendLang(seller, "data-not-loaded"));
                return null;
            });
            return;
        }

        // Prevent rapid spam / concurrent listing attempts
        if (safetyValidator.isLocked(seller.getUniqueId())) {
            sendLang(seller, "safety");
            return;
        }

        dev.lyy.friendlycore.modules.auctions.events.AuctionPreSellEvent preEvent =
                new dev.lyy.friendlycore.modules.auctions.events.AuctionPreSellEvent(seller, item, price);
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return;
        }
        price = preEvent.getPrice();

        double minPrice = plugin.getConfigService().get("modules/auctions/config.yml").getDouble("min-price", 1.0);
        double maxPrice = plugin.getConfigService().get("modules/auctions/config.yml").getDouble("max-price", -1.0);

        if (!Double.isFinite(price) || price <= 0.0 || price < minPrice) {
            sendLang(seller, "sell.price-too-low", "%amount%", formatPrice(minPrice));
            return;
        }
        if (maxPrice > 0 && price > maxPrice) {
            sendLang(seller, "sell.price-too-high", "%amount%", formatPrice(maxPrice));
            return;
        }

        int currentCount = getPlayerActiveItemCount(seller.getUniqueId());
        int limit = getPlayerListingLimit(seller);
        if (limit != -1 && currentCount >= limit) {
            sendLang(seller, "sell.limit-reached", "%current%", String.valueOf(currentCount), "%limit%", String.valueOf(limit));
            return;
        }

        // Validate blacklisted items & NBT payload size limits
        if (safetyValidator.isBlacklisted(item)) {
            sendLang(seller, "blacklisted");
            return;
        }

        if (safetyValidator.isOversized(item)) {
            sendLang(seller, "oversized");
            return;
        }

        safetyValidator.lock(seller.getUniqueId());
        final double finalPrice = price;

        // Re-verify the hand item on the entity thread before taking it to avoid desyncs
        plugin.getScheduler().runEntity(seller, () -> {
            ItemStack inHand = seller.getInventory().getItemInMainHand();
            if (inHand.getType() == Material.AIR || !inHand.isSimilar(item) || inHand.getAmount() < item.getAmount()) {
                safetyValidator.unlock(seller.getUniqueId());
                sendLang(seller, "sell.no-item");
                return;
            }

            if (inHand.getAmount() <= item.getAmount()) {
                seller.getInventory().setItemInMainHand(null);
            } else {
                inHand.setAmount(inHand.getAmount() - item.getAmount());
                seller.getInventory().setItemInMainHand(inHand);
            }

            long startTime = System.currentTimeMillis();
            AuctionItem auctionItem = new AuctionItem(0, item, seller.getUniqueId(), seller.getName(), finalPrice, startTime);

            CompletableFuture<Integer> insertFuture;
            try {
                insertFuture = plugin.getDatabaseManager().insertAuctionItem(auctionItem);
            } catch (RejectedExecutionException saturated) {
                safetyValidator.unlock(seller.getUniqueId());
                safetyValidator.giveOrDropItem(seller, item);
                sendLang(seller, "safety");
                return;
            }

            String sellerName = seller.getName();
            insertFuture.whenComplete((generatedId, failure) -> {
                if (failure != null || generatedId == null || generatedId <= 0) {
                    plugin.getLogger().log(Level.WARNING, "[Auctions] listing insert failed for " + sellerName + " ("
                            + sellerUuid + "); returning " + describe(item), failure);
                    plugin.getScheduler().runEntity(seller, () -> {
                        safetyValidator.unlock(sellerUuid);
                        safetyValidator.giveOrDropItem(seller, item);
                        sendLang(seller, "safety");
                    }, () -> {
                        safetyValidator.unlock(sellerUuid);
                        persistRecoveryItem(sellerUuid, sellerName, item);
                    });
                    return;
                }

                AuctionItem created = new AuctionItem(generatedId, item, sellerUuid, sellerName, finalPrice, startTime);
                cacheItem(created);
                packetHandler.publishItemInsert(generatedId);
                audit("listed #" + generatedId + " by " + sellerName + " (" + sellerUuid + ") " + describe(item) + " price=" + finalPrice);
                // Trailing fields (amount, id) are appended so older log rows still parse in the logs GUI.
                logAction(sellerUuid, sellerName, AuctionActionType.CREATE_AUCTION,
                        sellerName + "|" + item.getType().name() + "|" + finalPrice + "|" + item.getAmount() + "|" + generatedId);

                plugin.getScheduler().runEntity(seller, () -> {
                    safetyValidator.unlock(sellerUuid);
                    discordWebhook.sendListingWebhook(seller, created);
                    Bukkit.getPluginManager().callEvent(new dev.lyy.friendlycore.modules.auctions.events.AuctionSellEvent(seller, created));
                    String formattedPrice = formatPrice(finalPrice);
                    String formattedPriceShort = formatPriceShort(finalPrice);
                    sendLang(seller, "sell.success",
                            "%amount%", String.valueOf(created.getItemStack().getAmount()),
                            "%price_short%", formattedPriceShort,
                            "%price%", formattedPrice,
                            "%item%", getItemDisplayName(created.getItemStack()));

                    broadcastListing(created);

                    seller.playSound(seller.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
                }, () -> safetyValidator.unlock(sellerUuid));
            });
        });
    }

    public void broadcastListing(AuctionItem created) {
        if (created == null || created.getItemStack() == null) return;
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        if (msgs == null) return;

        String formattedPrice = formatPrice(created.getPrice());
        String formattedPriceShort = formatPriceShort(created.getPrice());
        String amountStr = String.valueOf(created.getItemStack().getAmount());
        String itemDisplayName = getItemDisplayName(created.getItemStack());
        String sellerName = created.getSellerName();

        String chatBroadcast = msgs.getString("sell.chat-broadcast", "");
        if (chatBroadcast != null && !chatBroadcast.isBlank()) {
            String bMsg = getPrefix() + chatBroadcast
                    .replace("%amount%", amountStr)
                    .replace("{amount}", amountStr)
                    .replace("%price_short%", formattedPriceShort)
                    .replace("{price_short}", formattedPriceShort)
                    .replace("%price%", formattedPrice)
                    .replace("{price}", formattedPrice)
                    .replace("%player%", sellerName)
                    .replace("{player}", sellerName)
                    .replace("%item%", itemDisplayName)
                    .replace("{item}", itemDisplayName);
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.getTextService().sendMessage(p, bMsg);
            }
        }
        String actionBroadcast = msgs.getString("sell.action-bar-broadcast", "");
        if (actionBroadcast != null && !actionBroadcast.isBlank()) {
            String aMsg = actionBroadcast
                    .replace("%amount%", amountStr)
                    .replace("{amount}", amountStr)
                    .replace("%price_short%", formattedPriceShort)
                    .replace("{price_short}", formattedPriceShort)
                    .replace("%price%", formattedPrice)
                    .replace("{price}", formattedPrice)
                    .replace("%player%", sellerName)
                    .replace("{player}", sellerName)
                    .replace("%item%", itemDisplayName)
                    .replace("{item}", itemDisplayName);
            Component parsed = plugin.getTextService().parse(aMsg);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(parsed);
            }
        }
    }

    public boolean isPlayerDataLocked(Player player) {
        return plugin.getHookManager() != null && plugin.getHookManager().getMcDataBridgeHook() != null
                && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player);
    }

    public void purchaseAuction(Player buyer, AuctionItem item) {
        if (buyer == null || item == null || !buyer.isOnline() || isPlayerDataLocked(buyer)) return;

        if (buyer.getInventory().firstEmpty() == -1) {
            sendLang(buyer, "buy.inventory-full");
            return;
        }

        if (item.getSellerUuid().equals(buyer.getUniqueId())) {
            cancelAuction(buyer, item);
            return;
        }

        if (item.isRemoved() || item.isExpired(getExpireDurationMillis()) || !activeItems.containsKey(item.getId())) {
            sendLang(buyer, "buy.item-not-found");
            return;
        }

        dev.lyy.friendlycore.modules.auctions.events.AuctionPrePurchaseEvent preEvent =
                new dev.lyy.friendlycore.modules.auctions.events.AuctionPrePurchaseEvent(buyer, item);
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return;
        }

        if (!economyService.hasBalance(buyer, item.getPrice())) {
            sendLang(buyer, "buy.not-enough-money");
            return;
        }

        UUID buyerUuid = buyer.getUniqueId();
        String buyerName = buyer.getName();
        // A double click must not withdraw twice while the first purchase is still in SQL.
        if (safetyValidator.isLocked(buyerUuid)) {
            sendLang(buyer, "safety");
            return;
        }

        // Withdraw funds upfront before atomic deletion
        if (!economyService.withdraw(buyer, item.getPrice())) {
            sendLang(buyer, "buy.not-enough-money");
            return;
        }
        safetyValidator.lock(buyerUuid);

        // Two-phase atomic removal to prevent double-purchase race conditions
        CompletableFuture<Boolean> removeFuture;
        try {
            removeFuture = plugin.getDatabaseManager().purchaseAuctionItem(item.getId(), item.getPrice(),
                    getExpireDurationMillis() > 0 ? System.currentTimeMillis() - getExpireDurationMillis() : Long.MIN_VALUE,
                    buyerUuid, buyerName);
        } catch (RejectedExecutionException saturated) {
            safetyValidator.unlock(buyerUuid);
            refundPurchase(buyer, item.getId(), item.getPrice());
            sendLang(buyer, "buy.item-not-found");
            return;
        }

        removeFuture.whenComplete((success, failure) -> {
            safetyValidator.unlock(buyerUuid);
            if (failure != null || !Boolean.TRUE.equals(success)) {
                if (failure != null) {
                    // An error after COMMIT would make this refund a duplicate; check history for #id if reported.
                    plugin.getLogger().log(Level.WARNING, "[Auctions] purchase of #" + item.getId() + " by " + buyerName
                            + " (" + buyerUuid + ") failed in SQL; refunding " + item.getPrice(), failure);
                }
                refundPurchase(buyer, item.getId(), item.getPrice());
                plugin.getScheduler().runEntity(buyer,
                        () -> sendLang(buyer, "buy.item-not-found"));
                return;
            }

            audit("sold #" + item.getId() + " " + describe(item.getItemStack()) + " seller=" + item.getSellerName()
                    + " (" + item.getSellerUuid() + ") buyer=" + buyerName + " (" + buyerUuid + ") price=" + item.getPrice());
            item.markRemoved();
            removeCachedItem(item.getId());
            packetHandler.publishItemDelete(item.getId());
            try {
                completeSellerPayout(item, buyerName);
            } catch (RuntimeException failureToNotify) {
                plugin.getLogger().log(Level.WARNING, "Sale committed; seller notification deferred", failureToNotify);
            }
            plugin.getScheduler().runEntity(buyer, () -> {
                safetyValidator.giveOrDropItem(buyer, item.getItemStack());
                buyer.playSound(buyer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                String formattedPrice = formatPrice(item.getPrice());
                String formattedPriceShort = formatPriceShort(item.getPrice());
                String amountStr = String.valueOf(item.getItemStack().getAmount());
                String itemDisplayName = getItemDisplayName(item.getItemStack());
                sendLang(buyer, "buy.success",
                        "%amount%", amountStr,
                        "%price_short%", formattedPriceShort,
                        "%price%", formattedPrice,
                        "%item%", itemDisplayName);

                org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
                String chatPurchase = msgs != null ? msgs.getString("buy.chat-purchase", "") : "";
                if (chatPurchase != null && !chatPurchase.isBlank()) {
                    String bMsg = getPrefix() + chatPurchase
                            .replace("%seller%", item.getSellerName())
                            .replace("{seller}", item.getSellerName())
                            .replace("%amount%", amountStr)
                            .replace("{amount}", amountStr)
                            .replace("%price_short%", formattedPriceShort)
                            .replace("{price_short}", formattedPriceShort)
                            .replace("%price%", formattedPrice)
                            .replace("{price}", formattedPrice)
                            .replace("%player%", buyer.getName())
                            .replace("{player}", buyer.getName())
                            .replace("%item%", itemDisplayName)
                            .replace("{item}", itemDisplayName);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        plugin.getTextService().sendMessage(p, bMsg);
                    }
                }
                String actionBroadcast = msgs != null ? msgs.getString("buy.action-bar-broadcast", "") : "";
                if ((actionBroadcast == null || actionBroadcast.isBlank()) && msgs != null) {
                    actionBroadcast = msgs.getString("buy.action-bar-purchase", "");
                }
                if (actionBroadcast != null && !actionBroadcast.isBlank()) {
                    String aMsg = actionBroadcast
                            .replace("%seller%", item.getSellerName())
                            .replace("{seller}", item.getSellerName())
                            .replace("%amount%", amountStr)
                            .replace("{amount}", amountStr)
                            .replace("%price_short%", formattedPriceShort)
                            .replace("{price_short}", formattedPriceShort)
                            .replace("%price%", formattedPrice)
                            .replace("{price}", formattedPrice)
                            .replace("%player%", buyer.getName())
                            .replace("{player}", buyer.getName())
                            .replace("%item%", itemDisplayName)
                            .replace("{item}", itemDisplayName);
                    Component parsedAction = plugin.getTextService().parse(aMsg);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendActionBar(parsedAction);
                    }
                }

                Bukkit.getPluginManager().callEvent(new dev.lyy.friendlycore.modules.auctions.events.AuctionPurchaseEvent(buyer, item));
                discordWebhook.sendPurchaseWebhook(buyer, item.getSellerName(), item.getPrice(), item.getItemStack());
            }, () -> persistRecoveryItem(buyerUuid, buyerName, item.getItemStack()));

            logAction(buyerUuid, buyerName, AuctionActionType.PURCHASE,
                    buyerName + "|" + item.getSellerName() + "|" + item.getItemStack().getType().name() + "|" + item.getPrice()
                            + "|" + item.getItemStack().getAmount() + "|" + item.getId());
        });
    }

    public void cancelAuction(Player player, AuctionItem item) {
        if (player == null || item == null || !player.isOnline() || isPlayerDataLocked(player)) return;
        if (!item.getSellerUuid().equals(player.getUniqueId()) && !player.hasPermission("friendlycore.auctions.admin") && !player.hasPermission("axauctions.admin")) {
            return;
        }

        if (player.getInventory().firstEmpty() == -1) {
            sendLang(player, "buy.inventory-full");
            return;
        }

        if (item.isRemoved() || !activeItems.containsKey(item.getId())) {
            sendLang(player, "my-items.failed");
            return;
        }

        removeAndReturnAuction(player, item, "my-items.removed");
    }

    public void claimExpiredAuction(Player player, AuctionItem item) {
        if (player == null || item == null || !player.isOnline() || isPlayerDataLocked(player)) return;
        if (!item.getSellerUuid().equals(player.getUniqueId())) return;

        if (player.getInventory().firstEmpty() == -1) {
            sendLang(player, "buy.inventory-full");
            return;
        }

        if (item.isRemoved() || !activeItems.containsKey(item.getId())) {
            sendLang(player, "my-items.failed");
            return;
        }

        removeAndReturnAuction(player, item, "my-items.removed");
    }

    private void removeAndReturnAuction(Player player, AuctionItem item, String successMessageKey) {
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        CompletableFuture<Boolean> removeFuture;
        try {
            removeFuture = plugin.getDatabaseManager().removeAuctionItem(item.getId());
        } catch (RejectedExecutionException saturated) {
            sendLang(player, "my-items.failed");
            return;
        }

        removeFuture.whenComplete((success, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(success)) {
                plugin.getScheduler().runEntity(player,
                        () -> sendLang(player, "my-items.failed"));
                return;
            }
            audit("returned #" + item.getId() + " to " + playerName + " (" + playerUuid + ") "
                    + describe(item.getItemStack()) + " price=" + item.getPrice());
            item.markRemoved();
            removeCachedItem(item.getId());
            packetHandler.publishItemDelete(item.getId());
            logAction(playerUuid, playerName, AuctionActionType.CANCEL_SELF,
                    playerName + "|" + item.getItemStack().getType().name() + "|" + item.getPrice()
                            + "|" + item.getItemStack().getAmount() + "|" + item.getId());
            plugin.getScheduler().runEntity(player, () -> {
                safetyValidator.giveOrDropItem(player, item.getItemStack());
                sendLang(player, successMessageKey,
                        "%amount%", String.valueOf(item.getItemStack().getAmount()),
                        "%item%", getItemDisplayName(item.getItemStack()),
                        "%price_short%", formatPriceShort(item.getPrice()),
                        "%price%", formatPrice(item.getPrice()));
            }, () -> persistRecoveryItem(playerUuid, playerName, item.getItemStack()));
        });
    }

    public void adminRemoveAuction(AuctionItem item, boolean returnToSeller, boolean notifySeller) {
        adminRemoveAuction(item, null, returnToSeller, notifySeller);
    }

    public void adminRemoveAuction(AuctionItem item, Player staff, boolean returnToSeller, boolean notifySeller) {
        if (item == null || item.isRemoved()) return;

        CompletableFuture<Boolean> removeFuture;
        try {
            removeFuture = plugin.getDatabaseManager().removeAuctionItem(item.getId());
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().log(Level.WARNING, "Auction admin removal was rejected for item #" + item.getId(), saturated);
            if (staff != null) {
                plugin.getScheduler().runEntity(staff, () -> sendLang(staff, "admin-action.failed"));
            }
            return;
        }
        removeFuture.thenAccept(success -> {
            if (!success) {
                if (staff != null) {
                    plugin.getScheduler().runEntity(staff, () -> sendLang(staff, "admin-action.failed"));
                }
                return;
            }

            item.markRemoved();
            removeCachedItem(item.getId());
            packetHandler.publishItemDelete(item.getId());

            String staffName = staff != null ? staff.getName() : "Staff";
            UUID staffUuid = staff != null ? staff.getUniqueId() : new UUID(0L, 0L);
            String itemName = getItemDisplayName(item.getItemStack());

            audit("staff " + staffName + " " + (returnToSeller ? "cancelled" : "removed") + " #" + item.getId() + " "
                    + describe(item.getItemStack()) + " seller=" + item.getSellerName() + " (" + item.getSellerUuid()
                    + ") item goes to " + (returnToSeller || staff == null ? "seller" : "staff"));
            logAction(staffUuid, staffName, AuctionActionType.CANCEL_OTHER,
                    staffName + "|" + notifySeller + "|" + returnToSeller + "|" + item.getSellerName() + "|" + itemName + "|" + item.getPrice()
                            + "|" + item.getItemStack().getAmount() + "|" + item.getId());

            if (staff != null) {
                plugin.getScheduler().runEntity(staff, () -> {
                    if (!returnToSeller) {
                        safetyValidator.giveOrDropItem(staff, item.getItemStack());
                    }
                    sendLang(staff, returnToSeller ? "admin-action.cancelled-info" : "admin-action.removed-info",
                            "%item%", itemName,
                            "%amount%", String.valueOf(item.getItemStack().getAmount()),
                            "%seller%", item.getSellerName(),
                            "%price_short%", formatPriceShort(item.getPrice()),
                            "%price%", formatPrice(item.getPrice()));
                }, () -> {
                    if (!returnToSeller) {
                        persistRecoveryItem(staffUuid, staffName, item.getItemStack());
                    }
                });
            }

            if (!plugin.isEnabled()) {
                finishOfflineAdminRemoval(item, staffName, returnToSeller, returnToSeller || staff == null, notifySeller);
                return;
            }

            Object scheduled = plugin.getScheduler().runGlobal(() -> {
                Player seller = Bukkit.getPlayer(item.getSellerUuid());
                if (seller != null && seller.isOnline()) {
                    plugin.getScheduler().runEntity(seller, () -> {
                        if (returnToSeller) {
                            safetyValidator.giveOrDropItem(seller, item.getItemStack());
                        } else if (staff == null) {
                            persistRecoveryItem(item.getSellerUuid(), item.getSellerName(), item.getItemStack());
                        }
                        if (notifySeller) {
                            String key = returnToSeller ? "admin-action.cancelled" : "admin-action.removed";
                            sendLang(seller, key,
                                    "%staff%", staffName,
                                    "%amount%", String.valueOf(item.getItemStack().getAmount()),
                                    "%item%", itemName,
                                    "%date%", formatDate(System.currentTimeMillis()));
                        }
                    }, () -> finishOfflineAdminRemoval(item, staffName, returnToSeller, returnToSeller || staff == null, notifySeller));
                    return;
                }
                finishOfflineAdminRemoval(item, staffName, returnToSeller, returnToSeller || staff == null, notifySeller);
            });
            if (scheduled == null) finishOfflineAdminRemoval(item, staffName, returnToSeller, returnToSeller || staff == null, notifySeller);
        });
    }

    private void finishOfflineAdminRemoval(AuctionItem item, String staffName, boolean returnToSeller,
                                           boolean keepForSeller, boolean notifySeller) {
        // Without staff to receive a removed item it goes to the seller's recovery archive, never nowhere.
        if (keepForSeller) {
            persistRecoveryItem(item.getSellerUuid(), item.getSellerName(), item.getItemStack());
        }
        if (notifySeller) {
            String key = returnToSeller ? "admin-action.cancelled" : "admin-action.removed";
            org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
            String template = msgs != null ? msgs.getString(key, "") : "";
            if (template != null && !template.isEmpty()) {
                String notif = getPrefix() + template
                        .replace("%staff%", staffName != null ? staffName : "Staff")
                        .replace("%amount%", String.valueOf(item.getItemStack().getAmount()))
                        .replace("%item%", getItemDisplayName(item.getItemStack()))
                        .replace("%date%", formatDate(System.currentTimeMillis()));
                notifySeller(item.getSellerUuid(), item.getSellerName(), item.getId(), notif);
            }
        }
    }

    private void completeSellerPayout(AuctionItem item, String buyerName) {
        // The SQL sale transaction has already recorded the full payout, on every server.
        // Trigger delivery before messages, so a notification failure cannot hold the money back.
        packetHandler.publishPayout(item.getSellerUuid(), item.getSellerName(), item.getPrice(), "Auction sold");
        plugin.getScheduler().runGlobal(() -> {
            Player seller = Bukkit.getPlayer(item.getSellerUuid());
            if (seller != null) {
                plugin.getScheduler().runEntity(seller, () -> checkPendingDeliveries(seller));
            } else {
                checkPendingPayoutsOffline(item.getSellerUuid(), item.getSellerName());
            }
        });

        org.bukkit.configuration.file.FileConfiguration messages = plugin.getConfigService().get("modules/auctions/messages.yml");
        String template = messages != null ? messages.getString("buy.notify-seller", "") : "";
        String formattedPrice = formatPrice(item.getPrice());
        String formattedPriceShort = formatPriceShort(item.getPrice());
        String amountStr = String.valueOf(item.getItemStack().getAmount());
        String itemName = getItemDisplayName(item.getItemStack());

        String actionTemplate = messages != null ? messages.getString("buy.notify-seller-action-bar", "") : "";
        if ((actionTemplate == null || actionTemplate.isBlank()) && messages != null) {
            actionTemplate = messages.getString("buy.action-bar-purchase", "");
        }
        String sellerActionBar = "";
        if (actionTemplate != null && !actionTemplate.isBlank()) {
            sellerActionBar = actionTemplate
                    .replace("%player%", buyerName)
                    .replace("{player}", buyerName)
                    .replace("%seller%", item.getSellerName())
                    .replace("{seller}", item.getSellerName())
                    .replace("%amount%", amountStr)
                    .replace("{amount}", amountStr)
                    .replace("%item%", itemName)
                    .replace("{item}", itemName)
                    .replace("%price_short%", formattedPriceShort)
                    .replace("{price_short}", formattedPriceShort)
                    .replace("%price%", formattedPrice)
                    .replace("{price}", formattedPrice);
        }

        if (template != null && !template.isEmpty()) {
            String fullChat = getPrefix() + template
                    .replace("%player%", buyerName)
                    .replace("{player}", buyerName)
                    .replace("%amount%", amountStr)
                    .replace("{amount}", amountStr)
                    .replace("%item%", itemName)
                    .replace("{item}", itemName)
                    .replace("%price_short%", formattedPriceShort)
                    .replace("{price_short}", formattedPriceShort)
                    .replace("%price%", formattedPrice)
                    .replace("{price}", formattedPrice);
            notifySeller(item.getSellerUuid(), item.getSellerName(), item.getId(), fullChat, sellerActionBar);
        } else if (!sellerActionBar.isEmpty()) {
            notifySellerActionBarOnly(item.getSellerUuid(), item.getSellerName(), sellerActionBar);
        }
    }

    private boolean onlyGiveMoneyOnJoin() {
        var config = plugin.getConfigService().get("modules/auctions/config.yml");
        return config != null && config.getBoolean("only-give-money-on-join", false);
    }

    private int getPayoutDelaySeconds() {
        var config = plugin.getConfigService().get("modules/auctions/config.yml");
        return config != null ? Math.max(0, config.getInt("delay-money-give-seconds", 3)) : 3;
    }

    public void promptSearchInput(Player player, Consumer<String> callback) {
        if (player == null) return;
        if (signInputManager != null) {
            signInputManager.openSearchInput(player, callback);
        } else {
            pendingSearchInputs.put(player.getUniqueId(), callback);
        }
    }

    public void removePendingSearchInput(UUID uuid) {
        if (uuid != null) {
            pendingSearchInputs.remove(uuid);
            if (signInputManager != null) {
                signInputManager.cleanupPlayer(uuid);
            }
        }
    }

    public boolean handleSearchChatInput(Player player, String message) {
        if (signInputManager != null && signInputManager.handleChatInput(player, message)) {
            return true;
        }
        Consumer<String> callback = pendingSearchInputs.remove(player.getUniqueId());
        if (callback != null) {
            callback.accept(message);
            return true;
        }
        return false;
    }

    public void cacheItem(AuctionItem item) {
        if (item == null) return;
        itemRefreshVersions.remove(item.getId());
        synchronized (cacheLock) {
            activeItems.put(item.getId(), item);
            cacheVersion++;
        }
        refreshOpenGuis();
    }

    public void refreshCachedItem(int id) {
        refreshCachedItem(id, false);
    }

    public void refreshCachedItem(int id, boolean broadcast) {
        long version = itemRefreshSequence.incrementAndGet();
        itemRefreshVersions.put(id, version);
        plugin.getDatabaseManager().loadAuctionItemById(id).whenComplete((item, failure) -> {
            if (!itemRefreshVersions.remove(id, version)) return;
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh cross-server auction #" + id, failure);
                return;
            }
            synchronized (cacheLock) {
                if (item == null) activeItems.remove(id);
                else activeItems.put(id, item);
                cacheVersion++;
            }
            refreshOpenGuis();
            if (broadcast && item != null) {
                plugin.getScheduler().runGlobal(() -> broadcastListing(item));
            }
        });
    }

    public void removeCachedItem(int id) {
        itemRefreshVersions.remove(id);
        synchronized (cacheLock) {
            AuctionItem item = activeItems.remove(id);
            if (item != null) item.markRemoved();
            cacheVersion++;
        }
        refreshOpenGuis();
    }

    public void syncUserLimitLocally(UUID uuid, int limit) {
        if (uuid != null) {
            AuctionUser user = userCache.get(uuid);
            if (user != null) {
                user.setExtraLimit(limit);
            }
        }
    }

    public dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics getStatistics(UUID uuid) {
        return statistics.getOrDefault(uuid, dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics.EMPTY);
    }

    public dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics getGlobalStatistics() {
        return globalStatistics;
    }

    private void refreshGlobalStatistics() {
        if (!globalStatisticsLoad.compareAndSet(false, true)) return;
        try {
            plugin.getDatabaseManager().loadAuctionStatistics(null).whenComplete((value, failure) -> {
                globalStatisticsLoad.set(false);
                if (failure == null && value != null) globalStatistics = value;
            });
        } catch (RejectedExecutionException saturated) {
            globalStatisticsLoad.set(false);
        }
    }

    private void refreshStatistics(UUID uuid) {
        if (!statisticsLoads.add(uuid)) return;
        try {
            plugin.getDatabaseManager().loadAuctionStatistics(uuid).whenComplete((value, failure) -> {
                statisticsLoads.remove(uuid);
                if (failure == null && userCache.containsKey(uuid)) {
                    var previous = statistics.put(uuid, value);
                    if (!value.equals(previous)) refreshOpenGuis();
                }
            });
        } catch (RejectedExecutionException saturated) {
            statisticsLoads.remove(uuid);
        }
    }

    private void refreshUserLimit(Player player) {
        AuctionUser current = userCache.get(player.getUniqueId());
        if (current == null) return;
        long expectedVersion = current.getExtraLimitVersion();
        try {
            plugin.getDatabaseManager().loadAuctionUser(player.getUniqueId(), player.getName())
                    .thenAccept(loaded -> {
                        if (loaded != null) current.applyLoadedExtraLimit(loaded.getExtraLimit(), expectedVersion);
                    });
        } catch (RejectedExecutionException ignored) {
            // The next periodic reconciliation retries it.
        }
    }

    public void registerActiveMenu(dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase menu) {
        if (menu != null) {
            activeMenus.add(menu);
        }
    }

    public void unregisterActiveMenu(dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase menu) {
        if (menu != null) {
            activeMenus.remove(menu);
            pendingMenuRefreshes.remove(menu);
        }
    }

    /**
     * Rebuilds every open auction menu a few ticks later. Bursts of cache changes collapse into one
     * rebuild per menu, and the viewers are spread over REFRESH_SPREAD_TICKS instead of one tick.
     */
    public void refreshOpenGuis() {
        int index = 0;
        for (dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase menu : activeMenus) {
            Player viewer = menu.getViewer();
            if (viewer == null || !viewer.isOnline() || !pendingMenuRefreshes.add(menu)) continue;
            long delay = 1L + (index++ % REFRESH_SPREAD_TICKS);
            plugin.getScheduler().runEntityLater(viewer, () -> {
                pendingMenuRefreshes.remove(menu);
                try {
                    if (viewer.isOnline() && viewer.getOpenInventory().getTopInventory().equals(menu.getInventory())) {
                        menu.refresh();
                    }
                } catch (Exception ignored) {}
            }, () -> pendingMenuRefreshes.remove(menu), delay);
        }
    }

    public AuctionUser getUser(UUID uuid) {
        return getUser(uuid, null);
    }

    public AuctionUser getUser(UUID uuid, String name) {
        AuctionUser user = userCache.computeIfAbsent(uuid, id -> new AuctionUser(uuid, name, 0));
        if (name != null) user.setName(name);
        if (!loadedUsers.contains(uuid)) getUserAsync(uuid, name);
        return user;
    }

    public CompletableFuture<AuctionUser> getUserAsync(UUID uuid, String name) {
        AuctionUser user = userCache.computeIfAbsent(uuid, id -> new AuctionUser(uuid, name, 0));
        if (name != null) user.setName(name);
        if (loadedUsers.contains(uuid)) return CompletableFuture.completedFuture(user);

        CompletableFuture<AuctionUser> load = userLoads.computeIfAbsent(uuid, id -> {
            long expectedVersion = user.getExtraLimitVersion();
            try {
                return plugin.getDatabaseManager().loadAuctionUser(uuid, name).thenApply(loaded -> {
                    if (loaded != null && userCache.get(uuid) == user) {
                        user.applyLoadedExtraLimit(loaded.getExtraLimit(), expectedVersion);
                        loadedUsers.add(uuid);
                    }
                    return user;
                });
            } catch (RuntimeException submissionFailure) {
                return CompletableFuture.failedFuture(submissionFailure);
            }
        });
        return load.whenComplete((loaded, failure) -> {
            userLoads.remove(uuid, load);
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to load auction user " + uuid, failure);
            }
        });
    }

    public void onPlayerQuit(UUID uuid) {
        if (uuid == null) return;
        userCache.remove(uuid);
        statistics.remove(uuid);
        userLoads.remove(uuid);
        loadedUsers.remove(uuid);
        deliveryChecksInFlight.remove(uuid);
        deliveryRechecks.remove(uuid);
        removePendingSearchInput(uuid);
        if (safetyValidator != null) {
            safetyValidator.unlock(uuid);
        }
    }

    public int getPlayerActiveItemCount(UUID uuid) {
        int count = 0;
        for (AuctionItem item : activeItems.values()) {
            if (!item.isRemoved() && item.getItemState() == AuctionItemState.ACTIVE && item.getSellerUuid().equals(uuid)) {
                count++;
            }
        }
        return count;
    }

    public int getActiveListingCount() {
        int count = 0;
        for (AuctionItem item : activeItems.values()) {
            if (!item.isRemoved() && item.getItemState() == AuctionItemState.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public int getPlayerTotalItemCount(UUID uuid) {
        int count = 0;
        for (AuctionItem item : activeItems.values()) {
            if (!item.isRemoved() && item.getSellerUuid().equals(uuid)) count++;
        }
        return count;
    }

    public List<AuctionItem> getPlayerActiveListings(UUID uuid) {
        List<AuctionItem> list = new ArrayList<>();
        for (AuctionItem item : activeItems.values()) {
            if (!item.isRemoved() && item.getItemState() == AuctionItemState.ACTIVE && item.getSellerUuid().equals(uuid)) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparingLong(AuctionItem::getStartTime).reversed());
        return list;
    }

    public List<AuctionItem> getPlayerExpiredListings(UUID uuid) {
        List<AuctionItem> list = new ArrayList<>();
        for (AuctionItem item : activeItems.values()) {
            if (!item.isRemoved() && item.getItemState() == AuctionItemState.EXPIRED && item.getSellerUuid().equals(uuid)) {
                list.add(item);
            }
        }
        list.sort(Comparator.comparingLong(AuctionItem::getStartTime).reversed());
        return list;
    }

    public List<AuctionItem> getFilteredActiveListings(String categoryId, AuctionSorting sorting, String searchFilter) {
        List<AuctionItem> list = new ArrayList<>();
        for (AuctionItem item : activeItems.values()) {
            if (item.isRemoved() || item.getItemState() != AuctionItemState.ACTIVE) {
                continue;
            }

            if (categoryId != null && !categoryId.equalsIgnoreCase("all") && !categoryManager.matches(categoryId, item.getItemStack())) {
                continue;
            }

            if (searchFilter != null && !searchFilter.isBlank() && !AuctionSearchEngine.matches(item, searchFilter)) {
                continue;
            }

            list.add(item);
        }

        // Already filtered above; only sort.
        filterAndSort(list, null, sorting, null);
        return list;
    }

    public void filterAndSort(List<AuctionItem> list, String categoryId, AuctionSorting sorting, String searchFilter) {
        list.removeIf(item -> (categoryId != null && !categoryId.equalsIgnoreCase("all")
                && !categoryManager.matches(categoryId, item.getItemStack()))
                || (searchFilter != null && !searchFilter.isBlank() && !AuctionSearchEngine.matches(item, searchFilter)));
        if (sorting == null) sorting = AuctionSorting.NEWEST;
        switch (sorting) {
            case NEWEST -> list.sort(Comparator.comparingLong(AuctionItem::getStartTime).reversed().thenComparingInt(AuctionItem::getId));
            case OLDEST -> list.sort(Comparator.comparingLong(AuctionItem::getStartTime).thenComparingInt(AuctionItem::getId));
            case CHEAPEST -> list.sort(Comparator.comparingDouble(AuctionItem::getPrice).thenComparingInt(AuctionItem::getId));
            case PRICIEST -> list.sort(Comparator.comparingDouble(AuctionItem::getPrice).reversed().thenComparingInt(AuctionItem::getId));
        }

    }

    public int getPlayerListingLimit(Player player) {
        if (player == null) return 5;
        if (player.isOp() || player.hasPermission("*")
                || player.hasPermission("friendlycore.auctions.limit.bypass")
                || player.hasPermission("friendlycore.auctions.limit.*")
                || player.hasPermission("axauctions.limit.bypass")
                || player.hasPermission("axauctions.limit.*")) {
            return -1;
        }

        int max = 0;
        for (org.bukkit.permissions.PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            if (!permission.getValue()) continue;
            String node = permission.getPermission().toLowerCase(Locale.ROOT);
            String prefix = node.startsWith("axauctions.limit.") ? "axauctions.limit."
                    : node.startsWith("friendlycore.auctions.limit.") ? "friendlycore.auctions.limit." : null;
            if (prefix == null) continue;
            try {
                max = Math.max(max, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Wildcards and named bypass nodes were handled above.
            }
        }

        if (max == 0) {
            max = plugin.getConfigService().get("modules/auctions/config.yml").getInt("default-listing-limit", 5);
        }

        AuctionUser user = getUser(player.getUniqueId(), player.getName());
        return max + user.getExtraLimit();
    }

    public void logAction(Player actor, AuctionActionType type, String extra) {
        if (actor != null) {
            logAction(actor.getUniqueId(), actor.getName(), type, extra);
        }
    }

    public void logAction(UUID uuid, String name, AuctionActionType type, String extra) {
        AuctionLogEntry entry = new AuctionLogEntry(0, uuid, name, type, extra, System.currentTimeMillis());
        try {
            plugin.getDatabaseManager().insertAuctionLog(entry);
        } catch (RejectedExecutionException saturated) {
            plugin.getLogger().log(Level.WARNING, "Auction audit log write was rejected", saturated);
        }
    }

    private void notifySellerExpired(AuctionItem item) {
        notifySellerOnce(item, "expired", "my-items.expired");
    }

    private void notifySellerDeleted(AuctionItem item) {
        notifySellerOnce(item, "deleted", "my-items.deleted");
    }

    private void notifySellerOnce(AuctionItem item, String eventKey, String messageKey) {
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        String template = msgs != null ? msgs.getString(messageKey, "") : "";
        if (template == null || template.isEmpty()) return;
        String message = getPrefix() + template
                .replace("%amount%", String.valueOf(item.getItemStack().getAmount()))
                .replace("%item%", getItemDisplayName(item.getItemStack()))
                .replace("%date%", formatDate(System.currentTimeMillis()));
        plugin.getDatabaseManager().insertAuctionNotificationOnce(
                item.getSellerUuid(), item.getId(), eventKey, message).thenAccept(inserted -> {
            if (!Boolean.TRUE.equals(inserted)) return;
            packetHandler.publishNotification(item.getSellerUuid(), item.getSellerName(), "");
            plugin.getScheduler().runGlobal(() -> {
                Player seller = Bukkit.getPlayer(item.getSellerUuid());
                if (seller != null) plugin.getScheduler().runEntity(seller, () -> checkPendingDeliveries(seller));
            });
        }).exceptionally(failure -> {
            plugin.getLogger().log(Level.WARNING, "Failed to persist auction lifecycle notification", failure);
            return null;
        });
    }

    public long getExpireDurationMillis() {
        return plugin.getConfigService().get("modules/auctions/config.yml").getLong("item-expire-time", 86400) * 1000L;
    }

    public long getDeletionDurationMillis() {
        return plugin.getConfigService().get("modules/auctions/config.yml").getLong("item-deletion-time", 604800) * 1000L;
    }

    public AuctionEconomyService getEconomyService() {
        return economyService;
    }

    public String formatPrice(double amount) {
        if (isShortPriceFormat()) {
            return formatPriceShort(amount);
        }
        return formatPriceClean(amount);
    }

    public static String formatPriceClean(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "0";
        }
        if (amount == (long) amount) {
            return String.format(Locale.US, "%,d", (long) amount);
        }
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(amount);
    }

    public static String formatPriceShort(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "0";
        }
        double abs = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
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
            return sign + formatShortVal(abs / 1_000.0) + "k";
        }
        return sign + formatPriceClean(abs);
    }

    private static String formatShortVal(double val) {
        if (val == (long) val) {
            return String.valueOf((long) val);
        }
        DecimalFormat df = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US));
        return df.format(val);
    }

    public boolean isShortPriceFormat() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        if (cfg == null) return false;
        return cfg.getBoolean("short-price-format", cfg.getBoolean("use-short-prices-in-gui", false));
    }

    public AuctionCategoryManager getCategoryManager() {
        return categoryManager;
    }

    public AuctionSafetyValidator getSafetyValidator() {
        return safetyValidator;
    }

    public AuctionDiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public AuctionPacketHandler getPacketHandler() {
        return packetHandler;
    }

    public AuctionConverterManager getConverterManager() {
        return converterManager;
    }

    public AuctionSignInputManager getSignInputManager() {
        return signInputManager;
    }

    public String getPrefix() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        if (cfg != null && cfg.contains("prefix")) {
            return cfg.getString("prefix");
        }
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        if (msgs != null && msgs.contains("prefix")) {
            return msgs.getString("prefix");
        }
        return "<gradient:#E63A35:#FF6B6B><b>Auctions</b></gradient> &7» ";
    }

    public void sendLang(CommandSender sender, String key, String... replacements) {
        if (sender == null) return;
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        if (msgs == null) return;
        String raw = msgs.getString(key);
        if (raw == null || raw.isEmpty()) return;
        String full = getPrefix() + raw;
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String k = replacements[i];
                String v = replacements[i + 1] != null ? replacements[i + 1] : "";
                full = full.replace(k, v);
                if (k.startsWith("%") && k.endsWith("%") && k.length() > 2) {
                    full = full.replace("{" + k.substring(1, k.length() - 1) + "}", v);
                }
            }
        }
        plugin.getTextService().sendMessage(sender, full);
    }

    public void sendFormatted(CommandSender sender, String text, String... replacements) {
        if (sender == null || text == null || text.isEmpty()) return;
        String full = text;
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String k = replacements[i];
                String v = replacements[i + 1] != null ? replacements[i + 1] : "";
                full = full.replace(k, v);
                if (k.startsWith("%") && k.endsWith("%") && k.length() > 2) {
                    full = full.replace("{" + k.substring(1, k.length() - 1) + "}", v);
                }
            }
        }
        plugin.getTextService().sendMessage(sender, full);
    }

    public String getItemDisplayName(ItemStack item) {
        return dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase.getItemDisplayName(item);
    }

    public void reloadDateFormat() {
        org.bukkit.configuration.file.FileConfiguration msgs = plugin.getConfigService().get("modules/auctions/messages.yml");
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/config.yml");
        String pattern = msgs != null ? msgs.getString("date-format", "yyyy. MM. dd HH:mm:ss") : "yyyy. MM. dd HH:mm:ss";
        if (cfg != null && cfg.contains("date-format")) {
            pattern = cfg.getString("date-format", pattern);
        }
        try {
            this.dateFormatter = java.time.format.DateTimeFormatter.ofPattern(pattern).withZone(java.time.ZoneId.systemDefault());
        } catch (Exception e) {
            this.dateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        }
    }

    public String formatDate(long millis) {
        if (dateFormatter == null) reloadDateFormat();
        return dateFormatter.format(java.time.Instant.ofEpochMilli(millis));
    }
}
