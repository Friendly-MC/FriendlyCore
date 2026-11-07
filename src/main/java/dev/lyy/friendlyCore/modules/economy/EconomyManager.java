package dev.lyy.friendlycore.modules.economy;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Handles player economy accounts, cache synchronization, batch persistence, and transactions.
 */
public class EconomyManager implements Listener {

    private final FriendlyCore plugin;
    private final DatabaseManager databaseManager;
    private final dev.lyy.friendlycore.modules.economy.migration.EssentialsMigrator essentialsMigrator;
    private final ConcurrentHashMap<UUID, AccountData> accountCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameToUuidMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<AccountData>> inFlightAccountLoads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> inFlightAccountSaves = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlightAtomicAdjustments = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> onlinePlayerNames = new ConcurrentHashMap<>();
    private final AccountOperationQueue transferQueue = new AccountOperationQueue();

    private volatile List<AccountData> baltopCache = new ArrayList<>();
    private volatile long lastBaltopRefresh = 0;

    private FriendlyVaultEconomy vaultProvider;
    private Object autoSaveTask;

    public EconomyManager(FriendlyCore plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.essentialsMigrator = new dev.lyy.friendlycore.modules.economy.migration.EssentialsMigrator(plugin);
    }

    public dev.lyy.friendlycore.modules.economy.migration.EssentialsMigrator getEssentialsMigrator() {
        return essentialsMigrator;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Register with Vault if enabled in configuration
        if (plugin.getConfigService().getEconomyConfig().getBoolean("vault-provider.enabled", true)) {
            this.vaultProvider = new FriendlyVaultEconomy(plugin, this);
            Bukkit.getServicesManager().register(Economy.class, vaultProvider, plugin, ServicePriority.Highest);
            plugin.getLogger().info("Registered FriendlyCore as Vault Economy Provider!");
        }

        // Schedule periodic auto-saving for dirty accounts and cache cleanup
        int saveInterval = plugin.getConfigService().getMainConfig().getInt("optimization.save-interval-seconds", 60);
        this.autoSaveTask = plugin.getScheduler().runAsyncTimer(
                () -> {
                    saveAllDirty();
                    cleanupOfflineAccounts();
                },
                saveInterval * 20L,
                saveInterval * 20L
        );

        // Populate cache for players already online (e.g. on plugin reload)
        for (Player online : Bukkit.getOnlinePlayers()) {
            onlinePlayerNames.put(online.getUniqueId(), online.getName());
            getAccountAsync(online.getUniqueId(), online.getName());
        }
        // Retry missing profiles after a short outage, including players whose pre-login load failed.
        plugin.getScheduler().runAsyncTimer(() -> onlinePlayerNames.forEach((uuid, name) -> {
            AccountData account = accountCache.get(uuid);
            if (account == null || account.isPlaceholder()) loadPlayerAccount(uuid, name);
        }), 100L, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String username = event.getName();
        if (username != null) {
            nameToUuidMap.put(username.toLowerCase(Locale.ROOT), uuid);
        }
        CompletableFuture<Void> pendingSave = inFlightAccountSaves.get(uuid);
        if (pendingSave != null) {
            pendingSave.whenComplete((ignored, error) -> loadPlayerAccount(uuid, username));
        } else {
            loadPlayerAccount(uuid, username);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        onlinePlayerNames.put(uuid, player.getName());
        CompletableFuture<Void> pendingSave = inFlightAccountSaves.get(uuid);
        if (pendingSave == null) {
            reloadAccountForJoin(uuid, player.getName());
            return;
        }

        // Do not block the server thread. Once the previous session's save settles,
        // either load the committed row or retain the still-dirty in-memory account
        // so a failed write cannot roll the player's balance back.
        pendingSave.whenComplete((ignored, error) -> reloadAccountForJoin(uuid, player.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        onlinePlayerNames.remove(uuid);
        AccountData data = accountCache.get(uuid);
        if (data != null && data.isDirty() && !data.isPlaceholder()) {
            if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().broadcastBalanceUpdate(uuid, data.getUsername(), data.getBalance());
            }
            queueAccountSave(data).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to asynchronously save account on quit for " + uuid + "; queued for autosave retry", error);
                }
            });
        }
    }

    private void reloadAccountForJoin(UUID uuid, String username) {
        AccountData cached = accountCache.get(uuid);
        if (cached != null) {
            if (username != null && !username.equalsIgnoreCase(cached.getUsername())) {
                cached.setUsername(username);
            }
            if (cached.isDirty() && !cached.isPlaceholder()) {
                // A failed or superseded quit save left newer authoritative data in memory.
                return;
            }
        }
        // Do NOT evict cached from accountCache here. Keeping cached in memory avoids a
        // cache-miss window where other join listeners (chat, tablist, scoreboard) calling
        // Vault getBalance would stall the main server thread on MySQL queries.
        CompletableFuture<AccountData> inFlight = inFlightAccountLoads.get(uuid);
        if (inFlight != null && !inFlight.isDone()) {
            return;
        }
        loadPlayerAccount(uuid, username);
    }

