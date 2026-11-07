package dev.lyy.friendlycore.modules.tpa.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.tpa.TpaManager;
import dev.lyy.friendlycore.modules.tpa.TpaRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Confirmation dialog presented to players before dispatching or accepting teleport requests.
 */
public class TpaConfirmGui extends PaginatedGui {

    public enum GuiMode {
        REQUEST,
        ACCEPT
    }

    private final TpaManager tpaManager;
    private final Player target;
    private final TpaRequest.Type requestType;
    private final GuiMode mode;

    public TpaConfirmGui(FriendlyCore plugin, TpaManager tpaManager, Player sender, Player target, TpaRequest.Type requestType, GuiMode mode) {
        super(plugin, sender, getGuiTitle(plugin, requestType, mode), getGuiRows(plugin, requestType, mode));
        this.tpaManager = tpaManager;
        this.target = target;
        this.requestType = requestType;
        this.mode = mode;
        setupItems();
    }

    private static FileConfiguration getInventoryConfig(FriendlyCore plugin, TpaRequest.Type type) {
        String fileName = type == TpaRequest.Type.TPHERE ? "modules/tpa/inventories/tpa-here-inventory.yml" : "modules/tpa/inventories/tpa-inventory.yml";
        return plugin.getConfigService().get(fileName);
    }

    private static String getSectionName(GuiMode mode) {
        return mode == GuiMode.ACCEPT ? "accept-inventory" : "request-inventory";
    }

    private static Component getGuiTitle(FriendlyCore plugin, TpaRequest.Type type, GuiMode mode) {
        FileConfiguration config = getInventoryConfig(plugin, type);
        String section = getSectionName(mode);
        String name = config.getString(section + ".name", "&8ᴄᴏɴғɪʀᴍ ʀᴇᴏ̨ᴜᴇsᴛ");
        return plugin.getTextService().parse(name);
    }

    private static int getGuiRows(FriendlyCore plugin, TpaRequest.Type type, GuiMode mode) {
        FileConfiguration config = getInventoryConfig(plugin, type);
        String section = getSectionName(mode);
        return config.getInt(section + ".rows", 3);
    }

    private void setupItems() {
        FileConfiguration config = getInventoryConfig(getPlugin(), requestType);
        String section = getSectionName(mode);

        String trueVal = tpaManager.getConfig().getString("placeholders-values.true", "Yes");
        String falseVal = tpaManager.getConfig().getString("placeholders-values.false", "No");
        String isFlying = (target != null && target.isFlying()) ? trueVal : falseVal;
        String worldName = target != null ? target.getWorld().getName() : "world";
        String worldNick = tpaManager.getWorldNickname(worldName);
        Material worldMaterial = tpaManager.getWorldIcon(worldName);
        String targetName = target != null ? target.getName() : "Player";

        Map<String, String> placeholders = Map.of(
                "%player%", targetName,
                "%world%", worldNick,
                "%is_flying%", isFlying
        );

        String cancelPath = section + ".icons.cancel-icon";
        if (config.contains(cancelPath)) {
            int slot = config.getInt(cancelPath + ".slot", 10);
            Material mat = Material.matchMaterial(config.getString(cancelPath + ".material", "RED_STAINED_GLASS_PANE"));
            if (mat == null) mat = Material.RED_STAINED_GLASS_PANE;
            String name = config.getString(cancelPath + ".display-name", "&4ᴄᴀɴᴄᴇʟ");
            List<String> lore = config.getStringList(cancelPath + ".lore");

            ItemStack stack = buildItem(mat, name, lore, placeholders, null);
            setItem(slot, new GuiItem(stack, e -> {
                e.setCancelled(true);
                getViewer().closeInventory();
            }));
        }

        String locPath = section + ".icons.location-icon";
        if (config.contains(locPath)) {
            int slot = config.getInt(locPath + ".slot", 12);
            String name = config.getString(locPath + ".display-name", "&aʟᴏᴄᴀᴛɪᴏɴ");
            List<String> lore = config.getStringList(locPath + ".lore");

            ItemStack stack = buildItem(worldMaterial, name, lore, placeholders, null);
            setItem(slot, new GuiItem(stack, e -> e.setCancelled(true)));
        }

        String playerPath = section + ".icons.player-icon";
        if (config.contains(playerPath)) {
            int slot = config.getInt(playerPath + ".slot", 13);
            String name = config.getString(playerPath + ".display-name", "&aᴘʟᴀʏᴇʀ");
            List<String> lore = config.getStringList(playerPath + ".lore");

            ItemStack stack = buildItem(Material.PLAYER_HEAD, name, lore, placeholders, target);
            setItem(slot, new GuiItem(stack, e -> e.setCancelled(true)));
        }

        String flyPath = section + ".icons.fly-icon";
        if (config.contains(flyPath)) {
            int slot = config.getInt(flyPath + ".slot", 14);
            Material mat = Material.matchMaterial(config.getString(flyPath + ".material", "FEATHER"));
            if (mat == null) mat = Material.FEATHER;
            String name = config.getString(flyPath + ".display-name", "&aɪs ғʟʏɪɴɢ");
            List<String> lore = config.getStringList(flyPath + ".lore");

            ItemStack stack = buildItem(mat, name, lore, placeholders, null);
            setItem(slot, new GuiItem(stack, e -> e.setCancelled(true)));
        }

        String confirmPath = section + ".icons.confirm-icon";
        if (config.contains(confirmPath)) {
            int slot = config.getInt(confirmPath + ".slot", 16);
            Material mat = Material.matchMaterial(config.getString(confirmPath + ".material", "LIME_STAINED_GLASS_PANE"));
            if (mat == null) mat = Material.LIME_STAINED_GLASS_PANE;
            String name = config.getString(confirmPath + ".display-name", "&aᴄᴏɴғɪʀᴍ");
            List<String> lore = config.getStringList(confirmPath + ".lore");

            ItemStack stack = buildItem(mat, name, lore, placeholders, null);
            setItem(slot, new GuiItem(stack, e -> {
                e.setCancelled(true);
                getViewer().closeInventory();
                if (mode == GuiMode.REQUEST) {
                    tpaManager.sendRequest(getViewer(), target, requestType, true);
                } else {
                    tpaManager.acceptRequest(getViewer(), target != null ? target.getName() : null);
                }
            }));
        }
    }

    private ItemStack buildItem(Material mat, String name, List<String> loreLines, Map<String, String> placeholders, Player skullOwner) {
        ItemBuilder builder = new ItemBuilder(mat);
        if (mat == Material.PLAYER_HEAD && skullOwner != null) {
            builder.skullOwner(skullOwner.getUniqueId(), skullOwner.getName());
        }
        if (name != null && !name.isEmpty()) {
            builder.name(getPlugin().getTextService().parse(name, placeholders));
        }
        if (loreLines != null && !loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(getPlugin().getTextService().parse(line, placeholders));
            }
            builder.lore(lore);
        }
        return builder.build();
    }
}
