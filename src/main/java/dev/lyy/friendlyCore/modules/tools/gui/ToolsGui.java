package dev.lyy.friendlycore.modules.tools.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Menu GUI for previewing and granting custom tools.
 */
public class ToolsGui extends PaginatedGui {

    public ToolsGui(FriendlyCore plugin, Player viewer) {
        super(plugin, viewer,
                plugin.getTextService().parse(plugin.getConfigService().getToolsConfig().getString("gui.title",
                        "&8✦ #a503fcғʀɪᴇɴᴅʟʏ ᴛᴏᴏʟs &8✦")),
                3);
        render();
    }

    public void render() {
        fillBorder(new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build());

        FriendlyCore plugin = getPlugin();
        boolean canClaim = getViewer().hasPermission("friendlycore.tools.admin") || getViewer().hasPermission("unicorntools.admin") || getViewer().hasPermission("friendlycore.admin");

        ItemStack nPick = plugin.getToolManager().createDrill(Material.NETHERITE_PICKAXE, getViewer().getName());
        setItem(10, new GuiItem(nPick, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(nPick);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Netherite Friendly Drill Pickaxe!", null);
        }));

        ItemStack dPick = plugin.getToolManager().createDrill(Material.DIAMOND_PICKAXE, getViewer().getName());
        setItem(11, new GuiItem(dPick, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(dPick);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Diamond Friendly Drill Pickaxe!", null);
        }));

        ItemStack nShovel = plugin.getToolManager().createDrill(Material.NETHERITE_SHOVEL, getViewer().getName());
        setItem(13, new GuiItem(nShovel, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(nShovel);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Netherite Friendly Drill Shovel!", null);
        }));

        ItemStack dShovel = plugin.getToolManager().createDrill(Material.DIAMOND_SHOVEL, getViewer().getName());
        setItem(14, new GuiItem(dShovel, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(dShovel);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Diamond Friendly Drill Shovel!", null);
        }));

        ItemStack nAxe = plugin.getToolManager().createChopper(Material.NETHERITE_AXE);
        setItem(15, new GuiItem(nAxe, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(nAxe);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Netherite Tree Chopper!", null);
        }));

        ItemStack dAxe = plugin.getToolManager().createChopper(Material.DIAMOND_AXE);
        setItem(16, new GuiItem(dAxe, e -> {
            e.setCancelled(true);
            if (!canClaim) {
                plugin.getTextService().sendMessage(getViewer(), "&cYou do not have permission to claim this tool.", null);
                return;
            }
            getViewer().getInventory().addItem(dAxe);
            getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);
            plugin.getTextService().sendMessage(getViewer(), "&aReceived Diamond Tree Chopper!", null);
        }));
    }
}
