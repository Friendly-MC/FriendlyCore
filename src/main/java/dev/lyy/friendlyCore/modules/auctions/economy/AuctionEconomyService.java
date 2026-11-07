package dev.lyy.friendlycore.modules.auctions.economy;

import dev.lyy.friendlycore.FriendlyCore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.Locale;

/**
 * Handles tax-free Vault economy transactions.
 */
public class AuctionEconomyService {

    private final FriendlyCore plugin;

    public AuctionEconomyService(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    private Economy getVault() {
        if (plugin.getHookManager() != null && plugin.getHookManager().getVaultEconomy() != null) {
            return plugin.getHookManager().getVaultEconomy();
        }
        return null;
    }

    public boolean hasEconomy() {
        return getVault() != null;
    }

    public double getBalance(Player player) {
        Economy eco = getVault();
        if (eco == null || player == null) return 0.0;
        return eco.getBalance(player);
    }

    public double getBalance(UUID uuid) {
        Economy eco = getVault();
        if (eco == null || uuid == null) return 0.0;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return eco.getBalance(offline);
    }

    public boolean hasBalance(Player player, double amount) {
        return hasEconomy() && Double.isFinite(amount) && amount > 0.0 && getBalance(player) >= amount;
    }

    public boolean withdraw(Player player, double amount) {
        Economy eco = getVault();
        if (eco == null || player == null || !Double.isFinite(amount) || amount <= 0.0) return false;
        return eco.withdrawPlayer(player, amount).transactionSuccess();
    }

    public boolean withdraw(UUID uuid, double amount) {
        Economy eco = getVault();
        if (eco == null || uuid == null || !Double.isFinite(amount) || amount <= 0.0) return false;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return eco.withdrawPlayer(offline, amount).transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        Economy eco = getVault();
        if (eco == null || player == null || !Double.isFinite(amount) || amount <= 0.0) return false;
        return eco.depositPlayer(player, amount).transactionSuccess();
    }

    public boolean deposit(UUID uuid, double amount) {
        Economy eco = getVault();
        if (eco == null || uuid == null || !Double.isFinite(amount) || amount <= 0.0) return false;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return eco.depositPlayer(offline, amount).transactionSuccess();
    }

    /** Formats money exactly as the configured Vault provider exposes it. */
    public String format(double amount) {
        Economy eco = getVault();
        if (eco != null && Double.isFinite(amount)) {
            try {
                String formatted = eco.format(amount);
                if (formatted != null && !formatted.isBlank()) return formatted;
            } catch (RuntimeException ignored) {
                // A broken formatter must not interrupt an auction transaction.
            }
        }
        return String.format(Locale.US, "%,.2f", amount);
    }

}
