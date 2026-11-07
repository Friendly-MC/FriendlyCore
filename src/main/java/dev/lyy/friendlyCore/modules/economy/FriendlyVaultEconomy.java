package dev.lyy.friendlycore.modules.economy;

import dev.lyy.friendlycore.FriendlyCore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Vault {@link Economy} implementation backed by FriendlyCore's EconomyManager.
 */
public class FriendlyVaultEconomy implements Economy {

    private final FriendlyCore plugin;
    private final EconomyManager economyManager;

    public FriendlyVaultEconomy(FriendlyCore plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled() && economyManager != null;
    }

    @Override
    public String getName() {
        return "FriendlyCore-Economy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return plugin.getConfigService().getEconomyConfig().getInt("format.decimal-places", 2);
    }

    @Override
    public String format(double amount) {
        boolean useSuffixes = plugin.getConfigService().getEconomyConfig().getBoolean("vault-provider.use-short-scale", false);
        return useSuffixes ? plugin.getNumberFormatter().format(amount) : plugin.getNumberFormatter().formatExact(amount);
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfigService().getEconomyConfig().getString("vault-provider.currency-plural", "dollars");
    }

    @Override
    public String currencyNameSingular() {
        return plugin.getConfigService().getEconomyConfig().getString("vault-provider.currency-singular", "dollar");
    }

    private OfflinePlayer resolvePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return online;
        UUID cached = economyManager.getNameToUuidMap().get(playerName.toLowerCase(java.util.Locale.ROOT));
        if (cached != null) {
            return Bukkit.getOfflinePlayer(cached);
        }
        if (plugin.getRedisService() != null && plugin.getRedisService().isEnabled()) {
            UUID netUuid = plugin.getRedisService().getNetworkPlayerUuid(playerName);
            if (netUuid != null) {
                economyManager.getNameToUuidMap().put(playerName.toLowerCase(java.util.Locale.ROOT), netUuid);
                return Bukkit.getOfflinePlayer(netUuid);
            }
        }
        // The name overload can perform a web lookup. Vault callers commonly run
        // on tick threads, so unknown names must fail without blocking or inventing a UUID.
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(playerName);
        if (offline != null) {
            economyManager.getNameToUuidMap().put(playerName.toLowerCase(java.util.Locale.ROOT), offline.getUniqueId());
        }
        return offline;
    }

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer offlinePlayer = resolvePlayer(playerName);
        return hasAccount(offlinePlayer);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) return false;
        return economyManager.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer offlinePlayer = resolvePlayer(playerName);
        return getBalance(offlinePlayer);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) return 0.0;
        return economyManager.getBalance(player.getUniqueId());
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (player == null || player.getUniqueId() == null || amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return false;
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer offlinePlayer = resolvePlayer(playerName);
        return withdrawPlayer(offlinePlayer, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null || player.getUniqueId() == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Amount cannot be negative or invalid");
        }
        if (amount == 0.0) {
            return new EconomyResponse(0.0, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        }
        if (player.isOnline() && player.getPlayer() != null && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player.getPlayer())) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Player data synchronizing across network");
        }
        AccountData cached = economyManager.getCachedAccount(player.getUniqueId());
        double prevBal = cached != null ? cached.getBalance() : -1.0;
        boolean success = economyManager.withdraw(player.getUniqueId(), player.getName(), amount, true);
        AccountData afterCached = economyManager.getCachedAccount(player.getUniqueId());
        double bal;
        if (success) {
            if (afterCached != null) {
                bal = afterCached.getBalance();
            } else if (prevBal >= 0) {
                bal = prevBal - amount;
            } else {
                bal = economyManager.getBalance(player.getUniqueId());
            }
            return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            if (afterCached != null) {
                bal = afterCached.getBalance();
            } else if (prevBal >= 0) {
                bal = prevBal;
            } else {
                bal = economyManager.getBalance(player.getUniqueId());
            }
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer offlinePlayer = resolvePlayer(playerName);
        return depositPlayer(offlinePlayer, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null || player.getUniqueId() == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Player is null");
        }
        if (amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Amount cannot be negative or invalid");
        }
        if (amount == 0.0) {
            return new EconomyResponse(0.0, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
        }
        if (player.isOnline() && player.getPlayer() != null && plugin.getHookManager().getMcDataBridgeHook().isPlayerLocked(player.getPlayer())) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Player data synchronizing across network");
        }
        AccountData cached = economyManager.getCachedAccount(player.getUniqueId());
        double prevBal = cached != null ? cached.getBalance() : -1.0;
        boolean success = economyManager.deposit(player.getUniqueId(), player.getName(), amount, true);
        AccountData afterCached = economyManager.getCachedAccount(player.getUniqueId());
        double bal;
        if (success) {
            if (afterCached != null) {
                bal = afterCached.getBalance();
            } else if (prevBal >= 0) {
                bal = prevBal + amount;
            } else {
                bal = economyManager.getBalance(player.getUniqueId());
            }
            return new EconomyResponse(amount, bal, EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            if (afterCached != null) {
                bal = afterCached.getBalance();
            } else if (prevBal >= 0) {
                bal = prevBal;
            } else {
                bal = economyManager.getBalance(player.getUniqueId());
            }
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Could not deposit");
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer offlinePlayer = resolvePlayer(playerName);
        return createPlayerAccount(offlinePlayer);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) return false;
        AccountData account = economyManager.getCachedAccount(player.getUniqueId());
        if (account != null) return true;
        return economyManager.getAccount(player.getUniqueId(), player.getName()) != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override public EconomyResponse createBank(String name, String player) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse deleteBank(String name) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse bankBalance(String name) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse bankHas(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks not supported"); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
