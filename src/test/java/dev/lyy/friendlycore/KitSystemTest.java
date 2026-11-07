package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.kits.Kit;
import dev.lyy.friendlycore.modules.kits.KitManager;
import dev.lyy.friendlycore.modules.kits.KitRequirementChecker;
import dev.lyy.friendlycore.modules.kits.model.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

public class KitSystemTest {

    @Test
    public void testPlayerKitDataAndDirtyTracking() {
        UUID uuid = UUID.randomUUID();
        PlayerKitData data = new PlayerKitData(uuid, "Steve");

        Assertions.assertEquals("Steve", data.getUsername());
        Assertions.assertFalse(data.isDirty());

        data.setCooldownUntil("starter", System.currentTimeMillis() + 100000L);
        Assertions.assertTrue(data.isDirty());
        Assertions.assertTrue(data.getCooldownUntil("starter") > System.currentTimeMillis());

        long v1 = data.getCurrentVersion();
        data.markSaved(v1);
        Assertions.assertFalse(data.isDirty());

        data.setOneTimeClaimed("starter", true);
        Assertions.assertTrue(data.isDirty());
        Assertions.assertTrue(data.isOneTimeClaimed("starter"));

        data.setBought("starter", true);
        Assertions.assertTrue(data.isBought("starter"));

        data.resetKit("starter");
        Assertions.assertEquals(0L, data.getCooldownUntil("starter"));
        Assertions.assertFalse(data.isOneTimeClaimed("starter"));
    }

    @Test
    public void testPlayerKitRecordVolatileAndCloning() {
        PlayerKitRecord record = new PlayerKitRecord("pvp", 123456789L, true, true, 987654321L);
        PlayerKitRecord clone = record.clone();

        Assertions.assertEquals("pvp", clone.getKitName());
        Assertions.assertEquals(123456789L, clone.getCooldownUntil());
        Assertions.assertTrue(clone.isOneTimeClaimed());
        Assertions.assertTrue(clone.isBought());
        Assertions.assertEquals(987654321L, clone.getLastClaimed());
    }

    @Test
    public void testKitItemAndSubModels() {
        KitItem item = new KitItem("DIAMOND_SWORD");
        item.setAmount(1);
        item.setName("<gradient:#ff0000:#00ff00>Excalibur</gradient>");
        item.setEnchants(List.of("SHARPNESS;5", "UNBREAKING;3"));
        item.setTrimData(new KitItemTrimData("SILENCE", "NETHERITE"));

        KitItem cloned = item.clone();
        Assertions.assertEquals(item.getId(), cloned.getId());
        Assertions.assertEquals(item.getName(), cloned.getName());
        Assertions.assertEquals(2, cloned.getEnchants().size());
        Assertions.assertNotNull(cloned.getTrimData());
        Assertions.assertEquals("SILENCE", cloned.getTrimData().getPattern());
        Assertions.assertEquals("NETHERITE", cloned.getTrimData().getMaterial());
    }

    @Test
    public void testKitActionCloning() {
        KitAction action = new KitAction("playsound: ENTITY_PLAYER_LEVELUP;1;1", new KitItem("GOLD_INGOT"), true, true);
        KitAction cloned = action.clone();

        Assertions.assertEquals(action.getAction(), cloned.getAction());
        Assertions.assertTrue(cloned.isExecuteBeforeItems());
        Assertions.assertTrue(cloned.isCountAsItem());
        Assertions.assertNotNull(cloned.getDisplayItem());
        Assertions.assertEquals("GOLD_INGOT", cloned.getDisplayItem().getId());
    }

    @Test
    public void testKitRequirementsModel() {
        KitRequirements req = new KitRequirements(true, List.of("%player_level% >= 10"), List.of("<red>Level 10 required!"), List.of("message: Kit purchased!"), 500.0);
        KitRequirements cloned = req.clone();

        Assertions.assertTrue(cloned.isOneTimeRequirements());
        Assertions.assertEquals(500.0, cloned.getPrice());
        Assertions.assertEquals(1, cloned.getExtraRequirements().size());
        Assertions.assertEquals(1, cloned.getMessage().size());
        Assertions.assertEquals(1, cloned.getActionsOnBuy().size());
    }

