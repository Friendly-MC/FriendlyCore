package dev.lyy.friendlycore.modules.tpa.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.tpa.SafeTeleportChecker;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin management menu for toggling global TPA safe teleport settings.
 */
public class TpaAdminGui extends PaginatedGui {

    private final TpaManager tpaManager;

    public TpaAdminGui(FriendlyCore plugin, TpaManager tpaManager, Player player) {
        super(plugin, player, getTitle(plugin), getRows(plugin));
        this.tpaManager = tpaManager;
        setupItems();
    }

    private static FileConfiguration getConfig(FriendlyCore plugin) {
        return plugin.getConfigService().get("modules/tpa/inventories/tpa-admin.yml");
    }

    private static Component getTitle(FriendlyCore plugin) {
        String title = getConfig(plugin).getString("admin-setting-inventory.name", "&8&lADMIN SETTINGS");
        return plugin.getTextService().parse(title);
    }

    private static int getRows(FriendlyCore plugin) {
        return getConfig(plugin).getInt("admin-setting-inventory.rows", 3);
    }

    private void setupItems() {
        FileConfiguration config = getConfig(getPlugin());
        SafeTeleportChecker.SafeCheckSettings safeSettings = tpaManager.getSafeSettings();
        boolean safeEnabled = safeSettings.isSafeTeleportEnabled();

        String stateKey = safeEnabled ? "enabled" : "disabled";
        String path = "admin-setting-inventory.safe-teleport-settings-icon." + stateKey;

        int slot = config.getInt(path + ".slot", 13);
        Material mat = Material.matchMaterial(config.getString(path + ".material", safeEnabled ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE"));
        if (mat == null) mat = safeEnabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String displayName = config.getString(path + ".display-name", safeEnabled ? "&7Safe Teleport &8» &aEnabled" : "&7Safe Teleport &8» &cDisabled");
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
            ClickType click = e.getClick();
            String clickSound = tpaManager.getConfig().getString("sounds.information-click", "block.tripwire.click_on");
            TextService.playSound(getViewer(), clickSound, 1.0f, 1.0f);

            if (click.isRightClick()) {
                tpaManager.openAdminSafeChecksGui(getViewer());
            } else {
                boolean newState = !safeSettings.isSafeTeleportEnabled();
                safeSettings.setSafeTeleportEnabled(newState);
                tpaManager.getConfig().set("safe-teleport", newState);
                getPlugin().getConfigService().save("tpa.yml");

                setupItems();
            }
        }));
    }
}
