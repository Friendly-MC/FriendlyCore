package dev.lyy.friendlycore.common.database;

import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.Transaction;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Common persistence contract for FriendlyCore storage implementations (MySQL, SQLite).
 * Provides asynchronous queries and batch operations across all plugin modules.
 */
public interface DatabaseManager {

    void initialize();

    java.sql.Connection getConnection() throws java.sql.SQLException;

    CompletableFuture<AccountData> loadAccount(UUID uuid, String username);

    CompletableFuture<UUID> getUuidByUsername(String username);

    CompletableFuture<Void> saveAccount(AccountData account);

    CompletableFuture<Void> saveAccountsBatch(Collection<AccountData> accounts);

    void saveAccountsBatchSync(Collection<AccountData> accounts);

    CompletableFuture<Boolean> atomicAdjustBalance(UUID uuid, String username, double delta, double maxBalance);

    CompletableFuture<Void> setPlayerBalance(UUID uuid, String username, double balance);

    CompletableFuture<List<AccountData>> getTopBalances(int limit);

    CompletableFuture<Void> logTransaction(Transaction transaction);

    CompletableFuture<List<Transaction>> getRecentTransactions(UUID uuid, int limit);

    CompletableFuture<PlayerStats> loadStats(UUID uuid, String username);

    CompletableFuture<Void> saveStatsBatch(Collection<PlayerStats> stats);

    CompletableFuture<TpaProfile> loadTpaProfile(UUID uuid, String username);

    CompletableFuture<Void> saveTpaProfile(TpaProfile profile);

    CompletableFuture<Void> saveTpaProfilesBatch(Collection<TpaProfile> profiles);

    CompletableFuture<dev.lyy.friendlycore.modules.kits.model.PlayerKitData> loadPlayerKitData(UUID uuid, String username);

    CompletableFuture<Void> savePlayerKitData(dev.lyy.friendlycore.modules.kits.model.PlayerKitData data);

    CompletableFuture<Void> savePlayerKitDataBatch(Collection<dev.lyy.friendlycore.modules.kits.model.PlayerKitData> batch);

    CompletableFuture<Void> resetPlayerKitData(String kitName, UUID uuid);

    CompletableFuture<KitClaimReservation> reserveKitClaim(UUID uuid, String username, String kitName,
                                                            long cooldownUntil, boolean oneTime);

    CompletableFuture<Boolean> completeKitClaimReservation(UUID uuid, String kitName, String token);

    CompletableFuture<Boolean> rollbackKitClaimReservation(UUID uuid, String kitName, String token);

    CompletableFuture<Boolean> loadNightVision(UUID uuid);

    CompletableFuture<Void> saveNightVision(UUID uuid, String username, boolean enabled);

    CompletableFuture<Void> saveNightVisionBatch(java.util.Map<UUID, Boolean> batch);

    CompletableFuture<java.util.Map<UUID, Boolean>> loadAllNightVision();

    CompletableFuture<dev.lyy.friendlycore.modules.homes.model.PlayerHomes> loadPlayerHomes(UUID uuid, String username);

    CompletableFuture<Void> savePlayerHomes(dev.lyy.friendlycore.modules.homes.model.PlayerHomes homes);

    CompletableFuture<Void> savePlayerHomesBatch(Collection<dev.lyy.friendlycore.modules.homes.model.PlayerHomes> batch);

    CompletableFuture<Void> deleteHome(UUID uuid, int slot);

    CompletableFuture<Void> deleteAllHomes(UUID uuid);

    CompletableFuture<Void> resetEconomyData();

    CompletableFuture<Void> pruneTransactions(long olderThanTimestamp);

    CompletableFuture<dev.lyy.friendlycore.modules.pets.model.PlayerPetData> loadPlayerPetData(UUID uuid, String username);

    CompletableFuture<Void> savePlayerPetData(dev.lyy.friendlycore.modules.pets.model.PlayerPetData data);

    CompletableFuture<Void> savePlayerPetDataBatch(Collection<dev.lyy.friendlycore.modules.pets.model.PlayerPetData> batch);

    /** Completes true when the row was inserted, false when the pet was already owned. */
    CompletableFuture<Boolean> unlockPet(UUID uuid, String petId);

    /** Completes true when the row was deleted, false when the pet was not owned. */
    CompletableFuture<Boolean> lockPet(UUID uuid, String petId);

    CompletableFuture<Void> setActivePet(UUID uuid, String petId);

    CompletableFuture<Void> setActivePets(UUID uuid, java.util.List<String> petIds);

    CompletableFuture<Void> setHidePet(UUID uuid, boolean hide);

    CompletableFuture<dev.lyy.friendlycore.modules.chat.model.ChatPlayerData> loadChatPlayerData(UUID uuid, String username);

    CompletableFuture<Void> saveChatPlayerData(dev.lyy.friendlycore.modules.chat.model.ChatPlayerData data);

    CompletableFuture<Void> saveChatPlayerDataBatch(Collection<dev.lyy.friendlycore.modules.chat.model.ChatPlayerData> batch);

