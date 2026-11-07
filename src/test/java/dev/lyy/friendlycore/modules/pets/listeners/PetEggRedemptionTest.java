package dev.lyy.friendlycore.modules.pets.listeners;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.TextService;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PetEggRedemptionTest {
    private final UUID uuid = UUID.randomUUID();
    private final List<String> unlocked = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<ItemStack> returned = new ArrayList<>();
    private Set<String> databasePets = Set.of();
    private PlayerPetData data;

    @Test
    void eggIsKeptUntilPetDataHasLoaded() throws Exception {
        EggStack egg = egg("vip");
        PlayerInteractEvent event = rightClick(player(egg), egg);
        listener().onPlayerInteract(event);
        assertTrue(event.isCancelled());
        assertEquals(1, egg.amount);
        assertTrue(unlocked.isEmpty());
        assertEquals(List.of("pets-loading"), messages);
    }

    @Test
    void ownedPetKeepsTheEggAndNewPetConsumesIt() throws Exception {
        data = new PlayerPetData(uuid, "Player");
        data.unlockPet("vip");
        EggStack owned = egg("vip");
        listener().onPlayerInteract(rightClick(player(owned), owned));
        assertEquals(1, owned.amount);
        assertTrue(unlocked.isEmpty());

        EggStack fresh = egg("king");
        listener().onPlayerInteract(rightClick(player(fresh), fresh));
        assertEquals(0, fresh.amount);
        assertEquals(List.of("king"), unlocked);
    }

    @Test
    void staleCacheReturnsTheEggWhenTheDatabaseAlreadyOwnsThePet() throws Exception {
        data = new PlayerPetData(uuid, "Player");
        databasePets = Set.of("vip");
        EggStack egg = egg("vip");
        listener().onPlayerInteract(rightClick(player(egg), egg));
        assertEquals(0, egg.amount);
        assertEquals(1, returned.size());
        assertEquals(1, returned.getFirst().getAmount());
        assertTrue(data.hasPet("vip"));
    }

    @Test
    void vanillaCannotConsumeEggsOnMobsOrFromDispensers() throws Exception {
        EggStack egg = egg("vip");
        PetSpawnEggListener listener = listener();
        PlayerInteractEntityEvent useOnMob = new PlayerInteractEntityEvent(player(egg), null, EquipmentSlot.HAND);
        listener.onUseOnEntity(useOnMob);
        assertTrue(useOnMob.isCancelled());

        BlockDispenseEvent dispense = new BlockDispenseEvent(null, egg, new Vector());
        listener.onDispense(dispense);
        assertTrue(dispense.isCancelled());
        BlockDispenseEvent plain = new BlockDispenseEvent(null, egg(null), new Vector());
        listener.onDispense(plain);
        assertFalse(plain.isCancelled());
    }

    private PetSpawnEggListener listener() throws Exception {
        FriendlyCore plugin = allocate(FriendlyCore.class);
        var text = FriendlyCore.class.getDeclaredField("textService");
        text.setAccessible(true);
        text.set(plugin, new TextService() {
            @Override public Component toComponent(String value) { return Component.text(value); }
        });
        var scheduler = FriendlyCore.class.getDeclaredField("scheduler");
        scheduler.setAccessible(true);
        scheduler.set(plugin, new FriendlyScheduler(null) {
            @Override public Object runEntity(Entity entity, Runnable task, Runnable retired) { task.run(); return null; }
        });
        Pets pets = allocate(Pets.class);
        pets.test = this;
        return new PetSpawnEggListener(plugin, pets);
    }

    private static PlayerInteractEvent rightClick(Player player, ItemStack item) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND);
    }

    /** Every unexpected call fails, so an untouched inventory proves the egg was kept. */
    private Player player(EggStack hand) {
        PlayerInventory inventory = proxy(PlayerInventory.class, (object, method, args) -> switch (method.getName()) {
            case "getItemInMainHand", "getItem" -> hand;
            case "setItemInMainHand" -> { hand.amount = 0; yield null; }
            case "addItem" -> { returned.add(((ItemStack[]) args[0])[0]); yield new HashMap<Integer, ItemStack>(); }
            default -> throw new AssertionError(method.getName());
        });
        return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "isOnline" -> true;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getInventory" -> inventory;
            case "getLocation", "playSound" -> null;
            case "sendMessage" -> {
                String text = String.valueOf(((net.kyori.adventure.text.TextComponent) args[0]).content());
                messages.add(text.startsWith("&eYour pets are still loading") ? "pets-loading" : text);
                yield null;
            }
            default -> throw new AssertionError(method.getName());
        });
    }

    private static EggStack egg(String petId) throws Exception {
        EggStack stack = allocate(EggStack.class);
        stack.amount = 1;
        PersistentDataContainer pdc = proxy(PersistentDataContainer.class, (object, method, args) -> switch (method.getName()) {
            case "get" -> petId;
            default -> throw new AssertionError(method.getName());
        });
        stack.meta = proxy(ItemMeta.class, (object, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> pdc;
            default -> throw new AssertionError(method.getName());
        });
        return stack;
    }

    private static class EggStack extends ItemStack {
        int amount;
        ItemMeta meta;
        @Override public Material getType() { return Material.PLAYER_HEAD; }
        @Override public ItemMeta getItemMeta() { return meta; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public EggStack clone() {
            try {
                EggStack copy = allocate(EggStack.class);
                copy.amount = amount;
                copy.meta = meta;
                return copy;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }

    private static class Pets extends PetManager {
        PetEggRedemptionTest test;
        Pets() { super(null); }
        @Override public Pet getPet(String id) { return Pet.fromConfig(id, new YamlConfiguration()); }
        @Override public PlayerPetData getPlayerData(UUID uuid) { return test.data; }
        @Override public FileConfiguration getMessages() { return new YamlConfiguration(); }
        @Override public CompletableFuture<Boolean> unlockPet(UUID uuid, String petId, boolean syncRedis) {
            test.unlocked.add(petId);
            test.data.unlockPet(petId);
            return CompletableFuture.completedFuture(!test.databasePets.contains(petId));
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
}
