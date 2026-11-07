package dev.lyy.friendlycore.modules.pvp;

import dev.lyy.friendlycore.FriendlyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages item usage cooldowns, banned consumables, and equipment restrictions during combat.
 */
public class CombatItemManager implements Listener {

    private final FriendlyCore plugin;
    private final CombatTagManager combatTagManager;
    private final Map<UUID, Map<Material, Long>> cooldownMap = new ConcurrentHashMap<>();

    public CombatItemManager(FriendlyCore plugin, CombatTagManager combatTagManager) {
        this.plugin = plugin;
        this.combatTagManager = combatTagManager;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean isOnCooldown(UUID uuid, Material material) {
        Map<Material, Long> playerCooldowns = cooldownMap.get(uuid);
        if (playerCooldowns == null) return false;
        Long expiry = playerCooldowns.get(material);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public int getRemainingCooldown(UUID uuid, Material material) {
        Map<Material, Long> playerCooldowns = cooldownMap.get(uuid);
        if (playerCooldowns == null) return 0;
        Long expiry = playerCooldowns.get(material);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    public void applyCooldown(Player player, Material material, int seconds) {
        if (player == null || material == null || seconds <= 0) return;
        // Do not restart a countdown that is already running
        if (isOnCooldown(player.getUniqueId(), material)) return;

        long expiry = System.currentTimeMillis() + (seconds * 1000L);
        cooldownMap.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(material, expiry);

        // Vanilla hotbar overlay only — no action bar / chat spam
        plugin.getScheduler().runEntity(player, () -> {
            if (player.isOnline()) {
                player.setCooldown(material, seconds * 20);
            }
        });
    }

    public static boolean shouldEnforceCooldowns(boolean enabled, boolean combatOnly, boolean inCombat) {
        if (!enabled) return false;
        return !combatOnly || inCombat;
    }

    private boolean shouldEnforceCooldowns(Player player) {
        if (player == null || plugin == null) return false;
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        return shouldEnforceCooldowns(
                config.getBoolean("cooldowns.enabled", true),
                config.getBoolean("cooldowns.combat-only", true),
                combatTagManager != null && combatTagManager.isTagged(player.getUniqueId())
        );
    }

    public static int lookupCooldownSeconds(ConfigurationSection itemsSec, Material material) {
        if (itemsSec == null || material == null) return 0;

        String name = material.name();
        if (itemsSec.contains(name)) {
            return Math.max(0, itemsSec.getInt(name, 0));
        }

        String lower = name.toLowerCase(Locale.ROOT);
        if (itemsSec.contains(lower)) {
            return Math.max(0, itemsSec.getInt(lower, 0));
        }

        for (String key : itemsSec.getKeys(false)) {
            if (key == null) continue;
            String normalized = key.trim().replace('-', '_');
            if (normalized.equalsIgnoreCase(name)) {
                return Math.max(0, itemsSec.getInt(key, 0));
            }
        }
        return 0;
    }

    private int getConfiguredCooldown(Material material) {
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("cooldowns.enabled", true)) return 0;
        return lookupCooldownSeconds(config.getConfigurationSection("cooldowns.items"), material);
    }

    private void applyConfiguredCooldown(Player player, Material material) {
        if (!shouldEnforceCooldowns(player)) return;
        int cd = getConfiguredCooldown(material);
        if (cd > 0) {
            applyCooldown(player, material, cd);
        }
    }

    private void applyConfiguredCooldownNextTick(Player player, Material material) {
        if (!shouldEnforceCooldowns(player) || plugin == null) return;
        int cd = getConfiguredCooldown(material);
        if (cd <= 0) return;
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline() && shouldEnforceCooldowns(player) && !isOnCooldown(player.getUniqueId(), material)) {
                applyCooldown(player, material, cd);
            }
        }, 1L);
    }

    public boolean isItemBlocked(Material material, Player player) {
        if (material == null) return false;
        FileConfiguration config = plugin.getConfigService().getPvpConfig();

        if (player != null && !combatTagManager.isTagged(player.getUniqueId())) return false;

        // Force elytra restriction when in combat if configured
        if (material == Material.ELYTRA && config.getBoolean("combat-tag.disable-elytra", true)) {
            return true;
        }

        if (!config.getBoolean("blocker.enabled", true)) return false;

        List<String> blockedList = config.getStringList("blocker.blocked-items");
        for (String blocked : blockedList) {
            if (blocked.equalsIgnoreCase(material.name())) {
                return true;
            }
        }
        return false;
    }

    public boolean isWearableItem(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") ||
                name.endsWith("_BOOTS") || material == Material.ELYTRA || material == Material.CARVED_PUMPKIN ||
                material == Material.PLAYER_HEAD || material == Material.ZOMBIE_HEAD || material == Material.CREEPER_HEAD ||
                material == Material.DRAGON_HEAD || material == Material.PIGLIN_HEAD || material == Material.SKELETON_SKULL ||
                material == Material.WITHER_SKELETON_SKULL || material == Material.TURTLE_HELMET;
    }

    private boolean canEquipIntoArmorSlot(Player player, Material material) {
        if (player == null || material == null) return false;
        String name = material.name();
        if (name.endsWith("_HELMET") || material == Material.CARVED_PUMPKIN || material == Material.TURTLE_HELMET || name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
            return player.getInventory().getHelmet() == null || player.getInventory().getHelmet().getType() == Material.AIR;
        }
        if (name.endsWith("_CHESTPLATE") || material == Material.ELYTRA) {
            return player.getInventory().getChestplate() == null || player.getInventory().getChestplate().getType() == Material.AIR;
        }
        if (name.endsWith("_LEGGINGS")) {
            return player.getInventory().getLeggings() == null || player.getInventory().getLeggings().getType() == Material.AIR;
        }
        if (name.endsWith("_BOOTS")) {
            return player.getInventory().getBoots() == null || player.getInventory().getBoots().getType() == Material.AIR;
        }
        return false;
    }

    private boolean checkCooldownAndBlock(Player player, Material material, Event cancelableEvent) {
        if (player == null || material == null) return false;

        // Check if item is entirely forbidden during combat
        if (isItemBlocked(material, player)) {
            if (cancelableEvent instanceof org.bukkit.event.Cancellable c) {
                c.setCancelled(true);
            }
            if (cancelableEvent instanceof PlayerInteractEvent pie) {
                pie.setUseItemInHand(Event.Result.DENY);
                pie.setUseInteractedBlock(Event.Result.DENY);
                if (isWearableItem(material)) {
                    resyncInventoryNextTick(player);
                }
            }
            plugin.getTextService().sendMessage(player, "blocker.item-blocked", Map.of("%item%", formatName(material)));
            return true;
        }

        // Check custom item cooldown timer (vanilla overlay only, no action bar)
        if (shouldEnforceCooldowns(player) && isOnCooldown(player.getUniqueId(), material)) {
            if (cancelableEvent instanceof org.bukkit.event.Cancellable c) {
                c.setCancelled(true);
            }
            if (cancelableEvent instanceof PlayerInteractEvent pie) {
                pie.setUseItemInHand(Event.Result.DENY);
                pie.setUseInteractedBlock(Event.Result.DENY);
            }
            int remaining = getRemainingCooldown(player.getUniqueId(), material);
            if (remaining > 0) {
                player.setCooldown(material, remaining * 20);
            }
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) return;

        Material mat = item.getType();

        // Prevent placing or detonating explosive anchors/crystals in safezone buffer regions
        if (mat == Material.END_CRYSTAL || mat == Material.RESPAWN_ANCHOR || (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR)) {
            Location targetLoc = event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : player.getLocation();
            if (mat == Material.END_CRYSTAL && event.getClickedBlock() != null) {
                targetLoc = event.getClickedBlock().getLocation().add(0, 1, 0);
            }
            if (isCrystalOrAnchorRestricted(player, mat == Material.END_CRYSTAL ? Material.END_CRYSTAL : Material.RESPAWN_ANCHOR, targetLoc)) {
                event.setCancelled(true);
                event.setUseItemInHand(Event.Result.DENY);
                event.setUseInteractedBlock(Event.Result.DENY);
                return;
            }

            if ((event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.RESPAWN_ANCHOR) || mat == Material.RESPAWN_ANCHOR) {
                combatTagManager.recordAnchorDetonation(targetLoc, player);
            }
        }

        if (handleFireworkInCombat(event, player, item)) {
            return;
        }

        if (checkCooldownAndBlock(player, mat, event)) {
            return;
        }
    }

    private boolean handleFireworkInCombat(PlayerInteractEvent event, Player player, ItemStack item) {
        if (item.getType() != Material.FIREWORK_ROCKET) return false;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return false;
        if (combatTagManager == null || !combatTagManager.isTagged(player.getUniqueId())) return false;
        if (isItemBlocked(item.getType(), player)) return false;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (config.getBoolean("blocker.fireworks.block-in-combat", false)) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            plugin.getTextService().sendMessage(player, "blocker.firework-blocked");
            return true;
        }

        int powerLimit = config.getInt("blocker.fireworks.power-limit", -1);
        if (powerLimit >= 0 && item.getItemMeta() instanceof FireworkMeta meta && meta.getPower() > powerLimit) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            plugin.getTextService().sendMessage(player, "blocker.firework-power-limited", Map.of("%power%", String.valueOf(meta.getPower())));
            return true;
        }

        if (player.isGliding()) {
            int elytraCooldown = config.getInt("blocker.fireworks.elytra-boost-cooldown", -1);
            int itemCooldown = shouldEnforceCooldowns(player) ? getConfiguredCooldown(Material.FIREWORK_ROCKET) : 0;
            int seconds = Math.max(elytraCooldown, itemCooldown);
            if (seconds > 0) {
                if (isOnCooldown(player.getUniqueId(), Material.FIREWORK_ROCKET)) {
                    event.setCancelled(true);
                    event.setUseItemInHand(Event.Result.DENY);
                    int remaining = getRemainingCooldown(player.getUniqueId(), Material.FIREWORK_ROCKET);
                    if (remaining > 0) {
                        player.setCooldown(Material.FIREWORK_ROCKET, remaining * 20);
                    }
                    return true;
                }
                plugin.getScheduler().runEntityLater(player, () -> {
                    if (player.isOnline()) {
                        applyCooldown(player, Material.FIREWORK_ROCKET, seconds);
                    }
                }, 1L);
                return true;
            }
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;

        Material mat = cooldownMaterialForProjectile(projectile);
        if (mat == null) return;

        checkCooldownAndBlock(player, mat, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobilityProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        Material mat = cooldownMaterialForProjectile(projectile);
        if (mat != null && projectile.getShooter() instanceof Player player) {
            List<String> customSafezones = plugin.getConfigService().getPvpConfig().getStringList("barriers.custom-safezones");
            if (combatTagManager.isTagged(player.getUniqueId())
                    && plugin.getHookManager().getProtectionHook().isPvpAllowed(player.getLocation(), player, customSafezones)) {
                combatTagManager.tagPlayer(player, null);
            }
            applyConfiguredCooldownNextTick(player, mat);
        }
    }

    private static Material cooldownMaterialForProjectile(Projectile projectile) {
        if (projectile instanceof EnderPearl) {
            return Material.ENDER_PEARL;
        }
        if (projectile instanceof WindCharge) {
            return Material.WIND_CHARGE;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!combatTagManager.isTagged(player.getUniqueId())) return;

        // Prevent equipping blocked items directly into armor slots
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            ItemStack movingItem = null;
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int hotbarSlot = event.getHotbarButton();
                if (hotbarSlot >= 0 && hotbarSlot < 9) {
                    movingItem = player.getInventory().getItem(hotbarSlot);
                }
            } else {
                movingItem = event.getCursor();
            }

            if (movingItem != null && movingItem.getType() != Material.AIR) {
                if (isItemBlocked(movingItem.getType(), player)) {
                    event.setCancelled(true);
                    resyncInventoryNextTick(player);
                    plugin.getTextService().sendMessage(player, "blocker.item-blocked", Map.of("%item%", formatName(movingItem.getType())));
                    return;
                }
            }
        }

        // Prevent shift-clicking wearable blocked items into armor slots
        if (event.isShiftClick()) {
            ItemStack currentItem = event.getCurrentItem();
            if (currentItem != null && currentItem.getType() != Material.AIR) {
                if (isWearableItem(currentItem.getType()) && isItemBlocked(currentItem.getType(), player)) {
                    if (canEquipIntoArmorSlot(player, currentItem.getType())) {
                        event.setCancelled(true);
                        resyncInventoryNextTick(player);
                        plugin.getTextService().sendMessage(player, "blocker.item-blocked", Map.of("%item%", formatName(currentItem.getType())));
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!combatTagManager.isTagged(player.getUniqueId())) return;

        ItemStack dragged = event.getOldCursor();
        if (dragged == null || dragged.getType() == Material.AIR) return;

        if (isItemBlocked(dragged.getType(), player)) {
            for (int rawSlot : event.getRawSlots()) {
                if (event.getView().getSlotType(rawSlot) == InventoryType.SlotType.ARMOR) {
                    event.setCancelled(true);
                    resyncInventoryNextTick(player);
                    plugin.getTextService().sendMessage(player, "blocker.item-blocked", Map.of("%item%", formatName(dragged.getType())));
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player)) return;
        if (!combatTagManager.isTagged(player.getUniqueId())) return;

        ItemStack item = event.getItem();
        if (item != null && isItemBlocked(item.getType(), player)) {
            event.setCancelled(true);
        }
    }

    private void resyncInventoryNextTick(Player player) {
        // Let the cancelled transaction finish before correcting the client's
        // predicted armor/cursor swap. Never restore captured items: doing so
        // could overwrite a later click or duplicate the elytra.
        plugin.getScheduler().runEntityLater(player, () -> {
            if (player.isOnline()) {
                player.updateInventory();
            }
        }, 1L);
    }

    private boolean isCrystalOrAnchorRestricted(Player player, Material material, Location targetLoc) {
        if (material != Material.END_CRYSTAL && material != Material.RESPAWN_ANCHOR) {
            return false;
        }
        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("explosions.prevent-safezone-bombing", true)) {
            return false;
        }

        int bufferRadius = Math.max(0, config.getInt("explosions.safezone-buffer-radius", 4));
        List<String> customSafezones = config.getStringList("barriers.custom-safezones");

        // Verify player is not attempting detonations from inside a safezone
        if (!plugin.getHookManager().getProtectionHook().isPvpAllowed(player.getLocation(), player, customSafezones)) {
            plugin.getTextService().sendMessage(player, "barriers.crystal-anchor-blocked-safezone");
            return true;
        }

        // Verify target explosion coordinates are not dangerously near a safezone boundary
        if (targetLoc != null) {
            if (plugin.getPvpManager().getDisplayBarrierManager().isNearSafezone(targetLoc, bufferRadius, customSafezones)) {
                plugin.getTextService().sendMessage(player, "barriers.crystal-anchor-blocked-safezone");
                return true;
            }
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material mat = event.getItem().getType();

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (config.getBoolean("blocker.block-eat", false)
                && combatTagManager.isTagged(player.getUniqueId())
                && event.getItem().getType().isEdible()) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, "blocker.eat-blocked");
            return;
        }

        if (checkCooldownAndBlock(player, mat, event)) {
            return;
        }

        applyConfiguredCooldownNextTick(player, mat);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (bow == null) return;

        Material mat = bow.getType();
        if (checkCooldownAndBlock(player, mat, event)) {
            return;
        }

        applyConfiguredCooldown(player, mat);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        Material mat = Material.TRIDENT;

        if (checkCooldownAndBlock(player, mat, null)) {
            player.setVelocity(player.getVelocity().multiply(0));
            return;
        }

        applyConfiguredCooldown(player, mat);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!combatTagManager.isTagged(player.getUniqueId())) return;
        PotionEffect newEffect = event.getNewEffect();
        if (newEffect == null) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (!config.getBoolean("blocker.enabled", true)) return;

        List<String> bannedPotions = config.getStringList("blocker.banned-potions");
        String effectName = newEffect.getType().getName();

        for (String banned : bannedPotions) {
            if (banned.equalsIgnoreCase(effectName)) {
                event.setCancelled(true);
                plugin.getTextService().sendMessage(player, "blocker.potion-blocked", Map.of("%potion%", effectName));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        FileConfiguration config = plugin.getConfigService().getPvpConfig();
        if (config.getBoolean("blocker.block-totem", false) && combatTagManager.isTagged(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.getTextService().sendMessage(player, "blocker.totem-blocked");
            return;
        }

        Material totemMat = Material.TOTEM_OF_UNDYING;
        if (checkCooldownAndBlock(player, totemMat, event)) {
            return;
        }

        applyConfiguredCooldown(player, totemMat);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Material mat = event.getBlockPlaced().getType();

        if (mat == Material.RESPAWN_ANCHOR) {
            if (isCrystalOrAnchorRestricted(player, mat, event.getBlockPlaced().getLocation())) {
                event.setCancelled(true);
                return;
            }
        }

        if (mat == Material.RESPAWN_ANCHOR || mat == Material.END_CRYSTAL || mat == Material.TNT) {
            checkCooldownAndBlock(player, mat, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        cooldownMap.remove(event.getPlayer().getUniqueId());
    }

    private String formatName(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String[] words = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    public void shutdown() {
        cooldownMap.clear();
    }
}
