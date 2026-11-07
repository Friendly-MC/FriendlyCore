package dev.lyy.friendlycore.modules.homes.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Confirmation dialog GUI for deleting a player's home location.
 */
public class ConfirmDeleteGui extends PaginatedGui {

    private final HomesManager homesManager;
    private final UUID targetUuid;
    private final String targetName;
    private final int slotIndex;
    private final ItemStack previewItem;

    public ConfirmDeleteGui(FriendlyCore plugin, HomesManager homesManager, Player viewer,
                            UUID targetUuid, String targetName, int slotIndex, ItemStack previewItem) {
        super(plugin, viewer,
                calculateTitle(plugin, targetName, slotIndex),
                calculateRows(plugin));
        this.homesManager = homesManager;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.slotIndex = slotIndex;
        this.previewItem = previewItem;

        buildGui();
    }

    private static int calculateRows(FriendlyCore plugin) {
        FileConfiguration config = plugin.getConfigService().get("modules/homes/inventories/confirm.yml");
        List<String> disposition = config.getStringList("gui-disposition");
        if (disposition.isEmpty()) {
            return 3;
        }
        return Math.min(6, Math.max(1, disposition.size()));
    }

    private static Component calculateTitle(FriendlyCore plugin, String targetName, int slotIndex) {
        FileConfiguration config = plugin.getConfigService().get("modules/homes/inventories/confirm.yml");
        String rawTitle = config.getString("title", "Confirm Delete");
        return plugin.getTextService().parse(rawTitle, Map.of(
                "%index%", String.valueOf(slotIndex),
                "%player%", targetName != null ? targetName : ""
        ));
    }

    private void buildGui() {
        FileConfiguration config = getPlugin().getConfigService().get("modules/homes/inventories/confirm.yml");
        List<String> disposition = config.getStringList("gui-disposition");
        if (disposition.isEmpty()) {
            disposition = List.of(
                    "---------",
                    "--d-h-a--",
                    "---------"
            );
        }

        int rows = getRows();

        Map<Character, ConfigurationSection> symbolItems = new HashMap<>();
        ConfigurationSection itemsSec = config.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                ConfigurationSection sec = itemsSec.getConfigurationSection(key);
                if (sec != null && sec.contains("symbol")) {
                    String sym = sec.getString("symbol");
                    if (sym != null && !sym.isEmpty()) {
                        symbolItems.put(sym.charAt(0), sec);
                    }
                }
            }
        }

        for (int r = 0; r < Math.min(rows, disposition.size()); r++) {
            String rowStr = disposition.get(r);
            for (int c = 0; c < Math.min(9, rowStr.length()); c++) {
                char symbol = rowStr.charAt(c);
                int invSlot = r * 9 + c;

                if (symbol == 'h') {
                    if (previewItem != null) {
                        setItem(invSlot, new GuiItem(previewItem, e -> e.setCancelled(true)));
                    }
                } else if (symbol == 'a') {
                    ConfigurationSection sec = symbolItems.getOrDefault('a', config.getConfigurationSection("items.confirm-update"));
                    ItemStack confirmItem = buildButton(sec, Material.LIME_STAINED_GLASS_PANE, "&aᴄᴏɴғɪʀᴍ");
                    setItem(invSlot, new GuiItem(confirmItem, e -> handleConfirm()));
                } else if (symbol == 'd') {
                    ConfigurationSection sec = symbolItems.getOrDefault('d', config.getConfigurationSection("items.cancel-update"));
                    ItemStack cancelItem = buildButton(sec, Material.RED_STAINED_GLASS_PANE, "&cᴄᴀɴᴄᴇʟ");
                    setItem(invSlot, new GuiItem(cancelItem, e -> handleCancel()));
                } else if (symbol == 'g') {
                    ConfigurationSection sec = symbolItems.getOrDefault('g', config.getConfigurationSection("items.go-back-update"));
                    ItemStack backItem = buildButton(sec, Material.BARRIER, "&4ʙᴀᴄᴋ");
                    setItem(invSlot, new GuiItem(backItem, e -> handleGoBack()));
                } else if (symbolItems.containsKey(symbol)) {
                    ItemStack filler = buildButton(symbolItems.get(symbol), Material.BLACK_STAINED_GLASS_PANE, " ");
                    setItem(invSlot, new GuiItem(filler, e -> e.setCancelled(true)));
                }
            }
        }
    }

    private ItemStack buildButton(ConfigurationSection sec, Material defaultMat, String defaultName) {
        Material mat = defaultMat;
        String name = defaultName;
        List<String> lore = new ArrayList<>();

        if (sec != null) {
            String matStr = sec.getString("material");
            if (matStr != null) {
                Material m = Material.matchMaterial(matStr);
                if (m != null) mat = m;
            }
            name = sec.getString("name", defaultName);
            lore = sec.getStringList("lore");
        }

        ItemBuilder builder = new ItemBuilder(mat)
                .name(getPlugin().getTextService().parse(name, Map.of("%index%", String.valueOf(slotIndex))));
        for (String l : lore) {
            builder.addLore(getPlugin().getTextService().parse(l, Map.of("%index%", String.valueOf(slotIndex))));
        }
        return builder.build();
    }

    private void handleConfirm() {
        PlayerHomes profile = homesManager.getPlayerHomes(targetUuid);
        if (profile != null) {
            HomeData removed = profile.removeHome(slotIndex);
            if (removed != null) {
                homesManager.savePlayerHomesAsync(profile);
            }
        } else {
            getPlugin().getDatabaseManager().deleteHome(targetUuid, slotIndex);
        }

        homesManager.playSound(getViewer(), "ui-click");
        if (getViewer().getUniqueId().equals(targetUuid)) {
            homesManager.sendHomeMessage(getViewer(), "home-deleted-chat", Map.of("%home%", String.valueOf(slotIndex)));
            homesManager.sendHomeActionBar(getViewer(), "home-deleted-action-bar", Map.of("%home%", String.valueOf(slotIndex)));
        } else {
            homesManager.sendHomeMessage(getViewer(), "home-removed-someone-else",
                    Map.of("%player%", targetName, "%home%", String.valueOf(slotIndex)));
        }

        getPlugin().getScheduler().runEntity(getViewer(), () -> {
            new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, 1).open();
        });
    }

    private void handleCancel() {
        homesManager.playSound(getViewer(), "ui-click");
        homesManager.sendHomeMessage(getViewer(), "delete-cancel-chat", Map.of());
        homesManager.sendHomeActionBar(getViewer(), "delete-cancel-actionbar", Map.of());
        getPlugin().getScheduler().runEntity(getViewer(), () -> {
            new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, 1).open();
        });
    }

    private void handleGoBack() {
        homesManager.playSound(getViewer(), "ui-click");
        getPlugin().getScheduler().runEntity(getViewer(), () -> {
            new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, 1).open();
        });
    }
}
