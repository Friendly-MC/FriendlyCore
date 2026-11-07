package dev.lyy.friendlycore.modules.auctions.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.auctions.AuctionManager;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionSorting;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;

import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Standardized paginated GUI frame for auction house interfaces, providing template loading,
 * slot range parsing, condition evaluation, action tag dispatching, and dynamic placeholder rendering.
 */
public abstract class AuctionMenuBase extends PaginatedGui {

    protected final AuctionManager auctionManager;
    protected final AuctionUser auctionUser;
    protected final String configPath;
    protected final AuctionMenuBase previousMenu;
    protected final List<Integer> itemSlots = new ArrayList<>();

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY_SECTION =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

    private static final Set<String> NON_ITEM_KEYS = Set.of(
            "title", "rows", "page-size", "auto-update-ticks", "type"
    );

    public static Component resolveConfigTitle(FriendlyCore plugin, String configPath, Player viewer, int page, int maxPages, String itemPlaceholder) {
        FileConfiguration cfg = plugin.getConfigService().get(configPath);
        String raw = cfg != null ? cfg.getString("title", "Auction House") : "Auction House";
        raw = raw.replace("%current_page%", String.valueOf(Math.max(1, page)))
                 .replace("%max_page%", String.valueOf(Math.max(1, maxPages)))
                 .replace("%page%", String.valueOf(Math.max(1, page)))
                 .replace("{current_page}", String.valueOf(Math.max(1, page)))
                 .replace("{max_page}", String.valueOf(Math.max(1, maxPages)))
                 .replace("{currentPage}", String.valueOf(Math.max(1, page)))
                 .replace("{maxPages}", String.valueOf(Math.max(1, maxPages)))
                 .replace("%player%", viewer != null ? viewer.getName() : "")
                 .replace("{player}", viewer != null ? viewer.getName() : "")
                 .replace("%item%", itemPlaceholder != null ? itemPlaceholder : "Item")
                 .replace("{item}", itemPlaceholder != null ? itemPlaceholder : "Item");
        return plugin.getTextService().parseCached(raw);
    }

