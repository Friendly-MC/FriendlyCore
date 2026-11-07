package dev.lyy.friendlycore.modules.pets.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles pet spawn egg redemption on right click.
 */
public class PetSpawnEggListener implements Listener {

    private final FriendlyCore plugin;
    private final PetManager petManager;

    public PetSpawnEggListener(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        String petId = petEggId(event.getItem());
        if (petId == null) return;

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        Pet pet = petManager.getPet(petId);
        if (pet == null) {
            player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.invalid-pet", "&cInvalid pet egg!")));
            return;
        }

        // Without loaded data the owned check is blind and the unlock bypasses the dirty-save retry.
        PlayerPetData data = petManager.getPlayerData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.pets-loading", "&eYour pets are still loading. Try again in a moment.")));
            return;
        }
        if (data.hasPet(pet.getId())) {
            player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.cannot-spawn-pet", "&cYou already have this pet unlocked!")));
            return;
        }

        // Consume 1 egg item if not in creative
        ItemStack refund = event.getItem().clone();
        refund.setAmount(1);
        boolean consumed = player.getGameMode() != org.bukkit.GameMode.CREATIVE;
        if (consumed) {
            EquipmentSlot hand = event.getHand() != null ? event.getHand() : EquipmentSlot.HAND;
            if (hand == EquipmentSlot.OFF_HAND) {
                ItemStack inOff = player.getInventory().getItemInOffHand();
                if (inOff.getAmount() > 1) {
                    inOff.setAmount(inOff.getAmount() - 1);
                } else {
                    player.getInventory().setItemInOffHand(null);
                }
            } else {
                ItemStack inHand = player.getInventory().getItemInMainHand();
                if (inHand.getAmount() > 1) {
                    inHand.setAmount(inHand.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            }
        }

        // A stale cache (e.g. a fast server switch) can miss a pet the database already has: return the egg.
        // A failed write is not refunded; the cached unlock stays dirty and autosave retries it.
        petManager.unlockPet(player.getUniqueId(), pet.getId(), true).thenAccept(added -> {
            if (added || !consumed) return;
            plugin.getScheduler().runEntity(player, () -> {
                if (!player.isOnline()) {
                    lostRefund(player, pet);
                    return;
                }
                for (ItemStack leftover : player.getInventory().addItem(refund).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                player.sendMessage(plugin.getTextService().toComponent(petManager.getMessages().getString("messages.cannot-spawn-pet", "&cYou already have this pet unlocked!")));
            }, () -> lostRefund(player, pet));
        });

        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        } catch (Throwable ignored) {}

        String msg = petManager.getMessages().getString("messages.pet-spawned", "&fYou've spawned your %pet%&f pet! Visit &a/pets&f to activate it!")
                .replace("%pet%", pet.getName());
        player.sendMessage(plugin.getTextService().toComponent(msg));
    }

    /** Vanilla spawns a mob and eats the egg when it is used on a matching mob. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        if (petEggId(event.getPlayer().getInventory().getItem(event.getHand())) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDispense(BlockDispenseEvent event) {
        if (petEggId(event.getItem()) != null) event.setCancelled(true);
    }

    private void lostRefund(Player player, Pet pet) {
        plugin.getLogger().warning(player.getName() + " already owned " + pet.getId()
                + " and left before their egg could be returned; give it back with /ecopets giveegg.");
    }

    static String petEggId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String petId = meta.getPersistentDataContainer().get(Pet.PET_EGG_KEY, PersistentDataType.STRING);
        return petId == null || petId.isBlank() ? null : petId;
    }
}
