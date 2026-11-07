package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.model.KitRequirements;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive menu for configuring kit pricing, one-time purchase unlock, and placeholder requirements.
 */
public class KitRequirementsEditorGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;

    public KitRequirementsEditorGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-price-requirements-2", "%kit%", kit.getName()),
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

        KitRequirements req = kit.getRequirements();
        if (req == null) {
            req = new KitRequirements(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0.0);
            kit.setRequirements(req);
        }

        double price = req.getPrice();
        boolean oneTimeReq = req.isOneTimeRequirements();

        ItemStack infoHeader = new ItemBuilder(Material.GOLD_INGOT)
                .name(Messages.component(getPlugin(), "kits", "editor-economy-requirements"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-configure-pricing-and-conditions-for", "%kit%", kit.getName()),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "price", "%price%", String.valueOf(getPlugin().getNumberFormatter().format(price))),
                        Messages.component(getPlugin(), "kits", oneTimeReq ? "editor-one-time-buy-yes-unlock-once" : "editor-one-time-buy-no-pay-every"),
                        Messages.component(getPlugin(), "kits", "editor-rules", "%extra_requirements%", String.valueOf(req.getExtraRequirements().size()))
                ))
                .build();
        setItem(4, new GuiItem(infoHeader, e -> e.setCancelled(true)));

        // Price Increments (Lime Glass Panes: slots 10, 11, 12, 13, 14)
        setupPriceButton(10, 10.0, true, Material.LIME_STAINED_GLASS_PANE);
        setupPriceButton(11, 100.0, true, Material.LIME_STAINED_GLASS_PANE);
        setupPriceButton(12, 1000.0, true, Material.LIME_STAINED_GLASS_PANE);
        setupPriceButton(13, 10000.0, true, Material.LIME_STAINED_GLASS_PANE);
        setupPriceButton(14, 50000.0, true, Material.LIME_STAINED_GLASS_PANE);

        // Price Decrements (Red Glass Panes: slots 19, 20, 21, 22, 23)
        setupPriceButton(19, 10.0, false, Material.RED_STAINED_GLASS_PANE);
        setupPriceButton(20, 100.0, false, Material.RED_STAINED_GLASS_PANE);
        setupPriceButton(21, 1000.0, false, Material.RED_STAINED_GLASS_PANE);
        setupPriceButton(22, 10000.0, false, Material.RED_STAINED_GLASS_PANE);
        setupPriceButton(23, 50000.0, false, Material.RED_STAINED_GLASS_PANE);

        ItemStack customPriceItem = new ItemBuilder(Material.NAME_TAG)
                .name(Messages.component(getPlugin(), "kits", "editor-set-custom-price"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-current-2", "%price%", String.valueOf(getPlugin().getNumberFormatter().format(price))),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-enter-price-in-chat")
                ))
                .build();
        setItem(16, new GuiItem(customPriceItem, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.startPrompt(getViewer(),
                    "ᴇɴᴛᴇʀ ɴᴇᴡ ᴘᴜʀᴄʜᴀѕᴇ ᴘʀɪᴄᴇ:",
                    String.valueOf(kit.getRequirements() != null ? kit.getRequirements().getPrice() : 0.0),
                    priceInput -> {
                        try {
                            double newPrice = Math.max(0.0, Double.parseDouble(priceInput.trim().replace("$", "").replace(",", "")));
                            KitRequirements r = kit.getRequirements();
                            kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), r.getExtraRequirements(), r.getMessage(), r.getActionsOnBuy(), newPrice));
                            getPlugin().getKitManager().updateKit(kit);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-price-updated-to", "%new_price%", String.valueOf(getPlugin().getNumberFormatter().format(newPrice))));
                        } catch (NumberFormatException ex) {
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-invalid-price-number"));
                        }
                        editorManager.openRequirementsEditor(getViewer(), kit);
                    },
                    () -> editorManager.openRequirementsEditor(getViewer(), kit)
            );
        }));

        // Slot 25: Free / Reset Price ($0)
        ItemStack freePriceItem = new ItemBuilder(Material.WATER_BUCKET)
                .name(Messages.component(getPlugin(), "kits", "editor-make-free-0"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-reset-purchase-price-to-0")
                ))
                .build();
        setItem(25, new GuiItem(freePriceItem, e -> {
            e.setCancelled(true);
            KitRequirements r = kit.getRequirements();
            kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), r.getExtraRequirements(), r.getMessage(), r.getActionsOnBuy(), 0.0));
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.4f);
            buildGui();
        }));

        ItemStack oneTimeReqItem = new ItemBuilder(oneTimeReq ? Material.BEACON : Material.DISPENSER)
                .name(Messages.component(getPlugin(), "kits", "editor-one-time-unlock"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-buy-once-then-claim-free"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", oneTimeReq ? "editor-status-enabled" : "editor-status-disabled"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-toggle-status")
                ))
                .build();
        setItem(28, new GuiItem(oneTimeReqItem, e -> {
            e.setCancelled(true);
            KitRequirements r = kit.getRequirements();
            kit.setRequirements(new KitRequirements(!r.isOneTimeRequirements(), r.getExtraRequirements(), r.getMessage(), r.getActionsOnBuy(), r.getPrice()));
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            buildGui();
        }));

        List<String> extraReqs = req.getExtraRequirements();
        List<Component> extraLore = new ArrayList<>();
        extraLore.add(Messages.component(getPlugin(), "kits", "editor-conditional-requirement-rules"));
        extraLore.add(Component.empty());
        if (extraReqs.isEmpty()) {
            extraLore.add(Messages.component(getPlugin(), "kits", "editor-none-configured"));
        } else {
            for (String ex : extraReqs) {
                extraLore.add(Messages.component(getPlugin(), "kits", "editor-label-4", "%ex%", String.valueOf(ex)));
            }
        }
        extraLore.add(Component.empty());
        extraLore.add(Messages.component(getPlugin(), "kits", "editor-add-condition"));
        extraLore.add(Messages.component(getPlugin(), "kits", "editor-clear-all-2"));

        ItemStack extraItem = new ItemBuilder(Material.WRITABLE_BOOK)
                .name(Messages.component(getPlugin(), "kits", "editor-extra-requirements"))
                .lore(extraLore)
                .build();
        setItem(30, new GuiItem(extraItem, e -> {
            e.setCancelled(true);
            if (e.isRightClick()) {
                KitRequirements r = kit.getRequirements();
                kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), new ArrayList<>(), r.getMessage(), r.getActionsOnBuy(), r.getPrice()));
                getPlugin().getKitManager().updateKit(kit);
                getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-cleared-all-extra-requirements"));
                buildGui();
            } else {
                getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                editorManager.startPrompt(getViewer(),
                        "ᴇɴᴛᴇʀ ʀᴇǫᴜɪʀᴇᴍᴇɴᴛ ᴄᴏɴᴅɪᴛɪᴏɴ (ᴇ.ɢ. '%player_level% >= 10'):",
                        "",
                        condInput -> {
                            String cond = condInput.trim();
                            if (!cond.isEmpty()) {
                                KitRequirements r = kit.getRequirements();
                                List<String> list = new ArrayList<>(r.getExtraRequirements());
                                list.add(cond);
                                kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), list, r.getMessage(), r.getActionsOnBuy(), r.getPrice()));
                                getPlugin().getKitManager().updateKit(kit);
                                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-added-condition", "%cond%", String.valueOf(cond)));
                            }
                            editorManager.openRequirementsEditor(getViewer(), kit);
                        },
                        () -> editorManager.openRequirementsEditor(getViewer(), kit)
                );
            }
        }));

        List<String> msgList = req.getMessage();
        List<Component> msgLore = new ArrayList<>();
        msgLore.add(Messages.component(getPlugin(), "kits", "editor-custom-message-if-requirement-fails"));
        msgLore.add(Component.empty());
        if (msgList.isEmpty()) {
            msgLore.add(Messages.component(getPlugin(), "kits", "editor-default-system-error-message"));
        } else {
            for (String m : msgList) {
                msgLore.add(Messages.component(getPlugin(), "kits", "editor-label-5", "%m%", String.valueOf(m)));
            }
        }
        msgLore.add(Component.empty());
        msgLore.add(Messages.component(getPlugin(), "kits", "editor-edit-message"));
        msgLore.add(Messages.component(getPlugin(), "kits", "editor-reset-to-default"));

        ItemStack msgItem = new ItemBuilder(Material.PAPER)
                .name(Messages.component(getPlugin(), "kits", "editor-error-message"))
                .lore(msgLore)
                .build();
        setItem(32, new GuiItem(msgItem, e -> {
            e.setCancelled(true);
            if (e.isRightClick()) {
                KitRequirements r = kit.getRequirements();
                kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), r.getExtraRequirements(), new ArrayList<>(), r.getActionsOnBuy(), r.getPrice()));
                getPlugin().getKitManager().updateKit(kit);
                getViewer().playSound(getViewer().getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-reset-error-message-to-default"));
                buildGui();
            } else {
                getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                List<String> curMsg = kit.getRequirements() != null ? kit.getRequirements().getMessage() : List.of();
                editorManager.startPrompt(getViewer(),
                        "ᴇɴᴛᴇʀ ᴇʀʀᴏʀ ᴍᴇѕѕᴀɢᴇ (ѕᴇᴘᴀʀᴀᴛᴇ ᴡɪᴛʜ ';'):",
                        String.join(" ; ", curMsg),
                        msgInput -> {
                            KitRequirements r = kit.getRequirements();
                            List<String> list = new ArrayList<>();
                            for (String part : msgInput.split(";")) {
                                if (!part.trim().isEmpty()) list.add(part.trim());
                            }
                            kit.setRequirements(new KitRequirements(r.isOneTimeRequirements(), r.getExtraRequirements(), list, r.getActionsOnBuy(), r.getPrice()));
                            getPlugin().getKitManager().updateKit(kit);
                            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-updated-requirement-error-message"));
                            editorManager.openRequirementsEditor(getViewer(), kit);
                        },
                        () -> editorManager.openRequirementsEditor(getViewer(), kit)
                );
            }
        }));

        ItemStack backButton = new ItemBuilder(Material.ARROW)
                .name(Messages.component(getPlugin(), "kits", "back"))
                .lore(List.of(Messages.component(getPlugin(), "kits", "editor-return-to-kit-dashboard")))
                .build();
        setItem(45, new GuiItem(backButton, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitDetail(getViewer(), kit);
        }));

        ItemStack saveButton = new ItemBuilder(Material.EMERALD)
                .name(Messages.component(getPlugin(), "kits", "editor-save-requirements"))
                .build();
        setItem(53, new GuiItem(saveButton, e -> {
            e.setCancelled(true);
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-requirements-for-saved", "%kit%", kit.getName()));
            editorManager.openKitDetail(getViewer(), kit);
        }));
    }

    private void setupPriceButton(int slot, double amount, boolean increment, Material mat) {
        String sign = increment ? "+" : "-";
        String color = increment ? "<color:#8DE969>" : "<color:#FF4D5A>";
        ItemStack btn = new ItemBuilder(mat)
                .name(Messages.component(getPlugin(), "kits", "editor-label-6", "%color%", String.valueOf(color), "%sign%", String.valueOf(sign), "%amount%", String.valueOf(getPlugin().getNumberFormatter().format(amount))))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", increment ? "editor-click-to-increase-price-by" : "editor-click-to-decrease-price-by", "%amount%", String.valueOf(getPlugin().getNumberFormatter().format(amount)))
                ))
                .build();

        setItem(slot, new GuiItem(btn, e -> {
            e.setCancelled(true);
            KitRequirements r = kit.getRequirements();
            double current = r != null ? r.getPrice() : 0.0;
            double next = increment ? current + amount : Math.max(0.0, current - amount);
            kit.setRequirements(new KitRequirements(r != null && r.isOneTimeRequirements(),
                    r != null ? r.getExtraRequirements() : List.of(),
                    r != null ? r.getMessage() : List.of(),
                    r != null ? r.getActionsOnBuy() : List.of(),
                    next));
            getPlugin().getKitManager().updateKit(kit);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            buildGui();
        }));
    }
}
