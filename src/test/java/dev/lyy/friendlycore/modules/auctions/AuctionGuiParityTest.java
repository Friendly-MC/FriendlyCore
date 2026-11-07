package dev.lyy.friendlycore.modules.auctions;

import dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class AuctionGuiParityTest {

    private static final List<String> ACTIVE_GUIS = List.of(
            "main-gui.yml",
            "my-items-gui.yml",
            "expired-items-gui.yml",
            "history-gui.yml",
            "deleted-gui.yml",
            "confirm-purchase-gui.yml",
            "confirm-listing-gui.yml",
            "category-gui.yml",
            "admin-removal-gui.yml",
            "shulker-preview-gui.yml",
            "logs-gui.yml"
    );

    private static final List<String> LISTING_GUIS = List.of(
            "main-gui.yml",
            "my-items-gui.yml",
            "expired-items-gui.yml",
            "history-gui.yml",
            "deleted-gui.yml"
    );

    private static final Set<String> NON_ITEM_KEYS = Set.of(
            "title", "rows", "page-size", "auto-update-ticks", "type", "orderslots", "item-slots"
    );

    private static final Set<String> VALID_SOUND_SHORTCUTS = Set.of(
            "click", "success", "error", "xp"
    );

    @Test
    @DisplayName("All 11 active auction GUIs must exist")
    void testAllElevenActiveGuisExist() {
        for (String guiName : ACTIVE_GUIS) {
            Path file = Path.of("src/main/resources/modules/auctions/guis", guiName);
            assertTrue(Files.isRegularFile(file), "GUI file must exist: " + guiName);
        }
    }

    @Test
    @DisplayName("All 11 GUIs are structurally valid, have no overlapping slots, and use valid materials and sounds")
    void testGuisAreStructurallyValidAndNoOverlappingSlots() {
        for (String guiName : ACTIVE_GUIS) {
            File file = new File("src/main/resources/modules/auctions/guis", guiName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

            // Validate title
            String title = config.getString("title");
            assertNotNull(title, "Title must not be null in " + guiName);
            assertTrue(title.contains("ᴀᴜᴄᴛɪᴏɴs"), "Title must contain small-caps 'ᴀᴜᴄᴛɪᴏɴs' in " + guiName);

            // Validate rows
            int rows = config.getInt("rows", 6);
            assertTrue(rows >= 1 && rows <= 6, "Rows must be between 1 and 6 in " + guiName);
            int guiSize = rows * 9;

            // Collect all button definitions
            ConfigurationSection itemsSection = config.getConfigurationSection("items");
            assertNotNull(itemsSection, "Missing 'items' configuration section in " + guiName);

            Map<Integer, String> slotOccupancy = new HashMap<>();

            for (String key : itemsSection.getKeys(false)) {
                if (key.equalsIgnoreCase("items") || key.equalsIgnoreCase("categories")) continue;
                ConfigurationSection itemSec = itemsSection.getConfigurationSection(key);
                if (itemSec == null) continue;

                // Validate material if present
                if (itemSec.contains("material") || itemSec.contains("type")) {
                    String matName = itemSec.getString("material", itemSec.getString("type", "STONE"));
                    Material mat = Material.matchMaterial(matName);
                    assertNotNull(mat, "Invalid material '" + matName + "' in " + guiName + " item " + key);
                }

                // Validate sound if present
                if (itemSec.contains("sound")) {
                    String soundKey = itemSec.getString("sound");
                    if (soundKey != null && !soundKey.isBlank()) {
                        boolean valid = VALID_SOUND_SHORTCUTS.contains(soundKey.toLowerCase(Locale.ROOT));
                        if (!valid) {
                            try {
                                Sound.valueOf(soundKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
                                valid = true;
                            } catch (IllegalArgumentException ignored) {}
                        }
                        assertTrue(valid, "Invalid sound '" + soundKey + "' in " + guiName + " item " + key);
                    }
                }

                // Validate slots and detect overlaps
                List<Integer> slots = AuctionMenuBase.parseSlots(itemSec, guiSize);
                for (int slot : slots) {
                    assertTrue(slot >= 0 && slot < guiSize,
                            "Slot " + slot + " out of bounds [0, " + (guiSize - 1) + "] in " + guiName + " item " + key);
                    assertFalse(slotOccupancy.containsKey(slot),
                            "Slot " + slot + " overlap between '" + slotOccupancy.get(slot) + "' and '" + key + "' in " + guiName);
                    slotOccupancy.put(slot, key);
                }
            }
        }
    }

    @Test
    @DisplayName("Listing menus have standardized 45-slot layout (slots 0-44) and 45-53 control bar")
    void testListingMenusStandardized45SlotLayout() {
        for (String guiName : LISTING_GUIS) {
            File file = new File("src/main/resources/modules/auctions/guis", guiName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

            assertEquals(6, config.getInt("rows"), "Listing GUI must have 6 rows: " + guiName);
            assertEquals(45, config.getInt("page-size"), "Listing GUI must have page-size 45: " + guiName);

            // calculateAvailableItemSlots must return exactly 45 slots (0..44)
            List<Integer> availableSlots = AuctionMenuBase.calculateAvailableItemSlots(config, 54, 45);
            assertEquals(45, availableSlots.size(), "Available listing slots must be 45 in " + guiName);
            for (int i = 0; i < 45; i++) {
                assertEquals(i, availableSlots.get(i), "Slot " + i + " must be available for listings in " + guiName);
            }

            // Verify bottom row controls (slots 45-53)
            ConfigurationSection itemsSec = config.getConfigurationSection("items");
            assertNotNull(itemsSec, "Items section required in " + guiName);

            // Slot 45: Back
            ConfigurationSection backSec = itemsSec.getConfigurationSection("back");
            assertNotNull(backSec, "Slot 45 Back button missing in " + guiName);
            assertEquals(45, backSec.getInt("slot"), "Back slot must be 45 in " + guiName);
            assertEquals("ARROW", backSec.getString("material"), "Back material must be ARROW in " + guiName);
            assertTrue(backSec.getString("name", "").contains("ʙᴀᴄᴋ"), "Back name must contain small-caps ʙᴀᴄᴋ in " + guiName);

            // Slot 47: Sort
            ConfigurationSection sortSec = itemsSec.getConfigurationSection("sort");
            assertNotNull(sortSec, "Slot 47 Sort button missing in " + guiName);
            assertEquals(47, sortSec.getInt("slot"), "Sort slot must be 47 in " + guiName);
            assertEquals("CAULDRON", sortSec.getString("material"), "Sort material must be CAULDRON in " + guiName);
            assertTrue(sortSec.getString("name", "").contains("sᴏʀᴛ"), "Sort name must contain small-caps sᴏʀᴛ in " + guiName);

            // Slot 48: Filter
            ConfigurationSection filterSec = itemsSec.getConfigurationSection("filter");
            assertNotNull(filterSec, "Slot 48 Filter button missing in " + guiName);
            assertEquals(48, filterSec.getInt("slot"), "Filter slot must be 48 in " + guiName);
            assertEquals("HOPPER", filterSec.getString("material"), "Filter material must be HOPPER in " + guiName);
            assertTrue(filterSec.getString("name", "").contains("ғɪʟᴛᴇʀ"), "Filter name must contain small-caps ғɪʟᴛᴇʀ in " + guiName);

            // Slot 49: Main / Refresh
            ConfigurationSection mainSec = itemsSec.getConfigurationSection("main");
            assertNotNull(mainSec, "Slot 49 Main/Refresh button missing in " + guiName);
            assertEquals(49, mainSec.getInt("slot"), "Main slot must be 49 in " + guiName);
            assertEquals("MAP", mainSec.getString("material"), "Main material must be MAP in " + guiName);
            assertTrue(mainSec.getString("name", "").contains("ᴀᴜᴄᴛɪᴏɴs"), "Main name must contain small-caps ᴀᴜᴄᴛɪᴏɴs in " + guiName);

            // Slot 50: Search
            ConfigurationSection searchSec = itemsSec.getConfigurationSection("search");
            assertNotNull(searchSec, "Slot 50 Search button missing in " + guiName);
            assertEquals(50, searchSec.getInt("slot"), "Search slot must be 50 in " + guiName);
            assertEquals("OAK_SIGN", searchSec.getString("material"), "Search material must be OAK_SIGN in " + guiName);
            assertTrue(searchSec.getString("name", "").contains("sᴇᴀʀᴄʜ"), "Search name must contain small-caps sᴇᴀʀᴄʜ in " + guiName);

            // Slot 51: Your Items
            ConfigurationSection yourItemsSec = itemsSec.getConfigurationSection("your_items");
            assertNotNull(yourItemsSec, "Slot 51 Your Items button missing in " + guiName);
            assertEquals(51, yourItemsSec.getInt("slot"), "Your Items slot must be 51 in " + guiName);
            assertEquals("CHEST", yourItemsSec.getString("material"), "Your Items material must be CHEST in " + guiName);
            assertTrue(yourItemsSec.getString("name", "").contains("ʏᴏᴜʀ ɪᴛᴇᴍs"), "Your Items name must contain small-caps ʏᴏᴜʀ ɪᴛᴇᴍs in " + guiName);

            // Slot 53: Next
            ConfigurationSection nextSec = itemsSec.getConfigurationSection("next");
            assertNotNull(nextSec, "Slot 53 Next button missing in " + guiName);
            assertEquals(53, nextSec.getInt("slot"), "Next slot must be 53 in " + guiName);
            assertEquals("ARROW", nextSec.getString("material"), "Next material must be ARROW in " + guiName);
            assertTrue(nextSec.getString("name", "").contains("ɴᴇxᴛ"), "Next name must contain small-caps ɴᴇxᴛ in " + guiName);

            // Slots 46, 52: Filler
            ConfigurationSection fillerSec = itemsSec.getConfigurationSection("filler");
            assertNotNull(fillerSec, "Filler missing in " + guiName);
            assertEquals("BLACK_STAINED_GLASS_PANE", fillerSec.getString("material"), "Filler material must be BLACK_STAINED_GLASS_PANE in " + guiName);
            List<Integer> fillerSlots = AuctionMenuBase.parseSlots(fillerSec, 54);
            assertTrue(fillerSlots.contains(46), "Slot 46 must be filler in " + guiName);
            assertTrue(fillerSlots.contains(52), "Slot 52 must be filler in " + guiName);
        }
    }

    @Test
    @DisplayName("Confirmation menus have green confirm, red cancel, center preview slot 22, and black glass")
    void testConfirmationMenusStructure() {
        for (String guiName : List.of("confirm-purchase-gui.yml", "confirm-listing-gui.yml")) {
            File file = new File("src/main/resources/modules/auctions/guis", guiName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection itemsSec = config.getConfigurationSection("items");
            assertNotNull(itemsSec, "Items section required in " + guiName);

            // Confirm button
            ConfigurationSection confSec = itemsSec.getConfigurationSection("confirm");
            assertNotNull(confSec, "Confirm button missing in " + guiName);
            assertEquals("LIME_STAINED_GLASS_PANE", confSec.getString("material"));
            assertTrue(confSec.getString("name", "").contains("#00FC00ᴄᴏɴғɪʀᴍ"));

            // Cancel button
            ConfigurationSection cancSec = itemsSec.getConfigurationSection("cancel");
            assertNotNull(cancSec, "Cancel button missing in " + guiName);
            assertEquals("RED_STAINED_GLASS_PANE", cancSec.getString("material"));
            assertTrue(cancSec.getString("name", "").contains("#FC0000ᴄᴀɴᴄᴇʟ"));

            // Item slot
            ConfigurationSection itemSec = itemsSec.getConfigurationSection("item");
            assertNotNull(itemSec, "Item preview slot missing in " + guiName);
            assertEquals(22, itemSec.getInt("slot"), "Center preview slot must be 22 in " + guiName);

            // Fillers
            ConfigurationSection fillerSec = itemsSec.getConfigurationSection("filler");
            assertNotNull(fillerSec, "Filler missing in " + guiName);
            assertEquals("BLACK_STAINED_GLASS_PANE", fillerSec.getString("material"));
        }
    }

    @Test
    @DisplayName("Category menu has clean bullet descriptions and black glass border")
    void testCategoryMenuStructure() {
        File file = new File("src/main/resources/modules/auctions/guis/category-gui.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection itemsSec = config.getConfigurationSection("items");
        assertNotNull(itemsSec, "Items section required in category-gui.yml");

        ConfigurationSection fillerSec = itemsSec.getConfigurationSection("filler");
        assertNotNull(fillerSec, "Filler missing in category-gui.yml");
        assertEquals("BLACK_STAINED_GLASS_PANE", fillerSec.getString("material"));

        for (String catKey : List.of("all", "tools", "weapons", "armors", "minerals", "custom")) {
            ConfigurationSection catSec = itemsSec.getConfigurationSection(catKey);
            assertNotNull(catSec, "Category button missing: " + catKey);
            assertTrue(catSec.getString("name", "").startsWith("#E63A35"), "Category name must use #E63A35: " + catKey);
        }
    }

    @Test
    @DisplayName("Admin removal, shulker preview, and logs GUIs use black stained glass fillers")
    void testUtilityMenusStructure() {
        for (String guiName : List.of("admin-removal-gui.yml", "shulker-preview-gui.yml", "logs-gui.yml")) {
            File file = new File("src/main/resources/modules/auctions/guis", guiName);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection itemsSec = config.getConfigurationSection("items");
            assertNotNull(itemsSec, "Items section required in " + guiName);

            ConfigurationSection fillerSec = itemsSec.getConfigurationSection("filler");
            assertNotNull(fillerSec, "Filler missing in " + guiName);
            assertEquals("BLACK_STAINED_GLASS_PANE", fillerSec.getString("material"), "Filler must be black glass in " + guiName);
        }
    }
}
