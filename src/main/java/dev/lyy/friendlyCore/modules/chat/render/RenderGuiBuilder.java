package dev.lyy.friendlycore.modules.chat.render;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.modules.chat.model.RenderSnapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds non-interactable preview GUIs for snapshots of items, player inventories, and enderchests.
 */
public class RenderGuiBuilder {

    private final FriendlyCore plugin;

    public RenderGuiBuilder(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds an interactive inventory for the given snapshot.
     */
    public Inventory buildGui(RenderSnapshot snapshot) {
        if (snapshot == null) return null;
        FileConfiguration guiConfig = plugin.getConfigService().getConfig("modules/chat/renders-gui.yml");

        return switch (snapshot.getType()) {
            case ITEM -> buildItemGui(snapshot, guiConfig);
            case INVENTORY -> buildInventoryGui(snapshot, guiConfig);
            case ENDERCHEST -> buildEnderchestGui(snapshot, guiConfig);
            case SHULKER -> buildShulkerGui(snapshot, guiConfig);
        };
    }

    private Inventory buildItemGui(RenderSnapshot snapshot, FileConfiguration config) {
        String titleStr = config.getString("item-gui.title", "<dark_gray>» <gradient:#00e676:#00b0ff><b>%player%'s Item Preview</b></gradient>")
                .replace("%player%", snapshot.getOwnerName());
        int size = config.getInt("item-gui.size", 9);
        int itemSlot = config.getInt("item-gui.item-slot", 4);

        Component title = plugin.getTextService().parse(titleStr);
        ChatPreviewHolder holder = new ChatPreviewHolder(snapshot);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        applyDecorations(inv, config.getConfigurationSection("item-gui.decorations"));

        ItemStack item = snapshot.getItem();
        if (item != null && item.getType() != Material.AIR) {
            inv.setItem(itemSlot, item);
        }

        return inv;
    }

    private Inventory buildInventoryGui(RenderSnapshot snapshot, FileConfiguration config) {
        String titleStr = config.getString("inventory-gui.title", "<dark_gray>» <gradient:#00d2ff:#3a7bd5><b>%player%'s Inventory</b></gradient>")
                .replace("%player%", snapshot.getOwnerName());
        int size = config.getInt("inventory-gui.size", 54);

        Component title = plugin.getTextService().parse(titleStr);
        ChatPreviewHolder holder = new ChatPreviewHolder(snapshot);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        applyDecorations(inv, config.getConfigurationSection("inventory-gui.decorations"));

        // Main Inventory contents (slots 0 - 26: main storage 9-35, slots 27 - 35: hotbar 0-8)
        ItemStack[] contents = snapshot.getContents();
        if (contents != null) {
            if (contents.length >= 36) {
                // Main inventory slots 9-35 -> GUI slots 0-26
                for (int i = 9; i <= 35; i++) {
                    if (contents[i] != null) {
                        inv.setItem(i - 9, contents[i]);
                    }
                }
                // Hotbar slots 0-8 -> GUI slots 27-35
                for (int i = 0; i <= 8; i++) {
                    if (contents[i] != null) {
                        inv.setItem(27 + i, contents[i]);
                    }
                }
            } else {
                for (int i = 0; i < Math.min(contents.length, 36); i++) {
                    if (contents[i] != null) {
                        inv.setItem(i, contents[i]);
                    }
                }
            }
        }

        // Armor (slots 45: helmet, 46: chestplate, 47: leggings, 48: boots)
        ItemStack[] armor = snapshot.getArmor();
        if (armor != null) {
            if (armor.length > 3 && armor[3] != null) inv.setItem(45, armor[3]); // Helmet
            if (armor.length > 2 && armor[2] != null) inv.setItem(46, armor[2]); // Chestplate
            if (armor.length > 1 && armor[1] != null) inv.setItem(47, armor[1]); // Leggings
            if (armor.length > 0 && armor[0] != null) inv.setItem(48, armor[0]); // Boots
        }

        // Main hand (slot 51)
        ItemStack mainHand = snapshot.getItem();
        if (mainHand != null) {
            inv.setItem(51, mainHand);
        }

        ItemStack offHand = snapshot.getOffHand();
        if (offHand != null) {
            inv.setItem(53, offHand);
        }

        return inv;
    }

    private Inventory buildEnderchestGui(RenderSnapshot snapshot, FileConfiguration config) {
        String titleStr = config.getString("enderchest-gui.title", "<dark_gray>» <gradient:#ffb300:#ff8f00><b>%player%'s Ender Chest</b></gradient>")
                .replace("%player%", snapshot.getOwnerName());
        int size = config.getInt("enderchest-gui.size", 45);

        Component title = plugin.getTextService().parse(titleStr);
        ChatPreviewHolder holder = new ChatPreviewHolder(snapshot);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        applyDecorations(inv, config.getConfigurationSection("enderchest-gui.decorations"));

        ItemStack[] contents = snapshot.getContents();
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, 27); i++) {
                if (contents[i] != null) {
                    inv.setItem(i, contents[i]);
                }
            }
        }

        return inv;
    }

    private Inventory buildShulkerGui(RenderSnapshot snapshot, FileConfiguration config) {
        String titleStr = config.getString("shulker-box-gui.title", "<dark_gray>» <gradient:#ab47bc:#7b1fa2><b>%player%'s Shulker Box</b></gradient>")
                .replace("%player%", snapshot.getOwnerName());
        int size = config.getInt("shulker-box-gui.size", 27);

        Component title = plugin.getTextService().parse(titleStr);
        ChatPreviewHolder holder = new ChatPreviewHolder(snapshot);
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);

        ItemStack item = snapshot.getItem();
        if (item != null && item.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            ItemStack[] shulkerContents = box.getInventory().getContents();
            for (int i = 0; i < Math.min(shulkerContents.length, size); i++) {
                if (shulkerContents[i] != null) {
                    inv.setItem(i, shulkerContents[i]);
                }
            }
        }

        return inv;
    }

    private void applyDecorations(Inventory inv, ConfigurationSection sec) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String matName = sec.getString(key + ".material", "BLACK_STAINED_GLASS_PANE");
            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;

            ItemStack pane = new ItemBuilder(mat)
                    .name(Component.text(" "))
                    .build();

            String slotsStr = sec.getString(key + ".slots", "");
            if (slotsStr.isEmpty()) {
                int singleSlot = sec.getInt(key + ".slot", -1);
                if (singleSlot >= 0 && singleSlot < inv.getSize()) {
                    inv.setItem(singleSlot, pane);
                }
            } else {
                for (int slot : parseSlots(slotsStr, inv.getSize())) {
                    inv.setItem(slot, pane);
                }
            }
        }
    }

    private List<Integer> parseSlots(String slotsStr, int maxSize) {
        List<Integer> result = new ArrayList<>();
        if (slotsStr == null || slotsStr.isBlank()) return result;
        String[] parts = slotsStr.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                if (range.length == 2) {
                    try {
                        int start = Integer.parseInt(range[0].trim());
                        int end = Integer.parseInt(range[1].trim());
                        for (int i = start; i <= end; i++) {
                            if (i >= 0 && i < maxSize) {
                                result.add(i);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                try {
                    int slot = Integer.parseInt(part);
                    if (slot >= 0 && slot < maxSize) {
                        result.add(slot);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }
}