    private CompletableFuture<Void> queueAccountSave(AccountData data) {
        UUID uuid = data.getUuid();
        AccountData snapshot = data.snapshot();
        CompletableFuture<Void> queued = inFlightAccountSaves.compute(uuid, (ignored, previous) -> {
            CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return predecessor.thenCompose(nothing -> databaseManager.saveAccount(snapshot))
                    .whenComplete((res, err) -> {
                        if (err == null) {
                            data.markSaved(snapshot.getCurrentVersion());
                        }
                    });
        });
        queued.whenComplete((ignored, error) -> inFlightAccountSaves.computeIfPresent(uuid, (key, c) -> c == queued ? null : c));
        return queued;
    }

    private void trackAdjustment(CompletableFuture<?> future) {
        inFlightAtomicAdjustments.add(future);
        future.whenComplete((res, err) -> inFlightAtomicAdjustments.remove(future));
    }

    private CompletableFuture<Boolean> executeChainedAdjustment(UUID uuid, java.util.function.Supplier<CompletableFuture<Boolean>> action) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletableFuture<Void> queued = inFlightAccountSaves.compute(uuid, (key, previous) -> {
            CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return predecessor.thenCompose(nothing -> action.get())
                    .whenComplete((applied, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(applied);
                        }
                    }).handle((val, err) -> null);
        });
        queued.whenComplete((ignored, error) -> inFlightAccountSaves.computeIfPresent(uuid, (key, c) -> c == queued ? null : c));
        return result;
    }

    private CompletableFuture<Void> executeChainedSave(UUID uuid, java.util.function.Supplier<CompletableFuture<Void>> action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> queued = inFlightAccountSaves.compute(uuid, (key, previous) -> {
            CompletableFuture<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.exceptionally(error -> null);
            return predecessor.thenCompose(nothing -> action.get())
                    .whenComplete((res, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(res);
                        }
                    }).handle((val, err) -> null);
        });
        queued.whenComplete((ignored, error) -> inFlightAccountSaves.computeIfPresent(uuid, (key, c) -> c == queued ? null : c));
        return result;
    }

    public CompletableFuture<AccountData> loadPlayerAccount(UUID uuid, String username) {
        if (username != null) {
            nameToUuidMap.put(username.toLowerCase(Locale.ROOT), uuid);
        }
        AccountData cached = accountCache.get(uuid);
        if (cached != null && cached.isDirty() && !cached.isPlaceholder()) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<AccountData> load = inFlightAccountLoads.computeIfAbsent(uuid, ignored ->
                databaseManager.loadAccount(uuid, username).thenApply(loaded ->
                        accountCache.compute(uuid, (key, existing) -> {
                            if (existing == null) return loaded;
                            if (existing.isPlaceholder()) {
                                double startingBal = 0.0;
                                try {
                                    if (plugin.getConfigService() != null && plugin.getConfigService().getEconomyConfig() != null) {
                                        startingBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
                                    }
                                } catch (Throwable ignored2) {}
                                double delta = existing.getBalance() - startingBal;
                                if (delta != 0.0) {
                                    if (delta > 0) {
                                        loaded.deposit(delta);
                                    } else {
                                        loaded.withdraw(-delta);
                                    }
                                }
                                if (existing.getUsername() != null && !existing.getUsername().equalsIgnoreCase("Player")
                                        && !existing.getUsername().equalsIgnoreCase("Unknown")) {
                                    loaded.setUsername(existing.getUsername());
                                }
                                return loaded;
                            }
                            if (existing.isDirty() || existing.getLastUpdated() >= loaded.getLastUpdated()) return existing;
                            return loaded;
                        })));
        return load.whenComplete((result, error) -> inFlightAccountLoads.remove(uuid, load));
    }

    public AccountData getCachedAccount(UUID uuid) {
        return accountCache.get(uuid);
    }

    public boolean hasAccount(UUID uuid) {
        if (uuid == null) return false;
        if (accountCache.containsKey(uuid)) return true;
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            if (offline.hasPlayedBefore() || offline.isOnline()) return true;
            if (nameToUuidMap.containsValue(uuid)) return true;
            getAccountAsync(uuid, null);
            return true;
        }
        return getAccount(uuid, "Player") != null;
    }

    public AccountData getAccount(UUID uuid, String username) {
        if (uuid == null) return null;
        AccountData account = accountCache.get(uuid);
        if (account != null) {
            if (username != null && !username.equalsIgnoreCase(account.getUsername())) {
                account.setUsername(username);
            }
            return account;
        }

        CompletableFuture<AccountData> inFlight = inFlightAccountLoads.get(uuid);
        if (inFlight != null && inFlight.isDone()) {
            try {
                AccountData loaded = inFlight.getNow(null);
                if (loaded != null) {
                    if (username != null && !username.equalsIgnoreCase(loaded.getUsername())) {
                        loaded.setUsername(username);
                    }
                    return loaded;
                }
            } catch (Exception ignored) {}
        }

        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            // NEVER block the primary server thread on database queries.
            getAccountAsync(uuid, username);
            double startBal = 0.0;
            try {
                if (plugin.getConfigService() != null && plugin.getConfigService().getEconomyConfig() != null) {
                    startBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
                }
            } catch (Throwable ignored) {}
            AccountData fallback = new AccountData(uuid, username != null ? username : "Player", startBal, true);
            accountCache.putIfAbsent(uuid, fallback);
            return fallback;
        }

        try {
            account = databaseManager.loadAccount(uuid, username).get(1500, TimeUnit.MILLISECONDS);
            AccountData loaded = account;
            account = accountCache.compute(uuid, (key, existing) -> {
                if (existing == null) return loaded;
                if (existing.isPlaceholder()) {
                    double startingBal = 0.0;
                    try {
                        if (plugin.getConfigService() != null && plugin.getConfigService().getEconomyConfig() != null) {
                            startingBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
                        }
                    } catch (Throwable ignored2) {}
                    double delta = existing.getBalance() - startingBal;
                    if (delta != 0.0) {
                        if (delta > 0) {
                            loaded.deposit(delta);
                        } else {
                            loaded.withdraw(-delta);
                        }
                    }
                    if (existing.getUsername() != null && !existing.getUsername().equalsIgnoreCase("Player")
                            && !existing.getUsername().equalsIgnoreCase("Unknown")) {
                        loaded.setUsername(existing.getUsername());
                    }
                    return loaded;
                }
                if (existing.isDirty() || existing.getLastUpdated() >= loaded.getLastUpdated()) return existing;
                return loaded;
            });
            if (username != null) {
                nameToUuidMap.put(username.toLowerCase(Locale.ROOT), uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timeout or error loading account for " + uuid + ": " + e.getMessage());
            getAccountAsync(uuid, username);
            return null;
        }
        return account;
    }

    public CompletableFuture<AccountData> getAccountAsync(UUID uuid, String username) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        AccountData cached = accountCache.get(uuid);
        if (cached != null) {
            if (username != null && !username.equalsIgnoreCase(cached.getUsername())) {
                cached.setUsername(username);
            }
            return CompletableFuture.completedFuture(cached);
        }

        try {
            return loadPlayerAccount(uuid, username).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Timeout or error loading account for " + uuid + ": " + e.getMessage());
                return null;
            });
        } catch (RuntimeException submissionFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not submit account load for " + uuid + ": " + submissionFailure.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public CompletableFuture<UUID> getUuidByNameAsync(String username) {
        if (username == null || username.isEmpty()) return CompletableFuture.completedFuture(null);
        String lower = username.toLowerCase(Locale.ROOT);
        UUID cached = nameToUuidMap.get(lower);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        Player online = Bukkit.getPlayerExact(username);
        if (online != null) {
            nameToUuidMap.put(lower, online.getUniqueId());
            return CompletableFuture.completedFuture(online.getUniqueId());
        }

        // Query Redis network player cache if available
        if (plugin.getRedisService().isEnabled()) {
            UUID netUuid = plugin.getRedisService().getNetworkPlayerUuid(username);
            if (netUuid != null) {
                nameToUuidMap.put(lower, netUuid);
                return CompletableFuture.completedFuture(netUuid);
            }
        }

        // Fall back to database query, then Bukkit offline player lookup
        return databaseManager.getUuidByUsername(username).thenCompose(dbUuid -> {
            if (dbUuid != null) {
                nameToUuidMap.put(lower, dbUuid);
                return CompletableFuture.completedFuture(dbUuid);
            }

            return CompletableFuture.supplyAsync(() -> {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(username);
                if (offline.hasPlayedBefore() || offline.isOnline()) {
                    nameToUuidMap.put(lower, offline.getUniqueId());
                    return offline.getUniqueId();
                }
                return null;
            }, plugin.getScheduler().getAsyncExecutor());
        });
    }

    public ConcurrentHashMap<String, UUID> getNameToUuidMap() {
        return nameToUuidMap;
    }

    public void invalidateAccountCache(UUID uuid) {
        if (uuid != null) {
            accountCache.remove(uuid);
        }
    }

    public double getBalance(UUID uuid) {
        if (uuid == null) return 0.0;
        if (onlinePlayerNames.containsKey(uuid)) {
            AccountData data = accountCache.get(uuid);
            if (data != null) {
                return data.getBalance();
            }
        }
        AccountData data = accountCache.get(uuid);
        if (data != null) {
            // Dirty offline entries can represent an in-flight quit save. They remain
            // authoritative until persistence succeeds and must not be replaced by
            // an older database row.
            return data.getBalance();
        }
        CompletableFuture<AccountData> inFlight = inFlightAccountLoads.get(uuid);
        if (inFlight != null && inFlight.isDone()) {
            try {
                AccountData loaded = inFlight.getNow(null);
                if (loaded != null) {
                    return loaded.getBalance();
                }
            } catch (Exception ignored) {}
        }
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            // NEVER block the main server tick thread on database queries.
            getAccountAsync(uuid, null);
            double startBal = 0.0;
            try {
                if (plugin.getConfigService() != null && plugin.getConfigService().getEconomyConfig() != null) {
                    startBal = plugin.getConfigService().getEconomyConfig().getDouble("vault-provider.starting-balance", 0.0);
                }
            } catch (Throwable ignored) {}
            return startBal;
        }
        try {
            data = databaseManager.loadAccount(uuid, null).get(1500, TimeUnit.MILLISECONDS);
            if (data != null) {
                if (!onlinePlayerNames.containsKey(uuid)) {
                    data.setDirty(false);
                }
                accountCache.put(uuid, data);
                return data.getBalance();
            }
            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public boolean setBalance(UUID uuid, double amount, boolean broadcast) {
        return setBalance(uuid, null, amount, broadcast);
    }

    public boolean setBalance(UUID uuid, String username, double amount, boolean broadcast) {
        if (uuid == null || amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        if (onlinePlayerNames.containsKey(uuid)) {
            AccountData data = accountCache.get(uuid);
            if (data == null) {
                data = getAccount(uuid, username);
            }
            if (data == null) return false;
            data.setPlaceholder(false);
            data.setBalance(amount);
            if (username != null && !username.equalsIgnoreCase("Unknown")) {
                data.setUsername(username);
            }
            if (broadcast && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().broadcastBalanceUpdate(uuid, data.getUsername(), amount);
            }
            return true;
        }

        // Offline / remote set: resolve username safely so we never overwrite real names with "Unknown"
        String resolvedUsername = username;
        if (resolvedUsername == null || resolvedUsername.equalsIgnoreCase("Unknown")) {
            resolvedUsername = nameToUuidMap.entrySet().stream()
                    .filter(e -> uuid.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
        if (resolvedUsername == null || resolvedUsername.equalsIgnoreCase("Unknown")) {
            try {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                if (offline.getName() != null && !offline.getName().isBlank()) {
                    resolvedUsername = offline.getName();
                }
            } catch (Throwable ignored) {}
        }

        final String finalUsername = resolvedUsername != null ? resolvedUsername : "Unknown";

        AccountData cached = accountCache.get(uuid);
        if (cached != null) {
            cached.setPlaceholder(false);
            cached.setBalance(amount);
            if (!finalUsername.equalsIgnoreCase("Unknown")) {
                cached.setUsername(finalUsername);
            }
            cached.setLastUpdated(System.currentTimeMillis());
            long version = cached.getCurrentVersion();

            CompletableFuture<Void> saveFuture = executeChainedSave(uuid, () ->
                    databaseManager.setPlayerBalance(uuid, finalUsername, amount));
            trackAdjustment(saveFuture);

            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                saveFuture.whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        accountCache.remove(uuid);
                        plugin.getLogger().log(Level.WARNING, "Failed to asynchronously save account balance set for " + uuid, ex);
                    } else {
                        cached.markSaved(version);
                    }
                });
            } else {
                try {
                    saveFuture.get(1500, TimeUnit.MILLISECONDS);
                    cached.markSaved(version);
                } catch (Exception e) {
                    accountCache.remove(uuid);
                    plugin.getLogger().log(Level.WARNING, "Failed to save account balance set for " + uuid, e);
                    return false;
                }
            }
        } else {
            CompletableFuture<Void> saveFuture = executeChainedSave(uuid, () ->
                    databaseManager.setPlayerBalance(uuid, finalUsername, amount));
            trackAdjustment(saveFuture);

            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                saveFuture.whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        accountCache.remove(uuid);
                        plugin.getLogger().log(Level.WARNING, "Failed to asynchronously save uncached balance set for " + uuid, ex);
                    } else {
                        CompletableFuture<Void> reloadFuture = databaseManager.loadAccount(uuid, finalUsername).thenAccept(loaded -> {
                            if (loaded != null) {
                                accountCache.compute(uuid, (k, existing) -> {
                                    if (existing == null || existing.isPlaceholder()) return loaded;
                                    return existing;
                                });
                            }
                        });
                        trackAdjustment(reloadFuture);
                    }
                });
            } else {
                try {
                    saveFuture.get(1500, TimeUnit.MILLISECONDS);
                    AccountData loaded = databaseManager.loadAccount(uuid, finalUsername).get(1500, TimeUnit.MILLISECONDS);
                    if (loaded != null) {
                        accountCache.put(uuid, loaded);
                    }
                } catch (Exception e) {
                    accountCache.remove(uuid);
                    plugin.getLogger().log(Level.WARNING, "Failed to save uncached balance set for " + uuid, e);
                    return false;
                }
            }
        }

        if (broadcast && plugin.getRedisService().isEnabled()) {
            plugin.getRedisService().broadcastBalanceUpdate(uuid, finalUsername, amount);
            plugin.getRedisService().broadcastBalanceInvalidate(uuid);
        }
        return true;
    }

    public boolean deposit(UUID uuid, double amount, boolean broadcast) {
        return deposit(uuid, null, amount, broadcast);
    }

    public boolean deposit(UUID uuid, String username, double amount, boolean broadcast) {
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount) || uuid == null) return false;

        // 1. If player is online locally, local memory is the authoritative session
        if (onlinePlayerNames.containsKey(uuid)) {
            AccountData data = accountCache.get(uuid);
            if (data == null) {
                data = getAccount(uuid, username);
            }
            if (data == null) return false;
            boolean ok = data.deposit(amount);
            if (ok && broadcast && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().broadcastCrossServerDeposit(uuid, data.getUsername(), amount);
            }
            return ok;
        }

        // 2. If player is online on another server across the network, forward transaction delta
        if (plugin.getRedisService().isEnabled() && plugin.getRedisService().isPlayerOnlineOnNetwork(uuid)) {
            String name = username;
            if (name == null) {
                name = nameToUuidMap.entrySet().stream()
                        .filter(e -> uuid.equals(e.getValue()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
            }
            plugin.getRedisService().broadcastCrossServerDeposit(uuid, name, amount);
            accountCache.remove(uuid);
            return true;
        }

        // 3. Player is offline network-wide: atomically adjust in database storage
        AccountData cached = accountCache.get(uuid);
        if (cached != null) {
            boolean ok = cached.deposit(amount);
            if (!ok) return false;
            long version = cached.getCurrentVersion();

            CompletableFuture<Boolean> dbFuture = executeChainedAdjustment(uuid, () ->
                    databaseManager.atomicAdjustBalance(
                            uuid, username != null ? username : cached.getUsername(), amount, AccountData.MAX_BALANCE));
            trackAdjustment(dbFuture);

            dbFuture.whenComplete((applied, ex) -> {
                if (ex != null) {
                    plugin.getLogger().log(Level.WARNING, "Error executing atomic offline deposit for " + uuid + "; account remains dirty for autosave", ex);
                    cached.setDirty(true);
                } else if (Boolean.FALSE.equals(applied)) {
                    plugin.getLogger().log(Level.WARNING, "Atomic offline deposit rejected for " + uuid);
                    accountCache.remove(uuid);
                } else {
                    cached.markSaved(version);
                    if (broadcast && plugin.getRedisService().isEnabled()) {
                        plugin.getRedisService().broadcastBalanceInvalidate(uuid);
                    }
                }
            });

            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                return true;
            }

            try {
                Boolean applied = dbFuture.get(1500, TimeUnit.MILLISECONDS);
                return Boolean.TRUE.equals(applied);
            } catch (Exception e) {
                accountCache.remove(uuid);
                plugin.getLogger().log(Level.WARNING, "Timeout executing atomic offline deposit for " + uuid, e);
                return false;
            }
        }

        // Cold offline account: submit atomic adjustment to DB
        CompletableFuture<Boolean> dbFuture = executeChainedAdjustment(uuid, () ->
                databaseManager.atomicAdjustBalance(uuid, username, amount, AccountData.MAX_BALANCE));
        trackAdjustment(dbFuture);

        dbFuture.whenComplete((applied, ex) -> {
            if (Boolean.TRUE.equals(applied)) {
                CompletableFuture<Void> reloadFuture = databaseManager.loadAccount(uuid, username).thenAccept(loaded -> {
                    if (loaded != null) {
                        accountCache.compute(uuid, (k, existing) -> {
                            if (existing == null || existing.isPlaceholder()) return loaded;
                            if (existing.isDirty()) return existing;
                            if (existing.getLastUpdated() > loaded.getLastUpdated()) return existing;
                            if (existing.getLastUpdated() == loaded.getLastUpdated() && existing.getBalance() >= loaded.getBalance()) return existing;
                            return loaded;
                        });
                    }
                });
                trackAdjustment(reloadFuture);
                if (broadcast && plugin.getRedisService().isEnabled()) {
                    plugin.getRedisService().broadcastBalanceInvalidate(uuid);
                }
            } else {
                accountCache.remove(uuid);
                if (ex != null) {
                    plugin.getLogger().log(Level.WARNING, "Error executing cold atomic offline deposit for " + uuid, ex);
                }
            }
        });

        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            return true;
        }

        try {
            Boolean applied = dbFuture.get(1500, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(applied);
        } catch (Exception e) {
            accountCache.remove(uuid);
            plugin.getLogger().log(Level.WARNING, "Timeout executing cold atomic offline deposit for " + uuid, e);
            return false;
        }
    }

    public boolean withdraw(UUID uuid, double amount, boolean broadcast) {
        return withdraw(uuid, null, amount, broadcast);
    }

    public boolean withdraw(UUID uuid, String username, double amount, boolean broadcast) {
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount) || uuid == null) return false;

        // 1. If player is online locally, local memory is authoritative
        if (onlinePlayerNames.containsKey(uuid)) {
            AccountData data = accountCache.get(uuid);
            if (data == null) {
                data = getAccount(uuid, username);
            }
            if (data == null) return false;
            boolean ok = data.withdraw(amount);
            if (ok && broadcast && plugin.getRedisService().isEnabled()) {
                plugin.getRedisService().broadcastCrossServerWithdraw(uuid, data.getUsername(), amount);
            }
            return ok;
        }

        // 2. If player is online on another server across the network, forward transaction delta
        if (plugin.getRedisService().isEnabled() && plugin.getRedisService().isPlayerOnlineOnNetwork(uuid)) {
            String name = username;
            if (name == null) {
                name = nameToUuidMap.entrySet().stream()
                        .filter(e -> uuid.equals(e.getValue()))
                        .map(Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
            }
            plugin.getRedisService().broadcastCrossServerWithdraw(uuid, name, amount);
            accountCache.remove(uuid);
            return true;
        }

        // 3. Player is offline network-wide: atomically adjust in database storage with balance >= 0 check
        AccountData cached = accountCache.get(uuid);
        if (cached != null) {
            if (cached.getBalance() < amount) {
                return false;
            }
            boolean ok = cached.withdraw(amount);
            if (!ok) return false;
            long version = cached.getCurrentVersion();

            CompletableFuture<Boolean> dbFuture = executeChainedAdjustment(uuid, () ->
                    databaseManager.atomicAdjustBalance(
                            uuid, username != null ? username : cached.getUsername(), -amount, AccountData.MAX_BALANCE));
            trackAdjustment(dbFuture);

            dbFuture.whenComplete((applied, ex) -> {
                if (ex != null || Boolean.FALSE.equals(applied)) {
                    plugin.getLogger().log(Level.WARNING, "Error executing atomic offline withdraw for " + uuid, ex);
                    accountCache.remove(uuid);
                } else {
                    cached.markSaved(version);
                    if (broadcast && plugin.getRedisService().isEnabled()) {
                        plugin.getRedisService().broadcastBalanceInvalidate(uuid);
                    }
                }
            });

            if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
                return true;
            }

            try {
                Boolean applied = dbFuture.get(1500, TimeUnit.MILLISECONDS);
                return Boolean.TRUE.equals(applied);
            } catch (Exception e) {
                accountCache.remove(uuid);
                plugin.getLogger().log(Level.WARNING, "Timeout executing atomic offline withdraw for " + uuid, e);
                return false;
            }
        }

        // A synchronous caller cannot await a cold account on the server thread.
        // Load it for a retry without starting a debit that could outlive a failure response.
        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            getAccountAsync(uuid, username);
            return false;
        }

        // Cold offline account: must verify balance >= amount in DB via atomicAdjustBalance
        CompletableFuture<Boolean> dbFuture = executeChainedAdjustment(uuid, () ->
                databaseManager.atomicAdjustBalance(uuid, username, -amount, AccountData.MAX_BALANCE));
        trackAdjustment(dbFuture);

        try {
            boolean ok = dbFuture.get(1500, TimeUnit.MILLISECONDS);
            if (ok) {
                CompletableFuture<Void> reloadFuture = databaseManager.loadAccount(uuid, username).thenAccept(loaded -> {
                    if (loaded != null) {
                        accountCache.compute(uuid, (k, existing) -> {
                            if (existing == null || existing.isPlaceholder()) return loaded;
                            if (existing.isDirty()) return existing;
                            if (existing.getLastUpdated() > loaded.getLastUpdated()) return existing;
                            return loaded;
                        });
                    }
                });
                trackAdjustment(reloadFuture);
                if (broadcast && plugin.getRedisService().isEnabled()) {
                    plugin.getRedisService().broadcastBalanceInvalidate(uuid);
                }
            } else {
                accountCache.remove(uuid);
            }
            return ok;
        } catch (Exception e) {
            dbFuture.cancel(true);
            accountCache.remove(uuid);
            plugin.getLogger().log(Level.WARNING, "Error or timeout executing cold atomic offline withdraw for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Boolean> transfer(UUID senderUuid, String senderName, UUID receiverUuid, String receiverName, double amount) {
        if (senderUuid == null || receiverUuid == null || senderUuid.equals(receiverUuid) || amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        return getAccountAsync(senderUuid, senderName)
                .thenCombine(getAccountAsync(receiverUuid, receiverName), AccountPair::new)
                .thenCompose(accounts -> {
            AccountData sender = accounts.sender();
            AccountData receiver = accounts.receiver();
            if (sender == null || receiver == null) return CompletableFuture.completedFuture(false);

            return enqueueTransfer(senderUuid, senderName, receiverUuid, receiverName, amount, sender, receiver);
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Failed to load accounts for payment", error);
            return false;
        });
    }

    private CompletableFuture<Boolean> enqueueTransfer(UUID senderUuid, String senderName,
                                                        UUID receiverUuid, String receiverName,
                                                        double amount, AccountData sender,
                                                        AccountData receiver) {
        return transferQueue.submit(senderUuid, receiverUuid, () ->
                executeTransfer(senderUuid, senderName, receiverUuid, receiverName, amount, sender, receiver));
    }

    private CompletableFuture<Boolean> executeTransfer(UUID senderUuid, String senderName,
                                                       UUID receiverUuid, String receiverName,
                                                       double amount, AccountData sender,
                                                       AccountData receiver) {

        // Lock accounts in deterministic UUID order to eliminate potential deadlocks.
        // The per-account future queue above keeps another payment from spending the
        // receiver credit before this transfer has either persisted or rolled back.
        AccountData firstLock = senderUuid.compareTo(receiverUuid) < 0 ? sender : receiver;
        AccountData secondLock = senderUuid.compareTo(receiverUuid) < 0 ? receiver : sender;

        synchronized (firstLock) {
            synchronized (secondLock) {
                if (receiver.getBalance() + amount > AccountData.MAX_BALANCE) {
                    return CompletableFuture.completedFuture(false);
                }
                if (!sender.withdraw(amount)) return CompletableFuture.completedFuture(false);
                if (!receiver.deposit(amount)) {
                    sender.deposit(amount);
                    return CompletableFuture.completedFuture(false);
                }
            }
        }

        CompletableFuture<Void> persistence;
        try {
            persistence = databaseManager.saveAccountsBatch(List.of(sender, receiver));
        } catch (RuntimeException submissionFailure) {
            rollbackTransfer(firstLock, secondLock, sender, receiver, amount, submissionFailure);
            return CompletableFuture.completedFuture(false);
        }

        return persistence.handle((ignored, persistenceFailure) -> {
            if (persistenceFailure != null) {
                rollbackTransfer(firstLock, secondLock, sender, receiver, amount, persistenceFailure);
                return false;
            }

            Transaction tx = new Transaction(0, senderUuid, senderName, receiverUuid, receiverName,
                    amount, Transaction.Type.PAYMENT, System.currentTimeMillis());
            try {
                        databaseManager.logTransaction(tx);
            } catch (RuntimeException auditFailure) {
                plugin.getLogger().log(Level.WARNING, "Payment succeeded but transaction audit logging was rejected", auditFailure);
            }

            try {
                        if (plugin.getRedisService().isEnabled()) {
                            plugin.getRedisService().broadcastBalanceUpdate(senderUuid, senderName, sender.getBalance());
                            plugin.getRedisService().broadcastCrossServerPay(receiverUuid, receiverName, amount,
                                    receiver.getBalance(), senderName);
                        }
            } catch (RuntimeException broadcastFailure) {
                plugin.getLogger().log(Level.WARNING, "Payment persisted but its Redis notification failed", broadcastFailure);
            }
            return true;
        });
    }

    private void rollbackTransfer(AccountData firstLock, AccountData secondLock,
                                  AccountData sender, AccountData receiver,
                                  double amount, Throwable error) {
        boolean receiverRolledBack;
        synchronized (firstLock) {
            synchronized (secondLock) {
                receiverRolledBack = receiver.withdraw(amount);
                if (receiverRolledBack) {
                    sender.deposit(amount);
                }
            }
        }
        if (!receiverRolledBack) {
            plugin.getLogger().log(Level.SEVERE,
                    "Payment persistence failed and rollback could not reclaim receiver funds; accounts remain dirty for recovery",
                    error);
            return;
        }
        plugin.getLogger().log(Level.SEVERE,
                "Payment persistence failed; in-memory transfer rolled back", error);
    }

    private record AccountPair(AccountData sender, AccountData receiver) {}

    private CompletableFuture<List<AccountData>> inFlightBaltop = null;

    public synchronized CompletableFuture<List<AccountData>> getBaltop(int limit) {
        int cacheDuration = plugin.getConfigService().getMainConfig().getInt("optimization.baltop-cache-seconds", 30);
        long now = System.currentTimeMillis();

        if (baltopCache != null && !baltopCache.isEmpty() && (now - lastBaltopRefresh < cacheDuration * 1000L)) {
            return CompletableFuture.completedFuture(baltopCache);
        }

        if (inFlightBaltop != null && !inFlightBaltop.isDone()) {
            return inFlightBaltop;
        }

        inFlightBaltop = databaseManager.getTopBalances(limit).thenApply(list -> {
            this.baltopCache = list;
            this.lastBaltopRefresh = System.currentTimeMillis();
            return list;
        });

        return inFlightBaltop;
    }

    public void cleanupOfflineAccounts() {
        long now = System.currentTimeMillis();
        boolean aggressive = accountCache.size() > 10000;
        long maxIdle = aggressive ? 300_000L : 1_800_000L;
        accountCache.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            AccountData data = entry.getValue();
            if (onlinePlayerNames.containsKey(uuid) || data.isDirty()) {
                return false;
            }
            return now - data.getLastUpdated() > maxIdle;
        });

        if (nameToUuidMap.size() > 10000) {
            nameToUuidMap.clear();
            onlinePlayerNames.forEach((uuid, name) -> nameToUuidMap.put(name.toLowerCase(Locale.ROOT), uuid));
        }
    }

    public void invalidateBaltopCache() {
        this.lastBaltopRefresh = 0;
    }

    public void clearLocalCache() {
        accountCache.clear();
        baltopCache.clear();
    }

    public boolean isOnlineLocally(UUID uuid) {
        return uuid != null && onlinePlayerNames.containsKey(uuid);
    }

    public CompletableFuture<Void> savePlayerAccount(UUID uuid) {
        AccountData account = accountCache.get(uuid);
        return account == null || account.isPlaceholder() ? CompletableFuture.completedFuture(null) : queueAccountSave(account);
    }

    public CompletableFuture<Void> syncOnlineAccountFromDatabase(UUID uuid) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> pendingSave = inFlightAccountSaves.get(uuid);
        CompletableFuture<Void> base = pendingSave != null
                ? pendingSave.exceptionally(e -> null)
                : CompletableFuture.completedFuture(null);

        CompletableFuture<Void> syncFuture = base.thenCompose(v -> databaseManager.loadAccount(uuid, null)).thenAccept(loaded -> {
            if (loaded == null) return;
            accountCache.computeIfPresent(uuid, (key, existing) -> {
                if (existing.isPlaceholder()) {
                    return loaded;
                }
                if (!existing.isDirty() || loaded.getLastUpdated() >= existing.getLastUpdated()) {
                    existing.setBalance(loaded.getBalance());
                    existing.setLastUpdated(loaded.getLastUpdated());
                    existing.markSaved(existing.getCurrentVersion());
                }
                return existing;
            });
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reload account for " + uuid + " on balance invalidate", e);
            return null;
        });
        trackAdjustment(syncFuture);
        return syncFuture;
    }

    public CompletableFuture<Void> saveAllDirty() {
        List<AccountData> dirtyAccounts = new ArrayList<>();
        for (AccountData data : accountCache.values()) {
            if (data.isDirty()) {
                dirtyAccounts.add(data);
            }
        }
        if (!dirtyAccounts.isEmpty()) {
            return databaseManager.saveAccountsBatch(dirtyAccounts);
        }
        return CompletableFuture.completedFuture(null);
    }

    public void saveAllDirtySync() {
        List<AccountData> dirtyAccounts = new ArrayList<>();
        for (AccountData data : accountCache.values()) {
            if (data.isDirty()) {
                dirtyAccounts.add(data);
            }
        }
        if (!dirtyAccounts.isEmpty()) {
            databaseManager.saveAccountsBatchSync(dirtyAccounts);
        }
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            plugin.getScheduler().cancel(autoSaveTask);
            autoSaveTask = null;
        }
        try {
            long deadline = System.currentTimeMillis() + 8000L;
            while (!inFlightAccountSaves.isEmpty() || !inFlightAtomicAdjustments.isEmpty() || !inFlightAccountLoads.isEmpty()) {
                List<CompletableFuture<?>> allPending = new ArrayList<>();
                allPending.addAll(inFlightAccountSaves.values());
                allPending.addAll(inFlightAtomicAdjustments);
                allPending.addAll(inFlightAccountLoads.values());

                if (allPending.isEmpty()) {
                    break;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    plugin.getLogger().warning("Timed out waiting for in-flight economy saves during shutdown; forcing synchronous batch save.");
                    break;
                }
                try {
                    CompletableFuture.allOf(allPending.toArray(CompletableFuture[]::new))
                            .handle((ignored, error) -> null)
                            .get(Math.min(remaining, 2000L), TimeUnit.MILLISECONDS);
                } catch (Exception timeoutOrInterrupted) {
                    plugin.getLogger().warning("Interrupted or timed out waiting for in-flight economy operations during shutdown: " + timeoutOrInterrupted.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error while draining in-flight economy operations during shutdown", e);
        } finally {
            try {
                saveAllDirtySync();
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Failed to synchronously save dirty accounts during shutdown", ex);
            }
        }
    }
}
