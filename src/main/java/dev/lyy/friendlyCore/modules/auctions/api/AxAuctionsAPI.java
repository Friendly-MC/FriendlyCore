package dev.lyy.friendlycore.modules.auctions.api;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Public programmatic API for AxAuctions integration compatibility.
 */
public class AxAuctionsAPI {

    private static FriendlyCore getPlugin() {
        return FriendlyCore.getInstance();
    }

    public static int getAuctionLimit(@NotNull Player player) {
        if (getPlugin() == null || getPlugin().getAuctionsModule() == null) return 5;
        return getPlugin().getAuctionsModule().getAuctionManager().getPlayerListingLimit(player);
    }

    public static int getAuctionItemAmount(@NotNull Player player) {
        if (getPlugin() == null || getPlugin().getAuctionsModule() == null) return 0;
        return getPlugin().getAuctionsModule().getAuctionManager().getPlayerActiveItemCount(player.getUniqueId());
    }

    public static AuctionManager getAuctionManager() {
        if (getPlugin() == null || getPlugin().getAuctionsModule() == null) return null;
        return getPlugin().getAuctionsModule().getAuctionManager();
    }
}
