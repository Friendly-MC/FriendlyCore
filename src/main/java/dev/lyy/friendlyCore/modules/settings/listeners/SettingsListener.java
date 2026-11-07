package dev.lyy.friendlycore.modules.settings.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import dev.lyy.friendlycore.modules.settings.events.PlayerSettingChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Event listener for player lifecycle and setting change reactions.
 */
public class SettingsListener implements Listener {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    public SettingsListener(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        settingsManager.loadPlayer(player.getUniqueId(), player.getName()).thenAccept(settings -> {
            if (settings == null) return;

            if (settings.get(SettingType.NIGHT_VISION)) {
                plugin.getScheduler().runEntity(player, () -> {
                    if (plugin.getNightVisionManager() != null) {
                        plugin.getNightVisionManager().enable(player, player, true);
                    }
                });
            }

            // Sync Chat & Private Messages states
            if (plugin.getChatModule() != null) {
                boolean chatEnabled = settings.get(SettingType.CHAT);
                plugin.getChatModule().setChatToggled(player.getUniqueId(), !chatEnabled);

                boolean pmEnabled = settings.get(SettingType.PRIVATE_MESSAGES);
                var chatData = plugin.getChatModule().getPlayerData(player.getUniqueId());
                if (chatData != null) {
                    chatData.setMessagesClosed(!pmEnabled);
                }
            }

            // Sync TPA state
            if (plugin.getTpaManager() != null) {
                var profile = plugin.getTpaManager().getProfile(player);
                if (profile != null) {
                    profile.setTpaEnabled(settings.get(SettingType.TPA));
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        settingsManager.unloadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSettingChange(PlayerSettingChangeEvent event) {
        Player player = event.getPlayer();
        SettingType setting = event.getSetting();
        boolean enabled = event.getNewValue();

        switch (setting) {
            case NIGHT_VISION -> {
                plugin.getScheduler().runEntity(player, () -> {
                    if (plugin.getNightVisionManager() != null) {
                        if (enabled) {
                            plugin.getNightVisionManager().enable(player, player, true);
                        } else {
                            plugin.getNightVisionManager().disable(player, player, true);
                        }
                    }
                });
            }
            case TPA -> {
                if (plugin.getTpaManager() != null) {
                    var profile = plugin.getTpaManager().getProfile(player);
                    if (profile != null) {
                        profile.setTpaEnabled(enabled);
                    }
                }
            }
            case CHAT -> {
                if (plugin.getChatModule() != null) {
                    plugin.getChatModule().setChatToggled(player.getUniqueId(), !enabled);
                }
            }
            case PRIVATE_MESSAGES -> {
                if (plugin.getChatModule() != null) {
                    var chatData = plugin.getChatModule().getPlayerData(player.getUniqueId());
                    if (chatData != null) {
                        chatData.setMessagesClosed(!enabled);
                    }
                }
            }
            case SCOREBOARD -> {
                if (!enabled) {
                    plugin.getScheduler().runEntity(player, () -> {
                        player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());
                    });
                }
            }
            default -> {}
        }
    }
}