    public static String getItemDisplayName(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "Air";
        // getItemMeta() copies the meta on every call, so read it once.
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName()) {
            Component displayName = meta.displayName();
            if (displayName != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).trim();
                if (!plain.isEmpty()) return plain;
            }
            String legacy = meta.getDisplayName();
            if (legacy != null && !legacy.isBlank()) {
                String stripped = org.bukkit.ChatColor.stripColor(legacy).trim();
                if (!stripped.isEmpty()) return stripped;
            }
        }
        return formatEnumName(item.getType().name());
    }

    public static String formatEnumName(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        String[] words = enumName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(" ");
            if (w.equalsIgnoreCase("tnt")) {
                sb.append("TNT");
            } else if (w.equalsIgnoreCase("xp")) {
                sb.append("XP");
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private Object autoUpdateTask;
    private long lastClickNanos;
    // openInventory always shows the creation title; setTitle resends the whole window, so skip no-op updates.
    private final String openTitle;
    private String shownTitle;

    public AuctionMenuBase(FriendlyCore plugin, Player viewer, String configPath, Component title, int rows, AuctionMenuBase previousMenu) {
        super(plugin, viewer, title, rows);
        this.openTitle = title != null ? LEGACY_SECTION.serialize(title) : "";
        this.shownTitle = openTitle;
        this.auctionManager = plugin.getAuctionsModule().getAuctionManager();
        this.auctionUser = auctionManager.getUser(viewer.getUniqueId(), viewer.getName());
        this.configPath = configPath;
        this.previousMenu = previousMenu;
    }

    public abstract void refresh();

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        FileConfiguration config = getPlugin().getConfigService().get("modules/auctions/config.yml");
        long cooldownMillis = config != null ? Math.max(0L,
                config.getLong("gui-refresh-cooldown-milliseconds", 100L)) : 100L;
        long now = System.nanoTime();
        if (lastClickNanos != 0L && now - lastClickNanos < cooldownMillis * 1_000_000L) return;
        lastClickNanos = now;
        super.handleClick(event);
    }

    @Override
    public void open() {
        auctionManager.registerActiveMenu(this);
        startAutoUpdateTask();
        shownTitle = openTitle;
        super.open();
        getPlugin().getScheduler().runEntity(getViewer(), this::updatePageTitle);
    }

    @Override
    public void setMaxPages(int pages) {
        super.setMaxPages(pages);
        super.setCurrentPage(Math.min(getCurrentPage(), getMaxPages()));
        updatePageTitle();
    }

    @Override
    public void setCurrentPage(int page) {
        super.setCurrentPage(Math.max(1, page));
        updatePageTitle();
    }

    public static boolean hasPagePlaceholder(String raw) {
        if (raw == null) return false;
        return raw.contains("%current_page%") || raw.contains("%max_page%") || raw.contains("%page%")
                || raw.contains("{current_page}") || raw.contains("{max_page}")
                || raw.contains("{currentPage}") || raw.contains("{maxPages}");
    }

    private void updatePageTitle() {
        if (configPath == null || !getViewer().isOnline()) return;
        FileConfiguration cfg = getPlugin().getConfigService().get(configPath);
        String raw = cfg != null ? cfg.getString("title", "") : "";
        if (!hasPagePlaceholder(raw)) return;
        if (getViewer().getOpenInventory() != null && getViewer().getOpenInventory().getTopInventory() != null && getViewer().getOpenInventory().getTopInventory().equals(getInventory())) {
            String title = LEGACY_SECTION.serialize(resolveConfigTitle(getPlugin(), configPath, getViewer(), getCurrentPage(), getMaxPages(), null));
            if (title.equals(shownTitle)) return;
            shownTitle = title;
            getViewer().getOpenInventory().setTitle(title);
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        stopAutoUpdateTask();
        auctionManager.unregisterActiveMenu(this);
        super.handleClose(event);
    }

    protected void reopenPreviousMenu(InventoryCloseEvent event) {
        if (event.getReason() != InventoryCloseEvent.Reason.PLAYER
                && event.getReason() != InventoryCloseEvent.Reason.PLUGIN
                && event.getReason() != InventoryCloseEvent.Reason.UNKNOWN) return;
        if (previousMenu == null || !getViewer().isOnline()) return;
        // Opening during InventoryCloseEvent recursively closes the same inventory.
        getPlugin().getScheduler().runEntityLater(getViewer(), () -> {
            if (!getViewer().isOnline() || getViewer().isDead()) return;
            InventoryType type = getViewer().getOpenInventory().getType();
            if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
                previousMenu.open();
            }
        }, 1L);
    }

    private void startAutoUpdateTask() {
        stopAutoUpdateTask();
        FileConfiguration cfg = getPlugin().getConfigService().get(configPath);
        if (cfg != null) {
            int ticks = cfg.getInt("auto-update-ticks", -1);
            if (ticks > 0) {
                autoUpdateTask = getPlugin().getScheduler().runEntityTimer(getViewer(), () -> {
                    if (getViewer().isOnline() && getViewer().getOpenInventory().getTopInventory().equals(getInventory())) {
                        refresh();
                    } else {
                        stopAutoUpdateTask();
                    }
                }, ticks, ticks);
            }
        }
    }

    private void stopAutoUpdateTask() {
        if (autoUpdateTask != null) {
            getPlugin().getScheduler().cancel(autoUpdateTask);
            autoUpdateTask = null;
        }
    }

    public AuctionMenuBase getPreviousMenu() {
        return previousMenu;
    }

    public static List<Integer> calculateAvailableItemSlots(FileConfiguration config, int guiSize, int maxPageSize) {
        List<Integer> explicit = new ArrayList<>();
        if (config != null) {
            if (config.contains("orderSlots")) {
                for (Object obj : config.getList("orderSlots", Collections.emptyList())) {
                    if (obj instanceof Integer num) explicit.add(num);
                    else if (obj instanceof Number num) explicit.add(num.intValue());
                }
            } else if (config.contains("item-slots")) {
                explicit.addAll(parseSlotString(config.getString("item-slots"), guiSize));
            }
            if (explicit.isEmpty()) {
                ConfigurationSection sec = config.getConfigurationSection("items.items");
                if (sec == null) sec = config.getConfigurationSection("items");
                if (sec != null && (sec.contains("slot") || sec.contains("slots"))) {
                    explicit.addAll(parseSlots(sec, guiSize));
                }
            }
        }
        if (!explicit.isEmpty()) {
            return explicit;
        }

        Set<Integer> occupied = new HashSet<>();
        if (config != null) {
            ConfigurationSection rootItemsSec = config.getConfigurationSection("items");
            if (rootItemsSec != null) {
                for (String key : rootItemsSec.getKeys(false)) {
                    if (key.equalsIgnoreCase("items") || key.equalsIgnoreCase("categories")) continue;
                    ConfigurationSection sec = rootItemsSec.getConfigurationSection(key);
                    if (sec != null) occupied.addAll(parseSlots(sec, guiSize));
                }
            }
            for (String key : config.getKeys(false)) {
                if (NON_ITEM_KEYS.contains(key.toLowerCase(Locale.ROOT)) || key.equalsIgnoreCase("items")) continue;
                ConfigurationSection sec = config.getConfigurationSection(key);
                if (sec != null) occupied.addAll(parseSlots(sec, guiSize));
            }
        }

        List<Integer> freeSlots = new ArrayList<>();
        int limit = maxPageSize > 0 ? maxPageSize : guiSize;
        for (int i = 0; i < guiSize; i++) {
            if (!occupied.contains(i)) {
                freeSlots.add(i);
                if (freeSlots.size() >= limit) break;
            }
        }

        if (freeSlots.isEmpty()) {
            for (int i = 0; i < 45 && i < guiSize; i++) {
                freeSlots.add(i);
            }
        }
        return freeSlots;
    }

    protected void loadTemplateButtons() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        // Check if items are nested under "items:" or placed directly at the root level
        ConfigurationSection rootItemsSec = config.getConfigurationSection("items");
        if (rootItemsSec != null) {
            for (String key : rootItemsSec.getKeys(false)) {
                if (key.equalsIgnoreCase("items") || key.equalsIgnoreCase("categories")) continue;
                ConfigurationSection sec = rootItemsSec.getConfigurationSection(key);
                if (sec != null) {
                    loadSingleTemplateButton(sec);
                }
            }
        }

        // Also check top-level root keys in case the config is using AxAuctions flat structure
        for (String key : config.getKeys(false)) {
            if (NON_ITEM_KEYS.contains(key.toLowerCase(Locale.ROOT)) || key.equalsIgnoreCase("items")) continue;
            ConfigurationSection sec = config.getConfigurationSection(key);
            if (sec != null) {
                loadSingleTemplateButton(sec);
            }
        }
    }

    public void playConfigSound(String soundKey) {
        if (soundKey == null || soundKey.isBlank()) return;
        FileConfiguration soundsCfg = getPlugin().getConfigService().get("modules/orders/sounds.yml");
        if (soundsCfg != null && soundsCfg.contains(soundKey)) {
            String soundName = soundsCfg.getString(soundKey + ".sound", "UI_BUTTON_CLICK");
            float vol = (float) soundsCfg.getDouble(soundKey + ".volume", 1.0);
            float pitch = (float) soundsCfg.getDouble(soundKey + ".pitch", 1.0);
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
                getViewer().playSound(getViewer().getLocation(), sound, vol, pitch);
                return;
            } catch (IllegalArgumentException ignored) {}
        }
        switch (soundKey.toLowerCase(Locale.ROOT)) {
            case "click" -> getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            case "success" -> getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            case "error" -> getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            case "xp" -> getViewer().playSound(getViewer().getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            default -> {
                try {
                    String clean = soundKey.trim().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
                    Sound sound = Sound.valueOf(clean);
                    getViewer().playSound(getViewer().getLocation(), sound, 1.0f, 1.0f);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void loadSingleTemplateButton(ConfigurationSection sec) {
        // Hide obsolete currency controls in existing server configurations too.
        if (sec.getName().equalsIgnoreCase("currency")
                || sec.getStringList("actions").stream().anyMatch(a -> a.toUpperCase(Locale.ROOT).startsWith("[CURRENCY]"))) return;
        List<String> conditions = sec.getStringList("conditions");
        if (!conditions.isEmpty()) {
            for (String condition : conditions) {
                String parsedCond = applyPlaceholders(condition);
                if (!AuctionExpressionEvaluator.eval(parsedCond)) {
                    return; // Condition check failed, do not render item
                }
            }
        }

        String matName = sec.getString("material", sec.getString("type", "STONE"));
        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.STONE;

        int amount = Math.max(1, sec.getInt("amount", 1));
        int cmd = sec.getInt("custom-model-data", -1);
        if (cmd <= 0) cmd = sec.getInt("customModelData", -1);
        boolean glow = sec.getBoolean("glow", false);

        String nameStr = sec.getString("name", "");
        List<String> loreList = sec.getStringList("lore");
        List<String> actions = new ArrayList<>(sec.getStringList("actions"));
        String soundKey = sec.getString("sound", null);

        boolean isSortButton = actions.stream().anyMatch(a -> a.toUpperCase(Locale.ROOT).contains("SORTING"))
                || sec.contains("selected_color")
                || sec.getName().equalsIgnoreCase("sort")
                || sec.getName().equalsIgnoreCase("sorting");

        if (isSortButton && actions.isEmpty()) {
            actions.add("[SORTING]");
        }

        List<Integer> slots = parseSlots(sec, getSize());
        if (slots.isEmpty()) return;

        ItemBuilder builder = new ItemBuilder(material)
                .amount(amount)
                .glow(glow);

        if (cmd > 0) builder.customModelData(cmd);
        if (!nameStr.isEmpty()) {
            builder.name(getPlugin().getTextService().parseCached(applyPlaceholders(nameStr)));
        }

        if (isSortButton) {
            String selColor = sec.getString("selected_color", "#E63A35");
            String unselColor = sec.getString("unselected_color", "&f");
            String bullet = sec.getString("bullet_icon", "▪ ");

            FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
            List<Component> sortLore = new ArrayList<>();
            for (AuctionSorting s : AuctionSorting.values()) {
                String color = (s == auctionUser.getSelectedSorting()) ? selColor : unselColor;
                String dispName = msgs != null ? msgs.getString("sorting." + s.getId(), s.getDisplayName()) : s.getDisplayName();
                sortLore.add(getPlugin().getTextService().parseCached(color + bullet + dispName));
            }
            builder.lore(sortLore);
        } else if (!loreList.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String l : loreList) {
                loreComponents.add(getPlugin().getTextService().parseCached(applyPlaceholders(l)));
            }
            builder.lore(loreComponents);
        }

        ItemStack built = builder.build();
        for (int slot : slots) {
            if (slot < 0 || slot >= getSize()) continue;
            setItem(slot, new GuiItem(built, event -> {
                if (soundKey != null && !soundKey.isBlank()) {
                    playConfigSound(soundKey);
                }
                handleActionClick(event, actions);
            }));
        }
    }

    public static List<Integer> parseSlots(ConfigurationSection sec, int guiSize) {
        List<Integer> slots = new ArrayList<>();
        if (sec.isInt("slot")) {
            slots.add(sec.getInt("slot"));
            return slots;
        }

        if (sec.isString("slot")) {
            slots.addAll(parseSlotString(sec.getString("slot"), guiSize));
            return slots;
        }

        if (sec.isList("slot")) {
            for (Object obj : sec.getList("slot", Collections.emptyList())) {
                if (obj instanceof Integer i) {
                    slots.add(i);
                } else if (obj != null) {
                    slots.addAll(parseSlotString(String.valueOf(obj), guiSize));
                }
            }
            return slots;
        }

        if (sec.isList("slots")) {
            for (Object obj : sec.getList("slots", Collections.emptyList())) {
                if (obj instanceof Integer i) {
                    slots.add(i);
                } else if (obj != null) {
                    slots.addAll(parseSlotString(String.valueOf(obj), guiSize));
                }
            }
            return slots;
        }

        return slots;
    }

    public static List<Integer> parseSlotString(String slotStr, int guiSize) {
        List<Integer> result = new ArrayList<>();
        if (slotStr == null || slotStr.isBlank()) return result;
        String s = slotStr.trim();

        try {
            int slot = Integer.parseInt(s);
            result.add(slot);
            return result;
        } catch (NumberFormatException ignored) {}

        if (s.equalsIgnoreCase("border")) {
            int rows = guiSize / 9;
            for (int col = 0; col < 9; col++) {
                result.add(col); // top row
                if (rows > 1) {
                    result.add((rows - 1) * 9 + col); // bottom row
                }
            }
            for (int row = 1; row < rows - 1; row++) {
                result.add(row * 9); // left column
                result.add(row * 9 + 8); // right column
            }
            return result;
        }

        if (s.contains("-")) {
            String[] parts = s.split("-");
            try {
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
                    result.add(i);
                }
            } catch (Exception ignored) {}
            return result;
        }

        // Stride syntax: e.g. "10|28" -> 10, 19, 28
        if (s.contains("|")) {
            String[] parts = s.split("\\|");
            try {
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                for (int i = start; i <= end; i += 9) {
                    result.add(i);
                }
            } catch (Exception ignored) {}
            return result;
        }

        return result;
    }

    protected void handleActionClick(InventoryClickEvent event, List<String> actions) {
        event.setCancelled(true);
        if (actions == null || actions.isEmpty()) return;

        for (String rawAction : actions) {
            executeAction(rawAction, event);
        }
    }

    protected void executeAction(String rawAction, InventoryClickEvent event) {
        if (rawAction == null || rawAction.isBlank()) return;
        String trimmed = rawAction.trim();

        String tag;
        String arg = "";
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            int endTag = trimmed.indexOf(']');
            tag = trimmed.substring(1, endTag).toUpperCase(Locale.ROOT);
            arg = trimmed.substring(endTag + 1).trim();
        } else {
            tag = trimmed.toUpperCase(Locale.ROOT);
        }

        switch (tag) {
            case "BACK" -> {
                if (getCurrentPage() > 1) {
                    setCurrentPage(getCurrentPage() - 1);
                    refresh();
                } else if (previousMenu != null) {
                    previousMenu.refresh();
                    previousMenu.open();
                } else if (!(this instanceof AuctionMainGui)) {
                    new AuctionMainGui(getPlugin(), getViewer(), 1, null).open();
                } else {
                    getViewer().closeInventory();
                }
            }
            case "REFRESH" -> refresh();
            case "PAGE" -> {
                String target = arg.toLowerCase(Locale.ROOT);
                if (target.equals("next") && getCurrentPage() < getMaxPages()) {
                    setCurrentPage(getCurrentPage() + 1);
                    refresh();
                } else if (target.equals("previous")) {
                    if (getCurrentPage() > 1) {
                        setCurrentPage(getCurrentPage() - 1);
                        refresh();
                    } else if (previousMenu != null) {
                        previousMenu.refresh();
                        previousMenu.open();
                    } else if (!(this instanceof AuctionMainGui)) {
                        new AuctionMainGui(getPlugin(), getViewer(), 1, null).open();
                    } else {
                        getViewer().closeInventory();
                    }
                } else {
                    try {
                        int p = Integer.parseInt(target);
                        if (p >= 1 && p <= getMaxPages()) {
                            setCurrentPage(p);
                            refresh();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "CATEGORY" -> {
                if (event.isShiftClick()) {
                    auctionUser.setSelectedCategory("all");
                } else if (arg.isEmpty()) {
                    if (event.isShiftClick()) {
                        auctionUser.setSelectedCategory("all");
                    } else if (event.isLeftClick()) {
                        String next = auctionManager.getCategoryManager().getNextCategory(auctionUser.getSelectedCategory());
                        auctionUser.setSelectedCategory(next);
                    } else if (event.isRightClick()) {
                        String prev = auctionManager.getCategoryManager().getPreviousCategory(auctionUser.getSelectedCategory());
                        auctionUser.setSelectedCategory(prev);
                    }
                } else {
                    String[] cats = arg.split("\\|");
                    if (cats.length == 1) {
                        auctionUser.setSelectedCategory(cats[0]);
                    } else {
                        if (event.isLeftClick()) {
                            auctionUser.setSelectedCategory(cats[0]);
                        } else {
                            auctionUser.setSelectedCategory(cats[1]);
                        }
                    }
                }
                refresh();
            }
            case "SORTING" -> {
                if (event.isShiftClick()) {
                    auctionUser.setSelectedSorting(AuctionSorting.NEWEST);
                } else if (arg.isEmpty()) {
                    if (event.isRightClick()) {
                        auctionUser.setSelectedSorting(auctionUser.getSelectedSorting().previous());
                    } else {
                        auctionUser.setSelectedSorting(auctionUser.getSelectedSorting().next());
                    }
                } else {
                    String[] sorts = arg.split("\\|");
                    if (sorts.length == 1) {
                        AuctionSorting s = AuctionSorting.fromId(sorts[0]);
                        if (s != null) auctionUser.setSelectedSorting(s);
                    } else {
                        AuctionSorting s1 = AuctionSorting.fromId(sorts[0]);
                        AuctionSorting s2 = AuctionSorting.fromId(sorts[1]);
                        if (event.isLeftClick()) {
                            if (s1 != null) auctionUser.setSelectedSorting(s1);
                        } else {
                            if (s2 != null) auctionUser.setSelectedSorting(s2);
                        }
                    }
                }
                refresh();
            }
            case "CURRENCY" -> { /* Legacy action: Vault is the only currency. */ }
            case "SEARCH" -> {
                if (event.isShiftClick()) {
                    auctionUser.setSearchFilter(null);
                    refresh();
                } else {
                    auctionManager.getSignInputManager().openSearchInput(getViewer(), query -> {
                        String filter = (query == null || query.isBlank()) ? null : query.trim();
                        auctionUser.setSearchFilter(filter);
                        getPlugin().getScheduler().runEntity(getViewer(), () -> {
                            if (this instanceof AuctionMyItemsGui) {
                                new AuctionMyItemsGui(getPlugin(), getViewer(), 1, null).open();
                            } else if (this instanceof AuctionExpiredGui) {
                                new AuctionExpiredGui(getPlugin(), getViewer(), 1, null).open();
                            } else {
                                new AuctionMainGui(getPlugin(), getViewer(), 1, null).open();
                            }
                        });
                    });
                }
            }
            case "MENU" -> {
                String menuName = arg.toLowerCase(Locale.ROOT).replace(".yml", "").replace(".yaml", "");
                switch (menuName) {
                    case "main" -> new AuctionMainGui(getPlugin(), getViewer(), 1, this).open();
                    case "my-items", "myitems" -> new AuctionMyItemsGui(getPlugin(), getViewer(), 1, this).open();
                    case "expired", "expired-items" -> new AuctionExpiredGui(getPlugin(), getViewer(), 1, this).open();
                    case "history" -> new AuctionHistoryGui(getPlugin(), getViewer(), 1, this).open();
                    case "logs" -> new AuctionLogsGui(getPlugin(), getViewer(), 1, this).open();
                    case "deleted" -> new AuctionDeletedGui(getPlugin(), getViewer(), 1, this).open();
                    case "category", "categories" -> new AuctionCategoryGui(getPlugin(), getViewer(), this).open();
                    case "close" -> getViewer().closeInventory();
                }
            }
            case "SOUND" -> {
                String[] parts = arg.split("\\|");
                if (parts.length > 1) {
                    try {
                        String soundName = parts[0].trim().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
                        Sound sound = Sound.valueOf(soundName);
                        float volume = Float.parseFloat(parts[1]);
                        float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0f;
                        getViewer().playSound(getViewer().getLocation(), sound, volume, pitch);
                    } catch (Exception ignored) {
                        playConfigSound(parts[0].trim());
                    }
                } else if (parts.length == 1 && !parts[0].isBlank()) {
                    playConfigSound(parts[0].trim());
                }
            }
            case "CONSOLE" -> {
                String cmd = applyPlaceholders(arg).replace("%player%", getViewer().getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            case "PLAYER" -> {
                String cmd = applyPlaceholders(arg).replace("%player%", getViewer().getName());
                getViewer().performCommand(cmd);
            }
            case "MESSAGE" -> {
                String msg = applyPlaceholders(arg).replace("%player%", getViewer().getName());
                getPlugin().getTextService().sendMessage(getViewer(), msg);
            }
            case "FIREWORK" -> {
                try {
                    String[] parts = arg.split("\\|");
                    String hex = parts[0].trim().replace("#", "");
                    Color color = Color.fromRGB(Integer.parseInt(hex, 16));
                    FireworkEffect.Type type = parts.length > 1 ? FireworkEffect.Type.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)) : FireworkEffect.Type.BALL;

                    Location loc = getViewer().getLocation();
                    if (loc.getWorld() != null) {
                        Firework fw = (Firework) loc.getWorld().spawnEntity(loc, EntityType.FIREWORK_ROCKET);
                        FireworkMeta fwm = fw.getFireworkMeta();
                        fwm.addEffect(FireworkEffect.builder().with(type).withColor(color).build());
                        fwm.setPower(0);
                        fw.setFireworkMeta(fwm);
                        fw.detonate();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public String applyPlaceholders(String text) {
        if (text == null) return "";
        // Most template lines carry no placeholders; skip the lookups below for them.
        if (text.indexOf('%') < 0 && text.indexOf('{') < 0) return text;
        org.bukkit.configuration.file.FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
        org.bukkit.configuration.file.FileConfiguration cfg = getPlugin().getConfigService().get("modules/auctions/config.yml");

        boolean categoriesEnabled = auctionManager.getCategoryManager().isEnabled();
        boolean expiredEnabled = cfg != null && cfg.getBoolean("separate-expired-items", false);
        boolean listingConfirm = cfg != null && cfg.getBoolean("auction-listing-confirmation", false);

        String sortingId = auctionUser.getSelectedSorting().getId();
        String sortingDisplayName = msgs != null ? msgs.getString("sorting." + sortingId, auctionUser.getSelectedSorting().getDisplayName()) : auctionUser.getSelectedSorting().getDisplayName();
        String noCurrencyStr = "Vault";
        UUID viewerId = getViewer().getUniqueId();
        var stats = auctionManager.getStatistics(viewerId);

        String result = text
                .replace("%current_page%", String.valueOf(getCurrentPage()))
                .replace("%max_page%", String.valueOf(getMaxPages()))
                .replace("%page%", String.valueOf(getCurrentPage()))
                .replace("{current_page}", String.valueOf(getCurrentPage()))
                .replace("{max_page}", String.valueOf(getMaxPages()))
                .replace("{currentPage}", String.valueOf(getCurrentPage()))
                .replace("{maxPages}", String.valueOf(getMaxPages()))
                .replace("%search%", (auctionUser.getSearchFilter() != null && !auctionUser.getSearchFilter().isBlank()) ? auctionUser.getSearchFilter() : "---")
                .replace("%selected_category%", auctionManager.getCategoryManager().getCategory(auctionUser.getSelectedCategory()).getName())
                .replace("%selected_category_raw%", auctionUser.getSelectedCategory())
                .replace("%selected_sorting%", sortingDisplayName)
                .replace("%selected_sorting_raw%", sortingId)
                .replace("%selected_currency%", noCurrencyStr)
                .replace("%selected_currency_raw%", "vault");
        // These scan every listing or every permission; only pay for them when the line uses them.
        if (result.contains("%sell_count%")) {
            result = result.replace("%sell_count%", String.valueOf(auctionManager.getPlayerTotalItemCount(viewerId)));
        }
        if (result.contains("%sell_limit%")) {
            int limit = auctionManager.getPlayerListingLimit(getViewer());
            String bypassIcon = msgs != null ? msgs.getString("limit-bypass-icon", "∞") : "∞";
            result = result.replace("%sell_limit%", limit == -1 ? bypassIcon : String.valueOf(limit));
        }
        if (result.contains("%total_active_count%")) {
            result = result.replace("%total_active_count%", String.valueOf(auctionManager.getActiveListingCount()));
        }
        result = result.replace("%items_sold%", String.valueOf(stats.sold()));
        if (result.contains("money_made")) {
            String madeShort = auctionManager.formatPriceShort(stats.earned());
            String made = auctionManager.formatPrice(stats.earned());
            result = result
                    .replace("%money_made_short%", madeShort)
                    .replace("{money_made_short}", madeShort)
                    .replace("%money_made%", made)
                    .replace("{money_made}", made);
        }
        result = result.replace("%items_purchased%", String.valueOf(stats.purchased()));
        if (result.contains("money_spent")) {
            String spentShort = auctionManager.formatPriceShort(stats.spent());
            String spent = auctionManager.formatPrice(stats.spent());
            result = result
                    .replace("%money_spent_short%", spentShort)
                    .replace("{money_spent_short}", spentShort)
                    .replace("%money_spent%", spent)
                    .replace("{money_spent}", spent);
        }
        return result
                .replace("%categories_enabled%", String.valueOf(categoriesEnabled))
                .replace("%expired_items_enabled%", String.valueOf(expiredEnabled))
                .replace("%auction_listing_confirmation_enabled%", String.valueOf(listingConfirm))
                .replace("%player%", getViewer().getName())
                .replace("{player}", getViewer().getName());
    }

    public ItemStack formatAuctionItemDisplay(AuctionItem item, String loreKey) {
        // getItemStack() already returns a private clone, safe to decorate in place.
        ItemStack display = item.getItemStack();
        if (display == null) return new ItemStack(Material.STONE);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) return display;

        org.bukkit.configuration.file.FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
        List<Component> metaLore = meta.hasLore() ? meta.lore() : null;
        List<Component> originalLore = metaLore != null ? metaLore : List.of();
        List<String> templateLore = msgs != null ? msgs.getStringList(loreKey) : List.of();
        if (templateLore.isEmpty() && msgs != null) {
            templateLore = msgs.getStringList("item-lore");
        }

        long expireDuration = auctionManager.getExpireDurationMillis();
        long deletionDuration = auctionManager.getDeletionDurationMillis();

        String expiresStr = formatTimeRemaining(item.getRemainingExpiryMillis(expireDuration));
        String deletionStr = formatTimeRemaining(item.getRemainingDeletionMillis(expireDuration, deletionDuration));
        String priceStr = auctionManager.formatPrice(item.getPrice());
        String priceShortStr = auctionManager.formatPriceShort(item.getPrice());
        String containerMsg = msgs != null ? msgs.getString("container", "&7Right-click to &fview contents") : "&7Right-click to &fview contents";
        String itemName = getItemDisplayName(display);
        String amountStr = String.valueOf(display.getAmount());
        boolean container = AuctionSafetyValidator.isContainer(display);

        List<Component> formattedLore = new ArrayList<>();
        boolean skipNextEmpty = false;
        for (String line : templateLore) {
            if (line.contains("%lore%")) {
                if (!originalLore.isEmpty()) {
                    formattedLore.addAll(originalLore);
                } else {
                    skipNextEmpty = true;
                }
                continue;
            }

            if (skipNextEmpty && line.trim().isEmpty()) {
                skipNextEmpty = false;
                continue;
            }
            skipNextEmpty = false;

            if (line.contains("%container%")) {
                if (container) {
                    Component containerComp = getPlugin().getTextService().parseCached(containerMsg);
                    if (containerComp.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                        containerComp = containerComp.decoration(TextDecoration.ITALIC, false);
                    }
                    formattedLore.add(containerComp);
                }
                continue;
            }

            String parsedLine = line
                    .replace("%seller%", item.getSellerName())
                    .replace("{seller}", item.getSellerName())
                    .replace("%price_short%", priceShortStr)
                    .replace("{price_short}", priceShortStr)
                    .replace("%price%", priceStr)
                    .replace("{price}", priceStr)
                    .replace("%expires%", expiresStr)
                    .replace("{expires}", expiresStr)
                    .replace("%deletion%", deletionStr)
                    .replace("{deletion}", deletionStr)
                    .replace("%item%", itemName)
                    .replace("{item}", itemName)
                    .replace("%amount%", amountStr)
                    .replace("{amount}", amountStr);

            Component comp = getPlugin().getTextService().parseCached(parsedLine);
            if (comp.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
                comp = comp.decoration(TextDecoration.ITALIC, false);
            }
            formattedLore.add(comp);
        }

        meta.lore(formattedLore);
        display.setItemMeta(meta);
        fireItemRenderEvent(display);
        return display;
    }

    protected void fireItemRenderEvent(ItemStack item) {
        if (item != null) {
            Bukkit.getPluginManager().callEvent(
                    new dev.lyy.friendlycore.modules.auctions.events.AuctionItemRenderEvent(getViewer(), item));
        }
    }

    public String formatTimeRemaining(long millis) {
        org.bukkit.configuration.file.FileConfiguration msgs = getPlugin().getConfigService().get("modules/auctions/messages.yml");
        org.bukkit.configuration.file.FileConfiguration cfg = getPlugin().getConfigService().get("modules/auctions/config.yml");
        String expiredText = msgs != null ? msgs.getString("expired", "Expired") : "Expired";
        int timerFormat = cfg != null ? cfg.getInt("timer-format", 1) : 1;
        String day = msgs != null ? msgs.getString("time.day", msgs.getString("time-units.days", "d")) : "d";
        String hour = msgs != null ? msgs.getString("time.hour", msgs.getString("time-units.hours", "h")) : "h";
        String minute = msgs != null ? msgs.getString("time.minute", msgs.getString("time-units.minutes", "m")) : "m";
        String second = msgs != null ? msgs.getString("time.second", msgs.getString("time-units.seconds", "s")) : "s";
        return formatTimeRemaining(millis, timerFormat, day, hour, minute, second, expiredText);
    }

    public static String formatTimeRemaining(long millis, int timerFormat, String day, String hour, String minute, String second, String expiredText) {
        if (millis <= 0) return expiredText;

        long total = (millis + 999L) / 1000L;
        long days = total / 86400L;
        long hours = (total % 86400L) / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;

        switch (timerFormat) {
            case 1 -> {
                // DonutSMP sleek readable style: e.g. 23h 59m, 1d 4h, 45m 12s, 30s
                if (days > 0) {
                    return hours > 0 ? days + day + " " + hours + hour : days + day;
                }
                if (hours > 0) {
                    return minutes > 0 ? hours + hour + " " + minutes + minute : hours + hour;
                }
                if (minutes > 0) {
                    return seconds > 0 ? minutes + minute + " " + seconds + second : minutes + minute;
                }
                return seconds + second;
            }
            case 2 -> {
                if (days > 0) return days + day;
                if (hours > 0) return hours + hour;
                if (minutes > 0) return minutes + minute;
                return seconds + second;
            }
            case 3 -> {
                if (days > 0) {
                    return String.format("%s%s %s%s %s%s %s%s", days, day, hours, hour, minutes, minute, seconds, second);
                }
                if (hours > 0) {
                    return String.format("%s%s %s%s %s%s", hours, hour, minutes, minute, seconds, second);
                }
                if (minutes > 0) {
                    return String.format("%s%s %s%s", minutes, minute, seconds, second);
                }
                return seconds + second;
            }
            case 4 -> {
                if (days > 0) {
                    return String.format("%02d:%02d:%02d:%02d", days, hours, minutes, seconds);
                }
                if (hours > 0) {
                    return String.format("%02d:%02d:%02d", hours, minutes, seconds);
                }
                return String.format("%02d:%02d", minutes, seconds);
            }
            default -> {
                return String.format("%02d:%02d", minutes, seconds);
            }
        }
    }

    protected void loadItemSlots() {
        itemSlots.clear();
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        int pageSize = config != null ? config.getInt("page-size", 36) : 36;
        itemSlots.addAll(calculateAvailableItemSlots(config, getSize(), pageSize));
    }

    protected void renderConfirmationButtons(Runnable onConfirm) {
        renderConfirmationButtons(onConfirm, 0.0, null, null);
    }

    protected void renderConfirmationButtons(Runnable onConfirm, double price, ItemStack itemStack, String sellerName) {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        ConfigurationSection confSec = null;
        ConfigurationSection cancSec = null;
        if (config != null) {
            confSec = config.getConfigurationSection("items.confirm");
            if (confSec == null) confSec = config.getConfigurationSection("confirm");

            cancSec = config.getConfigurationSection("items.cancel");
            if (cancSec == null) cancSec = config.getConfigurationSection("cancel");
        }

        renderConfirmButtons(confSec, onConfirm, price, itemStack, sellerName);
        renderCancelButtons(cancSec, price, itemStack, sellerName);
    }

    protected void renderConfirmButtons(ConfigurationSection confSec, Runnable onConfirm) {
        renderConfirmButtons(confSec, onConfirm, 0.0, null, null);
    }

    protected void renderConfirmButtons(ConfigurationSection confSec, Runnable onConfirm, double price, ItemStack itemStack, String sellerName) {
        List<Integer> confSlots = confSec != null ? parseSlots(confSec, getSize()) : List.of(10, 19, 28, 11, 20, 29);
        String confMatName = confSec != null ? confSec.getString("material", "LIME_STAINED_GLASS_PANE") : "LIME_STAINED_GLASS_PANE";
        Material confMat = Material.matchMaterial(confMatName);
        if (confMat == null) confMat = Material.LIME_STAINED_GLASS_PANE;
        String confName = confSec != null ? confSec.getString("name", "&#33FF33&lᴄᴏɴғɪʀᴍ") : "&#33FF33&lᴄᴏɴғɪʀᴍ";
        List<String> confLore = confSec != null ? confSec.getStringList("lore") : List.of();
        List<String> confActions = confSec != null ? confSec.getStringList("actions") : List.of("[SOUND] ui.button.click|1|1");

        String priceStr = price > 0 ? auctionManager.formatPrice(price) : "0";
        String priceShortStr = price > 0 ? auctionManager.formatPriceShort(price) : "0";
        String itemName = itemStack != null ? getItemDisplayName(itemStack) : "Item";
        String amountStr = String.valueOf(itemStack != null ? itemStack.getAmount() : 1);
        String seller = sellerName != null ? sellerName : "";

        ItemBuilder confBuilder = new ItemBuilder(confMat);
        if (!confName.isEmpty()) {
            String parsedName = applyPlaceholders(confName)
                    .replace("%price_short%", priceShortStr).replace("{price_short}", priceShortStr)
                    .replace("%price%", priceStr).replace("{price}", priceStr)
                    .replace("%item%", itemName).replace("{item}", itemName)
                    .replace("%amount%", amountStr).replace("{amount}", amountStr)
                    .replace("%seller%", seller).replace("{seller}", seller);
            confBuilder.name(getPlugin().getTextService().parseCached(parsedName));
        }
        if (!confLore.isEmpty()) {
            confBuilder.lore(confLore.stream().map(l -> {
                String parsed = applyPlaceholders(l)
                        .replace("%price_short%", priceShortStr).replace("{price_short}", priceShortStr)
                        .replace("%price%", priceStr).replace("{price}", priceStr)
                        .replace("%item%", itemName).replace("{item}", itemName)
                        .replace("%amount%", amountStr).replace("{amount}", amountStr)
                        .replace("%seller%", seller).replace("{seller}", seller);
                return getPlugin().getTextService().parseCached(parsed);
            }).toList());
        }
        ItemStack confStack = confBuilder.build();
        for (int slot : confSlots) {
            if (slot >= 0 && slot < getSize()) {
                setItem(slot, new GuiItem(confStack, event -> {
                    event.setCancelled(true);
                    for (String action : confActions) {
                        executeAction(action, event);
                    }
                    getViewer().closeInventory();
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                }));
            }
        }
    }

    protected void renderCancelButtons(ConfigurationSection cancSec) {
        renderCancelButtons(cancSec, 0.0, null, null);
    }

    protected void renderCancelButtons(ConfigurationSection cancSec, double price, ItemStack itemStack, String sellerName) {
        List<Integer> cancSlots = cancSec != null ? parseSlots(cancSec, getSize()) : List.of(15, 24, 33, 16, 25, 34);
        String cancMatName = cancSec != null ? cancSec.getString("material", "RED_STAINED_GLASS_PANE") : "RED_STAINED_GLASS_PANE";
        Material cancMat = Material.matchMaterial(cancMatName);
        if (cancMat == null) cancMat = Material.RED_STAINED_GLASS_PANE;
        String cancName = cancSec != null ? cancSec.getString("name", "&#FF3333&lᴄᴀɴᴄᴇʟ") : "&#FF3333&lᴄᴀɴᴄᴇʟ";
        List<String> cancLore = cancSec != null ? cancSec.getStringList("lore") : List.of();
        List<String> cancActions = cancSec != null ? cancSec.getStringList("actions") : List.of("[BACK]", "[SOUND] ui.button.click|1|1");

        String priceStr = price > 0 ? auctionManager.formatPrice(price) : "0";
        String priceShortStr = price > 0 ? auctionManager.formatPriceShort(price) : "0";
        String itemName = itemStack != null ? getItemDisplayName(itemStack) : "Item";
        String amountStr = String.valueOf(itemStack != null ? itemStack.getAmount() : 1);
        String seller = sellerName != null ? sellerName : "";

        ItemBuilder cancBuilder = new ItemBuilder(cancMat);
        if (!cancName.isEmpty()) {
            String parsedName = applyPlaceholders(cancName)
                    .replace("%price_short%", priceShortStr).replace("{price_short}", priceShortStr)
                    .replace("%price%", priceStr).replace("{price}", priceStr)
                    .replace("%item%", itemName).replace("{item}", itemName)
                    .replace("%amount%", amountStr).replace("{amount}", amountStr)
                    .replace("%seller%", seller).replace("{seller}", seller);
            cancBuilder.name(getPlugin().getTextService().parseCached(parsedName));
        }
        if (!cancLore.isEmpty()) {
            cancBuilder.lore(cancLore.stream().map(l -> {
                String parsed = applyPlaceholders(l)
                        .replace("%price_short%", priceShortStr).replace("{price_short}", priceShortStr)
                        .replace("%price%", priceStr).replace("{price}", priceStr)
                        .replace("%item%", itemName).replace("{item}", itemName)
                        .replace("%amount%", amountStr).replace("{amount}", amountStr)
                        .replace("%seller%", seller).replace("{seller}", seller);
                return getPlugin().getTextService().parseCached(parsed);
            }).toList());
        }
        ItemStack cancStack = cancBuilder.build();
        for (int slot : cancSlots) {
            if (slot >= 0 && slot < getSize()) {
                setItem(slot, new GuiItem(cancStack, event -> {
                    event.setCancelled(true);
                    for (String action : cancActions) {
                        executeAction(action, event);
                    }
                }));
            }
        }
    }

    public String formatDate(long millis) {
        return auctionManager != null ? auctionManager.formatDate(millis) : String.valueOf(millis);
    }
}
