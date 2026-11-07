package dev.lyy.friendlycore.modules.settings.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Direct shortcut commands for toggling specific settings (e.g. /sb, /chattoggle, /toggletpa, /togglepm).
 */
public class QuickToggleCommands implements CommandExecutor {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;
    private final SettingType settingType;

    public QuickToggleCommands(FriendlyCore plugin, SettingsManager settingsManager, SettingType settingType) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.settingType = settingType;
    }

    private String getMessage(String key, String fallback) {
        FileConfiguration msgs = plugin.getConfigService().getSettingsMessages();
        if (msgs != null && msgs.contains(key)) {
            return msgs.getString(key);
        }
        return fallback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            String onlyPlayers = getMessage("players-only", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ ᴄᴀɴ ᴏɴʟʏ ʙᴇ ᴜѕᴇᴅ ʙʏ ᴘʟᴀʏᴇʀѕ.");
            plugin.getTextService().sendMessage(sender, onlyPlayers);
            return true;
        }

        if (settingType.getPermission() != null && !player.hasPermission(settingType.getPermission())) {
            String noPerm = getMessage("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴛᴏɢɢʟᴇ <color:#E7B85C>%setting%<color:#D8D0C3>.");
            plugin.getTextService().sendMessage(player, noPerm, Map.of("%setting%", settingType.getDisplayName()));
            return true;
        }

        boolean next = settingsManager.toggleSetting(player.getUniqueId(), settingType);
        String template = next
                ? getMessage("setting-enabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#8DE969><bold>ᴇɴᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.")
                : getMessage("setting-disabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#FF4D5A><bold>ᴅɪѕᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.");

        plugin.getTextService().sendMessage(player, template, Map.of("%setting%", settingType.getDisplayName()));

        if (settingsManager.getSetting(player.getUniqueId(), SettingType.SOUNDS)) {
            player.playSound(player.getLocation(), next ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, next ? 2f : 0.8f);
        }

        return true;
    }
}
