package dev.lyy.friendlycore.modules.kits;

import dev.lyy.friendlycore.modules.kits.model.KitItem;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KitCreationTest {

    @Test
    void inventoryBuilderPreservesArmorAndOffhandSlots() {
        KitItem storageItem = new KitItem("DIAMOND");
        KitItem armorItem = new KitItem("DIAMOND_BOOTS");
        KitItem offhandItem = new KitItem("SHIELD");

        assertFalse(KitManager.applyInventorySlotMetadata(storageItem, 0));
        assertTrue(KitManager.applyInventorySlotMetadata(armorItem, 36));
        assertFalse(KitManager.applyInventorySlotMetadata(offhandItem, 40));
        assertFalse(storageItem.isOffhand());
        assertTrue(offhandItem.isOffhand());
    }

    @Test
    void inventoryBuilderRejectsEmptyContents() {
        assertNull(KitManager.buildKitDefinition("empty", new ItemStack[41], true));
    }
}
