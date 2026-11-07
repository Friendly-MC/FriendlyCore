package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.pvp.NewbieProtectionManager;
import dev.lyy.friendlycore.modules.pvp.PlayerStats;
import dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class PvpSystemTest {

    @Test
    public void testPlayerStatsAndKDRatio() {
        UUID uuid = UUID.randomUUID();
        PlayerStats stats = new PlayerStats(uuid, "Champion", 15, 5, 3, 10);

        Assertions.assertEquals(15, stats.getKills());
        Assertions.assertEquals(5, stats.getDeaths());
        Assertions.assertEquals(3.0, stats.getKDRatio());

        stats.recordKill();
        Assertions.assertEquals(16, stats.getKills());
        Assertions.assertEquals(4, stats.getCurrentStreak());
        Assertions.assertEquals(10, stats.getHighestStreak());

        stats.recordDeath();
        Assertions.assertEquals(6, stats.getDeaths());
        Assertions.assertEquals(0, stats.getCurrentStreak());
        Assertions.assertEquals(10, stats.getHighestStreak());
        Assertions.assertEquals(2.67, stats.getKDRatio());
    }

    @Test
    public void testMoneyStealCalculations() {
        double victimBalance = 1000.0;
        double rewardPercentage = 0.7; // 70%

        // Percentage Steal
        double stealAmount = victimBalance * rewardPercentage;
        Assertions.assertEquals(700.0, stealAmount);

        // Clamped when victim has less than flat amount
        double flatReward = 1500.0;
        double clampedSteal = Math.min(victimBalance, flatReward);
        Assertions.assertEquals(1000.0, clampedSteal);

        // Zero balance test
        double zeroBalance = 0.0;
        double zeroSteal = Math.max(0.0, Math.min(zeroBalance, zeroBalance * rewardPercentage));
        Assertions.assertEquals(0.0, zeroSteal);

        // PvpEconomyResult test
        dev.lyy.friendlycore.modules.pvp.PvpEconomyManager.PvpEconomyResult result =
                new dev.lyy.friendlycore.modules.pvp.PvpEconomyManager.PvpEconomyResult(700.0, 700.0, "700", "700");
        Assertions.assertEquals(700.0, result.killerGained());
        Assertions.assertEquals(700.0, result.victimLost());
        Assertions.assertEquals("700", result.formattedGained());
        Assertions.assertEquals("700", result.formattedLost());

        String template = "<gray>Slain by <white>%killer%</white> <dark_gray>•</dark_gray> <red>-$%money_lost%</red></gray>";
        String parsed = template.replace("%killer%", "Attacker").replace("%money_lost%", result.formattedLost());
        Assertions.assertEquals("<gray>Slain by <white>Attacker</white> <dark_gray>•</dark_gray> <red>-$700</red></gray>", parsed);
    }

    @Test
    public void testNewbieSessionDecrement() {
        UUID uuid = UUID.randomUUID();
        NewbieProtectionManager.NewbieSession session = new NewbieProtectionManager.NewbieSession(uuid, 60, null);

        Assertions.assertEquals(60, session.getInitialDuration());
        Assertions.assertEquals(60, session.getRemainingSeconds());

        session.decrement();
        Assertions.assertEquals(59, session.getRemainingSeconds());

        for (int i = 0; i < 60; i++) {
            session.decrement();
        }
        Assertions.assertEquals(0, session.getRemainingSeconds());
    }

    @Test
    public void testUntagReasonsEnum() {
        PlayerCombatUntagEvent.UntagReason reason1 = PlayerCombatUntagEvent.UntagReason.SERVER_SHUTDOWN;
        PlayerCombatUntagEvent.UntagReason reason2 = PlayerCombatUntagEvent.UntagReason.PLUGIN_RELOAD;
        PlayerCombatUntagEvent.UntagReason reason3 = PlayerCombatUntagEvent.UntagReason.KICKED;
        PlayerCombatUntagEvent.UntagReason reason4 = PlayerCombatUntagEvent.UntagReason.EXPIRED;
        PlayerCombatUntagEvent.UntagReason reason5 = PlayerCombatUntagEvent.UntagReason.QUIT;
        PlayerCombatUntagEvent.UntagReason reason6 = PlayerCombatUntagEvent.UntagReason.TRANSFER;

        Assertions.assertNotNull(reason1);
        Assertions.assertNotNull(reason2);
        Assertions.assertNotNull(reason3);
        Assertions.assertNotNull(reason4);
        Assertions.assertNotNull(reason5);
        Assertions.assertNotNull(reason6);
    }

    @Test
    public void testFiniteBounceVectorCalculation() {
        // Vertical move only: dx=0, dz=0
        double dx = 0.0;
        double dz = 0.0;
        double distSq = dx * dx + dz * dz;
        double strength = 1.2;

        org.bukkit.util.Vector bounce;
        if (distSq > 0.0001) {
            double dist = Math.sqrt(distSq);
            bounce = new org.bukkit.util.Vector((dx / dist) * strength, 0.35, (dz / dist) * strength);
        } else {
            bounce = new org.bukkit.util.Vector(0, 0.35, -strength);
        }

        Assertions.assertTrue(Double.isFinite(bounce.getX()));
        Assertions.assertTrue(Double.isFinite(bounce.getY()));
        Assertions.assertTrue(Double.isFinite(bounce.getZ()));
    }

    @Test
    public void testCombatNpcStateAndTimer() {
        UUID playerUuid = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();

        dev.lyy.friendlycore.modules.pvp.npc.CombatNpc npc = new dev.lyy.friendlycore.modules.pvp.npc.CombatNpc(
                playerUuid,
                "Naruto",
                entityUuid,
                null,
                15,
                null,
                null,
                null,
                30,
                0.5f,
                20.0,
                20.0,
                null
        );

        Assertions.assertEquals(playerUuid, npc.getPlayerUuid());
        Assertions.assertEquals("Naruto", npc.getPlayerName());
        Assertions.assertEquals(15, npc.getInitialDuration());
        Assertions.assertEquals(15, npc.getRemainingSeconds());
        Assertions.assertEquals(20.0, npc.getHealth());
        Assertions.assertEquals(20.0, npc.getMaxHealth());

        npc.setHealth(12.5);
        Assertions.assertEquals(12.5, npc.getHealth());

        int rem1 = npc.decrementTimer();
        Assertions.assertEquals(14, rem1);
        Assertions.assertEquals(14, npc.getRemainingSeconds());

        for (int i = 0; i < 14; i++) {
            npc.decrementTimer();
        }
        Assertions.assertEquals(0, npc.getRemainingSeconds());
    }

    @Test
    public void testDetonatorInfoTracking() {
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dev.lyy.friendlycore.modules.pvp.CombatTagManager.DetonatorInfo info =
                new dev.lyy.friendlycore.modules.pvp.CombatTagManager.DetonatorInfo(playerUuid, null, now);

        Assertions.assertEquals(playerUuid, info.getPlayerUuid());
        Assertions.assertEquals(now, info.getTimestamp());
        Assertions.assertNull(info.getLocation());
    }

    @Test
    public void testPvpCacheKeyUniqueness() {
        UUID world1 = UUID.randomUUID();
        UUID world2 = UUID.randomUUID();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        int x = 100, y = 64, z = -200;
        long posHash = (((long) (x & 0x3FFFFFF)) << 38) | (((long) (z & 0x3FFFFFF)) << 12) | ((long) (y & 0xFFF));

        long keyWorld1Player1 = world1.getMostSignificantBits() ^ posHash ^ player1.getMostSignificantBits();
        long keyWorld2Player1 = world2.getMostSignificantBits() ^ posHash ^ player1.getMostSignificantBits();
        long keyWorld1Player2 = world1.getMostSignificantBits() ^ posHash ^ player2.getMostSignificantBits();
        long keyWorld1NullPlayer = world1.getMostSignificantBits() ^ posHash ^ 0L;

        Assertions.assertNotEquals(keyWorld1Player1, keyWorld2Player1);
        Assertions.assertNotEquals(keyWorld1Player1, keyWorld1Player2);
        Assertions.assertNotEquals(keyWorld1Player1, keyWorld1NullPlayer);
    }

    @Test
    public void testUntagReasons() {
        dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason[] reasons =
                dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.values();

        Assertions.assertTrue(java.util.Arrays.asList(reasons).contains(dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.OPPONENT_DEATH));
        Assertions.assertTrue(java.util.Arrays.asList(reasons).contains(dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.GAMEMODE_CHANGE));
        Assertions.assertTrue(java.util.Arrays.asList(reasons).contains(dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.WORLD_CHANGE));
        Assertions.assertTrue(java.util.Arrays.asList(reasons).contains(dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.DEATH));
        Assertions.assertTrue(java.util.Arrays.asList(reasons).contains(dev.lyy.friendlycore.modules.pvp.events.PlayerCombatUntagEvent.UntagReason.EXPIRED));
    }

    @Test
    public void testNpcStateTransitions() {
        dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState state = dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.ACTIVE;
        Assertions.assertEquals(dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.ACTIVE, state);

        dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState[] states =
                dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.values();
        Assertions.assertTrue(java.util.Arrays.asList(states).contains(dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.ACTIVE));
        Assertions.assertTrue(java.util.Arrays.asList(states).contains(dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.SLAIN));
        Assertions.assertTrue(java.util.Arrays.asList(states).contains(dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.REJOINED));
        Assertions.assertTrue(java.util.Arrays.asList(states).contains(dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.NpcState.DESPAWNED));
    }

    @Test
    public void testCrossServerPvpActions() {
        dev.lyy.friendlycore.common.redis.CrossServerPacket.Action[] actions =
                dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.values();

        Assertions.assertTrue(java.util.Arrays.asList(actions).contains(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_SPAWN_SYNC));
        Assertions.assertTrue(java.util.Arrays.asList(actions).contains(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_DESPAWN_SYNC));
        Assertions.assertTrue(java.util.Arrays.asList(actions).contains(dev.lyy.friendlycore.common.redis.CrossServerPacket.Action.PVP_NPC_SLAIN_SYNC));
    }

    @Test
    public void testCombatNpcSlainMetadataKey() {
        Assertions.assertEquals("friendlycore:combat-npc-slain",
                dev.lyy.friendlycore.modules.pvp.npc.CombatNpcManager.COMBAT_NPC_SLAIN_META);
    }

    @Test
    public void testCombatNpcAntiDupe() {
        UUID playerUuid = UUID.randomUUID();
        // 41-slot inventory contents: 36 storage, 4 armor, 1 offhand
        org.bukkit.inventory.ItemStack[] fullInv = new org.bukkit.inventory.ItemStack[41];
        org.bukkit.inventory.ItemStack[] armor = new org.bukkit.inventory.ItemStack[4];

        dev.lyy.friendlycore.modules.pvp.npc.CombatNpc npc = new dev.lyy.friendlycore.modules.pvp.npc.CombatNpc(
                playerUuid, "TestPlayer", UUID.randomUUID(), null, 15,
                fullInv, armor, null, 10, 0.0f, 20.0, 20.0, null
        );

        Assertions.assertEquals(41, npc.getInventoryContents().length);
        Assertions.assertEquals(4, npc.getArmorContents().length);
        Assertions.assertNull(npc.getOffHandItem());
    }

    @Test
    public void testCombatItemCooldownLookupAndCombatOnly() {
        org.bukkit.configuration.file.YamlConfiguration mixed = new org.bukkit.configuration.file.YamlConfiguration();
        mixed.set("golden_apple", 60);
        mixed.set("ENDER_PEARL", 15);
        mixed.set("enchanted-golden-apple", 30);

        Assertions.assertEquals(60, dev.lyy.friendlycore.modules.pvp.CombatItemManager.lookupCooldownSeconds(mixed, org.bukkit.Material.GOLDEN_APPLE));
        Assertions.assertEquals(15, dev.lyy.friendlycore.modules.pvp.CombatItemManager.lookupCooldownSeconds(mixed, org.bukkit.Material.ENDER_PEARL));
        Assertions.assertEquals(30, dev.lyy.friendlycore.modules.pvp.CombatItemManager.lookupCooldownSeconds(mixed, org.bukkit.Material.ENCHANTED_GOLDEN_APPLE));
        Assertions.assertEquals(0, dev.lyy.friendlycore.modules.pvp.CombatItemManager.lookupCooldownSeconds(mixed, org.bukkit.Material.BREAD));

        Assertions.assertFalse(dev.lyy.friendlycore.modules.pvp.CombatItemManager.shouldEnforceCooldowns(false, true, true));
        Assertions.assertFalse(dev.lyy.friendlycore.modules.pvp.CombatItemManager.shouldEnforceCooldowns(true, true, false));
        Assertions.assertTrue(dev.lyy.friendlycore.modules.pvp.CombatItemManager.shouldEnforceCooldowns(true, true, true));
        Assertions.assertTrue(dev.lyy.friendlycore.modules.pvp.CombatItemManager.shouldEnforceCooldowns(true, false, false));
    }

    @Test
    public void testWearableItemCheck() {
        dev.lyy.friendlycore.modules.pvp.CombatItemManager itemManager =
                new dev.lyy.friendlycore.modules.pvp.CombatItemManager(null, null);

        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.DIAMOND_HELMET));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.NETHERITE_CHESTPLATE));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.IRON_LEGGINGS));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.GOLDEN_BOOTS));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.ELYTRA));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.CARVED_PUMPKIN));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.TURTLE_HELMET));
        Assertions.assertTrue(itemManager.isWearableItem(org.bukkit.Material.PLAYER_HEAD));

        Assertions.assertFalse(itemManager.isWearableItem(org.bukkit.Material.DIAMOND_SWORD));
        Assertions.assertFalse(itemManager.isWearableItem(org.bukkit.Material.ENDER_PEARL));
        Assertions.assertFalse(itemManager.isWearableItem(org.bukkit.Material.STICK));
    }

    @Test
    public void testArmorMitigationPointsAndToughness() {
        Assertions.assertEquals(3.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialArmorPoints(org.bukkit.Material.DIAMOND_HELMET));
        Assertions.assertEquals(8.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialArmorPoints(org.bukkit.Material.NETHERITE_CHESTPLATE));
        Assertions.assertEquals(6.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialArmorPoints(org.bukkit.Material.DIAMOND_LEGGINGS));
        Assertions.assertEquals(3.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialArmorPoints(org.bukkit.Material.NETHERITE_BOOTS));

        Assertions.assertEquals(3.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialToughness(org.bukkit.Material.NETHERITE_CHESTPLATE));
        Assertions.assertEquals(2.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialToughness(org.bukkit.Material.DIAMOND_HELMET));
        Assertions.assertEquals(0.0, dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.getMaterialToughness(org.bukkit.Material.IRON_CHESTPLATE));
    }

    @Test
    public void testNpcDamageMitigationFormula() {
        UUID playerUuid = UUID.randomUUID();

        // 1. Unarmored NPC: raw 10 damage should remain 10 damage
        dev.lyy.friendlycore.modules.pvp.npc.CombatNpc nakedNpc = new dev.lyy.friendlycore.modules.pvp.npc.CombatNpc(
                playerUuid, "NakedPlayer", UUID.randomUUID(), null, 15,
                new org.bukkit.inventory.ItemStack[36], new org.bukkit.inventory.ItemStack[4], null, 0, 0.0f, 20.0, 20.0, null
        );
        double unarmoredDamage = nakedNpc.calculateMitigatedDamage(10.0);
        Assertions.assertEquals(10.0, unarmoredDamage, 0.001);

        // 2. Full Netherite Armor calculation (20 armor, 12 toughness, 0 EPF): raw 10 damage should be mitigated substantially
        // defense = min(20, max(4, 20 - 10 / 5)) = 18.0
        // postArmor = 10 * (1 - 18 / 25) = 10 * 0.28 = 2.8
        double netheriteDamage = dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.calculateMitigatedDamage(10.0, 20.0, 12.0, 0);
        Assertions.assertEquals(2.8, netheriteDamage, 0.001);

        // 3. Full Netherite Armor + Full Protection IV (20 armor, 12 toughness, 16 EPF):
        // epfFactor = 16 * 0.04 = 0.64 (64% reduction)
        // finalDamage = 2.8 * (1 - 0.64) = 2.8 * 0.36 = 1.008
        double prot4NetheriteDamage = dev.lyy.friendlycore.modules.pvp.npc.CombatNpc.calculateMitigatedDamage(10.0, 20.0, 12.0, 16);
        Assertions.assertEquals(1.008, prot4NetheriteDamage, 0.001);
    }

    @Test
    public void testRecentOpponentInfo() {
        UUID opponentUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        dev.lyy.friendlycore.modules.pvp.CombatTagManager.RecentOpponentInfo info =
                new dev.lyy.friendlycore.modules.pvp.CombatTagManager.RecentOpponentInfo(opponentUuid, now);

        Assertions.assertEquals(opponentUuid, info.getOpponentUuid());
        Assertions.assertEquals(now, info.getTimestamp());
    }
}
