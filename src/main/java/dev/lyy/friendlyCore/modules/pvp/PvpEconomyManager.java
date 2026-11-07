package dev.lyy.friendlycore.modules.pvp;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.economy.FriendlyVaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Handles PvP kill rewards, death penalties, and balance stealing via Vault or internal economy.
 */
public class PvpEconomyManager {

    private final FriendlyCore plugin;
    private Economy vaultEconomy;
    private final Cache<UUID, PvpEconomyRecord> lastPvpRecords = CacheBuilder.newBuilder()
            .maximumSize(4096)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    public PvpEconomyManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        setupEconomy();
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            try {
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                if (rsp != null) {
                    this.vaultEconomy = rsp.getProvider();
                }
            } catch (Throwable ignored) {}
        }
    }

    public record PvpEconomyResult(double killerGained, double victimLost, String formattedGained, String formattedLost) {
        public static final PvpEconomyResult ZERO = new PvpEconomyResult(0.0, 0.0, "0", "0");
    }

    public record PvpEconomyRecord(String victimName, String killerName, double moneyLost, double moneyGained,
                                  String formattedLost, String formattedGained, long timestamp) {
        public static final PvpEconomyRecord EMPTY = new PvpEconomyRecord("", "", 0.0, 0.0, "0", "0", 0L);
    }

    public PvpEconomyRecord getLastRecord(UUID playerUuid) {
        if (playerUuid == null) return PvpEconomyRecord.EMPTY;
        PvpEconomyRecord record = lastPvpRecords.getIfPresent(playerUuid);
        return record != null ? record : PvpEconomyRecord.EMPTY;
    }

    public double getBalance(OfflinePlayer player) {
        if (player == null) return 0.0;
        if (vaultEconomy == null) {
            setupEconomy();
        }
        if (vaultEconomy != null && !(vaultEconomy instanceof FriendlyVaultEconomy)) {
            try {
                return vaultEconomy.getBalance(player);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not read PvP economy balance for " + player.getUniqueId(), error);
                return 0.0;
            }
        }
        if (plugin.getEconomyManager() != null) {
            return plugin.getEconomyManager().getBalance(player.getUniqueId());
        }
        return 0.0;
    }

    private boolean withdraw(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0.0 || !Double.isFinite(amount)) return false;
        if (vaultEconomy == null) {
            setupEconomy();
        }
        if (vaultEconomy != null && !(vaultEconomy instanceof FriendlyVaultEconomy)) {
            try {
                return vaultEconomy.withdrawPlayer(player, amount).transactionSuccess();
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "PvP withdrawal failed for " + player.getUniqueId(), error);
                return false;
            }
        }
        return plugin.getEconomyManager() != null
                && plugin.getEconomyManager().withdraw(player.getUniqueId(), amount, true);
    }

    private boolean deposit(OfflinePlayer player, double amount) {
        if (player == null || amount <= 0.0 || !Double.isFinite(amount)) return false;
        if (vaultEconomy == null) {
            setupEconomy();
        }
        if (vaultEconomy != null && !(vaultEconomy instanceof FriendlyVaultEconomy)) {
            try {
                return vaultEconomy.depositPlayer(player, amount).transactionSuccess();
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "PvP deposit failed for " + player.getUniqueId(), error);
                return false;
            }
        }
        return plugin.getEconomyManager() != null
                && plugin.getEconomyManager().deposit(player.getUniqueId(), amount, true);
    }

    public CompletableFuture<PvpEconomyResult> handlePlayerKill(Player killer, OfflinePlayer victim) {
        if (killer == null || victim == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return CompletableFuture.completedFuture(PvpEconomyResult.ZERO);
        }

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("player-kills.enabled", true)) return CompletableFuture.completedFuture(PvpEconomyResult.ZERO);

        if (victim.isOnline() && victim.getPlayer() != null && victim.getPlayer().hasPermission("friendlycore.pvp.economy.bypass")) {
            return CompletableFuture.completedFuture(PvpEconomyResult.ZERO);
        }

        if (vaultEconomy == null) setupEconomy();
        double rewardConfig = config.getDouble("player-kills.money-reward", 0.7);
        double penaltyConfig = config.getDouble("player-kills.money-penalty", 0.6);
        boolean isSteal = config.getBoolean("player-kills.money-steal", true);
        var internalEconomy = plugin.getEconomyManager();
        CompletableFuture<PvpEconomyResult> result;
        if ((vaultEconomy == null || vaultEconomy instanceof FriendlyVaultEconomy) && internalEconomy != null) {
            result = internalEconomy.getAccountAsync(victim.getUniqueId(), victim.getName()).thenCompose(account -> {
                double balance = account != null ? account.getBalance() : 0.0;
                if (isSteal) {
                    double amount = configuredAmount(rewardConfig, balance, true);
                    if (amount <= 0) return CompletableFuture.completedFuture(recordResult(killer, victim, 0, 0, true));
                    return internalEconomy.transfer(victim.getUniqueId(), victim.getName(), killer.getUniqueId(), killer.getName(), amount)
                            .thenApply(success -> recordResult(killer, victim, success ? amount : 0, success ? amount : 0, true));
                }
                return CompletableFuture.supplyAsync(
                        () -> settleKill(killer, victim, balance, rewardConfig, penaltyConfig, false),
                        task -> plugin.getScheduler().runAsync(task));
            });
        } else {
            result = CompletableFuture.completedFuture(settleKill(killer, victim, getBalance(victim), rewardConfig, penaltyConfig, isSteal));
        }
        return result.exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "PvP economy transaction failed for " + victim.getUniqueId(), error);
            return PvpEconomyResult.ZERO;
        });
    }

    private double configuredAmount(double configured, double balance, boolean clampToBalance) {
        if (!Double.isFinite(configured) || configured <= 0 || !Double.isFinite(balance) || balance < 0) return 0;
        double amount = configured <= 1 ? balance * configured : configured;
        return clampToBalance ? Math.min(balance, amount) : amount;
    }

    private PvpEconomyResult settleKill(Player killer, OfflinePlayer victim, double balance,
                                      double reward, double penalty, boolean steal) {
        double gained = configuredAmount(reward, balance, steal);
        double lost = steal ? gained : configuredAmount(penalty, balance, true);
        if (steal) {
            if (!withdraw(victim, lost)) return recordResult(killer, victim, 0, 0, true);
            if (!deposit(killer, gained)) {
                if (deposit(victim, lost)) return recordResult(killer, victim, 0, 0, true);
                plugin.getLogger().severe("PvP refund failed: restore " + lost + " to " + victim.getUniqueId());
                return recordResult(killer, victim, 0, lost, true);
            }
        } else {
            if (!withdraw(victim, lost)) lost = 0;
            if (!deposit(killer, gained)) gained = 0;
        }
        return recordResult(killer, victim, gained, lost, steal);
    }

    private PvpEconomyResult recordResult(Player killer, OfflinePlayer victim, double gained, double lost, boolean steal) {
        String victimName = victim.getName() != null ? victim.getName() : "Unknown";
        String killerName = killer.getName();
        String formattedLost = plugin.getNumberFormatter().format(lost);
        String formattedGained = plugin.getNumberFormatter().format(gained);
        long now = System.currentTimeMillis();
        lastPvpRecords.put(victim.getUniqueId(), new PvpEconomyRecord(victimName, killerName, lost, 0, formattedLost, "0", now));
        lastPvpRecords.put(killer.getUniqueId(), new PvpEconomyRecord(victimName, killerName, 0, gained, "0", formattedGained, now));
        if (gained > 0) {
            plugin.getScheduler().runEntity(killer, () -> plugin.getTextService().sendMessage(killer,
                    steal ? "economy.kill-steal-killer" : "economy.kill-reward",
                    Map.of("%amount%", formattedGained, "%victim%", victimName)));
        }
        Player victimPlayer = victim.getPlayer();
        if (lost > 0 && victimPlayer != null) {
            String message = steal ? (gained > 0 ? "economy.kill-steal-victim" : "economy.refund-failed") : "economy.death-penalty";
            plugin.getScheduler().runEntity(victimPlayer, () -> plugin.getTextService().sendMessage(victimPlayer,
                    message, Map.of("%amount%", formattedLost, "%killer%", killerName)));
        }
        return new PvpEconomyResult(gained, lost, formattedGained, formattedLost);
    }
}
