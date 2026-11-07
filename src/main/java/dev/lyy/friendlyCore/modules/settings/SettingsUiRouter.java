package dev.lyy.friendlycore.modules.settings;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.provider.*;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Intelligent UI Router that directs players to the best interface provider
 * based on their client type (Dialog vs Bedrock Form vs Legacy Chest GUI vs Chat).
 */
public class SettingsUiRouter {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    private final Map<String, SettingsMenuProvider> providers = new HashMap<>();
    private final JavaDialogSettingsProvider dialogProvider;
    private final BedrockSettingsProvider bedrockProvider;
    private final LegacyChestGuiSettingsProvider chestGuiProvider;
    private final ChatSettingsProvider chatProvider;

    public SettingsUiRouter(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;

        this.dialogProvider = new JavaDialogSettingsProvider(plugin, settingsManager);
        this.bedrockProvider = new BedrockSettingsProvider(plugin, settingsManager);
        this.chestGuiProvider = new LegacyChestGuiSettingsProvider(plugin, settingsManager);
        this.chatProvider = new ChatSettingsProvider(plugin, settingsManager);

        registerProvider(dialogProvider);
        registerProvider(bedrockProvider);
        registerProvider(chestGuiProvider);
        registerProvider(chatProvider);
    }

    public void registerProvider(SettingsMenuProvider provider) {
        providers.put(provider.getName().toUpperCase(), provider);
    }

    public void open(Player player) {
        open(player, null);
    }

    public void open(Player player, String requestedMode) {
        if (player == null || !player.isOnline()) return;

        if (requestedMode != null && !requestedMode.equalsIgnoreCase("AUTO")) {
            SettingsMenuProvider explicit = providers.get(requestedMode.toUpperCase());
            if (explicit != null && explicit.isSupported(player)) {
                explicit.openSettings(player);
                return;
            }
        }

        String configuredMode = plugin.getConfigService().getSettingsConfig() != null
                ? plugin.getConfigService().getSettingsConfig().getString("ui.default-mode", "AUTO")
                : "AUTO";

        if (!configuredMode.equalsIgnoreCase("AUTO")) {
            SettingsMenuProvider configured = providers.get(configuredMode.toUpperCase());
            if (configured != null && configured.isSupported(player)) {
                configured.openSettings(player);
                return;
            }
        }

        // Auto-detect best UI provider for player's client platform
        if (bedrockProvider.isSupported(player)) {
            bedrockProvider.openSettings(player);
            return;
        }

        // Prefer modern Dialogs for compatible Java clients
        if (dialogProvider.isSupported(player)) {
            dialogProvider.openSettings(player);
            return;
        }

        chestGuiProvider.openSettings(player);
    }

    public JavaDialogSettingsProvider getDialogProvider() {
        return dialogProvider;
    }

    public BedrockSettingsProvider getBedrockProvider() {
        return bedrockProvider;
    }

    public LegacyChestGuiSettingsProvider getChestGuiProvider() {
        return chestGuiProvider;
    }

    public ChatSettingsProvider getChatProvider() {
        return chatProvider;
    }
}
