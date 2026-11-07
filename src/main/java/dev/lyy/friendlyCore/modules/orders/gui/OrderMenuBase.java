package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.orders.OrderManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public abstract class OrderMenuBase extends PaginatedGui {

    protected final OrderManager orderManager;
    protected final String configPath;
    protected final OrderMenuBase previousMenu;
    private long lastClickTime = 0;

    public OrderMenuBase(FriendlyCore plugin, Player viewer, String configPath, Component title, int rows, OrderMenuBase previousMenu) {
        super(plugin, viewer, title, rows);
        this.orderManager = plugin.getOrdersModule().getOrderManager();
        this.configPath = configPath;
        this.previousMenu = previousMenu;
    }

    public static Component resolveConfigTitle(FriendlyCore plugin, String configPath, String fallback) {
        FileConfiguration config = plugin.getConfigService().get(configPath);
        String raw = config != null ? config.getString("title", fallback) : fallback;
        if (raw == null) raw = fallback;
        raw = raw.replace("{currentPage}", "1").replace("{maxPages}", "1")
                .replace("{current_page}", "1").replace("{max_page}", "1");
        return plugin.getTextService().parse(raw);
    }

    public void updatePageTitle() {
        FileConfiguration cfg = getPlugin().getConfigService().get(configPath);
        String raw = cfg != null ? cfg.getString("title", "") : "";
        if (!raw.contains("{currentPage}") && !raw.contains("{maxPages}")
                && !raw.contains("{current_page}") && !raw.contains("{max_page}")) return;
        String formatted = raw.replace("{currentPage}", String.valueOf(getCurrentPage()))
                .replace("{maxPages}", String.valueOf(getMaxPages()))
                .replace("{current_page}", String.valueOf(getCurrentPage()))
                .replace("{max_page}", String.valueOf(getMaxPages()));
        if (getViewer().getOpenInventory() != null && getViewer().getOpenInventory().getTopInventory().equals(getInventory())) {
            getViewer().getOpenInventory().setTitle(LegacyComponentSerializer.legacySection()
                    .serialize(getPlugin().getTextService().parse(formatted)));
        }
    }

    @Override
    public void setMaxPages(int pages) {
        super.setMaxPages(pages);
        updatePageTitle();
    }

    @Override
    public void setCurrentPage(int currentPage) {
        super.setCurrentPage(currentPage);
        updatePageTitle();
    }

    public abstract void refresh();

    @Override
    public void open() {
        refresh();
        super.open();
        getPlugin().getScheduler().runEntity(getViewer(), this::updatePageTitle);
    }

    public void clear() {
        for (int i = 0; i < getSize(); i++) {
            setItem(i, (GuiItem) null);
        }
    }

    public void close() {
        getPlugin().getScheduler().runEntity(getViewer(), () -> getViewer().closeInventory());
    }

    public void nextPage() {
        if (getCurrentPage() < getMaxPages()) {
            setCurrentPage(getCurrentPage() + 1);
            refresh();
        }
    }

    public void previousPage() {
        if (getCurrentPage() > 1) {
            setCurrentPage(getCurrentPage() - 1);
            refresh();
        }
    }

    public OrderMenuBase getPreviousMenu() {
        return previousMenu;
    }

    protected boolean checkClickCooldown() {
        FileConfiguration config = getPlugin().getConfigService().get("modules/orders/config.yml");
        long cooldownMs = config != null ? config.getLong("click-cooldown-ms", 100) : 100;
        long now = System.currentTimeMillis();
        if (now - lastClickTime < cooldownMs) {
            return false;
        }
        lastClickTime = now;
        return true;
    }

    protected void playConfigSound(String soundKey) {
        if (soundKey == null || soundKey.isEmpty()) return;
        FileConfiguration soundsCfg = getPlugin().getConfigService().get("modules/orders/sounds.yml");
        if (soundsCfg != null && soundsCfg.contains(soundKey)) {
            String soundName = soundsCfg.getString(soundKey + ".sound", "UI_BUTTON_CLICK");
            float vol = (float) soundsCfg.getDouble(soundKey + ".volume", 1.0);
            float pitch = (float) soundsCfg.getDouble(soundKey + ".pitch", 1.0);
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
                getViewer().playSound(getViewer().getLocation(), sound, vol, pitch);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    protected void loadTemplateButtons() {
        FileConfiguration config = getPlugin().getConfigService().get(configPath);
        if (config == null) return;

        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection itemSec = itemsSection.getConfigurationSection(key);
            if (itemSec == null) continue;

            // Skip dynamic item definitions handled by subclass
            if (key.equals("actual_order") || key.equals("expired_order") || key.equals("your_order") || key.equals("max_orders") || key.equals("delivery_order") || key.equals("order_info")) {
                continue;
            }

            List<Integer> slots = parseSlots(itemSec);
            if (slots.isEmpty()) continue;

            String matName = itemSec.getString("material", "STONE");
            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = Material.STONE;

            String name = itemSec.getString("name", " ");
            List<String> lore = itemSec.getStringList("lore");
            int customModelData = itemSec.getInt("customModelData", -1);
            String soundKey = itemSec.getString("sound", "click");

            ItemBuilder builder = new ItemBuilder(mat);
            if (!name.isEmpty()) builder.name(name);
            if (!lore.isEmpty()) builder.loreStrings(lore);
            if (customModelData != -1) builder.customModelData(customModelData);
            builder.flags(ItemFlag.HIDE_ATTRIBUTES);

            ItemStack stack = builder.build();

            for (int slot : slots) {
                if (slot < 0 || slot >= getSize()) continue;
                setItem(slot, new GuiItem(stack, e -> {
                    if (!checkClickCooldown()) return;
                    playConfigSound(soundKey);
                    handleButtonClick(key, e);
                }));
            }
        }
    }

    protected List<Integer> parseSlots(ConfigurationSection section) {
        List<Integer> result = new ArrayList<>();
        if (section.isList("slot")) {
            for (Object obj : section.getList("slot")) {
                if (obj instanceof Number num) {
                    result.add(num.intValue());
                }
            }
        } else if (section.contains("slot")) {
            int singleSlot = section.getInt("slot", -1);
            if (singleSlot != -1) result.add(singleSlot);
        }
        return result;
    }

    protected void handleButtonClick(String buttonKey, InventoryClickEvent e) {
        switch (buttonKey.toLowerCase(Locale.ROOT)) {
            case "back", "previous" -> {
                if (getCurrentPage() > 1) {
                    previousPage();
                } else if (previousMenu != null) {
                    previousMenu.open();
                } else {
                    close();
                }
            }
            case "next" -> nextPage();
            case "cancel" -> {
                if (previousMenu != null) {
                    previousMenu.open();
                } else {
                    close();
                }
            }
        }
    }
}
