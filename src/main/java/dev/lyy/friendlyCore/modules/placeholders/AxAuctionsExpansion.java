package dev.lyy.friendlycore.modules.placeholders;

import dev.lyy.friendlycore.FriendlyCore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Drop-in AxAuctions PlaceholderAPI expansion for 100% backward compatibility.
 */
public class AxAuctionsExpansion extends PlaceholderExpansion {

    private final FriendlyCore plugin;
    private final FriendlyExpansion friendlyExpansion;

    public AxAuctionsExpansion(FriendlyCore plugin, FriendlyExpansion friendlyExpansion) {
        this.plugin = plugin;
        this.friendlyExpansion = friendlyExpansion;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "axauctions";
    }

    @Override
    public @NotNull String getAuthor() {
        return "FriendlyDev";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        return friendlyExpansion.onRequest(offlinePlayer, "axauctions_" + params);
    }
}
