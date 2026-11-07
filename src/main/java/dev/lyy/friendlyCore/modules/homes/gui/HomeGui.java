package dev.lyy.friendlycore.modules.homes.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.homes.HomesManager;
import dev.lyy.friendlycore.modules.homes.model.HomeData;
import dev.lyy.friendlycore.modules.homes.model.PlayerHomes;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Paginated GUI menu for viewing, setting, renaming, and teleporting to player homes.
 */
public class HomeGui extends PaginatedGui {

    private final HomesManager homesManager;
    private final UUID targetUuid;
    private final String targetName;
    private final int page;

    public HomeGui(FriendlyCore plugin, HomesManager homesManager, Player viewer,
                   UUID targetUuid, String targetName, int page) {
        super(plugin, viewer,
                calculateTitle(plugin, targetUuid, targetName, Math.max(1, page)),
                calculateRows(plugin));
        this.homesManager = homesManager;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.page = Math.max(1, page);

        buildGui();
    }

    public HomeGui(FriendlyCore plugin, HomesManager homesManager, Player viewer) {
        this(plugin, homesManager, viewer, viewer.getUniqueId(), viewer.getName(), 1);
    }

    @Override
    public void open() {
        if (homesManager.isLoading(targetUuid)) {
            Messages.send(getPlugin(), getViewer(), "homes", "homes-are-still-loading-please-try");
            return;
        }
        super.open();
    }

    private static int calculateRows(FriendlyCore plugin) {
        FileConfiguration config = plugin.getConfigService().get("modules/homes/inventories/home.yml");
        List<String> layout = config.getStringList("layout");
        if (layout.isEmpty()) {
            return 4;
        }
        return Math.min(6, Math.max(1, layout.size()));
    }

    private static Component calculateTitle(FriendlyCore plugin, UUID targetUuid, String targetName, int page) {
        FileConfiguration config = plugin.getConfigService().get("modules/homes/inventories/home.yml");
        String rawTitle = config.getString("title", "&0⏵ Home | Page: %page%");
        return plugin.getTextService().parse(rawTitle, Map.of(
            "%page%", String.valueOf(page),
            "%player%", targetName != null ? targetName : ""
        ));
    }