    CompletableFuture<Void> addChatIgnored(UUID playerUuid, UUID ignoredUuid);

    CompletableFuture<Void> removeChatIgnored(UUID playerUuid, UUID ignoredUuid);

    CompletableFuture<Void> clearChatIgnored(UUID playerUuid);

    CompletableFuture<Void> logChatInfraction(UUID playerUuid, String message, int points, String server);

    CompletableFuture<dev.lyy.friendlycore.modules.settings.PlayerSettings> loadSettings(UUID uuid, String username);

    CompletableFuture<Void> saveSettings(dev.lyy.friendlycore.modules.settings.PlayerSettings settings);

    CompletableFuture<Void> saveSettingsBatch(Collection<dev.lyy.friendlycore.modules.settings.PlayerSettings> batch);

    CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem>> loadActiveAuctions();

    CompletableFuture<Integer> insertAuctionItem(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item);

    CompletableFuture<Boolean> removeAuctionItem(int id);

    CompletableFuture<Boolean> archiveAuctionItem(int id);

    CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics> loadAuctionStatistics(UUID uuid);

    CompletableFuture<Boolean> expireAuctionItem(int id, long startTime);

    CompletableFuture<Boolean> purchaseAuctionItem(int id, double price, long earliestStart, UUID buyerUuid, String buyerName);

    CompletableFuture<Void> updateAuctionItem(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item);

    CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionItem> loadAuctionItemById(int id);

    CompletableFuture<dev.lyy.friendlycore.modules.auctions.model.AuctionUser> loadAuctionUser(UUID uuid, String name);

    CompletableFuture<Void> saveAuctionUser(dev.lyy.friendlycore.modules.auctions.model.AuctionUser user);

    CompletableFuture<Void> insertAuctionHistory(dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry entry);

    CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry>> loadAuctionHistory(UUID userUuid, int limit);

    CompletableFuture<Void> insertAuctionDeleted(dev.lyy.friendlycore.modules.auctions.model.AuctionItem item);

    CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionItem>> loadAuctionDeleted(UUID userUuid, int limit);

    CompletableFuture<Boolean> removeAuctionDeleted(int id);

    CompletableFuture<Void> insertAuctionLog(dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry entry);

    CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry>> loadAuctionLogs(UUID userUuid, int limit);

    CompletableFuture<Void> insertAuctionNotification(UUID userUuid, int itemId, String message);

    CompletableFuture<Boolean> insertAuctionNotificationOnce(UUID userUuid, int itemId, String eventKey, String message);

    CompletableFuture<List<String>> fetchAndClearAuctionNotifications(UUID userUuid);

    CompletableFuture<Void> insertAuctionPayout(UUID userUuid, double amount, String reason);

    CompletableFuture<List<dev.lyy.friendlycore.modules.auctions.model.AuctionPayout>> fetchAndClearAuctionPayouts(UUID userUuid);

    CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadActiveOrders();

    CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadOrdersByCreator(UUID creatorUuid);

    CompletableFuture<dev.lyy.friendlycore.modules.orders.model.BuyOrder> loadOrderById(UUID orderId);

    CompletableFuture<Void> insertOrder(dev.lyy.friendlycore.modules.orders.model.BuyOrder order);

    CompletableFuture<Void> insertDelivery(dev.lyy.friendlycore.modules.orders.model.OrderDelivery delivery);

    CompletableFuture<Boolean> fulfillOrderAtomic(UUID orderId, long quantity, double payout,
                                                   dev.lyy.friendlycore.modules.orders.model.OrderDelivery delivery,
                                                   dev.lyy.friendlycore.modules.orders.model.OrderHistoryEntry history);

    CompletableFuture<Boolean> cancelOrderAtomic(UUID orderId);

    CompletableFuture<Boolean> expireOrderAtomic(UUID orderId);

    CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.BuyOrder>> loadExpiredActiveOrders(long now);

    CompletableFuture<Void> incrementClaimedAmount(UUID orderId, int amount);

    CompletableFuture<Boolean> deleteOrder(UUID orderId);

    CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.OrderDelivery>> loadUnclaimedDeliveries(UUID buyerUuid);

    CompletableFuture<List<Integer>> claimDeliveries(UUID buyerUuid, List<Integer> deliveryIds);

    /** Restore only claims that this caller acquired and has not awarded. */
    CompletableFuture<Void> restoreClaimedDeliveries(UUID buyerUuid, List<Integer> deliveryIds);

    CompletableFuture<Void> insertOrderLog(dev.lyy.friendlycore.modules.orders.model.OrderLogEntry entry);

    CompletableFuture<Void> insertOrderNotification(UUID userUuid, UUID orderId, String message);

    CompletableFuture<List<dev.lyy.friendlycore.modules.orders.model.OrderNotification>> fetchAndClearOrderNotifications(UUID userUuid);

    CompletableFuture<Void> resetAllData();

    void shutdown();
}
