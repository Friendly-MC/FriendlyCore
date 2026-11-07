package dev.lyy.friendlycore.modules.tpa.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Player settings GUI for toggling personal TPA confirmation dialogs.
 */
public class TpaSettingGui extends PaginatedGui {

    private final TpaManager tpaManager;

    public TpaSettingGui(FriendlyCore plugin, TpaManager tpaManager, Player player) {
        super(plugin, player, getTitle(plugin), getRows(plugin));
        this.tpaManager = tpaManager;
        setupItems();
    }

    private static FileConfiguration getConfig(FriendlyCore plugin) {
        return plugin.getConfigService().get("modules/tpa/inventories/tpa-settings.yml");
    }

    private static Component getTitle(FriendlyCore plugin) {
        String title = getConfig(plugin).getString("teleport-setting-inventory.name", "&8&lSETTING");
        return plugin.getTextService().parse(title);
    }

    private static int getRows(FriendlyCore plugin) {
        return getConfig(plugin).getInt("teleport-setting-inventory.rows", 3);
    }

    private void setupItems() {
        FileConfiguration config = getConfig(getPlugin());
        TpaProfile profile = tpaManager.getProfile(getViewer());
        boolean showGui = profile != null && profile.isShowGui();

        String stateKey = showGui ? "enabled" : "disabled";
        String path = "teleport-setting-inventory.show-inventory-icon." + stateKey;

        int slot = config.getInt(path + ".slot", 13);
        Material mat = Material.matchMaterial(config.getString(path + ".material", showGui ? "LIME_DYE" : "RED_DYE"));
        if (mat == null) mat = showGui ? Material.LIME_DYE : Material.RED_DYE;
        String displayName = config.getString(path + ".display-name", showGui ? "&7Show Inventory &8» &aEnabled" : "&7Show Inventory &8» &cDisabled");
        List<String> loreLines = config.getStringList(path + ".lore");

        ItemBuilder builder = new ItemBuilder(mat);
        builder.name(getPlugin().getTextService().parse(displayName));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(getPlugin().getTextService().parse(line));
            }
            builder.lore(lore);
        }

        ItemStack item = builder.build();
        setItem(slot, new GuiItem(item, e -> {
            e.setCancelled(true);
            if (profile != null) {
                boolean newState = !profile.isShowGui();
                profile.setShowGui(newState);
                getPlugin().getDatabaseManager().saveTpaProfile(profile);

                String msgKey = newState ? "tpa.tpa-gui-toggle-on" : "tpa.tpa-gui-toggle-off";
                tpaManager.sendConfigMessage(getViewer(), msgKey, Map.of());

                String clickSound = tpaManager.getConfig().getString("sounds.information-click", "block.tripwire.click_on");
                TextService.playSound(getViewer(), clickSound, 1.0f, 1.0f);

                setupItems();
            }
        }));
    }
}
