package dev.lyy.friendlycore.modules.pets.gui;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Inset collection gallery with the two equipped companions in the header. */
public class PetsGui extends PaginatedGui {
    private final PetManager petManager;

    public PetsGui(FriendlyCore plugin, Player player, PetManager manager, int page) {
        super(plugin, player, title(plugin, player, manager, page), 6);
        petManager = manager;
        setCurrentPage(Math.max(1, Math.min(page, pageCount(player, manager, menu(plugin)))));
        render();
    }

    public static void open(FriendlyCore plugin, Player player, PetManager manager, int page) {
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline()) player.openInventory(new PetsGui(plugin, player, manager, page).getInventory());
        }, 1L);
    }

    private static FileConfiguration menu(FriendlyCore plugin) {
        return plugin.getConfigService().get("modules/pets/inventories/pets.yml");
    }

    /** Owned ids, including pets whose config failed to load, so they never look lost. */
    private static List<String> collection(Player player, PetManager manager) {
        PlayerPetData data = manager.getPlayerData(player.getUniqueId());
        return data == null ? List.of() : data.getUnlockedPets().stream().sorted().toList();
    }

    static int pageSize(FileConfiguration config) {
        return config == null ? 28 : Math.clamp(config.getInt("page-size", 28), 1, 28);
    }

    static int collectionSlot(int index) {
        if (index < 0 || index >= 28) throw new IllegalArgumentException("Collection index must be 0–27");
        return 10 + index / 7 * 9 + index % 7;
    }

    private static int pageCount(Player player, PetManager manager, FileConfiguration config) {
        int size = pageSize(config);
        return Math.max(1, (collection(player, manager).size() + size - 1) / size);
    }

    private static Component title(FriendlyCore plugin, Player player, PetManager manager, int page) {
        FileConfiguration config = menu(plugin);
        int total = pageCount(player, manager, config);
        String raw = config == null ? "&0Pets &8%page%/%max_page%" : config.getString("title", "&0Pets &8%page%/%max_page%");
        if (List.of("&8Pets &7| %page%/%max_page%", "&8Pets (%page%/%max_page%)", "ᴘᴇᴛs (%page%/%max_page%)",
                "&8Pets &7%page%/%max_page%").contains(raw)) {
            raw = "&0Pets &8%page%/%max_page%";
        }
        return plugin.getTextService().toComponent(raw.replace("%page%", String.valueOf(Math.max(1, Math.min(page, total))))
                .replace("%max_page%", String.valueOf(total)));
    }

    private Component text(String value) {
        return getPlugin().getTextService().toComponent(value);
    }

    private GuiItem item(Material material, String name, String... lore) {
        return new GuiItem(new ItemBuilder(material).name(text(name))
                .lore(java.util.Arrays.stream(lore).map(this::text).toList()).build(), e -> e.setCancelled(true));
    }

    private GuiItem button(String key, Material material, String name, String... lore) {
        FileConfiguration config = menu(getPlugin());
        if (config != null) {
            Material configured = Material.matchMaterial(config.getString("items." + key + ".material", material.name()));
            if (configured != null) material = configured;
            List<String> shippedNames = switch (key) {
                case "back" -> List.of("#E63A35« ʙᴀᴄᴋ");
                case "next" -> List.of("#E63A35ɴᴇxᴛ »");
                case "unequip" -> List.of("#E63A35ᴜɴᴇǫᴜɪᴘ", "&fUnequip both pets");
                default -> List.of();
            };
            String configuredName = config.getString("items." + key + ".name", name);
            if (!shippedNames.contains(configuredName)) name = configuredName;
        }
        return item(material, name, lore);
    }

    private GuiItem action(GuiItem display, Runnable onClick) {
        return new GuiItem(display.getItemStack(), e -> {
            e.setCancelled(true);
            onClick.run();
            click();
        });
    }

    /** Header: companions flank the summary. Footer: pages at the edges, controls centred. */
    public void render() {
        Player player = getViewer();
        for (int slot = 0; slot < getSize(); slot++) setItem(slot, (GuiItem) null);
        fillBorder(new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(Component.empty()).build());
        setItem(49, action(button("close", Material.BARRIER, "&fClose"), this::closeMenu));

        PlayerPetData data = petManager.getPlayerData(player.getUniqueId());
        if (data == null) {
            setItem(22, item(Material.CLOCK, "&fLoading your pets", "&7Reopen this menu in a moment."));
            return;
        }

        List<String> pets = collection(player, petManager);
        int size = pageSize(menu(getPlugin()));
        int pages = Math.max(1, (pets.size() + size - 1) / size);
        if (getCurrentPage() > pages) {
            open(getPlugin(), player, petManager, pages);
            return;
        }
        setMaxPages(pages);
        int page = getCurrentPage();
        int start = (page - 1) * size;
        for (int index = start; index < Math.min(start + size, pets.size()); index++) {
            Pet pet = petManager.getPet(pets.get(index));
            setItem(collectionSlot(index - start), pet != null ? petItem(player, pet, data)
                    : item(Material.GRAY_DYE, "&7" + pets.get(index), "&8Unavailable right now.", "&8It stays in your collection."));
        }
        if (pets.isEmpty()) setItem(22, item(Material.EGG, "&fNo pets yet",
                "&7Right-click a pet egg to add", "&7it to your collection."));

        List<String> equipped = data.getActivePets();
        setItem(4, item(Material.BOOK, "&fCollection",
                "&7Collected &f" + pets.size(), "&7Equipped &f" + equipped.size() + "&7/" + PlayerPetData.MAX_ACTIVE_PETS,
                "", "&7Equip up to two pets at once."));
        for (int index = 0; index < PlayerPetData.MAX_ACTIVE_PETS; index++) {
            Pet pet = index < equipped.size() ? petManager.getPet(equipped.get(index)) : null;
            setItem(index == 0 ? 3 : 5, pet != null ? petItem(player, pet, data)
                    : item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Companion " + (index + 1), "&8Empty"));
        }

        String pageLine = "&7Page &f" + page + " &7of &f" + pages;
        if (page > 1) setItem(45, action(button("back", Material.ARROW, "&fPrevious page", pageLine),
                () -> open(getPlugin(), player, petManager, page - 1)));
        if (page < pages) setItem(53, action(button("next", Material.ARROW, "&fNext page", pageLine),
                () -> open(getPlugin(), player, petManager, page + 1)));
        boolean hidden = data.isHidePet();
        setItem(48, action(item(hidden ? Material.ENDER_EYE : Material.ENDER_PEARL,
                hidden ? "&fShow companions" : "&fHide companions",
                "&7Effects stay active while hidden."), () -> {
            petManager.toggleHidePet(player);
            render();
        }));
        if (!equipped.isEmpty()) setItem(50, action(button("unequip", Material.RED_DYE, "&fUnequip all",
                "&7Return your companions", "&7to the collection."), () -> {
            petManager.deactivatePet(player, true);
            render();
        }));
    }

    private GuiItem petItem(Player player, Pet pet, PlayerPetData data) {
        boolean active = data.isActivePet(pet.getId());
        String icon = pet.getIconString();
        Material material = Material.matchMaterial(icon.split(" ")[0]);
        ItemBuilder builder = new ItemBuilder(material == null ? Material.PLAYER_HEAD : material);
        if (icon.startsWith("player_head texture:")) builder.skullTexture(icon.substring("player_head texture:".length()).trim());
        builder.name(text(pet.getDisplayName()).colorIfAbsent(NamedTextColor.WHITE)).glow(active);

        List<Component> lore = new ArrayList<>();
        if (!pet.getDescription().isBlank()) {
            lore.add(text("&7" + pet.getDescription()));
            lore.add(Component.empty());
        }
        if (!pet.getEffectsDescription().isEmpty()) {
            lore.add(text("&fEffects"));
            pet.getEffectsDescription().forEach(line -> lore.add(text(line.replace("%pet%", pet.getDisplayName()))));
            lore.add(Component.empty());
        }
        if (active) {
            lore.add(text("#62D4C1Equipped &8· &7Companion " + (data.getActivePets().indexOf(pet.getId()) + 1)));
            if (!pet.areConditionsMet(player)) lore.add(text("&eEffects paused here"));
            lore.add(text("&fLeft-click &7to unequip"));
            if (pet.getSpawnEggConfig().isWithdrawable()) lore.add(text("&fRight-click &7to withdraw as an egg"));
        } else if (!pet.canActivate(player)) {
            lore.add(text("&cRequirements not met"));
        } else if (data.getActivePets().size() >= PlayerPetData.MAX_ACTIVE_PETS) {
            lore.add(text("&7Both companion slots are in use"));
        } else {
            lore.add(text("&fLeft-click &7to equip"));
        }

        return new GuiItem(builder.lore(lore).build(), e -> {
            e.setCancelled(true);
            PlayerPetData current = petManager.getPlayerData(player.getUniqueId());
            if (current == null) return;
            if (e.isRightClick()) {
                if (current.isActivePet(pet.getId()) && pet.getSpawnEggConfig().isWithdrawable()) {
                    WithdrawConfirmGui.open(getPlugin(), player, petManager, pet, getCurrentPage());
                }
            } else if (e.isLeftClick()) {
                if (current.isActivePet(pet.getId())) petManager.deactivatePet(player, pet.getId(), true);
                else petManager.activatePet(player, pet, true);
                render();
            }
            click();
        });
    }

    private void closeMenu() {
        getPlugin().getScheduler().runEntityLater(getViewer(), () -> {
            if (getViewer().isOnline() && getViewer().getOpenInventory().getTopInventory().getHolder() == this) getViewer().closeInventory();
        }, 1L);
    }

    private void click() {
        getViewer().playSound(getViewer().getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }
}
