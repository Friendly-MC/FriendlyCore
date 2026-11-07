package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitItemParser;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive 54-slot inventory editor for kit items and dedicated equipment slots.
 */
public class KitItemsEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;

    public KitItemsEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-items-editor", "%kit%", kit.getName()),
                6);
        this.editorManager = editorManager;
        this.kit = kit;
        loadExistingItems();
        setupControlBar();
    }

    @Override
    public boolean isItemMovementAllowed(int slot) {
        // Allow free item movement in slots 0 to 40 and player inventory
        if (slot < 0) return true; // Shift click / drag general
        return slot <= 40;
    }

    private void loadExistingItems() {
        for (int i = 0; i <= 40; i++) {
            getInventory().setItem(i, null);
        }

        int contentCursor = 0;
        for (KitItem ki : kit.getKitItems()) {
            if (ki == null) continue;
            ItemStack stack = KitItemParser.toItemStack(ki, getViewer(), kit);
            if (stack == null || stack.getType().isAir()) continue;

            KitItem.EquipmentSlot slot = ki.getEquipmentSlot();
            if (slot != null) {
                switch (slot) {
                    case HEAD -> getInventory().setItem(36, stack);
                    case CHEST -> getInventory().setItem(37, stack);
                    case LEGS -> getInventory().setItem(38, stack);
                    case FEET -> getInventory().setItem(39, stack);
                    case OFF_HAND -> getInventory().setItem(40, stack);
                }
            } else if (ki.isOffhand()) {
                getInventory().setItem(40, stack);
            } else {
                if (contentCursor <= 35) {
                    getInventory().setItem(contentCursor, stack);
                    contentCursor++;
                }
            }
        }
    }

    private void setupControlBar() {
        ItemStack sep = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
        for (int i = 41; i <= 44; i++) {
            setItem(i, new GuiItem(sep, e -> e.setCancelled(true)));
        }

        ItemStack addHeld = new ItemBuilder(Material.HOPPER)
                .name(Messages.component(getPlugin(), "kits", "editor-add-hand-item"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-insert-your-main-hand-item")
                ))
                .build();
        setItem(45, new GuiItem(addHeld, e -> {
            e.setCancelled(true);
            ItemStack hand = getViewer().getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-you-are-not-holding-any-item"));
                getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
                return;
            }
            int firstFree = getFirstFreeContentSlot();
            if (firstFree != -1) {
                getInventory().setItem(firstFree, hand.clone());
                getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            } else {
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-no-free-content-slot-available"));
            }
        }));

        // Slot 46: Import All From Player Inventory
        ItemStack importInv = new ItemBuilder(Material.CHEST_MINECART)
                .name(Messages.component(getPlugin(), "kits", "editor-import-inventory"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-load-all-items-from-your-inventory")
                ))
                .build();
        setItem(46, new GuiItem(importInv, e -> {
            e.setCancelled(true);
            PlayerInventory pInv = getViewer().getInventory();

            for (int i = 0; i <= 40; i++) {
                getInventory().setItem(i, null);
            }

            if (pInv.getHelmet() != null && !pInv.getHelmet().getType().isAir()) getInventory().setItem(36, pInv.getHelmet().clone());
            if (pInv.getChestplate() != null && !pInv.getChestplate().getType().isAir()) getInventory().setItem(37, pInv.getChestplate().clone());
            if (pInv.getLeggings() != null && !pInv.getLeggings().getType().isAir()) getInventory().setItem(38, pInv.getLeggings().clone());
            if (pInv.getBoots() != null && !pInv.getBoots().getType().isAir()) getInventory().setItem(39, pInv.getBoots().clone());
            if (pInv.getItemInOffHand() != null && !pInv.getItemInOffHand().getType().isAir()) getInventory().setItem(40, pInv.getItemInOffHand().clone());

            // Load main contents into slots 0-35
            int slotIdx = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack is = pInv.getItem(i);
                if (is != null && !is.getType().isAir()) {
                    getInventory().setItem(slotIdx, is.clone());
                    slotIdx++;
                }
            }

            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-imported-current-inventory-into-editor"));
        }));

        ItemStack clearItem = new ItemBuilder(Material.LAVA_BUCKET)
                .name(Messages.component(getPlugin(), "kits", "editor-clear-all"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-wipe-all-items-from-this-kit")
                ))
                .build();
        setItem(47, new GuiItem(clearItem, e -> {
            e.setCancelled(true);
            for (int i = 0; i <= 40; i++) {
                getInventory().setItem(i, null);
            }
            getViewer().playSound(getViewer().getLocation(), Sound.ITEM_BUCKET_EMPTY_LAVA, 1.0f, 1.0f);
        }));

        ItemStack infoItem = new ItemBuilder(Material.BOOK)
                .name(Messages.component(getPlugin(), "kits", "editor-items-guide"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-drag-drop-and-rearrange-items"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-main-kit-items"),
                        Messages.component(getPlugin(), "kits", "editor-helmet"),
                        Messages.component(getPlugin(), "kits", "editor-chestplate"),
                        Messages.component(getPlugin(), "kits", "editor-leggings"),
                        Messages.component(getPlugin(), "kits", "editor-boots"),
                        Messages.component(getPlugin(), "kits", "editor-off-hand")
                ))
                .build();
        setItem(49, new GuiItem(infoItem, e -> e.setCancelled(true)));

        ItemStack cancelItem = new ItemBuilder(Material.BARRIER)
                .name(Messages.component(getPlugin(), "kits", "editor-discard-back"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-revert-changes-and-return")
                ))
                .build();
        setItem(51, new GuiItem(cancelItem, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitDetail(getViewer(), kit);
        }));

        ItemStack saveItem = new ItemBuilder(Material.LIME_CONCRETE)
                .name(Messages.component(getPlugin(), "kits", "editor-save-items"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-save-all-items-and-armor")
                ))
                .build();
        setItem(53, new GuiItem(saveItem, e -> {
            e.setCancelled(true);
            saveItemsToKit();
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-saved-items-to-kit", "%kit_items%", String.valueOf(kit.getKitItems().size()), "%kit%", kit.getName()));
            editorManager.openKitDetail(getViewer(), kit);
        }));
    }

    private void saveItemsToKit() {
        List<KitItem> items = new ArrayList<>();

        for (int i = 0; i <= 35; i++) {
            ItemStack is = getInventory().getItem(i);
            if (is == null || is.getType().isAir()) continue;
            KitItem ki = KitItemParser.fromItemStack(is, kit.isSaveOriginalItems());
            if (ki != null) {
                items.add(ki);
            }
        }

        ItemStack helm = getInventory().getItem(36);
        if (helm != null && !helm.getType().isAir()) {
            KitItem ki = KitItemParser.fromItemStack(helm, kit.isSaveOriginalItems());
            if (ki != null) {
                ki.setEquipmentSlot(KitItem.EquipmentSlot.HEAD);
                items.add(ki);
            }
        }

        ItemStack chest = getInventory().getItem(37);
        if (chest != null && !chest.getType().isAir()) {
            KitItem ki = KitItemParser.fromItemStack(chest, kit.isSaveOriginalItems());
            if (ki != null) {
                ki.setEquipmentSlot(KitItem.EquipmentSlot.CHEST);
                items.add(ki);
            }
        }

        ItemStack legs = getInventory().getItem(38);
        if (legs != null && !legs.getType().isAir()) {
            KitItem ki = KitItemParser.fromItemStack(legs, kit.isSaveOriginalItems());
            if (ki != null) {
                ki.setEquipmentSlot(KitItem.EquipmentSlot.LEGS);
                items.add(ki);
            }
        }

        ItemStack boots = getInventory().getItem(39);
        if (boots != null && !boots.getType().isAir()) {
            KitItem ki = KitItemParser.fromItemStack(boots, kit.isSaveOriginalItems());
            if (ki != null) {
                ki.setEquipmentSlot(KitItem.EquipmentSlot.FEET);
                items.add(ki);
            }
        }

        ItemStack offhand = getInventory().getItem(40);
        if (offhand != null && !offhand.getType().isAir()) {
            KitItem ki = KitItemParser.fromItemStack(offhand, kit.isSaveOriginalItems());
            if (ki != null) {
                ki.setEquipmentSlot(KitItem.EquipmentSlot.OFF_HAND);
                items.add(ki);
            }
        }

        kit.setKitItems(items);
    }

    private int getFirstFreeContentSlot() {
        for (int i = 0; i <= 35; i++) {
            ItemStack is = getInventory().getItem(i);
            if (is == null || is.getType().isAir()) return i;
        }
        return -1;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 41 && rawSlot < getSize()) {
            event.setCancelled(true);
            super.handleClick(event);
        }
    }
}
