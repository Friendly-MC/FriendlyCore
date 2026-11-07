package dev.lyy.friendlycore.modules.settings.provider;

import org.bukkit.entity.Player;

/**
 * Interface for all Settings UI rendering providers.
 */
public interface SettingsMenuProvider {

    /**
     * Unique identifier for the provider (e.g. "DIALOG", "BEDROCK", "CHEST_GUI", "CHAT").
     */
    String getName();

    /**
     * Check if this provider is applicable/supported for the given player.
     */
    boolean isSupported(Player player);

    /**
     * Opens the settings interface for the player.
     */
    void openSettings(Player player);
}