    @Test
    public void testKitItemYamlSerialization() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();

        KitItem original = new KitItem("BOW");
        original.setAmount(1);
        original.setName("<gold>Super Bow</gold>");
        original.setLore(List.of("<gray>Line 1", "<gray>Line 2"));
        original.setEnchants(List.of("POWER;5", "FLAME;1"));
        original.setDurability((short) 15);
        original.setCustomModelData(1001);
        original.setOffhand(true);
        original.setPreviewSlot(4);

        dev.lyy.friendlycore.modules.kits.KitItemParser.saveKitItem(original, yaml, "test_item");
        KitItem loaded = dev.lyy.friendlycore.modules.kits.KitItemParser.loadKitItem(yaml, "test_item");

        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("BOW", loaded.getId());
        Assertions.assertEquals(1, loaded.getAmount());
        Assertions.assertEquals("<gold>Super Bow</gold>", loaded.getName());
        Assertions.assertEquals(2, loaded.getLore().size());
        Assertions.assertEquals(2, loaded.getEnchants().size());
        Assertions.assertEquals((short) 15, loaded.getDurability());
        Assertions.assertEquals(1001, loaded.getCustomModelData());
        Assertions.assertTrue(loaded.isOffhand());
        Assertions.assertEquals(4, loaded.getPreviewSlot());
    }

    @Test
    public void testConditionEvaluator() {
        KitRequirementChecker checker = new KitRequirementChecker(null);

        Assertions.assertTrue(checker.evaluateCondition(null, "10 >= 5"));
        Assertions.assertTrue(checker.evaluateCondition(null, "5 <= 10"));
        Assertions.assertTrue(checker.evaluateCondition(null, "10 > 5"));
        Assertions.assertTrue(checker.evaluateCondition(null, "5 < 10"));
        Assertions.assertTrue(checker.evaluateCondition(null, "100 == 100"));
        Assertions.assertTrue(checker.evaluateCondition(null, "50 != 20"));
        Assertions.assertTrue(checker.evaluateCondition(null, "hello equals hello"));
        Assertions.assertTrue(checker.evaluateCondition(null, "hello contains ell"));
        Assertions.assertFalse(checker.evaluateCondition(null, "hello contains xyz"));
        Assertions.assertFalse(checker.evaluateCondition(null, "5 >= 10"));
        Assertions.assertFalse(checker.evaluateCondition(null, "5 > 10"));
    }

    @Test
    public void testPlayerKits2Old1221MigrationAndParsing() {
        java.io.File folder = new java.io.File("decompi-plugin/PlayerKits2-main/old-1.22.1/kits");
        if (!folder.exists()) {
            return; // Skip if directory not present in test env
        }

        java.io.File[] kitFiles = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        Assertions.assertNotNull(kitFiles);
        Assertions.assertTrue(kitFiles.length > 0, "Should have found kit files in old-1.22.1/kits");

        dev.lyy.friendlycore.modules.kits.KitManager kitManager = new dev.lyy.friendlycore.modules.kits.KitManager(null);

        for (java.io.File kitFile : kitFiles) {
            String kitName = kitFile.getName().substring(0, kitFile.getName().length() - 4);
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try {
                String rawContent = java.nio.file.Files.readString(kitFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                // Sanitize serialized class header to prevent Bukkit getUnsafe NPE logging in headless unit test
                String sanitized = rawContent.replace("==: org.bukkit.inventory.ItemStack", "legacy_type: org.bukkit.inventory.ItemStack");
                yaml.loadFromString(sanitized);
            } catch (Exception e) {
                yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(kitFile);
            }
            Kit kit = kitManager.parseKitFromSection(yaml, kitName);

            Assertions.assertNotNull(kit, "Kit " + kitName + " should parse successfully");
            Assertions.assertNotNull(kit.getKitItems(), "Kit " + kitName + " items should not be null");
            Assertions.assertFalse(kit.getKitItems().isEmpty(), "Kit " + kitName + " should have items parsed");

            if ("starter".equalsIgnoreCase(kitName)) {
                Assertions.assertEquals(3600, kit.getCooldownSeconds());
                Assertions.assertTrue(kit.isOneTime());
                Assertions.assertTrue(kit.isAutoArmor());
                Assertions.assertEquals(12, kit.getKitItems().size());
            } else if ("food".equalsIgnoreCase(kitName)) {
                Assertions.assertEquals(10800, kit.getCooldownSeconds());
                Assertions.assertFalse(kit.isOneTime());
                Assertions.assertEquals(1, kit.getKitItems().size());
            } else if ("king".equalsIgnoreCase(kitName)) {
                Assertions.assertEquals(86400, kit.getCooldownSeconds());
                Assertions.assertTrue(kit.isPermissionRequired());
                Assertions.assertEquals(1, kit.getKitItems().size());
            }
        }
    }

    @Test
    public void testCrossServerKitPackets() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        UUID playerUuid = UUID.randomUUID();

        // 1. KIT_CLAIM_SYNC packet test
        long cd = System.currentTimeMillis() + 3600000L;
        String claimPayload = "king;" + cd + ";false;true";
        dev.lyy.friendlycore.common.redis.CrossServerPacket claimPacket = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "lobby-1",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.KIT_CLAIM_SYNC,
                playerUuid,
                "Alex",
                cd,
                claimPayload
        );
        String jsonClaim = gson.toJson(claimPacket);
        dev.lyy.friendlycore.common.redis.CrossServerPacket deserializedClaim = gson.fromJson(jsonClaim, dev.lyy.friendlycore.common.redis.CrossServerPacket.class);
        Assertions.assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.KIT_CLAIM_SYNC, deserializedClaim.getAction());
        Assertions.assertEquals(playerUuid, deserializedClaim.getTargetUuid());
        Assertions.assertEquals("Alex", deserializedClaim.getTargetName());
        Assertions.assertEquals(claimPayload, deserializedClaim.getExtra());

        // 2. KIT_GIVE_REMOTE packet test
        String givePayload = "starter;false;CONSOLE";
        dev.lyy.friendlycore.common.redis.CrossServerPacket givePacket = new dev.lyy.friendlycore.common.redis.CrossServerPacket(
                "survival-1",
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.KIT_GIVE_REMOTE,
                playerUuid,
                "Alex",
                0,
                givePayload
        );
        String jsonGive = gson.toJson(givePacket);
        dev.lyy.friendlycore.common.redis.CrossServerPacket deserializedGive = gson.fromJson(jsonGive, dev.lyy.friendlycore.common.redis.CrossServerPacket.class);
        Assertions.assertEquals(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.KIT_GIVE_REMOTE, deserializedGive.getAction());
        Assertions.assertEquals(givePayload, deserializedGive.getExtra());

        // 3. In-memory data update test
        PlayerKitData data = new PlayerKitData(playerUuid, "Alex");
        data.setCooldownUntil("king", cd);
        data.setBought("king", true);
        Assertions.assertEquals(cd, data.getCooldownUntil("king"));
        Assertions.assertTrue(data.isBought("king"));

        data.resetKit("king");
        Assertions.assertEquals(0L, data.getCooldownUntil("king"));
        Assertions.assertFalse(data.isOneTimeClaimed("king"));
    }

    @Test
    public void testPlayerKitDataMerging() {
        UUID uuid = UUID.randomUUID();
        PlayerKitData data = new PlayerKitData(uuid, "Alex");

        long now = System.currentTimeMillis();
        PlayerKitRecord localRecord = new PlayerKitRecord("warrior", now + 100000L, false, true, now);
        data.addRecord(localRecord);

        // Simulate incoming record from database or Redis packet with different attributes
        PlayerKitRecord incomingRecord = new PlayerKitRecord("warrior", now + 50000L, true, false, now + 1000L);
        data.mergeRecord(incomingRecord);

        PlayerKitRecord merged = data.getRecord("warrior");
        Assertions.assertNotNull(merged);
        // Max cooldown is retained
        Assertions.assertEquals(now + 100000L, merged.getCooldownUntil());
        // One-time claimed becomes true (true || false)
        Assertions.assertTrue(merged.isOneTimeClaimed());
        // Bought remains true (true || false)
        Assertions.assertTrue(merged.isBought());
        // Last claimed is the max timestamp
        Assertions.assertEquals(now + 1000L, merged.getLastClaimed());
    }

    @Test
    public void testCrossServerKitSyncUncachedPlayer() {
        KitManager kitManager = new KitManager(null);
        UUID targetUuid = UUID.randomUUID();

        // 1. Simulate server receiving KIT_CLAIM_SYNC before player has joined this server
        long cooldown = System.currentTimeMillis() + 600000L;
        kitManager.updateLocalRecord(targetUuid, "knight", cooldown, true, true);

        PlayerKitData cachedData = kitManager.getPlayerData(targetUuid);
        Assertions.assertNotNull(cachedData);
        Assertions.assertEquals(cooldown, cachedData.getCooldownUntil("knight"));
        Assertions.assertTrue(cachedData.isOneTimeClaimed("knight"));
        Assertions.assertTrue(cachedData.isBought("knight"));
        Assertions.assertFalse(cachedData.isDirty(), "Synced record should not be marked dirty on recipient server");

        // 2. Simulate subsequent DB load returning older/partial state
        PlayerKitRecord dbRecord = new PlayerKitRecord("knight", System.currentTimeMillis() - 1000L, false, false, 500L);
        cachedData.addRecord(dbRecord);

        // Smart merge must preserve newer Redis claim and cooldown
        Assertions.assertEquals(cooldown, cachedData.getCooldownUntil("knight"));
        Assertions.assertTrue(cachedData.isOneTimeClaimed("knight"));
        Assertions.assertTrue(cachedData.isBought("knight"));

        // 3. Test cooldown sync
        long newCd = System.currentTimeMillis() + 1200000L;
        kitManager.updateLocalCooldown(targetUuid, "archer", newCd);
        Assertions.assertEquals(newCd, cachedData.getCooldownUntil("archer"));

        // 4. Test reset
        kitManager.resetLocalCache("knight", targetUuid);
        Assertions.assertEquals(0L, cachedData.getCooldownUntil("knight"));
        Assertions.assertFalse(cachedData.isOneTimeClaimed("knight"));
    }

    @Test
    public void testCrossServerResetAllAndMultiKitClaim() {
        KitManager kitManager = new KitManager(null);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        // 1. Setup multiple player claims
        long cd = System.currentTimeMillis() + 999999L;
        kitManager.updateLocalRecord(p1, "starter", cd, true, false);
        kitManager.updateLocalRecord(p1, "vip", cd, false, true);
        kitManager.updateLocalRecord(p2, "starter", cd, true, false);

        Assertions.assertTrue(kitManager.getPlayerData(p1).isOneTimeClaimed("starter"));
        Assertions.assertTrue(kitManager.getPlayerData(p1).isBought("vip"));
        Assertions.assertTrue(kitManager.getPlayerData(p2).isOneTimeClaimed("starter"));

        // 2. Global reset of starter kit across network
        kitManager.resetLocalCacheForAll("starter");

        Assertions.assertFalse(kitManager.getPlayerData(p1).isOneTimeClaimed("starter"));
        Assertions.assertEquals(0L, kitManager.getPlayerData(p1).getCooldownUntil("starter"));
        Assertions.assertFalse(kitManager.getPlayerData(p2).isOneTimeClaimed("starter"));
        // vip kit for p1 should be unaffected
        Assertions.assertTrue(kitManager.getPlayerData(p1).isBought("vip"));
        Assertions.assertEquals(cd, kitManager.getPlayerData(p1).getCooldownUntil("vip"));

        // 3. Offline cache cleanup verification
        kitManager.cleanupOfflineData();
        // Since neither p1 nor p2 is online and neither is marked dirty, cache is pruned
        Assertions.assertNull(kitManager.getPlayerData(p1));
        Assertions.assertNull(kitManager.getPlayerData(p2));
    }
}
