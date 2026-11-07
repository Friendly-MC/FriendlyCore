package dev.lyy.friendlycore.modules.orders.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class PickEnchantmentsGui extends OrderMenuBase {

    private final ItemStack itemTemplate;
    private final Map<Enchantment, Integer> selectedEnchants = new HashMap<>();
    private final List<Enchantment> applicableEnchants = new ArrayList<>();

    public static void openMenu(FriendlyCore plugin, Player player, ItemStack itemTemplate, OrderMenuBase previousMenu) {
        PickEnchantmentsGui gui = new PickEnchantmentsGui(plugin, player, itemTemplate, previousMenu);
        gui.open();
    }

    public PickEnchantmentsGui(FriendlyCore plugin, Player viewer, ItemStack itemTemplate, OrderMenuBase previousMenu) {
        super(plugin, viewer, "modules/orders/guis/pick_enchantments.yml",
                resolveConfigTitle(plugin, "modules/orders/guis/pick_enchantments.yml", "ᴘɪᴄᴋ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛs"), 6, previousMenu);
        this.itemTemplate = itemTemplate != null ? itemTemplate.clone() : new ItemStack(Material.STONE);
        initApplicableEnchants();
    }

    private void initApplicableEnchants() {
        for (Enchantment ench : Enchantment.values()) {
            if (itemTemplate.getType() == Material.BOOK || itemTemplate.getType() == Material.ENCHANTED_BOOK || ench.canEnchantItem(itemTemplate)) {
                applicableEnchants.add(ench);
            }
        }
        applicableEnchants.sort(Comparator.comparing(e -> e.getKey().getKey()));
    }

    @Override
    public void refresh() {
        clear();
        loadTemplateButtons();

        int pageSize = 45;
        int maxPages = Math.max(1, (int) Math.ceil((double) applicableEnchants.size() / pageSize));
        setMaxPages(maxPages);

        int startIndex = (getCurrentPage() - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, applicableEnchants.size());

        for (int i = startIndex; i < endIndex; i++) {
            Enchantment ench = applicableEnchants.get(i);
            int slot = i - startIndex;
            if (slot >= 45) break;

            int currentLevel = selectedEnchants.getOrDefault(ench, 0);
            boolean isSelected = currentLevel > 0;

            String enchName = formatEnchantmentName(ench);
            ItemBuilder builder = new ItemBuilder(Material.ENCHANTED_BOOK)
                    .name((isSelected ? "#00FF00" : "&7") + enchName + (isSelected ? " " + toRoman(currentLevel) : ""))
                    .loreStrings(List.of(
                            "&7Max Level: &f" + ench.getMaxLevel(),
                            "",
                            isSelected ? "&aSelected (Level " + currentLevel + ")" : "&fClick to select",
                            "&eLeft-Click: Increase Level",
                            "&cRight-Click: Remove"
                    ));

            setItem(slot, new GuiItem(builder.build(), e -> {
                if (!checkClickCooldown()) return;
                playConfigSound("click");
                if (e.isLeftClick()) {
                    int next = currentLevel + 1;
                    if (next > ench.getMaxLevel()) next = 1;
                    selectedEnchants.put(ench, next);
                } else if (e.isRightClick()) {
                    selectedEnchants.remove(ench);
                }
                refresh();
            }));
        }
    }

    private String formatEnchantmentName(Enchantment ench) {
        String key = ench.getKey().getKey().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : key.split(" ")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private String toRoman(int num) {
        String[] roman = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return num >= 1 && num <= 10 ? roman[num] : String.valueOf(num);
    }

    @Override
    protected void handleButtonClick(String buttonKey, InventoryClickEvent e) {
        super.handleButtonClick(buttonKey, e);
        if (buttonKey.equalsIgnoreCase("confirm")) {
            ItemStack finalTemplate = itemTemplate.clone();
            if (finalTemplate.getType() == Material.BOOK && !selectedEnchants.isEmpty()) {
                finalTemplate.setType(Material.ENCHANTED_BOOK);
            }

            ItemMeta meta = finalTemplate.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta bookMeta) {
                selectedEnchants.forEach((ench, lvl) -> bookMeta.addStoredEnchant(ench, lvl, true));
            } else if (meta != null) {
                selectedEnchants.forEach((ench, lvl) -> meta.addEnchant(ench, lvl, true));
            }
            finalTemplate.setItemMeta(meta);

            CreateOrderGui.openMenu(getPlugin(), getViewer(), finalTemplate, this);
        }
    }
}
