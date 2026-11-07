package dev.lyy.friendlycore.modules.essentials.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

/**
 * Single source of truth for the invsee slot layout and for merging viewer edits back into a target.
 *
 * <p>Canonical 41-slot order (also the cross-server wire format): 0-35 storage, 36 helmet,
 * 37 chestplate, 38 leggings, 39 boots, 40 offhand. In the 54-slot GUI, storage keeps its index,
 * armor sits at 45-48, offhand at 50, and row 36-44 plus 49/51-53 are locked decoration.</p>
 */
public final class InvseeLayout {

    public static final int SIZE = 41;
    public static final int GUI_SIZE = 54;

    private InvseeLayout() {}

    public static int guiSlot(int index) {
        if (index < 36) return index;
        return index == 40 ? 50 : 45 + (index - 36);
    }

    public static boolean isLockedGuiSlot(int rawSlot) {
        return (rawSlot >= 36 && rawSlot < 45) || rawSlot == 49 || (rawSlot >= 51 && rawSlot < GUI_SIZE);
    }

    public static ItemStack get(PlayerInventory inv, int index) {
        return switch (index) {
            case 36 -> inv.getHelmet();
            case 37 -> inv.getChestplate();
            case 38 -> inv.getLeggings();
            case 39 -> inv.getBoots();
            case 40 -> inv.getItemInOffHand();
            default -> inv.getItem(index);
        };
    }

    public static void set(PlayerInventory inv, int index, ItemStack item) {
        switch (index) {
            case 36 -> inv.setHelmet(item);
            case 37 -> inv.setChestplate(item);
            case 38 -> inv.setLeggings(item);
            case 39 -> inv.setBoots(item);
            case 40 -> inv.setItemInOffHand(item);
            default -> inv.setItem(index, item);
        }
    }

    /** Detached copy of the player's inventory in canonical order; air becomes null. */
    public static ItemStack[] fromPlayer(PlayerInventory inv) {
        ItemStack[] items = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) items[i] = copy(get(inv, i));
        return items;
    }

    /** Detached copy of a 54-slot invsee GUI in canonical order. */
    public static ItemStack[] fromGui(Inventory gui) {
        ItemStack[] items = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) items[i] = copy(gui.getItem(guiSlot(i)));
        return items;
    }

    /** Writes canonical items into the GUI, touching only slots that actually differ. */
    public static void toGui(Inventory gui, ItemStack[] items) {
        for (int i = 0; i < Math.min(SIZE, items.length); i++) {
            int slot = guiSlot(i);
            if (!same(gui.getItem(slot), items[i])) gui.setItem(slot, copy(items[i]));
        }
    }

    public static Inventory createGui(Component title, Component dividerName, Component infoName, ItemStack[] items) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, title);
        ItemStack divider = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = divider.getItemMeta();
        meta.displayName(dividerName);
        divider.setItemMeta(meta);
        for (int i = 36; i < 45; i++) inv.setItem(i, divider);
        ItemStack info = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(infoName);
        info.setItemMeta(infoMeta);
        inv.setItem(53, info);
        toGui(inv, items);
        return inv;
    }

    /** Detached copies of every item; air becomes null. */
    public static ItemStack[] copyAll(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = copy(items[i]);
        return copy;
    }

    public static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    public static boolean same(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.getType().isAir();
        boolean bEmpty = b == null || b.getType().isAir();
        if (aEmpty || bEmpty) return aEmpty == bEmpty;
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    /**
     * Writes every slot the viewer changed ({@code edited != base}) where the target still holds the
     * base item. A slot the target changed meanwhile is a conflict and is left as the target has it,
     * so a stale view can never restore an item the target already dropped or used.
     *
     * @return number of conflicting slots skipped
     */
    public static int applyEdits(ItemStack[] base, ItemStack[] edited,
                                 IntFunction<ItemStack> current, BiConsumer<Integer, ItemStack> write) {
        return mergeEdits(base, edited, current, write, InvseeLayout::same);
    }

    // Generic so the merge rules can be unit tested without a Bukkit server.
    public static <T> int mergeEdits(T[] base, T[] edited, IntFunction<T> current,
                                     BiConsumer<Integer, T> write, BiPredicate<T, T> same) {
        int conflicts = 0;
        for (int i = 0; i < Math.min(base.length, edited.length); i++) {
            if (same.test(base[i], edited[i])) continue;
            if (same.test(current.apply(i), base[i])) {
                write.accept(i, edited[i]);
            } else {
                // ponytail: per-slot compare-and-set, not a transaction. If the viewer moved this item into
                // their own inventory while the target changed the slot, the copy can't be taken back.
                conflicts++;
            }
        }
        return conflicts;
    }

    /** Gives the item to the player, dropping whatever does not fit at their feet. */
    public static void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    /** Moves the cursor item back into the player's inventory (or onto the ground). */
    public static void returnCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) return;
        player.setItemOnCursor(null);
        giveOrDrop(player, cursor);
    }
}
