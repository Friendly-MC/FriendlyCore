package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class EcoPetsMigrationTest {

    @Test
    @DisplayName("Verify reading and parsing real eco data.yml for EcoPets")
    void testEcoDataYmlReading() {
        File dataFile = new File("plugin-config/eco/data.yml");
        if (!dataFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection playerSec = yaml.getConfigurationSection("player");
        assertNotNull(playerSec);

        int totalUsers = playerSec.getKeys(false).size();
        assertEquals(9146, totalUsers, "eco data.yml should contain 9,146 players");

        int playersWithPets = 0;
        Map<UUID, PlayerPetData> petDataMap = new HashMap<>();

        for (String uuidStr : playerSec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr.trim());
                ConfigurationSection userSec = playerSec.getConfigurationSection(uuidStr);
                if (userSec == null) continue;

                String username = userSec.getString("eco:player_name", userSec.getString("eco:player_display_name", "Player"));
                String activePet = userSec.getString("ecopets:active_pet", null);
                boolean hidePet = userSec.getBoolean("ecopets:hide_pet", false);

                Set<String> unlocked = new HashSet<>();
                for (String key : userSec.getKeys(false)) {
                    if (key.startsWith("ecopets:") && (key.endsWith("_level") || key.endsWith("_xp"))) {
                        String petId = key.substring(8);
                        if (petId.endsWith("_level")) petId = petId.substring(0, petId.length() - 6);
                        if (petId.endsWith("_xp")) petId = petId.substring(0, petId.length() - 3);
                        if (!petId.isBlank()) {
                            unlocked.add(petId.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (activePet != null && !activePet.isBlank()) {
                    unlocked.add(activePet.toLowerCase(Locale.ROOT));
                }

                if (!unlocked.isEmpty() || (activePet != null && !activePet.isBlank()) || hidePet) {
                    PlayerPetData petData = new PlayerPetData(uuid, username);
                    for (String pid : unlocked) {
                        petData.unlockPet(pid);
                    }
                    if (activePet != null && !activePet.isBlank()) {
                        petData.setActivePet(activePet);
                    }
                    petData.setHidePet(hidePet);
                    petDataMap.put(uuid, petData);
                    playersWithPets++;
                }
            } catch (Exception ignored) {}
        }

        assertTrue(playersWithPets >= 28, "Should find at least 28 players with pet unlocks/settings");

        // Verify a specific known player (ce4b89b2-d917-47fd-b5f8-1fffeca0158b -> king pet)
        UUID kingUser = UUID.fromString("ce4b89b2-d917-47fd-b5f8-1fffeca0158b");
        PlayerPetData kingData = petDataMap.get(kingUser);
        assertNotNull(kingData);
        assertTrue(kingData.hasPet("king"));
        assertEquals("king", kingData.getActivePet());
        assertTrue(kingData.isHidePet());
    }

    @Test
    @DisplayName("Verify EcoPets pet yml configuration files exist")
    void testEcoPetsConfigsExist() {
        File petsDir = new File("plugin-config/EcoPets/pets");
        if (!petsDir.exists()) return;

        File[] files = petsDir.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        assertNotNull(files);
        assertTrue(files.length >= 4, "Should find king, vip, mvp, mvpp pet configs");

        Set<String> names = new HashSet<>();
        for (File f : files) {
            names.add(f.getName());
        }
        assertTrue(names.contains("king.yml"));
        assertTrue(names.contains("vip.yml"));
        assertTrue(names.contains("mvp.yml"));
        assertTrue(names.contains("mvpp.yml"));
    }
}
