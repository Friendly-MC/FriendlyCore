package dev.lyy.friendlycore.modules.pets.gui;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.common.gui.GuiItem;
import dev.lyy.friendlycore.common.gui.PaginatedGui;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.WithdrawResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Confirmation GUI for withdrawing a pet back into a tradeable spawn egg.
 */
public class WithdrawConfirmGui extends PaginatedGui {

    private final PetManager petManager;
    private final Pet pet;
    private final int returnPage;

    public WithdrawConfirmGui(FriendlyCore plugin, Player viewer, PetManager petManager, Pet pet) {
        this(plugin, viewer, petManager, pet, 1);
    }

    public WithdrawConfirmGui(FriendlyCore plugin, Player viewer, PetManager petManager, Pet pet, int returnPage) {
        super(plugin, viewer, title(plugin), 3);
        this.petManager = petManager;
        this.pet = pet;
        this.returnPage = returnPage;
        render();
    }

    public static void open(FriendlyCore plugin, Player player, PetManager petManager, Pet pet) {
        open(plugin, player, petManager, pet, 1);
    }

    private static Component title(FriendlyCore plugin) {
        String title = plugin.getConfigService().get("modules/pets/inventories/confirm.yml")
                .getString("title", "&0Withdraw pet");
        if (List.of("&cConfirm Withdrawal", "ᴘᴇᴛs » ᴡɪᴛʜᴅʀᴀᴡ", "&8Withdraw pet").contains(title)) title = "&0Withdraw pet";
        return plugin.getTextService().toComponent(title);
    }

    public static void open(FriendlyCore plugin, Player player, PetManager petManager, Pet pet, int returnPage) {
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline()) player.openInventory(new WithdrawConfirmGui(plugin, player, petManager, pet, returnPage).getInventory());
        }, 1L);
    }

    public void render() {
        Player player = getViewer();

        ItemStack mask = new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name(Component.empty()).build();
        fillBorder(mask);

        Material egg = Material.matchMaterial(pet.getSpawnEggConfig().getItem().split(" ")[0]);
        ItemStack petInfo = new ItemBuilder(egg == null ? Material.EGG : egg)
                .name(getPlugin().getTextService().toComponent(pet.getDisplayName()).colorIfAbsent(NamedTextColor.WHITE))
                .lore(List.of(
                        Messages.component(getPlugin(), "pets", "convert-this-pet-into-a-tradeable"),
                        Messages.component(getPlugin(), "pets", "it-will-leave-your-collection"),
                        Component.empty(),
                        Messages.component(getPlugin(), "pets", "withdraw-cost", "%cost%", pet.getSpawnEggConfig().isWithdrawPriceEnabled()
                                ? pet.getSpawnEggConfig().getWithdrawPriceDisplay() : Messages.raw(getPlugin(), "pets", "withdraw-cost-free"))
                ))
                .build();
        setItem(13, new GuiItem(petInfo, e -> e.setCancelled(true)));

        ItemStack confirmItem = new ItemBuilder(Material.LIME_DYE)
                .name(Messages.component(getPlugin(), "pets", "withdraw-pet"))
                .lore(List.of(
                        Messages.component(getPlugin(), "pets", "click-to-receive-the-spawn-egg")
                ))
                .build();
        setItem(11, new GuiItem(confirmItem, e -> {
            e.setCancelled(true);
            getPlugin().getScheduler().runEntityLater(player, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != this) return;
                player.closeInventory();
                WithdrawResult res = petManager.withdrawPet(player, pet);
                if (res == WithdrawResult.CANNOT_AFFORD) {
                    player.sendMessage(getPlugin().getTextService().toComponent(petManager.getMessages().getString("messages.cannot-afford-withdraw", "&cYou cannot afford to withdraw this pet!")));
                } else if (res == WithdrawResult.INVENTORY_FULL) {
                    player.sendMessage(getPlugin().getTextService().toComponent(petManager.getMessages().getString("messages.inventory-full", "&cYour inventory is full! Make space before withdrawing your pet.")));
                } else if (res == WithdrawResult.NOT_WITHDRAWABLE) {
                    player.sendMessage(getPlugin().getTextService().toComponent(petManager.getMessages().getString("messages.pet-not-withdrawable", "&cThis pet cannot be withdrawn.")));
                } else if (res == WithdrawResult.NO_ACTIVE) {
                    player.sendMessage(getPlugin().getTextService().toComponent(petManager.getMessages().getString("messages.no-active-pet-to-withdraw", "&eEquip this pet before withdrawing it.")));
                } else if (res == WithdrawResult.OK) {
                    try {
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                    } catch (Throwable ignored) {}
                }
                PetsGui.open(getPlugin(), player, petManager, returnPage);
            }, 1L);
        }));

        ItemStack cancelItem = new ItemBuilder(Material.ARROW)
                .name(Messages.component(getPlugin(), "pets", "keep-pet"))
                .lore(List.of(Messages.component(getPlugin(), "pets", "return-to-your-collection")))
                .build();
        setItem(15, new GuiItem(cancelItem, e -> {
            e.setCancelled(true);
            try {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
            } catch (Throwable ignored) {}
            PetsGui.open(getPlugin(), player, petManager, returnPage);
        }));
    }
}
