package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interactive settings dashboard for configuring an individual kit.
 */
public class KitDetailEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;

    public KitDetailEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-kit-dashboard", "%kit%", kit.getName()),
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

        ItemStack headerItem = new ItemBuilder(Material.NETHER_STAR)
                .name(Messages.component(getPlugin(), "kits", "editor-dashboard", "%kit%", kit.getName()))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-configuring-kit", "%kit%", kit.getName()),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-items", "%kit_items%", String.valueOf(kit.getKitItems().size())),
                        Messages.component(getPlugin(), "kits", "editor-cooldown", "%cooldown_seconds%", String.valueOf(KitManager.formatDurationDetailed(kit.getCooldownSeconds()))),
                        Messages.component(getPlugin(), "kits", kit.isPermissionRequired() ? "editor-permission-yes" : "editor-permission-no")
                ))
                .build();
        setItem(4, new GuiItem(headerItem, e -> e.setCancelled(true)));

        ItemStack itemsButton = new ItemBuilder(Material.CHEST)
                .name(Messages.component(getPlugin(), "kits", "editor-edit-kit-items"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-modify-items-armor-slots"),
                        Messages.component(getPlugin(), "kits", "editor-amounts-and-equipment"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-items-2", "%kit_items%", String.valueOf(kit.getKitItems().size())),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-open-items-editor")
                ))
                .build();
        setItem(10, new GuiItem(itemsButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openItemsEditor(getViewer(), kit);
        }));

        ItemStack cooldownButton = new ItemBuilder(Material.CLOCK)
                .name(Messages.component(getPlugin(), "kits", "editor-kit-cooldown"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-time-between-claims"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-s", "%cooldown_seconds%", String.valueOf(KitManager.formatDurationDetailed(kit.getCooldownSeconds())), "%cooldown_seconds_2%", String.valueOf(kit.getCooldownSeconds())),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-1-minute"),
                        Messages.component(getPlugin(), "kits", "editor-1-minute-2"),
                        Messages.component(getPlugin(), "kits", "editor-1-hour"),
                        Messages.component(getPlugin(), "kits", "editor-1-hour-2"),
                        Messages.component(getPlugin(), "kits", "editor-custom-chat-input")
                ))
                .build();
        setItem(11, new GuiItem(cooldownButton, e -> handleCooldownClick(e)));

        String currentPerm = kit.getCustomPermission() != null ? kit.getCustomPermission() : "friendlycore.kits." + kit.getName().toLowerCase(Locale.ROOT);
        ItemStack permButton = new ItemBuilder(kit.isPermissionRequired() ? Material.IRON_DOOR : Material.OAK_DOOR)
                .name(Messages.component(getPlugin(), "kits", "editor-permission"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-require-permission-to-claim"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isPermissionRequired() ? "editor-required-yes" : "editor-required-no-everyone"),
                        Messages.component(getPlugin(), "kits", "editor-node", "%current_perm%", String.valueOf(currentPerm)),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-required"),
                        Messages.component(getPlugin(), "kits", "editor-edit-permission-node")
                ))
                .build();
        setItem(12, new GuiItem(permButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            if (e.isRightClick()) {
                editorManager.startPrompt(getViewer(),
                        "ᴇɴᴛᴇʀ ɴᴇᴡ ᴘᴇʀᴍɪѕѕɪᴏɴ ɴᴏᴅᴇ:",
                        currentPerm,
                        permInput -> {
                            String node = permInput.trim();
                            kit.setCustomPermission(node);
                            kit.setPermissionRequired(!node.equalsIgnoreCase("none") && !node.isEmpty());
                            getPlugin().getKitManager().updateKit(kit);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-permission-node-updated-to", "%node%", String.valueOf(node)));
                            editorManager.openKitDetail(getViewer(), kit);
                        },
                        () -> editorManager.openKitDetail(getViewer(), kit)
                );
            } else {
                kit.setPermissionRequired(!kit.isPermissionRequired());
                getPlugin().getKitManager().updateKit(kit);
                buildGui();
            }
        }));

        ItemStack oneTimeButton = new ItemBuilder(kit.isOneTime() ? Material.TOTEM_OF_UNDYING : Material.EXPERIENCE_BOTTLE)
                .name(Messages.component(getPlugin(), "kits", "editor-one-time-claim"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-allow-claiming-only-once"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isOneTime() ? "editor-status-enabled" : "editor-status-disabled"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(13, new GuiItem(oneTimeButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            kit.setOneTime(!kit.isOneTime());
            getPlugin().getKitManager().updateKit(kit);
            buildGui();
        }));

        ItemStack autoArmorButton = new ItemBuilder(Material.DIAMOND_CHESTPLATE)
                .name(Messages.component(getPlugin(), "kits", "editor-auto-armor"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-auto-equip-wearable-items"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isAutoArmor() ? "editor-status-enabled" : "editor-status-disabled"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(14, new GuiItem(autoArmorButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            kit.setAutoArmor(!kit.isAutoArmor());
            getPlugin().getKitManager().updateKit(kit);
            buildGui();
        }));

        ItemStack clearInvButton = new ItemBuilder(kit.isClearInventory() ? Material.LAVA_BUCKET : Material.BUCKET)
                .name(Messages.component(getPlugin(), "kits", "editor-clear-inventory"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-clear-player-inventory-on-claim"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isClearInventory() ? "editor-status-enabled-2" : "editor-status-disabled-2"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(15, new GuiItem(clearInvButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            kit.setClearInventory(!kit.isClearInventory());
            getPlugin().getKitManager().updateKit(kit);
            buildGui();
        }));

        ItemStack saveOrigButton = new ItemBuilder(Material.ENCHANTED_BOOK)
                .name(Messages.component(getPlugin(), "kits", "editor-save-original-nbt"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-preserve-custom-nbt-and-tags"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isSaveOriginalItems() ? "editor-status-enabled" : "editor-status-disabled"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(16, new GuiItem(saveOrigButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            kit.setSaveOriginalItems(!kit.isSaveOriginalItems());
            getPlugin().getKitManager().updateKit(kit);
            buildGui();
        }));

        ItemStack placeholdersButton = new ItemBuilder(Material.WRITABLE_BOOK)
                .name(Messages.component(getPlugin(), "kits", "editor-placeholders"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-parse-placeholders-in-items"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.isAllowPlaceholdersOnOriginalItems() ? "editor-status-enabled" : "editor-status-disabled"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(19, new GuiItem(placeholdersButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            kit.setAllowPlaceholdersOnOriginalItems(!kit.isAllowPlaceholdersOnOriginalItems());
            getPlugin().getKitManager().updateKit(kit);
            buildGui();
        }));

        double price = kit.getRequirements() != null ? kit.getRequirements().getPrice() : 0.0;
        ItemStack reqButton = new ItemBuilder(Material.GOLD_INGOT)
                .name(Messages.component(getPlugin(), "kits", "editor-price-requirements"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-configure-price-and-conditions"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "price", "%price%", String.valueOf(getPlugin().getNumberFormatter().format(price))),
                        Messages.component(getPlugin(), "kits", kit.getRequirements() != null && kit.getRequirements().isOneTimeRequirements() ? "editor-one-time-buy-yes" : "editor-one-time-buy-no"),
                        Messages.component(getPlugin(), "kits", "editor-rules", "%extra_requirements%", String.valueOf((kit.getRequirements() != null ? kit.getRequirements().getExtraRequirements().size() : 0))),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-open-requirements-editor")
                ))
                .build();
        setItem(21, new GuiItem(reqButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openRequirementsEditor(getViewer(), kit);
        }));

        // Slot 22: Display Items & Icons Button
        ItemStack displayButton = new ItemBuilder(Material.ITEM_FRAME)
                .name(Messages.component(getPlugin(), "kits", "editor-display-icons-states"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-customize-icons-for-all-5-states"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", kit.getDisplayItemDefault() != null ? "editor-default-custom" : "editor-default-auto"),
                        Messages.component(getPlugin(), "kits", kit.getDisplayItemCooldown() != null ? "editor-cooldown-custom" : "editor-cooldown-default"),
                        Messages.component(getPlugin(), "kits", kit.getDisplayItemNoPermission() != null ? "editor-no-perm-custom" : "editor-no-perm-default"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-open-display-editor")
                ))
                .build();
        setItem(22, new GuiItem(displayButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openDisplayEditor(getViewer(), kit);
        }));

        ItemStack actionsButton = new ItemBuilder(Material.COMMAND_BLOCK)
                .name(Messages.component(getPlugin(), "kits", "editor-actions-sounds"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-sounds-titles-and-commands"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-claim-actions", "%claim_actions%", String.valueOf(kit.getClaimActions().size())),
                        Messages.component(getPlugin(), "kits", "editor-error-actions", "%error_actions%", String.valueOf(kit.getErrorActions().size())),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-open-actions-editor")
                ))
                .build();
        setItem(23, new GuiItem(actionsButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openActionsEditor(getViewer(), kit);
        }));

        ItemStack previewButton = new ItemBuilder(Material.ENDER_EYE)
                .name(Messages.component(getPlugin(), "kits", "editor-preview-kit"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-open-the-live-preview-menu"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-preview")
                ))
                .build();
        setItem(25, new GuiItem(previewButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            getPlugin().getKitGuiManager().openPreview(getViewer(), kit.getName(), "main_inventory");
        }));

        ItemStack testClaimButton = new ItemBuilder(Material.DISPENSER)
                .name(Messages.component(getPlugin(), "kits", "editor-test-claim"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-deliver-kit-to-your-inventory"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-test-claim-2")
                ))
                .build();
        setItem(31, new GuiItem(testClaimButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            getPlugin().getKitManager().claimKitAsync(getViewer(), kit, true, false);
        }));

        ItemStack deleteButton = new ItemBuilder(Material.TNT)
                .name(Messages.component(getPlugin(), "kits", "editor-delete-kit"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-permanently-remove-this-kit"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-delete-confirmation")
                ))
                .build();
        setItem(38, new GuiItem(deleteButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            editorManager.openDeleteConfirm(getViewer(), kit);
        }));

        ItemStack saveButton = new ItemBuilder(Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", "editor-save-kit"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-save-changes-to-disk-and-network"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-save")
                ))
                .build();
        setItem(40, new GuiItem(saveButton, e -> {
            e.setCancelled(true);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.4f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-kit-saved-successfully", "%kit%", kit.getName()));
            buildGui();
        }));

        ItemStack backButton = new ItemBuilder(Material.ARROW)
                .name(Messages.component(getPlugin(), "kits", "back"))
                .lore(List.of(Messages.component(getPlugin(), "kits", "editor-return-to-kit-list")))
                .build();
        setItem(42, new GuiItem(backButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitList(getViewer(), 1);
        }));
    }

    private void handleCooldownClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        event.setCancelled(true);
        ClickType click = event.getClick();
        long current = kit.getCooldownSeconds();

        if (click == ClickType.MIDDLE || click == ClickType.DROP) {
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ɴᴇᴡ ᴄᴏᴏʟᴅᴏᴡɴ (ᴇ.ɢ. 1ᴅ 12ʜ, 45ᴍ, 3600, 0):",
                    KitManager.formatDurationDetailed(current),
                    durationInput -> {
                        long seconds = KitManager.parseDurationSeconds(durationInput);
                        kit.setCooldownSeconds(seconds);
                        getPlugin().getKitManager().updateKit(kit);
                        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-cooldown-updated-to-s", "%seconds%", String.valueOf(KitManager.formatDurationDetailed(seconds)), "%seconds_2%", String.valueOf(seconds)));
                        editorManager.openKitDetail(getViewer(), kit);
                    },
                    () -> editorManager.openKitDetail(getViewer(), kit)
            );
            return;
        }

        long delta = 0;
        if (click.isShiftClick()) {
            delta = click.isLeftClick() ? 3600 : -3600;
        } else {
            delta = click.isLeftClick() ? 60 : -60;
        }

        long newCd = Math.max(0, current + delta);
        kit.setCooldownSeconds(newCd);
        getPlugin().getKitManager().updateKit(kit);
        getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        buildGui();
    }
}
