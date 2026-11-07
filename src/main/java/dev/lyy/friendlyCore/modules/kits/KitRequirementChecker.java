package dev.lyy.friendlycore.modules.kits;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.model.KitRequirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Validates kit purchase requirements, placeholder conditions, and economy prices.
 */
public final class KitRequirementChecker {

    private final FriendlyCore plugin;

    public KitRequirementChecker(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public boolean meetsRequirements(Player player, KitRequirements requirements) {
        if (player == null || requirements == null) return true;

        // Check economy balance if a price is configured
        if (requirements.getPrice() > 0) {
            double bal = getPlayerBalance(player);
            if (bal < requirements.getPrice()) {
                return false;
            }
        }

        List<String> conditions = requirements.getExtraRequirements();
        if (conditions != null && !conditions.isEmpty()) {
            for (String cond : conditions) {
                if (!evaluateCondition(player, cond)) {
                    return false;
                }
            }
        }

        return true;
    }

    public boolean chargePrice(Player player, double price) {
        if (price <= 0 || player == null || plugin == null) return true;

        if (plugin.getHookManager().isVaultEconomyAvailable()) {
            net.milkbowl.vault.economy.Economy vaultEco = plugin.getHookManager().getVaultEconomy();
            if (vaultEco != null) {
                net.milkbowl.vault.economy.EconomyResponse resp = vaultEco.withdrawPlayer(player, price);
                return resp != null && resp.transactionSuccess();
            }
        }

        if (plugin.getEconomyManager() != null) {
            return plugin.getEconomyManager().withdraw(player.getUniqueId(), price, true);
        }

        return true;
    }

    public double getPlayerBalance(Player player) {
        if (player == null || plugin == null) return 0.0;

        if (plugin.getHookManager().isVaultEconomyAvailable()) {
            net.milkbowl.vault.economy.Economy vaultEco = plugin.getHookManager().getVaultEconomy();
            if (vaultEco != null) {
                return vaultEco.getBalance(player);
            }
        }

        if (plugin.getEconomyManager() != null) {
            var account = plugin.getEconomyManager().getCachedAccount(player.getUniqueId());
            return account != null ? account.getBalance() : 0.0;
        }

        return 0.0;
    }

    public boolean evaluateCondition(Player player, String rawCondition) {
        if (rawCondition == null || rawCondition.trim().isEmpty()) return true;

        String cond = rawCondition;
        if (player != null) {
            cond = cond.replace("%player%", player.getName())
                    .replace("%player_name%", player.getName());
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                cond = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, cond);
            }
        }

        String[] operators = {">=", "<=", "!=", "==", ">", "<", "equals", "contains", "!contains"};
        for (String op : operators) {
            int idx = cond.indexOf(" " + op + " ");
            if (idx != -1) {
                String left = cond.substring(0, idx).trim();
                String right = cond.substring(idx + op.length() + 2).trim();
                return compare(left, op, right);
            }
        }

        return true;
    }

    private boolean compare(String left, String op, String right) {
        try {
            double leftNum = Double.parseDouble(left);
            double rightNum = Double.parseDouble(right);

            return switch (op) {
                case ">=" -> leftNum >= rightNum;
                case "<=" -> leftNum <= rightNum;
                case ">" -> leftNum > rightNum;
                case "<" -> leftNum < rightNum;
                case "==", "equals" -> Math.abs(leftNum - rightNum) < 0.0001;
                case "!=" -> Math.abs(leftNum - rightNum) >= 0.0001;
                default -> false;
            };
        } catch (NumberFormatException ignored) {}

        return switch (op) {
            case "==", "equals" -> left.equalsIgnoreCase(right);
            case "!=" -> !left.equalsIgnoreCase(right);
            case "contains" -> left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case "!contains" -> !left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            default -> false;
        };
    }
}