    private void buildGui() {
        FileConfiguration config = getPlugin().getConfigService().get("modules/homes/inventories/home.yml");
        PlayerHomes profile = homesManager.getPlayerHomes(targetUuid);
        if (profile == null) {
            if (homesManager.isLoading(targetUuid)) {
                Messages.send(getPlugin(), getViewer(), "homes", "homes-are-still-loading-please-try");
                return;
            }
            profile = new PlayerHomes(targetUuid, targetName);
        } else if (getViewer().getUniqueId().equals(targetUuid)) {
            homesManager.checkAndEnforceHomeLimits(getViewer(), profile);
        }

        int maxHomes = homesManager.getMaxHomes(targetUuid);
        boolean multiplePages = config.getBoolean("enabled-multiple-pages", true);

        List<String> layout = config.getStringList("layout");
        if (layout.isEmpty()) {
            layout = List.of(
                    "---------",
                    "--hhhhh--",
                    "---------",
                    "---------"
            );
        }

        int rows = getRows();

        List<int[]> homeTokens = new ArrayList<>(); // [row, col]
        Set<Integer> homeOccupiedSlots = new HashSet<>();

        for (int r = 0; r < Math.min(rows, layout.size()); r++) {
            String rowStr = layout.get(r);
            for (int c = 0; c < Math.min(9, rowStr.length()); c++) {
                if (rowStr.charAt(c) == 'h') {
                    homeTokens.add(new int[]{r, c});
                    homeOccupiedSlots.add(r * 9 + c);
                    if (r + 1 < rows) {
                        homeOccupiedSlots.add((r + 1) * 9 + c);
                    }
                }
            }
        }

        int homesPerPage = Math.max(1, homeTokens.size());
        int totalPages = multiplePages ? Math.max(1, (int) Math.ceil((double) maxHomes / homesPerPage)) : 1;

        Map<Character, ConfigurationSection> symbolItems = new HashMap<>();
        ConfigurationSection itemsSec = config.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                ConfigurationSection sec = itemsSec.getConfigurationSection(key);
                if (sec != null && sec.contains("symbol")) {
                    String sym = sec.getString("symbol");
                    if (sym != null && !sym.isEmpty()) {
                        symbolItems.put(sym.charAt(0), sec);
                    }
                }
            }
        }

        for (int r = 0; r < Math.min(rows, layout.size()); r++) {
            String rowStr = layout.get(r);
            for (int c = 0; c < Math.min(9, rowStr.length()); c++) {
                int invSlot = r * 9 + c;
                if (!homeOccupiedSlots.contains(invSlot)) {
                    char ch = rowStr.charAt(c);
                    if (symbolItems.containsKey(ch)) {
                        ItemStack filler = buildItemFromConfig(symbolItems.get(ch), Material.BLACK_STAINED_GLASS_PANE, " ");
                        setItem(invSlot, new GuiItem(filler, e -> e.setCancelled(true)));
                    }
                }
            }
        }

        for (int i = 0; i < homeTokens.size(); i++) {
            int[] token = homeTokens.get(i);
            int r = token[0];
            int c = token[1];
            int topSlot = r * 9 + c;
            int bottomSlot = (r + 1 < rows) ? (r + 1) * 9 + c : -1;
            int homeSlotIndex = (page - 1) * homesPerPage + (i + 1);

            HomeData home = profile.getHome(homeSlotIndex);
            boolean hasHome = home != null;
            boolean hasPermission = homeSlotIndex <= maxHomes;

            if (hasHome) {
                ItemStack bedItem = buildTpBedItem(config, home, homeSlotIndex);
                setItem(topSlot, new GuiItem(bedItem, e -> handleHomeClick(e, home, homeSlotIndex, bedItem)));

                if (bottomSlot >= 0) {
                    ItemStack dyeItem = buildDeleteDyeItem(config, home, homeSlotIndex);
                    setItem(bottomSlot, new GuiItem(dyeItem, e -> handleDeleteClick(e, home, homeSlotIndex, bedItem)));
                }
            } else if (hasPermission) {
                ItemStack unclaimedBed = buildUnclaimedBedItem(config, homeSlotIndex);
                setItem(topSlot, new GuiItem(unclaimedBed, e -> handleClaimSlot(homeSlotIndex)));

                if (bottomSlot >= 0) {
                    ItemStack unclaimedDye = buildUnclaimedDyeItem(config, homeSlotIndex);
                    setItem(bottomSlot, new GuiItem(unclaimedDye, e -> handleClaimSlot(homeSlotIndex)));
                }
            } else {
                ItemStack lockedBed = buildLockedBedItem(config, homeSlotIndex);
                setItem(topSlot, new GuiItem(lockedBed, e -> handleLockedClick()));

                if (bottomSlot >= 0) {
                    ItemStack lockedDye = buildLockedDyeItem(config, homeSlotIndex);
                    setItem(bottomSlot, new GuiItem(lockedDye, e -> handleLockedClick()));
                }
            }
        }

        if (multiplePages) {
            int prevSlot = config.getInt("items.previous-page-slot", -1);
            int nextSlot = config.getInt("items.next-page-slot", -1);

            if (page > 1 && prevSlot >= 0 && prevSlot < getSize()) {
                ItemStack prevItem = buildItemFromConfig(config.getConfigurationSection("items.previous-page"), Material.RED_STAINED_GLASS_PANE, "#E13E3EPrevious Page");
                setItem(prevSlot, new GuiItem(prevItem, e -> {
                    homesManager.playSound(getViewer(), "ui-click");
                    new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, page - 1).open();
                }));
            }

            if (page < totalPages && nextSlot >= 0 && nextSlot < getSize()) {
                ItemStack nextItem = buildItemFromConfig(config.getConfigurationSection("items.next-page"), Material.LIME_STAINED_GLASS_PANE, "#63E781Next Page");
                setItem(nextSlot, new GuiItem(nextItem, e -> {
                    homesManager.playSound(getViewer(), "ui-click");
                    new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, page + 1).open();
                }));
            }
        }

        List<Map<?, ?>> fillersList = config.getMapList("fillers");
        Map<String, String> fillerPlaceholders = Map.of(
                "%player%", targetName,
                "%homes_count%", String.valueOf(profile.getHomesCount()),
                "%max_homes%", String.valueOf(maxHomes),
                "%page%", String.valueOf(page),
                "%total%", String.valueOf(totalPages),
                "%max_pages%", String.valueOf(totalPages)
        );

        for (Map<?, ?> map : fillersList) {
            try {
                int slot = ((Number) map.get("slot")).intValue();
                if (slot < 0 || slot >= getSize()) continue;
                Object matObj = map.get("material");
                String matStr = matObj != null ? String.valueOf(matObj) : "BLACK_STAINED_GLASS_PANE";
                Material mat = Material.matchMaterial(matStr);
                if (mat == null) mat = Material.BLACK_STAINED_GLASS_PANE;

                Object nameObj = map.get("name");
                String name = nameObj != null ? String.valueOf(nameObj) : " ";
                Object loreObj = map.get("lore");
                List<?> loreList = (loreObj instanceof List<?> list) ? list : Collections.emptyList();

                ItemBuilder builder = new ItemBuilder(mat)
                        .name(getPlugin().getTextService().parse(name, fillerPlaceholders));
                for (Object l : loreList) {
                    builder.addLore(getPlugin().getTextService().parse(String.valueOf(l), fillerPlaceholders));
                }
                setItem(slot, new GuiItem(builder.build(), e -> e.setCancelled(true)));
            } catch (Exception ignored) {}
        }
    }

    private void handleHomeClick(InventoryClickEvent event, HomeData home, int slotIndex, ItemStack preview) {
        if (event.isRightClick()) {
            if (!homesManager.isDisableHomeRenaming()) {
                homesManager.startChatRename(getViewer(), targetUuid, slotIndex);
                getViewer().closeInventory();
            }
            return;
        }

        // Left Click -> Teleport
        homesManager.playSound(getViewer(), "ui-click");
        getViewer().closeInventory();
        homesManager.teleportPlayerToHome(getViewer(), home);
    }

    private void handleDeleteClick(InventoryClickEvent event, HomeData home, int slotIndex, ItemStack preview) {
        if (event.isRightClick()) {
            if (!homesManager.isDisableHomeRenaming()) {
                homesManager.startChatRename(getViewer(), targetUuid, slotIndex);
                getViewer().closeInventory();
            }
            return;
        }

        // Left Click -> Delete
        homesManager.playSound(getViewer(), "ui-click");
        if (homesManager.isEnableConfirmationGui()) {
            getViewer().closeInventory();
            new ConfirmDeleteGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, slotIndex, preview).open();
        } else {
            PlayerHomes profile = homesManager.getPlayerHomes(targetUuid);
            if (profile != null) {
                profile.removeHome(slotIndex);
                homesManager.savePlayerHomesAsync(profile);
            } else {
                getPlugin().getDatabaseManager().deleteHome(targetUuid, slotIndex);
            }
            homesManager.sendHomeMessage(getViewer(), "home-deleted-chat", Map.of("%home%", String.valueOf(slotIndex)));
            new HomeGui(getPlugin(), homesManager, getViewer(), targetUuid, targetName, page).open();
        }
    }

    private void handleClaimSlot(int slotIndex) {
        if (!getViewer().getUniqueId().equals(targetUuid)) {
            return;
        }
        homesManager.playSound(getViewer(), "ui-click");
        getViewer().closeInventory();
        homesManager.setHomeAtLocation(getViewer(), slotIndex, "", getViewer().getLocation());
    }

    private void handleLockedClick() {
        homesManager.playSound(getViewer(), "error");
        homesManager.sendHomeMessage(getViewer(), "reached_max_number_of_homes_homes", Map.of());
    }

    private ItemStack buildTpBedItem(FileConfiguration config, HomeData home, int index) {
        Material bedMat = Material.LIGHT_BLUE_BED;
        if (config.getBoolean("colored-beds.enabled", true)) {
            List<String> list = config.getStringList("colored-beds.list");
            if (!list.isEmpty()) {
                String matName = list.get((index - 1) % list.size());
                Material match = Material.matchMaterial(matName);
                if (match != null) bedMat = match;
            }
        }

        ConfigurationSection sec = config.getConfigurationSection("items.tp-home-bed");
        return buildTemplatedItem(sec, bedMat, "&9ʜome %home%", home, index);
    }

    private ItemStack buildDeleteDyeItem(FileConfiguration config, HomeData home, int index) {
        ConfigurationSection sec = config.getConfigurationSection("items.delete-home");
        return buildTemplatedItem(sec, Material.BLUE_DYE, "&9ʜome %home%", home, index);
    }

    private ItemStack buildUnclaimedBedItem(FileConfiguration config, int index) {
        ConfigurationSection sec = config.getConfigurationSection("items.no-home-bed");
        return buildTemplatedItem(sec, Material.LIGHT_GRAY_BED, "&7ɴᴏ ʜᴏᴍᴇ sᴇᴛ", null, index);
    }

    private ItemStack buildUnclaimedDyeItem(FileConfiguration config, int index) {
        ConfigurationSection sec = config.getConfigurationSection("items.no-home");
        return buildTemplatedItem(sec, Material.GRAY_DYE, "&7ɴᴏ ʜᴏᴍᴇ sᴇᴛ", null, index);
    }

    private ItemStack buildLockedBedItem(FileConfiguration config, int index) {
        ConfigurationSection sec = config.getConfigurationSection("items.no-perm-bed");
        return buildTemplatedItem(sec, Material.RED_BED, "&4ɴᴏ ᴘᴇʀᴍɪssɪᴏɴ", null, index);
    }

    private ItemStack buildLockedDyeItem(FileConfiguration config, int index) {
        ConfigurationSection sec = config.getConfigurationSection("items.no-perm");
        return buildTemplatedItem(sec, Material.RED_DYE, "&4ɴᴏ ᴘᴇʀᴍɪssɪᴏɴ", null, index);
    }

    private ItemStack buildTemplatedItem(ConfigurationSection sec, Material fallbackMat, String defaultName, HomeData home, int index) {
        Material mat = fallbackMat;
        String name = defaultName;
        List<String> lore = new ArrayList<>();

        if (sec != null) {
            String matStr = sec.getString("material");
            if (matStr != null) {
                Material m = Material.matchMaterial(matStr);
                if (m != null) mat = m;
            }
            name = sec.getString("name", defaultName);
            lore = sec.getStringList("lore");
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%index%", String.valueOf(index));
        placeholders.put("%home%", (home != null && home.getName() != null && !home.getName().isEmpty()) ? home.getName() : String.valueOf(index));
        placeholders.put("%server%", home != null ? home.getServerId() : "");
        placeholders.put("%world%", home != null ? home.getWorldName() : "");
        placeholders.put("%x%", home != null ? String.format("%.1f", home.getX()) : "0");
        placeholders.put("%y%", home != null ? String.format("%.1f", home.getY()) : "0");
        placeholders.put("%z%", home != null ? String.format("%.1f", home.getZ()) : "0");

        ItemBuilder builder = new ItemBuilder(mat)
                .name(getPlugin().getTextService().parse(name, placeholders));
        for (String l : lore) {
            builder.addLore(getPlugin().getTextService().parse(l, placeholders));
        }
        return builder.build();
    }

    private ItemStack buildItemFromConfig(ConfigurationSection sec, Material defaultMat, String defaultName) {
        Material mat = defaultMat;
        String name = defaultName;
        List<String> lore = new ArrayList<>();

        if (sec != null) {
            String matStr = sec.getString("material");
            if (matStr != null) {
                Material m = Material.matchMaterial(matStr);
                if (m != null) mat = m;
            }
            name = sec.getString("name", defaultName);
            lore = sec.getStringList("lore");
        }

        ItemBuilder builder = new ItemBuilder(mat)
                .name(getPlugin().getTextService().parse(name));
        for (String l : lore) {
            builder.addLore(getPlugin().getTextService().parse(l));
        }
        return builder.build();
    }
}
