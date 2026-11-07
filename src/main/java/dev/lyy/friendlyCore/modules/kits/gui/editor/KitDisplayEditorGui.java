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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interactive menu for configuring the 5 display item states of a kit.
 */
public class KitDisplayEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;

    public KitDisplayEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-display-states", "%kit%", kit.getName()),
                6);
        this.editorManager = editorManager;
        this.kit = kit;
        buildGui();
    }

    public void buildGui() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        ItemStack border = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
        fillBorder(border);

        ItemStack infoHeader = new ItemBuilder(Material.ITEM_FRAME)
                .name(Messages.component(getPlugin(), "kits", "editor-display-states-2"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-customize-icons-names-and-lore"),
                        Messages.component(getPlugin(), "kits", "editor-for-each-kit-state-in-the")
                ))
                .build();
        setItem(4, new GuiItem(infoHeader, e -> e.setCancelled(true)));

        setupStateSlot(11, "Default (Available)", kit::getDisplayItemDefault, kit::setDisplayItemDefault, Material.CHEST);

        setupStateSlot(13, "Cooldown (Active Timer)", kit::getDisplayItemCooldown, kit::setDisplayItemCooldown, Material.CLOCK);

        setupStateSlot(15, "No Permission (Locked)", kit::getDisplayItemNoPermission, kit::setDisplayItemNoPermission, Material.BARRIER);

        setupStateSlot(29, "One-Time (Claimed)", kit::getDisplayItemOneTime, kit::setDisplayItemOneTime, Material.TOTEM_OF_UNDYING);

        setupStateSlot(33, "One-Time Req (Unlocked)", kit::getDisplayItemOneTimeRequirements, kit::setDisplayItemOneTimeRequirements, Material.EMERALD);

        ItemStack backButton = new ItemBuilder(Material.ARROW)
                .name(Messages.component(getPlugin(), "kits", "back"))
                .lore(List.of(Messages.component(getPlugin(), "kits", "editor-return-to-kit-dashboard")))
                .build();
        setItem(45, new GuiItem(backButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitDetail(getViewer(), kit);
        }));

        ItemStack guideItem = new ItemBuilder(Material.BOOK)
                .name(Messages.component(getPlugin(), "kits", "editor-instructions"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-set-icon-from-hand"),
                        Messages.component(getPlugin(), "kits", "editor-edit-display-name"),
                        Messages.component(getPlugin(), "kits", "editor-edit-lore-lines"),
                        Messages.component(getPlugin(), "kits", "editor-reset-state")
                ))
                .build();
        setItem(49, new GuiItem(guideItem, e -> e.setCancelled(true)));

        ItemStack saveButton = new ItemBuilder(Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", "editor-save-states"))
                .build();
        setItem(53, new GuiItem(saveButton, e -> {
            e.setCancelled(true);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-display-states-for-saved", "%kit%", kit.getName()));
            editorManager.openKitDetail(getViewer(), kit);
        }));
    }

    private void setupStateSlot(int slot, String stateName,
                                Supplier<KitItem> getter,
                                Consumer<KitItem> setter,
                                Material fallbackMat) {
        KitItem current = getter.get();
        ItemStack icon = null;
        if (current != null) {
            icon = KitItemParser.toItemStack(current, getViewer(), kit);
        }
        if (icon == null || icon.getType().isAir()) {
            icon = new ItemStack(fallbackMat);
        } else {
            icon = icon.clone();
        }
        icon.setAmount(1);

        String namePreview = current != null && current.getName() != null
                ? current.getName()
                : "<color:#8A8A8A>ᴅᴇғᴀᴜʟᴛ ɴᴀᴍᴇ</color>";
        int loreLinesCount = current != null && current.getLore() != null ? current.getLore().size() : 0;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-icon", "%icon_type%", String.valueOf(icon.getType().name())));
        lore.add(Messages.component(getPlugin(), "kits", "editor-name", "%name_preview_formatted%", String.valueOf(namePreview)));
        lore.add(Messages.component(getPlugin(), "kits", "editor-lines", "%lore_lines_count%", String.valueOf(loreLinesCount)));
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-set-icon-from-hand"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-edit-display-name"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-edit-lore-lines"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-reset-state"));

        ItemStack finalItem = new ItemBuilder(icon)
                .name(Messages.component(getPlugin(), "kits", "editor-label-2", "%state_name%", stateName))
                .lore(lore)
                .build();

        setItem(slot, new GuiItem(finalItem, e -> handleStateClick(e, stateName, getter, setter, fallbackMat)));
    }

    private void handleStateClick(InventoryClickEvent event, String stateName,
                                  Supplier<KitItem> getter,
                                  Consumer<KitItem> setter,
                                  Material fallbackMat) {
        event.setCancelled(true);
        ClickType click = event.getClick();
        KitItem current = getter.get();

        if (click.isShiftClick() && click.isRightClick()) {
            setter.accept(null);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-reset-state-to-default", "%state_name%", stateName));
            buildGui();
            return;
        }

        if (click.isShiftClick() && click.isLeftClick()) {
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ʟᴏʀᴇ ʟɪɴᴇѕ (ѕᴇᴘᴀʀᴀᴛᴇ ᴡɪᴛʜ ';' ᴏʀ '|'):",
                    current != null && current.getLore() != null ? String.join(" | ", current.getLore()) : "",
                    loreInput -> {
                        KitItem ki = current != null ? current : new KitItem(fallbackMat.name());
                        List<String> newLore = new ArrayList<>();
                        String[] parts = loreInput.split("[;|]");
                        for (String part : parts) {
                            newLore.add(part.trim());
                        }
                        ki.setLore(newLore);
                        setter.accept(ki);
                        getPlugin().getKitManager().updateKit(kit);
                        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-updated-lore-for", "%state_name%", stateName));
                        editorManager.openDisplayEditor(getViewer(), kit);
                    },
                    () -> editorManager.openDisplayEditor(getViewer(), kit)
            );
            return;
        }

        if (click.isRightClick()) {
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ᴅɪѕᴘʟᴀʏ ɴᴀᴍᴇ ғᴏʀ " + stateName + ":",
                    current != null && current.getName() != null ? current.getName() : "",
                    nameInput -> {
                        KitItem ki = current != null ? current : new KitItem(fallbackMat.name());
                        ki.setName(nameInput.trim());
                        setter.accept(ki);
                        getPlugin().getKitManager().updateKit(kit);
                        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-updated-display-name-for", "%state_name%", stateName));
                        editorManager.openDisplayEditor(getViewer(), kit);
                    },
                    () -> editorManager.openDisplayEditor(getViewer(), kit)
            );
            return;
        }

        // Left-Click -> Set Icon from Hand or Cursor
        ItemStack handItem = getViewer().getInventory().getItemInMainHand();
        if (handItem.getType().isAir()) {
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-hold-an-item-in-main-hand"));
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            return;
        }

        KitItem newKi = KitItemParser.fromItemStack(handItem, kit.isSaveOriginalItems());
        if (current != null) {
            if (current.getName() != null) newKi.setName(current.getName());
            if (current.getLore() != null) newKi.setLore(current.getLore());
        }
        setter.accept(newKi);
        getPlugin().getKitManager().updateKit(kit);
        getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-updated-icon-for-to", "%state_name%", stateName, "%hand_item_type%", String.valueOf(handItem.getType().name())));
        buildGui();
    }
}
