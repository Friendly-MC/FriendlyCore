package dev.lyy.friendlycore;

import dev.lyy.friendlycore.common.ConfigMigrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigMigratorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Test migration moves legacy flat files to per-module structure")
    void testLegacyMigration() throws IOException {
        // Create simulated legacy files
        File modulesDir = new File(tempDir.toFile(), "modules");
        modulesDir.mkdirs();
        File legacyEco = new File(modulesDir, "economy.yml");
        Files.writeString(legacyEco.toPath(), "test-key: test-value");

        File messagesDir = new File(tempDir.toFile(), "messages");
        messagesDir.mkdirs();
        File legacyGeneralMsg = new File(messagesDir, "general.yml");
        Files.writeString(legacyGeneralMsg.toPath(), "no-perm: error");

        File legacySounds = new File(tempDir.toFile(), "sounds-new.yml");
        Files.writeString(legacySounds.toPath(), "sound: dummy");

        File legacyTeamsGui = new File(tempDir.toFile(), "inventories/teams");
        legacyTeamsGui.mkdirs();
        File teamGuiFile = new File(legacyTeamsGui, "team-gui.yml");
        Files.writeString(teamGuiFile.toPath(), "inventory: test");

        ConfigMigrator migrator = new ConfigMigrator(tempDir.toFile(), Logger.getLogger("FriendlyCoreTest"));
        migrator.migrate();

        // Verify economy config relocated
        File newEco = new File(tempDir.toFile(), "modules/economy/config.yml");
        assertTrue(newEco.exists(), "modules/economy/config.yml should exist");
        assertEquals("test-key: test-value", Files.readString(newEco.toPath()));
        assertFalse(legacyEco.exists(), "Legacy economy.yml should no longer exist");

        // Verify root messages.yml created from messages/general.yml
        File newMsg = new File(tempDir.toFile(), "messages.yml");
        assertTrue(newMsg.exists(), "messages.yml should exist at root");
        assertEquals("no-perm: error", Files.readString(newMsg.toPath()));

        // Verify team-gui moved to modules/teams/inventories/team-gui.yml
        File newTeamGui = new File(tempDir.toFile(), "modules/teams/inventories/team-gui.yml");
        assertTrue(newTeamGui.exists(), "modules/teams/inventories/team-gui.yml should exist");

        // Verify legacy sounds file deleted
        assertFalse(legacySounds.exists(), "sounds-new.yml should be purged");
    }
}
