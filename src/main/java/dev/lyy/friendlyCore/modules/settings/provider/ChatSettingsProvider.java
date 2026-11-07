package dev.lyy.friendlycore.modules.settings.provider;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Interactive Text / Chat CLI UI Provider using Adventure MiniMessage.
 * Displays clickable toggle buttons directly inside chat styled with the EssentialsX Colorful theme.
 */
public class ChatSettingsProvider implements SettingsMenuProvider {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    public ChatSettingsProvider(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

    @Override
    public String getName() {
        return "CHAT";
    }

    @Override
    public boolean isSupported(Player player) {
        return true;
    }

    private String getMessage(String key, String fallback) {
        FileConfiguration msgs = plugin.getConfigService().getSettingsMessages();
        if (msgs != null && msgs.contains(key)) {
            return msgs.getString(key);
        }
        return fallback;
    }

    @Override
    public void openSettings(Player player) {
        String header = getMessage("chat-header", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ᴘʟᴀʏᴇʀ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>• <color:#8A8A8A>ᴄʟɪᴄᴋ ᴛᴏ ᴛᴏɢɢʟᴇ");
        String footer = getMessage("chat-footer", "<dark_gray>─────────────────────────────────────────</dark_gray>");
        String lineTemplate = getMessage("chat-line", " <dark_gray>• <color:#E7B85C>%setting% %button% <dark_gray>» <color:#D8D0C3>%description%");

        player.sendMessage(plugin.getTextService().parse(header));

        for (SettingType type : SettingType.values()) {
            if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                continue;
            }
            boolean state = settingsManager.getSetting(player.getUniqueId(), type);
            String toggleBtn = state
                    ? "<click:run_command:'/settings toggle " + type.getKey() + "'><hover:show_text:'<color:#D8D0C3>Click to disable <color:#E7B85C>" + type.getDisplayName() + "'><color:#8DE969><bold>[ᴏɴ]</bold></color></hover></click>"
                    : "<click:run_command:'/settings toggle " + type.getKey() + "'><hover:show_text:'<color:#D8D0C3>Click to enable <color:#E7B85C>" + type.getDisplayName() + "'><color:#FF4D5A><bold>[ᴏғғ]</bold></color></hover></click>";

            Component line = plugin.getTextService().parse(lineTemplate, Map.of(
                    "%setting%", type.getDisplayName(),
                    "%button%", toggleBtn,
                    "%description%", type.getDescription()
            ));
            player.sendMessage(line);
        }

        player.sendMessage(plugin.getTextService().parse(footer));
    }
}
