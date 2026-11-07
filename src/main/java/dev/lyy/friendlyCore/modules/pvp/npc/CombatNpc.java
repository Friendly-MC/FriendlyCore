package dev.lyy.friendlycore.modules.pvp.npc;

import dev.lyy.friendlycore.FriendlyCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates a logged-out player's combat state, inventory snapshot, equipment, and spawned NPC entity.
 */
public class CombatNpc {

    public enum NpcState {
        ACTIVE,
        SLAIN,
        REJOINED,
        DESPAWNED
    }

    private final UUID playerUuid;
    private final String playerName;
    private final UUID entityUuid;
    private final LivingEntity entity;
    private final int initialDuration;
    private int remainingSeconds;
    private final java.util.concurrent.atomic.AtomicReference<NpcState> state =
            new java.util.concurrent.atomic.AtomicReference<>(NpcState.ACTIVE);

    private final ItemStack[] inventoryContents;
    private final ItemStack[] armorContents;
    private final ItemStack offHandItem;
    private final int level;
    private final float exp;
    private double health;
    private final double maxHealth;
    private final Location spawnLocation;

    public CombatNpc(UUID playerUuid,
                     String playerName,
                     UUID entityUuid,
                     LivingEntity entity,
                     int durationSeconds,
                     ItemStack[] inventoryContents,
                     ItemStack[] armorContents,
                     ItemStack offHandItem,
                     int level,
                     float exp,
                     double health,
                     double maxHealth,
                     Location spawnLocation) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.entityUuid = entityUuid;
        this.entity = entity;
        this.initialDuration = durationSeconds;
        this.remainingSeconds = durationSeconds;
        this.inventoryContents = cloneItemArray(inventoryContents);
        this.armorContents = cloneItemArray(armorContents);
        this.offHandItem = offHandItem != null ? offHandItem.clone() : null;
        this.level = level;
        this.exp = exp;
        this.health = health;
        this.maxHealth = maxHealth;
        this.spawnLocation = spawnLocation;
    }

    private static ItemStack[] cloneItemArray(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            if (source[i] != null) {
                copy[i] = source[i].clone();
            }
        }
        return copy;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getEntityUuid() {
        return entityUuid;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public int getInitialDuration() {
        return initialDuration;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public void setRemainingSeconds(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }

    public NpcState getState() {
        return state.get();
    }

    public boolean tryTransitionState(NpcState expected, NpcState newState) {
        return state.compareAndSet(expected, newState);
    }

    public boolean isResolved() {
        return state.get() != NpcState.ACTIVE;
    }

    public int decrementTimer() {
        if (this.remainingSeconds > 0) {
            this.remainingSeconds--;
        }
        return this.remainingSeconds;
    }

    public ItemStack[] getInventoryContents() {
        return inventoryContents;
    }

    public ItemStack[] getArmorContents() {
        return armorContents;
    }

    public ItemStack getOffHandItem() {
        return offHandItem;
    }

    public int getLevel() {
        return level;
    }

    public float getExp() {
        return exp;
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public Location getSpawnLocation() {
        return spawnLocation;
    }

    public void updateCustomName(FriendlyCore plugin, String format) {
        if (entity == null || !entity.isValid() || entity.isDead()) return;
        Map<String, String> placeholders = Map.of(
                "%player%", playerName,
                "%time%", String.valueOf(remainingSeconds)
        );
        Component parsed = plugin.getTextService().parse(format, placeholders);
        entity.customName(parsed);
        entity.setCustomNameVisible(true);
    }

    public void dropLoot(Location dropLoc) {
        if (dropLoc == null || dropLoc.getWorld() == null) return;
        World world = dropLoc.getWorld();

        // Drop main storage inventory (slots 0-35 only, preventing double-drop of armor/offhand)
        if (inventoryContents != null) {
            int maxStorageSlots = Math.min(36, inventoryContents.length);
            for (int i = 0; i < maxStorageSlots; i++) {
                ItemStack item = inventoryContents[i];
                if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                    world.dropItemNaturally(dropLoc, item.clone());
                }
            }
        }

        if (armorContents != null) {
            for (ItemStack item : armorContents) {
                if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                    world.dropItemNaturally(dropLoc, item.clone());
                }
            }
        }

        if (offHandItem != null && offHandItem.getType() != Material.AIR && offHandItem.getAmount() > 0) {
            world.dropItemNaturally(dropLoc, offHandItem.clone());
        }

        // Drop calculated player experience capped to vanilla death limits
        int totalExp = calculateTotalExp(level, exp);
        if (totalExp > 0) {
            int droppedExp = Math.min(100, totalExp);
            ExperienceOrb orb = world.spawn(dropLoc, ExperienceOrb.class);
            orb.setExperience(droppedExp);
        }
    }

    private int calculateTotalExp(int level, float exp) {
        int total = 0;
        if (level <= 16) {
            total = (level * level) + (6 * level);
        } else if (level <= 31) {
            total = (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            total = (int) (4.5 * level * level - 162.5 * level + 2220);
        }
        return total;
    }

    public double calculateMitigatedDamage(double rawDamage) {
        if (rawDamage <= 0.0) return 0.0;

        double armorPoints = 0.0;
        double toughness = 0.0;
        int epf = 0;

        if (armorContents != null) {
            for (ItemStack item : armorContents) {
                if (item != null && item.getType() != Material.AIR) {
                    armorPoints += getMaterialArmorPoints(item.getType());
                    toughness += getMaterialToughness(item.getType());
                    epf += getProtectionEnchantmentLevel(item);
                }
            }
        }

        return calculateMitigatedDamage(rawDamage, armorPoints, toughness, epf);
    }

    public static double calculateMitigatedDamage(double rawDamage, double armorPoints, double toughness, int epf) {
        if (rawDamage <= 0.0) return 0.0;

        double defense = Math.min(20.0, Math.max(armorPoints / 5.0, armorPoints - rawDamage / (2.0 + toughness / 4.0)));
        double postArmorDamage = rawDamage * (1.0 - (defense / 25.0));

        // Enchantment Protection Factor (EPF): 4% per level, capped at 80% (20 EPF)
        double epfFactor = Math.min(20, Math.max(0, epf)) * 0.04;
        double finalDamage = postArmorDamage * (1.0 - epfFactor);

        return Math.max(0.5, finalDamage);
    }

    public static double getMaterialArmorPoints(Material mat) {
        if (mat == null) return 0.0;
        String name = mat.name();
        if (name.equals("LEATHER_HELMET") || name.equals("LEATHER_BOOTS")) return 1.0;
        if (name.equals("LEATHER_LEGGINGS")) return 2.0;
        if (name.equals("LEATHER_CHESTPLATE")) return 3.0;

        if (name.equals("GOLDEN_BOOTS") || name.equals("CHAINMAIL_BOOTS")) return 1.0;
        if (name.equals("GOLDEN_HELMET") || name.equals("CHAINMAIL_HELMET") || name.equals("TURTLE_HELMET")) return 2.0;
        if (name.equals("GOLDEN_LEGGINGS") || name.equals("CHAINMAIL_LEGGINGS")) return 3.0;
        if (name.equals("GOLDEN_CHESTPLATE") || name.equals("CHAINMAIL_CHESTPLATE")) return 5.0;

        if (name.equals("IRON_BOOTS") || name.equals("IRON_HELMET")) return 2.0;
        if (name.equals("IRON_LEGGINGS")) return 5.0;
        if (name.equals("IRON_CHESTPLATE")) return 6.0;

        if (name.equals("DIAMOND_BOOTS") || name.equals("NETHERITE_BOOTS") || name.equals("DIAMOND_HELMET") || name.equals("NETHERITE_HELMET")) return 3.0;
        if (name.equals("DIAMOND_LEGGINGS") || name.equals("NETHERITE_LEGGINGS")) return 6.0;
        if (name.equals("DIAMOND_CHESTPLATE") || name.equals("NETHERITE_CHESTPLATE")) return 8.0;

        return 0.0;
    }

    public static double getMaterialToughness(Material mat) {
        if (mat == null) return 0.0;
        String name = mat.name();
        if (name.startsWith("DIAMOND_")) return 2.0;
        if (name.startsWith("NETHERITE_")) return 3.0;
        return 0.0;
    }

    public static int getProtectionEnchantmentLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        int level = 0;
        try {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
                String key = entry.getKey().getKey().getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.equals("protection")) {
                    level += entry.getValue();
                }
            }
        } catch (Throwable ignored) {}
        return level;
    }

    public void despawn(boolean withParticles) {
        if (entity != null && entity.isValid()) {
            if (withParticles && entity.getLocation().getWorld() != null) {
                Location loc = entity.getLocation().add(0, 1, 0);
                loc.getWorld().spawnParticle(Particle.PORTAL, loc, 20, 0.3, 0.5, 0.3, 0.1);
                loc.getWorld().playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
            }
            entity.remove();
        }
    }
}
