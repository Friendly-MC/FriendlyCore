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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin sub-menu for fine-tuning individual safety check rules (vehicles, entities, water, falling).
 */
public class TpaAdminSafeChecksGui extends PaginatedGui {

    private final TpaManager tpaManager;

    public TpaAdminSafeChecksGui(FriendlyCore plugin, TpaManager tpaManager, Player player) {
        super(plugin, player, getTitle(plugin), getRows(plugin));
        this.tpaManager = tpaManager;
        setupItems();
    }

    private static FileConfiguration getConfig(FriendlyCore plugin) {
        return plugin.getConfigService().get("modules/tpa/inventories/tpa-safe-teleport-settings.yml");
    }

    private static Component getTitle(FriendlyCore plugin) {
        String title = getConfig(plugin).getString("safe-teleport-setting-inventory.name", "&8&lSAFE TELEPORT SETTINGS");
        return plugin.getTextService().parse(title);
    }

    private static int getRows(FriendlyCore plugin) {
        return getConfig(plugin).getInt("safe-teleport-setting-inventory.rows", 4);
    }

    private void setupItems() {
        FileConfiguration config = getConfig(getPlugin());
        SafeTeleportChecker.SafeCheckSettings settings = tpaManager.getSafeSettings();

        String backPath = "safe-teleport-setting-inventory.back-button";
        if (config.contains(backPath)) {
            int slot = config.getInt(backPath + ".slot", 31);
            Material mat = Material.matchMaterial(config.getString(backPath + ".material", "ARROW"));
            if (mat == null) mat = Material.ARROW;
            String name = config.getString(backPath + ".display-name", "&7&l« &cBack");
            List<String> lore = config.getStringList(backPath + ".lore");

            ItemStack item = buildSimpleItem(mat, name, lore);
            setItem(slot, new GuiItem(item, e -> {
                e.setCancelled(true);
                playClickSound();
                tpaManager.openAdminGui(getViewer());
            }));
        }

        setupCheckToggle("check-vehicle-icon", settings.isCheckVehicle(), () -> {
            boolean newState = !settings.isCheckVehicle();
            settings.setCheckVehicle(newState);
            tpaManager.getConfig().set("safe-teleport-checks.check-vehicle", newState);
            getPlugin().getConfigService().save("tpa.yml");
        });

        setupCheckToggle("check-nearby-entities-icon", settings.isCheckNearbyEntity(), () -> {
            boolean newState = !settings.isCheckNearbyEntity();
            settings.setCheckNearbyEntity(newState);
            tpaManager.getConfig().set("safe-teleport-checks.check-nearby-entity", newState);
            getPlugin().getConfigService().save("tpa.yml");
        });

        setupCheckToggle("check-falling-icon", settings.isCheckFalling(), () -> {
            boolean newState = !settings.isCheckFalling();
            settings.setCheckFalling(newState);
            tpaManager.getConfig().set("safe-teleport-checks.check-falling", newState);
            getPlugin().getConfigService().save("tpa.yml");
        });

        setupCheckToggle("check-water-icon", settings.isCheckWater(), () -> {
            boolean newState = !settings.isCheckWater();
            settings.setCheckWater(newState);
            tpaManager.getConfig().set("safe-teleport-checks.check-water", newState);
            getPlugin().getConfigService().save("tpa.yml");
        });

        setupCheckToggle("check-signs-icon", settings.isCheckSign(), () -> {
            boolean newState = !settings.isCheckSign();
            settings.setCheckSign(newState);
            tpaManager.getConfig().set("safe-teleport-checks.check-sign", newState);
            getPlugin().getConfigService().save("tpa.yml");
        });
    }

    private void setupCheckToggle(String iconKey, boolean isEnabled, Runnable onToggle) {
        FileConfiguration config = getConfig(getPlugin());
        String stateKey = isEnabled ? "enabled" : "disabled";
        String path = "safe-teleport-setting-inventory." + iconKey + "." + stateKey;

        if (!config.contains(path)) return;

        int slot = config.getInt(path + ".slot");
        Material mat = Material.matchMaterial(config.getString(path + ".material", isEnabled ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE"));
        if (mat == null) mat = isEnabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        String name = config.getString(path + ".display-name", "");
        List<String> lore = config.getStringList(path + ".lore");

        ItemStack item = buildSimpleItem(mat, name, lore);
        setItem(slot, new GuiItem(item, e -> {
            e.setCancelled(true);
            playClickSound();
            if (onToggle != null) {
                onToggle.run();
            }
            setupItems();
        }));
    }

    private void playClickSound() {
        String sound = tpaManager.getConfig().getString("sounds.information-click", "block.tripwire.click_on");
        TextService.playSound(getViewer(), sound, 1.0f, 1.0f);
    }

    private ItemStack buildSimpleItem(Material mat, String name, List<String> loreLines) {
        ItemBuilder builder = new ItemBuilder(mat);
        if (name != null && !name.isEmpty()) {
            builder.name(getPlugin().getTextService().parse(name));
        }
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(getPlugin().getTextService().parse(line));
            }
            builder.lore(lore);
        }
        return builder.build();
    }
}
