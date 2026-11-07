package dev.lyy.friendlycore.modules.kits.gui;

import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitManager;
import dev.lyy.friendlycore.modules.kits.migration.PlayerKitsMigrator;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoArmorPreviewMigrationTest {

    @Test
    void explicitSlotsAreReservedBeforeImplicitItems() {
        KitGuiManager.PreviewSlotPlan plan = KitGuiManager.planPreviewSlots(List.of(1, -1, -1), 54);

        assertEquals(List.of(1, 0, 2), plan.assignedSlots());
        assertTrue(plan.warnings().isEmpty());
    }

    @Test
    void duplicateAndControlRowSlotsFallBackWithoutCollisions() {
        KitGuiManager.PreviewSlotPlan plan = KitGuiManager.planPreviewSlots(
                List.of(2, 2, 45, 44, -1), 54);

        assertEquals(5, new HashSet<>(plan.assignedSlots()).size());
        assertTrue(plan.assignedSlots().stream().allMatch(slot -> slot >= 0 && slot < 45));
        assertEquals(2, plan.assignedSlots().get(0));
        assertEquals(44, plan.assignedSlots().get(3));
        assertTrue(plan.warnings().stream().anyMatch(message -> message.contains("duplicates preview_slot 2")));
        assertTrue(plan.warnings().stream().anyMatch(message -> message.contains("preview_slot 45 outside")));
    }

    @Test
    void fullContentAreaReportsUnplacedItems() {
        List<Integer> requested = java.util.Collections.nCopies(46, -1);
        KitGuiManager.PreviewSlotPlan plan = KitGuiManager.planPreviewSlots(requested, 54);

        assertEquals(-1, plan.assignedSlots().get(45));
        assertTrue(plan.warnings().stream().anyMatch(message -> message.contains("all 45 content slots")));
    }

    @Test
    void playerKitsV1QuotedValuesAndEquipmentMetadataMigrate() throws Exception {
        YamlConfiguration yaml = loadFixture();
        Kit kit = parseV1Kit(yaml.getConfigurationSection("Kits.legacy"), "legacy");

        assertNotNull(kit);
        assertEquals(3600, kit.getCooldownSeconds());
        assertTrue(kit.isOneTime());
        assertTrue(kit.isAutoArmor());
        assertEquals(4, kit.getKitItems().size());

        KitItem boots = kit.getKitItems().get(0);
        assertEquals(2, boots.getAmount());
        assertEquals(5, boots.getPreviewSlot());
        assertEquals(KitItem.EquipmentSlot.FEET, boots.getEquipmentSlot());

        KitItem shield = kit.getKitItems().get(1);
        assertEquals(16, shield.getAmount());
        assertTrue(shield.isOffhand());
        assertEquals(KitItem.EquipmentSlot.OFF_HAND, shield.getEquipmentSlot());

        assertEquals(KitItem.EquipmentSlot.HEAD, kit.getKitItems().get(2).getEquipmentSlot());
        assertEquals(KitItem.EquipmentSlot.CHEST, kit.getKitItems().get(3).getEquipmentSlot());
    }

    @Test
    void explicitEquipmentSlotDoesNotRequireAutoArmor() throws Exception {
        YamlConfiguration yaml = loadFixture();
        Kit kit = parseV1Kit(
                yaml.getConfigurationSection("Kits.explicit_without_auto_armor"),
                "explicit_without_auto_armor");

        assertNotNull(kit);
        assertFalse(kit.isAutoArmor());
        assertEquals(3, kit.getKitItems().getFirst().getAmount());
        assertEquals(KitItem.EquipmentSlot.CHEST, kit.getKitItems().getFirst().getEquipmentSlot());
    }

    @Test
    void inferLegacyEquipmentSlotResolvesExpectedSlots() {
        assertEquals(KitItem.EquipmentSlot.HEAD, KitManager.inferLegacyEquipmentSlot(Material.CARVED_PUMPKIN));
        assertEquals(KitItem.EquipmentSlot.HEAD, KitManager.inferLegacyEquipmentSlot(Material.DIAMOND_HELMET));
        assertEquals(KitItem.EquipmentSlot.HEAD, KitManager.inferLegacyEquipmentSlot(Material.PLAYER_HEAD));
        assertEquals(KitItem.EquipmentSlot.HEAD, KitManager.inferLegacyEquipmentSlot(Material.ZOMBIE_HEAD));
        assertEquals(KitItem.EquipmentSlot.HEAD, KitManager.inferLegacyEquipmentSlot(Material.SKELETON_SKULL));
        assertEquals(KitItem.EquipmentSlot.CHEST, KitManager.inferLegacyEquipmentSlot(Material.DIAMOND_CHESTPLATE));
        assertEquals(KitItem.EquipmentSlot.CHEST, KitManager.inferLegacyEquipmentSlot(Material.ELYTRA));
        assertEquals(KitItem.EquipmentSlot.LEGS, KitManager.inferLegacyEquipmentSlot(Material.IRON_LEGGINGS));
        assertEquals(KitItem.EquipmentSlot.FEET, KitManager.inferLegacyEquipmentSlot(Material.NETHERITE_BOOTS));
        assertNull(KitManager.inferLegacyEquipmentSlot(Material.DIAMOND_SWORD));
        assertNull(KitManager.inferLegacyEquipmentSlot(Material.SHIELD));
        assertNull(KitManager.inferLegacyEquipmentSlot(null));
    }

    @Test
    void shippedSchemaDocumentsEquipmentAndPreviewFallbacks() throws Exception {
        String config = readResource("/modules/kits/config.yml");
        String inventory = readResource("/modules/kits/inventories/kits.yml");

        assertTrue(config.contains("equipment_slot: \"HEAD\""));
        assertTrue(config.contains("offhand: false"));
        assertTrue(config.contains("preview_slot: 4"));
        assertTrue(config.contains("occupied equipment slot is not overwritten"));
        assertTrue(config.contains("whole claim is restored and rolled back"));
        assertTrue(inventory.contains("bottom row is reserved"));
        assertTrue(inventory.contains("duplicate or invalid"));
    }

    private static Kit parseV1Kit(ConfigurationSection section, String key) throws Exception {
        Method method = PlayerKitsMigrator.class.getDeclaredMethod(
                "parseV1Kit", ConfigurationSection.class, String.class);
        method.setAccessible(true);
        return (Kit) method.invoke(null, section, key);
    }

    private static YamlConfiguration loadFixture() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(readResource("/fixtures/playerkits-v1-auto-armor.yml"));
        return yaml;
    }

    private static String readResource(String path) throws Exception {
        try (InputStream stream = AutoArmorPreviewMigrationTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing test resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
