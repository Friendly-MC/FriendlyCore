package dev.lyy.friendlycore.modules.settings.provider;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal Legacy Chest GUI Provider for Java Edition players.
 * Renders a clean 27-slot chest menu with dynamic ON/OFF indicators and instant sound feedback.
 */
public class LegacyChestGuiSettingsProvider implements SettingsMenuProvider {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    public LegacyChestGuiSettingsProvider(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

    @Override
    public String getName() {
        return "CHEST_GUI";
    }

    @Override
    public boolean isSupported(Player player) {
        return true; // Universal fallback
    }

    @Override
    public void openSettings(Player player) {
        FileConfiguration config = plugin.getConfigService().getSettingsConfig();
        String titleRaw = config != null ? config.getString("ui.title", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient>")
                : "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient>";

        PaginatedGui gui = new PaginatedGui(plugin, player, plugin.getTextService().parse(titleRaw), 3);

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideFlags().build();
        gui.fillEmpty(filler);

        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        int index = 0;

        for (SettingType type : SettingType.values()) {
            if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                continue;
            }
            if (index >= slots.length) break;

            int slot = slots[index++];
            boolean enabled = settingsManager.getSetting(player.getUniqueId(), type);

            List<String> lore = new ArrayList<>();
            lore.add("<color:#D8D0C3>" + type.getDescription());
            lore.add("");
            lore.add("<dark_gray>• <color:#D8D0C3>Status: " + (enabled ? "<color:#8DE969><bold>✔ ᴇɴᴀʙʟᴇᴅ</bold></color>" : "<color:#FF4D5A><bold>✖ ᴅɪѕᴀʙʟᴇᴅ</bold></color>"));
            lore.add("");
            lore.add("<dark_gray>» <color:#E7B85C>Click to " + (enabled ? "<color:#FF4D5A>disable" : "<color:#8DE969>enable") + "<color:#E7B85C>!");

            ItemStack icon = new ItemBuilder(type.getIcon())
                    .name("<color:#E7B85C><bold>" + type.getDisplayName())
                    .loreStrings(lore)
                    .glow(enabled)
                    .hideFlags()
                    .build();

            gui.setItem(slot, new GuiItem(icon, event -> {
                boolean nextVal = !enabled;
                settingsManager.setSetting(player.getUniqueId(), type, nextVal);

                if (settingsManager.getSetting(player.getUniqueId(), SettingType.SOUNDS)) {
                    String soundKey = nextVal ? "ui.sounds.enable.sound" : "ui.sounds.disable.sound";
                    String soundDefault = nextVal ? "BLOCK_NOTE_BLOCK_PLING" : "BLOCK_NOTE_BLOCK_BASS";
                    String soundName = config != null ? config.getString(soundKey, soundDefault) : soundDefault;
                    float pitch = nextVal ? 2.0f : 0.8f;
                    TextService.playSound(player, soundName, 1.0f, pitch);
                }

                openSettings(player);
            }));
        }

        ItemStack closeBtn = new ItemBuilder(Material.BARRIER)
                .name("<gradient:#FF4D5A:#FF9A9A><bold>✖ ᴄʟᴏѕᴇ</bold></gradient>")
                .loreStrings(List.of("<color:#D8D0C3>Click to close settings."))
                .hideFlags()
                .build();
        gui.setItem(22, new GuiItem(closeBtn, event -> player.closeInventory()));

        gui.open();
    }
}
