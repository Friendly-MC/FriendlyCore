package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.teams.model.TeamEnderChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class TeamVaultCodecSafetyTest {
    private TeamEnderChest chest(ItemStack[] slots, java.util.concurrent.atomic.AtomicInteger writes) throws Exception {
        TeamEnderChest chest = new TeamEnderChest(1, null);
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> slots.clone();
                    case "setItem" -> { writes.incrementAndGet(); slots[(int) args[0]] = (ItemStack) args[1]; yield null; }
                    case "clear" -> { writes.incrementAndGet(); java.util.Arrays.fill(slots, null); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Field field = TeamEnderChest.class.getDeclaredField("inventory");
        field.setAccessible(true); field.set(chest, inventory);
        return chest;
    }

    @Test
    void corruptDataRefusesOpeningAndPreservesCachedContents() throws Exception {
        ItemStack[] slots = new ItemStack[9];
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        TeamEnderChest chest = chest(slots, writes);
        assertFalse(chest.deserialize("not-base64!"));
        assertEquals(0, writes.get(), "Rejected data must not mutate any cached slots");
    }

    @Test
    void unreadableItemRefusesWholeSnapshotInsteadOfServingCachedItem() throws Exception {
        ItemStack[] slots = new ItemStack[9];
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        TeamEnderChest chest = chest(slots, writes);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeInt(0); out.writeInt(1); out.writeByte(0);
        }
        assertFalse(chest.deserialize(Base64.getEncoder().encodeToString(bytes.toByteArray())));
        assertEquals(0, writes.get(), "Rejected data must not mutate any cached slots");
    }

    @Test
    void authoritativeEmptySnapshotClearsOldItems() throws Exception {
        ItemStack[] slots = new ItemStack[9];
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(chest(slots, writes).deserialize(""));
        assertEquals(1, writes.get(), "Authoritative empty data must clear the inventory");
    }

    @Test
    void unavailableInventoryCannotSerializeAsEmptyChest() {
        assertThrows(IllegalStateException.class, () -> new TeamEnderChest(1, null).serialize());
    }
}
