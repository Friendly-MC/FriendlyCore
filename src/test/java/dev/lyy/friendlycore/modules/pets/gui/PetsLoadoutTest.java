package dev.lyy.friendlycore.modules.pets.gui;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.FriendlyScheduler;
import dev.lyy.friendlycore.common.database.DatabaseManager;
import dev.lyy.friendlycore.common.database.MySQLDatabase;
import dev.lyy.friendlycore.common.database.SQLiteDatabase;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.effects.PetTrigger;
import dev.lyy.friendlycore.modules.pets.display.PetDisplayManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PetsLoadoutTest {
    @Test
    void twoDistinctOwnedPetsCanBeEquippedAndRemovedIndependently() {
        PlayerPetData data = new PlayerPetData(UUID.randomUUID(), "Player");
        for (String id : List.of("vip", "mvp", "king")) data.unlockPet(id);
        assertTrue(data.equipPet("VIP"));
        assertTrue(data.equipPet("mvp"));
        assertFalse(data.equipPet("king"));
        assertFalse(data.equipPet("vip"));
        assertFalse(data.equipPet("unknown"));
        List<String> snapshot = data.getActivePets();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("king"));
        data.setDirty(false);
        assertTrue(data.lockPet("vip"));
        assertEquals(List.of("mvp"), data.getActivePets());
        assertEquals(List.of("vip", "mvp"), snapshot);
        assertTrue(data.isDirty());
        assertTrue(data.equipPet("king"));
        assertTrue(data.unequipPet("MVP"));
        assertEquals("king", data.getActivePet());
        data.setActivePet(null);
        assertTrue(data.getActivePets().isEmpty());
    }

    @Test
    void corruptSavedLoadoutsAndLegacySinglePetDataAreNormalized() {
        PlayerPetData legacy = new PlayerPetData(UUID.randomUUID(), "Player", Set.of("vip"), null,
                "VIP", false, 1);
        assertEquals(List.of("vip"), legacy.getActivePets());
        assertFalse(legacy.isDirty());
        legacy.setActivePets(java.util.Arrays.asList(null, "unknown", "VIP", "vip", " "));
        assertEquals(List.of("vip"), legacy.getActivePets());
    }

    @Test
    void anOlderSaveCannotClearNewerUnsavedChanges() {
        PlayerPetData data = new PlayerPetData(UUID.randomUUID(), "Player");
        data.unlockPet("vip");
        data.equipPet("vip");
        PlayerPetData snapshot = data.snapshot();
        data.setHidePet(true);
        data.markSaved(snapshot.getLastUpdated());
        assertTrue(data.isDirty());
        assertFalse(snapshot.isHidePet());
        assertEquals(List.of("vip"), snapshot.getActivePets());
        data.markSaved(data.getLastUpdated());
        assertFalse(data.isDirty());
    }

    @Test
    void collectionPageSizeNeverCoversTheFooter() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("page-size", 100);
        assertEquals(28, PetsGui.pageSize(config));
        config.set("page-size", -100);
        assertEquals(1, PetsGui.pageSize(config));
        assertEquals(28, PetsGui.pageSize(null));
        Set<Integer> slots = new java.util.HashSet<>();
        for (int index = 0; index < 28; index++) {
            int slot = PetsGui.collectionSlot(index);
            assertTrue(slot >= 10 && slot <= 43);
            assertTrue(slot % 9 > 0 && slot % 9 < 8);
            assertTrue(slots.add(slot));
        }
        assertThrows(IllegalArgumentException.class, () -> PetsGui.collectionSlot(-1));
        assertThrows(IllegalArgumentException.class, () -> PetsGui.collectionSlot(28));
    }

    @Test
    void labelsPreserveConfiguredNamesColorsAndCustomFormatsWithoutRepeatingTheOwner() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("name", "&6&lKING PET &eCrown");
        Pet pet = Pet.fromConfig("king", config);
        assertEquals("&6&lKING PET &eCrown", pet.getDisplayName());
        assertEquals("&6&lKING PET &eCrown", pet.getName());
        assertEquals("&f&6&lKING PET &eCrown", PetDisplayManager.formatHologramName("%player%&f's %pet%", "LongPlayerName", pet));
        assertEquals("LongPlayerName: &6&lKING PET &eCrown", PetDisplayManager.formatHologramName("%player%: %pet_short%", "LongPlayerName", pet));
        config.set("display-name", "Phoenix");
        assertEquals("Phoenix", Pet.fromConfig("king", config).getDisplayName());
    }

    @Test
    void listEffectsUseTheirDefaultTriggersWithoutLimitingTheList() {
        YamlConfiguration config = new YamlConfiguration();
        java.util.ArrayList<java.util.Map<String, Object>> effects = new java.util.ArrayList<>();
        effects.add(java.util.Map.of("id", "bow_attack"));
        effects.add(java.util.Map.of("id", "damage_reduction"));
        for (int index = 0; index < 100; index++) effects.add(java.util.Map.of("id", "potion_effect", "effect", "SPEED"));
        config.set("effects", effects);
        Pet pet = Pet.fromConfig("test", config);
        assertEquals(102, pet.getEffects().size());
        assertTrue(pet.getEffects().get(0).hasTrigger(PetTrigger.BOW_ATTACK));
        assertTrue(pet.getEffects().get(1).hasTrigger(PetTrigger.TAKE_DAMAGE));
        assertTrue(pet.getEffects().get(2).hasTrigger(PetTrigger.PASSIVE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothDatabaseDialectsMigrateAndRoundTripBothSlots(boolean mysql) throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        FriendlyCore plugin = (FriendlyCore) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(FriendlyCore.class);
        FriendlyScheduler scheduler = new FriendlyScheduler(plugin);
        Field schedulerField = FriendlyCore.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(plugin, scheduler);
        java.util.logging.Logger logger = java.util.logging.Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        Field loggerField = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(plugin, logger);
        DatabaseManager database = mysql ? new MySQLDatabase(plugin) : new SQLiteDatabase(plugin);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql ? "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1" : "jdbc:sqlite::memory:");
        config.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            Field poolField = database.getClass().getDeclaredField("dataSource");
            poolField.setAccessible(true);
            poolField.set(database, pool);
            try (Connection connection = pool.getConnection(); Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE friendlycore_player_pet_settings (uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(36) NOT NULL, active_pet VARCHAR(64), hide_pet BOOLEAN NOT NULL DEFAULT 0, last_updated BIGINT NOT NULL)");
                stmt.executeUpdate("CREATE TABLE friendlycore_pets (uuid VARCHAR(36), pet_id VARCHAR(64), unlocked_at BIGINT, PRIMARY KEY(uuid, pet_id))");
                var migrate = database.getClass().getDeclaredMethod("addColumnIfMissing", Statement.class, String.class, String.class, String.class);
                migrate.setAccessible(true);
                // H2 reports duplicate columns differently from MySQL's error 1060.
                for (int attempt = 0; attempt < (mysql ? 1 : 2); attempt++) {
                    migrate.invoke(database, stmt, "friendlycore_player_pet_settings", "secondary_pet", "VARCHAR(64) NULL");
                }
            }
            PlayerPetData data = new PlayerPetData(UUID.randomUUID(), "Player");
            data.unlockPet("vip");
            data.unlockPet("king");
            data.equipPet("vip");
            data.equipPet("king");
            database.savePlayerPetData(data).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("vip", "king"), database.loadPlayerPetData(data.getUuid(), "Player").get(5, TimeUnit.SECONDS).getActivePets());
            database.setActivePets(data.getUuid(), List.of("king", "vip")).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("king", "vip"), database.loadPlayerPetData(data.getUuid(), "Player").get(5, TimeUnit.SECONDS).getActivePets());
            data.unequipPet("vip");
            database.savePlayerPetDataBatch(List.of(data)).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("king"), database.loadPlayerPetData(data.getUuid(), "Player").get(5, TimeUnit.SECONDS).getActivePets());
            database.setActivePet(data.getUuid(), null).get(5, TimeUnit.SECONDS);
            assertTrue(database.loadPlayerPetData(data.getUuid(), "Player").get(5, TimeUnit.SECONDS).getActivePets().isEmpty());

            // Eggs and withdrawals trust these results instead of the cache.
            UUID owner = UUID.randomUUID();
            assertTrue(database.unlockPet(owner, "vip").get(5, TimeUnit.SECONDS));
            assertFalse(database.unlockPet(owner, "VIP").get(5, TimeUnit.SECONDS));
            assertTrue(database.lockPet(owner, "vip").get(5, TimeUnit.SECONDS));
            assertFalse(database.lockPet(owner, "vip").get(5, TimeUnit.SECONDS));
            try (Connection connection = pool.getConnection(); Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DROP TABLE friendlycore_pets");
            }
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> database.lockPet(owner, "vip").get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.sql.SQLException.class, failed.getCause());
        } finally {
            scheduler.shutdown();
        }
    }
}
