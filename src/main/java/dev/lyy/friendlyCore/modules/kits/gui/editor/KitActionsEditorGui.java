package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.model.KitAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive menu for managing kit claim actions and error actions.
 */
public class KitActionsEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;
    private boolean viewingClaimActions = true; // true = claim actions, false = error actions

    public KitActionsEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-actions-editor", "%kit%", kit.getName()),
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
        for (int i = 0; i < 9; i++) {
            setItem(i, new GuiItem(border, e -> e.setCancelled(true)));
        }

        ItemStack claimTab = new ItemBuilder(viewingClaimActions ? Material.EMERALD_BLOCK : Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", viewingClaimActions ? "editor-tab-claim-actions-active" : "editor-tab-claim-actions",
                        "%count%", String.valueOf(kit.getClaimActions().size())))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-actions-on-successful-claim"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-view-claim-actions")
                ))
                .build();
        setItem(2, new GuiItem(claimTab, e -> {
            e.setCancelled(true);
            viewingClaimActions = true;
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            buildGui();
        }));

        ItemStack errorTab = new ItemBuilder(!viewingClaimActions ? Material.REDSTONE_BLOCK : Material.REDSTONE)
                .name(Messages.component(getPlugin(), "kits", !viewingClaimActions ? "editor-tab-error-actions-active" : "editor-tab-error-actions",
                        "%count%", String.valueOf(kit.getErrorActions().size())))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-actions-on-claim-failure"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-view-error-actions")
                ))
                .build();
        setItem(6, new GuiItem(errorTab, e -> {
            e.setCancelled(true);
            viewingClaimActions = false;
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            buildGui();
        }));

        List<KitAction> currentList = viewingClaimActions ? kit.getClaimActions() : kit.getErrorActions();
        ItemStack infoItem = new ItemBuilder(Material.COMMAND_BLOCK)
                .name(Messages.component(getPlugin(), "kits", viewingClaimActions ? "editor-claim-actions-2" : "editor-error-actions-2"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-total-actions-actions", "%current_list%", String.valueOf(currentList.size())),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-test-execute"),
                        Messages.component(getPlugin(), "kits", "editor-delete-action"),
                        Messages.component(getPlugin(), "kits", "editor-toggle-execute-before-items")
                ))
                .build();
        setItem(4, new GuiItem(infoItem, e -> e.setCancelled(true)));

        // Action Cards in Content Area (Slots 9 to 44)
        int slotCursor = 9;
        for (int i = 0; i < currentList.size() && slotCursor <= 44; i++) {
            KitAction action = currentList.get(i);
            int actionIndex = i;
            ItemStack actionCard = buildActionCard(action, actionIndex);
            setItem(slotCursor, new GuiItem(actionCard, e -> handleActionCardClick(e, actionIndex)));
            slotCursor++;
        }

        while (slotCursor <= 44) {
            setItem(slotCursor, (GuiItem) null);
            slotCursor++;
        }

        for (int i = 45; i < 54; i++) {
            setItem(i, new GuiItem(border, e -> e.setCancelled(true)));
        }

        ItemStack backButton = new ItemBuilder(Material.ARROW)
                .name(Messages.component(getPlugin(), "kits", "back"))
                .lore(List.of(Messages.component(getPlugin(), "kits", "editor-return-to-kit-dashboard")))
                .build();
        setItem(45, new GuiItem(backButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitDetail(getViewer(), kit);
        }));

        // Quick Add Buttons:
        // Slot 47: Add Sound Action
        ItemStack soundBtn = new ItemBuilder(Material.JUKEBOX)
                .name(Messages.component(getPlugin(), "kits", "editor-add-sound"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-levelup-sound"),
                        Messages.component(getPlugin(), "kits", "editor-firework-sound"),
                        Messages.component(getPlugin(), "kits", "editor-custom-sound")
                ))
                .build();
        setItem(47, new GuiItem(soundBtn, e -> handleAddSoundClick(e)));

        ItemStack fireworkBtn = new ItemBuilder(Material.FIREWORK_ROCKET)
                .name(Messages.component(getPlugin(), "kits", "editor-add-firework"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-celebratory-firework-effect")
                ))
                .build();
        setItem(48, new GuiItem(fireworkBtn, e -> {
            e.setCancelled(true);
            addAction("firework: BALL;AQUA,LIME;WHITE;true;true");
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-firework-action"));
            buildGui();
        }));

        ItemStack cmdBtn = new ItemBuilder(Material.COMMAND_BLOCK)
                .name(Messages.component(getPlugin(), "kits", "editor-add-command"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-run-server-command-as-console")
                ))
                .build();
        setItem(49, new GuiItem(cmdBtn, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ᴄᴏɴѕᴏʟᴇ ᴄᴏᴍᴍᴀɴᴅ (ᴡɪᴛʜᴏᴜᴛ '/'):",
                    "",
                    cmdInput -> {
                        String cmd = cmdInput.trim().startsWith("/") ? cmdInput.trim().substring(1) : cmdInput.trim();
                        if (!cmd.isEmpty()) {
                            addAction("console_command: " + cmd);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-command", "%cmd%", String.valueOf(cmd)));
                        }
                        editorManager.openActionsEditor(getViewer(), kit);
                    },
                    () -> editorManager.openActionsEditor(getViewer(), kit)
            );
        }));

        ItemStack titleBtn = new ItemBuilder(Material.OAK_SIGN)
                .name(Messages.component(getPlugin(), "kits", "editor-add-title"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-display-screen-title-on-claim")
                ))
                .build();
        setItem(50, new GuiItem(titleBtn, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ᴛɪᴛʟᴇ ᴀɴᴅ ѕᴜʙᴛɪᴛʟᴇ ѕᴇᴘᴀʀᴀᴛᴇᴅ ʙʏ ';':",
                    "",
                    titleInput -> {
                        String[] parts = titleInput.split(";");
                        String mainTitle = parts.length > 0 ? parts[0].trim() : "Kit Claimed!";
                        String subTitle = parts.length > 1 ? parts[1].trim() : "";
                        addAction("title: " + mainTitle + ";" + subTitle + ";10;60;20");
                        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-title-action"));
                        editorManager.openActionsEditor(getViewer(), kit);
                    },
                    () -> editorManager.openActionsEditor(getViewer(), kit)
            );
        }));

        ItemStack rawBtn = new ItemBuilder(Material.ANVIL)
                .name(Messages.component(getPlugin(), "kits", "editor-add-raw-action"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-enter-any-raw-action-string")
                ))
                .build();
        setItem(51, new GuiItem(rawBtn, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ʀᴀᴡ ᴀᴄᴛɪᴏɴ (ᴇ.ɢ. 'message: <green>Enjoy!'):",
                    "",
                    rawInput -> {
                        String raw = rawInput.trim();
                        if (!raw.isEmpty()) {
                            addAction(raw);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-action", "%raw%", String.valueOf(raw)));
                        }
                        editorManager.openActionsEditor(getViewer(), kit);
                    },
                    () -> editorManager.openActionsEditor(getViewer(), kit)
            );
        }));

        ItemStack saveBtn = new ItemBuilder(Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", "editor-save-actions"))
                .build();
        setItem(53, new GuiItem(saveBtn, e -> {
            e.setCancelled(true);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-actions-for-saved", "%kit%", kit.getName()));
            editorManager.openKitDetail(getViewer(), kit);
        }));
    }

    private ItemStack buildActionCard(KitAction action, int index) {
        String actStr = action.getAction();
        Material mat = Material.REDSTONE_TORCH;
        if (actStr.startsWith("playsound:")) mat = Material.JUKEBOX;
        else if (actStr.startsWith("firework:")) mat = Material.FIREWORK_ROCKET;
        else if (actStr.startsWith("console_command:")) mat = Material.COMMAND_BLOCK;
        else if (actStr.startsWith("player_command:")) mat = Material.REPEATER;
        else if (actStr.startsWith("title:")) mat = Material.OAK_SIGN;
        else if (actStr.startsWith("actionbar:")) mat = Material.WRITABLE_BOOK;
        else if (actStr.startsWith("message:")) mat = Material.PAPER;

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-action", "%act_str%", String.valueOf(actStr)));
        lore.add(Messages.component(getPlugin(), "kits", action.isExecuteBeforeItems() ? "editor-before-items-yes" : "editor-before-items-no"));
        lore.add(Component.empty());
        lore.add(Messages.component(getPlugin(), "kits", "editor-test-execute"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-toggle-order"));
        lore.add(Messages.component(getPlugin(), "kits", "editor-delete-action"));

        return new ItemBuilder(mat)
                .name(Messages.component(getPlugin(), "kits", "editor-action-2", "%index%", String.valueOf((index + 1))))
                .lore(lore)
                .build();
    }

    private void handleActionCardClick(org.bukkit.event.inventory.InventoryClickEvent event, int index) {
        event.setCancelled(true);
        ClickType click = event.getClick();
        List<KitAction> currentList = viewingClaimActions ? kit.getClaimActions() : kit.getErrorActions();
        if (index < 0 || index >= currentList.size()) return;

        KitAction action = currentList.get(index);

        if (click.isRightClick()) {
            currentList.remove(index);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-removed-action", "%index%", String.valueOf((index + 1))));
            buildGui();
            return;
        }

        if (click.isShiftClick()) {
            // Toggle Execute Before Items
            KitAction updated = new KitAction(action.getAction(), action.getDisplayItem(), !action.isExecuteBeforeItems(), action.isCountAsItem());
            currentList.set(index, updated);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            buildGui();
            return;
        }

        // Left-Click -> Test Execute
        getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-testing", "%action%", String.valueOf(action.getAction())));
        getPlugin().getKitManager().getActionExecutor().executeAction(getViewer(), action.getAction(), kit);
    }

    private void handleAddSoundClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        event.setCancelled(true);
        ClickType click = event.getClick();

        if (click.isShiftClick()) {
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ѕᴏᴜɴᴅ (ᴇ.ɢ. 'ENTITY_PLAYER_LEVELUP;1.0;1.0'):",
                    "",
                    soundInput -> {
                        String raw = soundInput.trim();
                        if (!raw.isEmpty()) {
                            addAction("playsound: " + raw);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-sound", "%raw%", String.valueOf(raw)));
                        }
                        editorManager.openActionsEditor(getViewer(), kit);
                    },
                    () -> editorManager.openActionsEditor(getViewer(), kit)
            );
        } else if (click.isRightClick()) {
            addAction("playsound: ENTITY_FIREWORK_ROCKET_LAUNCH;1.0;1.2");
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-firework-sound"));
            buildGui();
        } else {
            addAction("playsound: ENTITY_PLAYER_LEVELUP;1.0;1.0");
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-level-up-sound"));
            buildGui();
        }
    }

    private void addAction(String actionText) {
        KitAction action = new KitAction(actionText, null, false, false);
        if (viewingClaimActions) {
            List<KitAction> list = new ArrayList<>(kit.getClaimActions());
            list.add(action);
            kit.setClaimActions(list);
        } else {
            List<KitAction> list = new ArrayList<>(kit.getErrorActions());
            list.add(action);
            kit.setErrorActions(list);
        }
        getPlugin().getKitManager().updateKit(kit);
    }
}
