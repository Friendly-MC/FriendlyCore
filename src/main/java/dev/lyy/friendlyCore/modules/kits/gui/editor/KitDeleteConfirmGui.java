package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitItemParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Safety confirmation dialog before permanently deleting a kit definition.
 */
public class KitDeleteConfirmGui extends PaginatedGui {

    private final KitEditorManager editorManager;
    private final Kit kit;

    public KitDeleteConfirmGui(FriendlyCore plugin, KitEditorManager editorManager, Player viewer, Kit kit) {
        super(plugin, viewer,
                Messages.component(plugin, "kits", "editor-delete", "%kit%", kit.getName()),
                3);
        this.editorManager = editorManager;
        this.kit = kit;
        buildGui();
    }

    public void buildGui() {
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();
        fillEmpty(filler);

        ItemStack icon = null;
        if (kit.getDisplayItemDefault() != null) {
            icon = KitItemParser.toItemStack(kit.getDisplayItemDefault(), getViewer(), kit);
        }
        if (icon == null || icon.getType().isAir()) {
            icon = new ItemStack(Material.TNT);
        } else {
            icon = icon.clone();
        }
        icon.setAmount(1);

        ItemStack centerInfo = new ItemBuilder(icon)
                .name(Messages.component(getPlugin(), "kits", "editor-label", "%kit%", kit.getName()))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-permanently-delete-this-kit"),
                        Component.empty(),
                        Messages.component(getPlugin(), "kits", "editor-items", "%kit_items%", String.valueOf(kit.getKitItems().size())),
                        Messages.component(getPlugin(), "kits", "editor-this-action-cannot-be-undone")
                ))
                .build();
        setItem(13, new GuiItem(centerInfo, e -> e.setCancelled(true)));

        ItemStack cancelItem = new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(Messages.component(getPlugin(), "kits", "editor-no-cancel"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-keep-this-kit-and-return")
                ))
                .build();
        setItem(11, new GuiItem(cancelItem, e -> {
            e.setCancelled(true);
            getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            editorManager.openKitDetail(getViewer(), kit);
        }));

        ItemStack confirmItem = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                .name(Messages.component(getPlugin(), "kits", "editor-yes-delete"))
                .lore(List.of(
                        Messages.component(getPlugin(), "kits", "editor-permanently-erase-kit")
                ))
                .build();
        setItem(15, new GuiItem(confirmItem, e -> {
            e.setCancelled(true);
            boolean deleted = getPlugin().getKitManager().deleteKit(kit.getName());
            if (deleted) {
                getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-kit-deleted-successfully", "%kit%", kit.getName()));
            } else {
                getViewer().sendMessage(Messages.component(getPlugin(), "kits", "editor-that-kit-does-not-exist"));
            }
            editorManager.openKitList(getViewer(), 1);
        }));
    }
}
