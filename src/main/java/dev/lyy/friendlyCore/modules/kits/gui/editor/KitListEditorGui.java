package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitItemParser;
import dev.lyy.friendlycore.modules.kits.KitManager;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Paginated interactive GUI listing all registered kits for management and editing.
 */
public class KitListEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;

    public KitListEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, int page) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-all-kits"),
                6);
        this.editorManager = editorManager;
        setCurrentPage(Math.max(1, page));
        buildGui();
    }

    public void buildGui() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }

        ItemStack border = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
        for (int i = 0; i < 9; i++) {
            setItem(i, new GuiItem(border, e -> e.setCancelled(true)));
        }

        ItemStack createItem = new ItemBuilder(Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", "editor-create-new-kit"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-create-a-new-kit-definition"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-create-from-your-inventory"),
                        Messages.component(getPlugin(), "kits", "editor-create-empty-template")
                ))
                .build();
        setItem(2, new GuiItem(createItem, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            boolean fromInv = e.isLeftClick();

            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ɴᴇᴡ ᴋɪᴛ ɴᴀᴍᴇ:",
                    "",
                    nameInput -> {
                        String name = nameInput.trim().replace(" ", "_");
                        if (name.isEmpty() || name.length() > 32) {
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-invalid-kit-name-1-32-chars"));
                            editorManager.openKitList(getViewer(), getCurrentPage());
                            return;
                        }
                        if (getPlugin().getKitManager().getKit(name) != null) {
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "a-kit-with-name-already-exists", "%kit%", name));
                            editorManager.openKitList(getViewer(), getCurrentPage());
                            return;
                        }

                        boolean created;
                        if (fromInv) {
                            created = getPlugin().getKitManager().createKit(name, getViewer(), true);
                        } else {
                            Kit emptyKit = new Kit(name);
                            emptyKit.setCooldownSeconds(3600);
                            KitItem defaultDisplay = new KitItem("CHEST");
                            defaultDisplay.setName("<gradient:#7BD88F:#B9FBC0><bold>" + name + " Kit</bold></gradient>");
                            defaultDisplay.setLore(List.of("<color:#D8D0C3>Newly created kit.</color>", "", "<color:#8DE969>Left-Click to claim</color>", "<color:#6EDBFF>Right-Click to preview</color>"));
                            emptyKit.setDisplayItemDefault(defaultDisplay);
                            created = getPlugin().getKitManager().updateKit(emptyKit);
                        }

                        if (created) {
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-kit-created-successfully", "%kit%", name));
                            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                            Kit createdKit = getPlugin().getKitManager().getKit(name);
                            editorManager.openKitDetail(getViewer(), createdKit);
                        } else {
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-inventory-was-empty-created-empty-template"));
                            Kit emptyKit = new Kit(name);
                            emptyKit.setCooldownSeconds(3600);
                            getPlugin().getKitManager().updateKit(emptyKit);
                            Kit createdKit = getPlugin().getKitManager().getKit(name);
                            editorManager.openKitDetail(getViewer(), createdKit);
                        }
                    },
                    () -> editorManager.openKitList(getViewer(), getCurrentPage())
            );
        }));

        int totalKits = getPlugin().getKitManager().getAllKits().size();
        ItemStack infoItem = new ItemBuilder(Material.NETHER_STAR)
                .name(Messages.component(getPlugin(), "kits", "editor-kit-system"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-manage-and-edit-all-server-kits"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-total-kits", "%total_kits%", String.valueOf(totalKits))
                ))
                .build();
        setItem(4, new GuiItem(infoItem, e -> e.setCancelled(true)));

        ItemStack reloadItem = new ItemBuilder(Material.FEATHER)
                .name(Messages.component(getPlugin(), "kits", "editor-reload-kits"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-reload-all-kits-from-disk-and")
                ))
                .build();
        setItem(6, new GuiItem(reloadItem, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.4f);
            getPlugin().getKitManager().loadSettings();
            getPlugin().getKitManager().loadKits();
            if (getPlugin().getKitGuiManager() != null) {
                getPlugin().getKitGuiManager().loadConfig();
            }
            if (getPlugin().getKitManager().getCrossServerService() != null) {
                getPlugin().getKitManager().getCrossServerService().broadcastReload();
            }
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-kits-and-menus-reloaded-successfully"));
            buildGui();
        }));

        // Kit Cards in Content Area (Slots 9 - 44)
        List<Kit> sortedKits = new ArrayList<>(getPlugin().getKitManager().getAllKits());
        sortedKits.sort(Comparator.comparing(Kit::getName, String.CASE_INSENSITIVE_ORDER));

        int contentSlots = 36; // slots 9 to 44
        int totalPages = Math.max(1, (int) Math.ceil((double) sortedKits.size() / contentSlots));
        setMaxPages(totalPages);
        if (getCurrentPage() > totalPages) setCurrentPage(totalPages);

        int startIndex = (getCurrentPage() - 1) * contentSlots;
        int endIndex = Math.min(startIndex + contentSlots, sortedKits.size());

        int slotCursor = 9;
        for (int i = startIndex; i < endIndex; i++) {
            Kit kit = sortedKits.get(i);
            ItemStack kitCard = buildKitCard(kit);
            setItem(slotCursor, new GuiItem(kitCard, e -> handleKitCardClick(e, kit)));
            slotCursor++;
        }

        while (slotCursor <= 44) {
            setItem(slotCursor, (GuiItem) null);
            slotCursor++;
        }

        // Bottom Navigation Bar (Slots 45 - 53)
        for (int i = 45; i < 54; i++) {
            if (i != 48 && i != 49 && i != 50 && i != 45) {
                setItem(i, new GuiItem(border, e -> e.setCancelled(true)));
            }
        }

        ItemStack closeItem = new ItemBuilder(Material.BARRIER)
                .name(Messages.component(getPlugin(), "kits", "editor-close"))
                .build();
        setItem(45, new GuiItem(closeItem, e -> {
            e.setCancelled(true);
            getViewer().closeInventory();
        }));

        addNavigationControls(
                () -> {
                    setCurrentPage(getCurrentPage() - 1);
                    buildGui();
                },
                () -> {
                    setCurrentPage(getCurrentPage() + 1);
                    buildGui();
                }
        );
    }

    private ItemStack buildKitCard(Kit kit) {
        ItemStack icon = null;
        if (kit.getDisplayItemDefault() != null) {
            icon = KitItemParser.toItemStack(kit.getDisplayItemDefault(), getViewer(), kit);
        }
        if (icon == null || icon.getType().isAir()) {
            if (!kit.getKitItems().isEmpty()) {
                icon = KitItemParser.toItemStack(kit.getKitItems().get(0), getViewer(), kit);
            }
        }
        if (icon == null || icon.getType().isAir()) {
            icon = new ItemStack(Material.CHEST);
        } else {
            icon = icon.clone();
        }
        icon.setAmount(1);

        String cooldownStr = KitManager.formatDurationDetailed(kit.getCooldownSeconds());
        String permStr = kit.isPermissionRequired()
                ? "<color:#E7B85C>" + (kit.getCustomPermission() != null ? kit.getCustomPermission() : "friendlycore.kits." + kit.getName().toLowerCase(Locale.ROOT)) + "</color>"
                : "<color:#8DE969>ᴇᴠᴇʀʏᴏɴᴇ</color>";
        String priceStr = (kit.getRequirements() != null && kit.getRequirements().getPrice() > 0)
                ? "<color:#8DE969>$" + getPlugin().getNumberFormatter().format(kit.getRequirements().getPrice()) + "</color>"
                : "<color:#8DE969>ғʀᴇᴇ</color>";

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-items", "%kit_items%", String.valueOf(kit.getKitItems().size())));
        lore.add(Messages.component(getPlugin(), "kits", "editor-cooldown-2", "%cooldown_str%", String.valueOf(cooldownStr)));
        lore.add(Messages.component(getPlugin(), "kits", "editor-permission-2", "%perm_str%", String.valueOf(permStr)));
        lore.add(Messages.component(getPlugin(), "kits", "editor-price", "%price_str%", String.valueOf(priceStr)));
        lore.add(Messages.component(getPlugin(), "kits", "editor-list-auto-armor-one-time",
                "%auto_armor_formatted%", yesNo(kit.isAutoArmor()), "%one_time_formatted%", yesNo(kit.isOneTime())));
        lore.add(Messages.component(getPlugin(), "kits", "editor-list-clear-inv-original-nbt",
                "%clear_inventory_formatted%", yesNo(kit.isClearInventory()), "%original_nbt_formatted%", yesNo(kit.isSaveOriginalItems())));
        lore.add(Messages.component(getPlugin(), "kits", "editor-claim-error", "%claim_actions%", String.valueOf(kit.getClaimActions().size()), "%error_actions%", String.valueOf(kit.getErrorActions().size())));
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-open-settings-dashboard"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-direct-edit-items"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-preview-kit-2"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-test-claim-3"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-delete-kit-2"));

        return new ItemBuilder(icon)
                .name(Messages.component(getPlugin(), "kits", "editor-label-3", "%kit%", kit.getName()))
                .lore(lore)
                .build();
    }

    private void handleKitCardClick(org.bukkit.event.inventory.InventoryClickEvent event, Kit kit) {
        event.setCancelled(true);
        ClickType click = event.getClick();

        if (click == ClickType.MIDDLE) {
            // Middle-Click -> Test Claim
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            getPlugin().getKitManager().claimKitAsync(getViewer(), kit, true, false);
            return;
        }

        if (click.isShiftClick() && click.isLeftClick()) {
            // Shift + Left -> Preview Kit
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            getPlugin().getKitGuiManager().openPreview(getViewer(), kit.getName(), "main_inventory");
            return;
        }

        if (click.isShiftClick() && click.isRightClick()) {
            // Shift + Right -> Delete Kit Confirmation
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            editorManager.openDeleteConfirm(getViewer(), kit);
            return;
        }

        if (click.isRightClick()) {
            // Right-Click -> Direct Items Editor
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openItemsEditor(getViewer(), kit);
            return;
        }

        // Left-Click -> Kit Detail Overview
        getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
        editorManager.openKitDetail(getViewer(), kit);
    }

    private String yesNo(boolean value) {
        return Messages.raw(getPlugin(), "kits", value ? "editor-yes" : "editor-no");
    }
}
