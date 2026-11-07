package dev.lyy.friendlycore.modules.kits.gui;

import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitManager;
import dev.lyy.friendlycore.modules.kits.gui.editor.KitChatPrompt;
import dev.lyy.friendlycore.modules.kits.gui.editor.KitEditorManager;
import dev.lyy.friendlycore.modules.kits.model.KitAction;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import dev.lyy.friendlycore.modules.kits.model.KitRequirements;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KitEditorSystemTest {

    @Test
    void parseDurationSecondsHandlesStandardAndComplexUnits() {
        assertEquals(0L, KitManager.parseDurationSeconds(null));
        assertEquals(0L, KitManager.parseDurationSeconds(""));
        assertEquals(0L, KitManager.parseDurationSeconds("none"));
        assertEquals(0L, KitManager.parseDurationSeconds("0"));
        assertEquals(0L, KitManager.parseDurationSeconds("free"));

        assertEquals(3600L, KitManager.parseDurationSeconds("3600"));
        assertEquals(60L, KitManager.parseDurationSeconds("1m"));
        assertEquals(3600L, KitManager.parseDurationSeconds("1h"));
        assertEquals(86400L, KitManager.parseDurationSeconds("1d"));

        assertEquals(86400L + 7200L + 1800L + 15L, KitManager.parseDurationSeconds("1d 2h 30m 15s"));
        assertEquals(5400L, KitManager.parseDurationSeconds("1.5h"));
        assertEquals(150L, KitManager.parseDurationSeconds("2m 30s"));
    }

    @Test
    void formatDurationDetailedProducesHumanReadableText() {
        assertEquals("None (Instant)", KitManager.formatDurationDetailed(0));
        assertEquals("None (Instant)", KitManager.formatDurationDetailed(-10));
        assertEquals("30s", KitManager.formatDurationDetailed(30));
        assertEquals("1m 30s", KitManager.formatDurationDetailed(90));
        assertEquals("1h", KitManager.formatDurationDetailed(3600));
        assertEquals("1d 2h 30m 15s", KitManager.formatDurationDetailed(86400 + 7200 + 1800 + 15));
    }

    @Test
    void chatPromptLifecycleAndCallbacks() {
        AtomicReference<String> inputResult = new AtomicReference<>();
        AtomicBoolean cancelCalled = new AtomicBoolean(false);

        KitChatPrompt prompt = new KitChatPrompt(
                null,
                "Enter Test Value",
                "oldValue",
                inputResult::set,
                () -> cancelCalled.set(true),
                1000L
        );

        assertEquals("Enter Test Value", prompt.getPromptTitle());
        assertEquals("oldValue", prompt.getCurrentValue());
        assertFalse(prompt.isExpired());

        prompt.getOnInput().accept("new_cool_kit");
        assertEquals("new_cool_kit", inputResult.get());

        prompt.getOnCancel().run();
        assertTrue(cancelCalled.get());
    }

    @Test
    void editorManagerPromptTrackingAndCancellation() {
        KitEditorManager editorManager = new KitEditorManager(null);
        UUID fakeUuid = UUID.randomUUID();

        assertFalse(editorManager.hasActivePrompt(fakeUuid));
        assertFalse(editorManager.hasActivePrompt(null));

        editorManager.removePlayer(fakeUuid);
    }

    @Test
    void fullKitDefinitionAndDisplayStatesRoundtrip() {
        Kit kit = new Kit("gladiator");
        kit.setCooldownSeconds(7200);
        kit.setPermissionRequired(true);
        kit.setCustomPermission("friendlycore.kits.gladiator");
        kit.setOneTime(false);
        kit.setAutoArmor(true);
        kit.setClearInventory(false);
        kit.setSaveOriginalItems(true);
        kit.setAllowPlaceholdersOnOriginalItems(true);

        // Add items
        KitItem helm = new KitItem("DIAMOND_HELMET");
        helm.setEquipmentSlot(KitItem.EquipmentSlot.HEAD);
        KitItem sword = new KitItem("DIAMOND_SWORD");
        sword.setName("<red>Gladiator Blade</red>");
        kit.setKitItems(List.of(helm, sword));

        // Add 5 display states
        KitItem defaultDisp = new KitItem("NETHERITE_SWORD");
        defaultDisp.setName("<gold>Gladiator Kit</gold>");
        kit.setDisplayItemDefault(defaultDisp);

        KitItem cdDisp = new KitItem("CLOCK");
        cdDisp.setName("<gray>Gladiator Cooldown: %time%</gray>");
        kit.setDisplayItemCooldown(cdDisp);

        KitItem nopermDisp = new KitItem("BARRIER");
        nopermDisp.setName("<red>Gladiator Locked</red>");
        kit.setDisplayItemNoPermission(nopermDisp);

        KitItem oneTimeDisp = new KitItem("TOTEM_OF_UNDYING");
        oneTimeDisp.setName("<yellow>Gladiator Claimed</yellow>");
        kit.setDisplayItemOneTime(oneTimeDisp);

        KitItem boughtDisp = new KitItem("EMERALD");
        boughtDisp.setName("<green>Gladiator Unlocked</green>");
        kit.setDisplayItemOneTimeRequirements(boughtDisp);

        // Add Actions
        kit.setClaimActions(List.of(
                new KitAction("playsound: ENTITY_PLAYER_LEVELUP;1;1", null, false, false),
                new KitAction("title: <gold>GLADIATOR</gold>;<yellow>Kit Claimed;10;60;20", null, true, false)
        ));
        kit.setErrorActions(List.of(
                new KitAction("playsound: BLOCK_NOTE_BLOCK_BASS;1;0.8", null, false, false)
        ));

        // Add Requirements
        kit.setRequirements(new KitRequirements(
                true,
                List.of("%player_level% >= 20"),
                List.of("<red>Level 20 Required!"),
                List.of("playsound: ENTITY_EXPERIENCE_ORB_PICKUP;1;1"),
                1500.0
        ));

        // Serialize and Deserialize via YamlConfiguration
        KitManager manager = new KitManager(null);
        YamlConfiguration config = new YamlConfiguration();
        manager.saveKitToSection(config, "kits.gladiator", kit);

        Kit parsed = manager.parseKitFromSection(config.getConfigurationSection("kits.gladiator"), "gladiator");

        assertNotNull(parsed);
        assertEquals("gladiator", parsed.getName());
        assertEquals(7200, parsed.getCooldownSeconds());
        assertTrue(parsed.isPermissionRequired());
        assertEquals("friendlycore.kits.gladiator", parsed.getCustomPermission());
        assertTrue(parsed.isAutoArmor());
        assertTrue(parsed.isSaveOriginalItems());
        assertTrue(parsed.isAllowPlaceholdersOnOriginalItems());

        assertEquals(2, parsed.getKitItems().size());
        assertEquals(KitItem.EquipmentSlot.HEAD, parsed.getKitItems().get(0).getEquipmentSlot());

        // Verify Display items
        assertNotNull(parsed.getDisplayItemDefault());
        assertEquals("NETHERITE_SWORD", parsed.getDisplayItemDefault().getId());
        assertEquals("<gold>Gladiator Kit</gold>", parsed.getDisplayItemDefault().getName());

        assertNotNull(parsed.getDisplayItemCooldown());
        assertEquals("CLOCK", parsed.getDisplayItemCooldown().getId());

        assertNotNull(parsed.getDisplayItemNoPermission());
        assertEquals("BARRIER", parsed.getDisplayItemNoPermission().getId());

        assertNotNull(parsed.getDisplayItemOneTime());
        assertEquals("TOTEM_OF_UNDYING", parsed.getDisplayItemOneTime().getId());

        assertNotNull(parsed.getDisplayItemOneTimeRequirements());
        assertEquals("EMERALD", parsed.getDisplayItemOneTimeRequirements().getId());

        // Verify Actions
        assertEquals(2, parsed.getClaimActions().size());
        assertEquals("playsound: ENTITY_PLAYER_LEVELUP;1;1", parsed.getClaimActions().get(0).getAction());
        assertTrue(parsed.getClaimActions().get(1).isExecuteBeforeItems());
        assertEquals(1, parsed.getErrorActions().size());

        // Verify Requirements
        assertNotNull(parsed.getRequirements());
        assertEquals(1500.0, parsed.getRequirements().getPrice());
        assertTrue(parsed.getRequirements().isOneTimeRequirements());
        assertEquals(List.of("%player_level% >= 20"), parsed.getRequirements().getExtraRequirements());
        assertEquals(List.of("<red>Level 20 Required!"), parsed.getRequirements().getMessage());
    }
}
