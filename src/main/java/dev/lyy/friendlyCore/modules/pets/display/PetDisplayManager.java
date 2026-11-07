package dev.lyy.friendlycore.modules.pets.display;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.modules.pets.PetManager;
import dev.lyy.friendlycore.modules.pets.model.Pet;
import dev.lyy.friendlycore.modules.pets.model.PlayerPetData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import net.kyori.adventure.util.TriState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, Folia-compatible Pet Entity display manager and motion controller.
 * Ensures smooth following without unwanted spinning rotation, and seamless teleportation across worlds.
 */
public class PetDisplayManager {

    private static final double DEFAULT_EYE_HEIGHT = 1.62;
    private static final double MAX_Y_OFFSET = 0.5;
    private static final NamespacedKey COMPANION_KEY = new NamespacedKey("friendlycore", "pet_companion");
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final FriendlyCore plugin;
    private final PetManager petManager;
    private final Map<UUID, Map<String, PetDisplayEntity>> trackedEntities = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private boolean showHologram = true;
    private boolean nightGlow = true;
    private String hologramName = "&f%pet_short%";
    private double pairSpacing = 2.1;
    private double xOffset = 0.75;
    private double yOffset = 0.75;
    private double zOffset = 0.75;
    private boolean bobbing = true;
    private double bobbingIntensity = 0.15;
    private boolean rotation = false;
    private double rotationIntensity = 20.0;
    private float facingResponse = 0.65f;
    private float facingYawOffset = 180.0f;
    private boolean combatSafePositioning = true;
    private double combatBackOffset = 1.25;
    private int combatTeleportDuration = 2;
    private boolean cameraSafetyEnabled = true;
    private float cameraSafetyTurnThreshold = 35.0f;
    private int cameraSafetyHoldTicks = 4;
    private double cameraSafetyBackOffset = 1.75;
    private double cameraSafetyHeightBoost = 0.35;
    private int cameraSafetyTeleportDuration = 1;
    private double scale = 1.0;
    private boolean itemDisplayEnabled = true;
    private int teleportDuration = 2;
    private int movementInterpolationTicks = 2;
    private boolean playfulAnimations = true;
    private double playfulAnimationChance = 0.25;

    private long tickCounter = 0L;

    public PetDisplayManager(FriendlyCore plugin, PetManager petManager) {
        this.plugin = plugin;
        this.petManager = petManager;
        reloadSettings();
    }

    public void reloadSettings() {
        FileConfiguration config = petManager.getConfig();
        if (config == null) return;

        this.enabled = config.getBoolean("pet-entity.enabled", true);
        this.showHologram = config.getBoolean("pet-entity.show-hologram", true);
        this.nightGlow = config.getBoolean("pet-entity.night-glow", true);
        this.hologramName = config.getString("pet-entity.name", "&f%pet_short%");
        this.pairSpacing = Math.max(1.5, config.getDouble("pet-entity.pair-spacing", 2.1));
        this.xOffset = config.getDouble("pet-entity.location-x-offset", 0.75);
        this.yOffset = config.getDouble("pet-entity.location-y-offset", 0.75);
        this.zOffset = config.getDouble("pet-entity.location-z-offset", 0.75);
        this.bobbing = config.getBoolean("pet-entity.bobbing", true);
        this.bobbingIntensity = config.getDouble("pet-entity.bobbing-intensity", 0.15);
        this.rotation = config.getBoolean("pet-entity.rotation", false);
        this.rotationIntensity = config.getDouble("pet-entity.rotation-intensity", 20.0);
        this.facingResponse = (float) Math.max(0.05, Math.min(1.0,
                config.getDouble("pet-entity.facing-response", 0.65)));
        this.facingYawOffset = (float) config.getDouble("pet-entity.facing-yaw-offset", 180.0);
        this.combatSafePositioning = config.getBoolean("pet-entity.combat-positioning.enabled", true);
        this.combatBackOffset = Math.max(0.0,
                config.getDouble("pet-entity.combat-positioning.back-offset", 1.25));
        this.combatTeleportDuration = Math.clamp(
                config.getInt("pet-entity.combat-positioning.teleport-duration", 2), 2, 5);
        this.cameraSafetyEnabled = config.getBoolean("pet-entity.camera-safety.enabled", true);
        this.cameraSafetyTurnThreshold = (float) Math.max(1.0, Math.min(180.0,
                config.getDouble("pet-entity.camera-safety.sharp-turn-threshold", 35.0)));
        this.cameraSafetyHoldTicks = Math.max(1, Math.min(20,
                config.getInt("pet-entity.camera-safety.hold-ticks", 4)));
        this.cameraSafetyBackOffset = Math.max(0.0,
                config.getDouble("pet-entity.camera-safety.back-offset", 1.75));
        this.cameraSafetyHeightBoost = Math.max(0.0,
                config.getDouble("pet-entity.camera-safety.height-boost", 0.35));
        this.cameraSafetyTeleportDuration = Math.clamp(
                config.getInt("pet-entity.camera-safety.teleport-duration", 1), 1, 5);
        this.scale = Math.max(0.0625, Math.min(16.0, config.getDouble("pet-entity.scale", 1.0)));
        this.itemDisplayEnabled = config.getBoolean("pet-entity.item-display.enabled", true);
        this.teleportDuration = Math.max(0, Math.min(59, config.getInt("pet-entity.item-display.teleport-duration", 2)));
        this.movementInterpolationTicks = Math.clamp(config.getInt("pet-entity.item-display.movement-interpolation-ticks", 2), 2, 5);
        this.playfulAnimations = config.getBoolean("pet-entity.item-display.playful-animations.enabled", true);
        this.playfulAnimationChance = Math.max(0.0, Math.min(100.0, config.getDouble("pet-entity.item-display.playful-animations.chance", 25.0))) / 100.0;

        despawnAll();
    }

    /**
     * Called on each server tick (1t) to update pet entity positions and animations.
     */
    public void tick() {
        if (!enabled) {
            if (!trackedEntities.isEmpty()) {
                despawnAll();
            }
            return;
        }

        tickCounter++;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isValid() || !player.isOnline()) {
                despawn(player.getUniqueId());
                continue;
            }

            PlayerPetData data = petManager.getPlayerData(player.getUniqueId());
            if ((data == null || data.isHidePet() || data.getActivePet() == null)
                    && !trackedEntities.containsKey(player.getUniqueId())) {
                continue;
            }

            // Region safety: tick on player's region scheduler on Folia
            plugin.getScheduler().runEntity(player, () -> tickPlayer(player));
        }
    }

    private void tickPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline() || player.isDead() || player.isInvisible()) {
            despawn(player.getUniqueId());
            return;
        }

        PlayerPetData data = petManager.getPlayerData(player.getUniqueId());
        if (data == null || data.isHidePet() || data.getActivePet() == null) {
            despawn(player.getUniqueId());
            return;
        }

        Map<String, PetDisplayEntity> displays = trackedEntities.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        for (String id : displays.keySet()) {
            if (!data.isActivePet(id) || petManager.getPet(id) == null) despawn(player.getUniqueId(), id);
        }
        java.util.List<String> pets = data.getActivePets();
        double sideOffset = pets.size() > 1 ? Math.copySign(Math.max(pairSpacing / 2.0, Math.abs(xOffset)), xOffset) : xOffset;
        int slot = 0;
        for (String id : pets) {
            Pet pet = petManager.getPet(id);
            if (pet != null) tickPet(player, pet, slot == 0 ? sideOffset : -sideOffset);
            slot++;
        }
    }

    private void tickPet(Player player, Pet pet, double sideOffset) {
        Map<String, PetDisplayEntity> displays = trackedEntities.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        PetDisplayEntity tracked = displays.get(pet.getId());
        if (tracked == null || tracked.isDespawned() || tracked.getPet() != pet
                || (tracked.getEntity() != null && !tracked.getEntity().isValid())) {
            despawn(player.getUniqueId(), pet.getId());
            spawnTrackedPet(player, pet, sideOffset, displays);
            return;
        }

        final PetDisplayEntity activeTracked = tracked;
        Entity entity = activeTracked.getEntity();
        if (entity == null) return; // Spawn is queued on the destination's Folia region.
        if (!entity.isValid() || entity.isDead()) {
            despawn(player.getUniqueId(), pet.getId());
            return;
        }

        activeTracked.checkInFlightTimeout();

        // Seamless Cross-World & Large-Distance Teleport Catch (e.g. after /tp, /warp, /home, /rtp, portals)
        Location playerLoc = player.getLocation();
        if (activeTracked.shouldUpdateLighting(tickCounter)) {
            boolean bright = nightGlow && isNightTime(playerLoc.getWorld().getTime());
            plugin.getScheduler().runEntity(entity, () -> {
                if (!activeTracked.isDespawned() && entity.isValid()) applyNightLighting(entity, bright);
            });
        }
        // The companion can be in a different Folia region; use the cached successful target.
        if (activeTracked.isFarFrom(playerLoc, 576.0)) {
            despawn(player.getUniqueId(), pet.getId());
            spawnTrackedPet(player, pet, sideOffset, displays);
            return;
        }

        // Turning must use the current yaw; damping plus client interpolation doubles the visual lag.
        float playerYaw = playerLoc.getYaw();
        boolean ownerTurning = activeTracked.updateOwnerTurning(playerYaw);
        boolean cameraSafetyActive = cameraSafetyEnabled && activeTracked.updateCameraSafety(
                playerYaw,
                cameraSafetyTurnThreshold,
                cameraSafetyHoldTicks);
        float smoothYaw = !activeTracked.isInitializedYaw() || ownerTurning || cameraSafetyActive
                ? playerYaw : interpolateAngle(activeTracked.getSmoothYaw(), playerYaw, facingResponse);
        activeTracked.setSmoothYaw(smoothYaw);
        activeTracked.setInitializedYaw(true);
        double safetyBlend = activeTracked.smoothCameraSafety(cameraSafetyActive);

        double eyeY = player.getEyeLocation().getY();
        double baseY = playerLoc.getY() + DEFAULT_EYE_HEIGHT;
        double targetYOffset = pet.getEntityType() != null ? 0.0
                : Math.max(-MAX_Y_OFFSET, Math.min(MAX_Y_OFFSET, baseY - eyeY));

        double smoothY = activeTracked.getSmoothYOffset() + (targetYOffset - activeTracked.getSmoothYOffset()) * 0.15;
        activeTracked.setSmoothYOffset(smoothY);

        double safetyHeight = cameraSafetyHeightBoost * safetyBlend;
        Location target = playerLoc.clone().add(
                0.0,
                DEFAULT_EYE_HEIGHT + smoothY + pet.getEntityYOffset(yOffset) + safetyHeight,
                0.0);

        // Position follows the current view immediately so a fast turn can never
        // leave the pet hanging in front of the player's crosshair.
        boolean combatPositioning = combatSafePositioning && isCombatTagged(player);
        double backOffset = combatPositioning ? Math.max(zOffset, combatBackOffset) : zOffset;
        backOffset += Math.max(0.0, cameraSafetyBackOffset - backOffset) * safetyBlend;
        double[] offset = calculateFollowerOffset(playerYaw, sideOffset, backOffset);
        target.add(offset[0], 0.0, offset[1]);

        // Client movement packets can straddle ticks. Keep the motion state through short gaps.
        boolean ownerMoved = activeTracked.updateOwnerMotion(playerLoc);
        double bob = bobbing && ownerMoved ? Math.sin(tickCounter * 0.1) * bobbingIntensity : 0.0;
        target.add(0.0, activeTracked.smoothBobOffset(bob), 0.0);

        // Facing direction: Look in smoothed view direction (or spin if rotation=true)
        float desiredYaw = smoothYaw;
        if (rotation) {
            desiredYaw = (float) ((rotationIntensity * tickCounter) % 360.0);
        }
        desiredYaw = calculateDisplayYaw(desiredYaw, pet.getEntityType() != null ? 0.0f : facingYawOffset);
        target.setYaw(desiredYaw);
        target.setPitch(0.0f);

        // Follow each running tick; stationary pets retain the slower, change-only updates.
        long minInterval = ownerMoved ? 1L : 4L;
        boolean throttled = !cameraSafetyActive && !ownerTurning
                && (tickCounter - activeTracked.getLastTeleportTick() < minInterval);

        // Move on the entity scheduler; keep the client interpolation window stable when stopping.
        if (!throttled && (activeTracked.shouldTeleport(target, ownerMoved || ownerTurning)
                || activeTracked.shouldRotate(desiredYaw, ownerMoved || ownerTurning || rotation))) {
            long teleportTicket = activeTracked.tryStartTeleportTicket();
            if (teleportTicket == 0L) {
                return;
            }
            activeTracked.setLastTeleportTick(tickCounter);
            Runnable move = () -> {
                if (!entity.isValid() || activeTracked.isDespawned()) {
                    activeTracked.completeTeleport(teleportTicket);
                    return;
                }
                try {
                    if (entity instanceof ItemDisplay display) {
                        int duration = ownerTurning || cameraSafetyActive
                                ? cameraSafetyTeleportDuration
                                : (combatPositioning ? combatTeleportDuration : movementInterpolationTicks);
                        if (display.getTeleportDuration() != duration) {
                            display.setTeleportDuration(duration);
                        }
                    }
                    if (plugin.getScheduler().isFolia()) {
                        entity.teleportAsync(target).whenComplete((success, error) -> {
                            activeTracked.completeTeleport(
                                    teleportTicket, target, Boolean.TRUE.equals(success) && error == null);
                        });
                    } else {
                        // The follower's destination is beside an online owner in loaded chunks.
                        // Paper can move it now instead of waiting for an async teleport callback.
                        activeTracked.completeTeleport(teleportTicket, target, entity.teleport(target));
                    }
                } catch (Throwable t) {
                    activeTracked.completeTeleport(teleportTicket, target, false);
                }
            };
            if (plugin.getScheduler().isFolia() && Bukkit.isOwnedByCurrentRegion(entity)) {
                // The owner's tick already owns nearby companions; avoid another queued tick.
                move.run();
            } else {
                plugin.getScheduler().runEntity(entity, move, () -> activeTracked.completeTeleport(teleportTicket));
            }
        }
    }

    private void spawnTrackedPet(Player player, Pet pet, double sideOffset, Map<String, PetDisplayEntity> displays) {
        UUID uuid = player.getUniqueId();
        Location location = initialPetLocation(player, pet, sideOffset);
        PetDisplayEntity pending = new PetDisplayEntity(uuid, pet, null);
        displays.put(pet.getId(), pending);
        Runnable spawn = () -> {
            PlayerPetData data = petManager.getPlayerData(uuid);
            if (!enabled || trackedEntities.get(uuid) != displays || displays.get(pet.getId()) != pending
                    || data == null || data.isHidePet() || !data.isActivePet(pet.getId())) {
                displays.remove(pet.getId(), pending);
                return;
            }
            try {
                Entity entity = spawnPetEntity(location, pet, player);
                if (entity != null && entity.isValid()) {
                    applyNightLighting(entity, nightGlow && isNightTime(location.getWorld().getTime()));
                    pending.setEntity(entity);
                }
            } finally {
                if (pending.getEntity() == null) displays.remove(pet.getId(), pending);
            }
        };
        if (plugin.getScheduler().isFolia()) {
            // Offsets can cross a region boundary even while the owner stays in the same chunk.
            if (plugin.getScheduler().runLocation(location, spawn) == null) displays.remove(pet.getId(), pending);
        } else {
            spawn.run();
        }
    }

    private Entity spawnPetEntity(Location location, Pet pet, Player player) {
        if (location == null || location.getWorld() == null || pet == null) return null;

        String texture = pet.getEntityTexture();

        if (pet.getEntityType() != null) {
            return location.getWorld().spawn(location, pet.getEntityType().getEntityClass().asSubclass(Mob.class), mob -> {
                mob.getPersistentDataContainer().set(COMPANION_KEY, PersistentDataType.BYTE, (byte) 1);
                mob.setAI(false);
                mob.setAware(false);
                mob.setGravity(false);
                mob.setCollidable(false);
                mob.setInvulnerable(true);
                mob.setSilent(true);
                mob.setPersistent(false);
                mob.setRemoveWhenFarAway(false);
                mob.setCanPickupItems(false);
                mob.setDespawnInPeacefulOverride(TriState.FALSE);
                if (mob instanceof Allay allay) allay.setCanDuplicate(false);
                AttributeInstance scaleAttribute = mob.getAttribute(Attribute.SCALE);
                if (scaleAttribute != null) scaleAttribute.setBaseValue(pet.getEntityScale(scale));
                if (showHologram) {
                    mob.customName(plugin.getTextService().toComponent(formatHologramName(hologramName, player.getName(), pet)));
                }
                mob.setCustomNameVisible(showHologram);
            });
        }

        if (itemDisplayEnabled) {
            return spawnItemDisplay(location, pet, texture, player);
        } else {
            return spawnArmorStand(location, pet, texture, player);
        }
    }

    private ItemDisplay spawnItemDisplay(Location location, Pet pet, String texture, Player player) {
        ItemStack item;
        if (texture.startsWith("item:")) {
            String matName = texture.substring("item:".length()).trim().toUpperCase();
            Material mat = Material.matchMaterial(matName);
            item = new ItemStack(mat != null ? mat : Material.BARRIER);
        } else if (!texture.isBlank() && !texture.contains(":")) {
            item = new ItemBuilder(Material.PLAYER_HEAD).skullTexture(texture).build();
        } else {
            item = new ItemStack(Material.PLAYER_HEAD);
        }

        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(item);
            display.setItemDisplayTransform(item.getType() == Material.PLAYER_HEAD
                    ? ItemDisplay.ItemDisplayTransform.HEAD
                    : ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(Display.Billboard.FIXED);
            display.setPersistent(false);
            display.setInvulnerable(true);

            if (showHologram) {
                String formatted = formatHologramName(hologramName, player.getName(), pet);
                display.customName(plugin.getTextService().toComponent(formatted));
                display.setCustomNameVisible(true);
            } else {
                display.setCustomNameVisible(false);
            }

            display.setTeleportDuration(combatSafePositioning && isCombatTagged(player)
                    ? combatTeleportDuration
                    : movementInterpolationTicks);
            display.setInterpolationDuration(teleportDuration);

            float s = (float) pet.getEntityScale(scale);
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(s, s, s),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));
        });
    }

    private ArmorStand spawnArmorStand(Location location, Pet pet, String texture, Player player) {
        ItemStack helmet;
        if (texture.startsWith("item:")) {
            String matName = texture.substring("item:".length()).trim().toUpperCase();
            Material mat = Material.matchMaterial(matName);
            helmet = new ItemStack(mat != null ? mat : Material.BARRIER);
        } else if (!texture.isBlank() && !texture.contains(":")) {
            helmet = new ItemBuilder(Material.PLAYER_HEAD).skullTexture(texture).build();
        } else {
            helmet = new ItemStack(Material.PLAYER_HEAD);
        }

        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setInvulnerable(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setCollidable(false);
            stand.setPersistent(false);
            stand.setMarker(true);

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }

            stand.getEquipment().setHelmet(helmet);

            if (showHologram) {
                String formatted = formatHologramName(hologramName, player.getName(), pet);
                stand.customName(plugin.getTextService().toComponent(formatted));
                stand.setCustomNameVisible(true);
            } else {
                stand.setCustomNameVisible(false);
            }

            try {
                AttributeInstance scaleAttr = stand.getAttribute(Attribute.SCALE);
                if (scaleAttr != null) {
                    scaleAttr.setBaseValue(pet.getEntityScale(scale));
                }
            } catch (Throwable ignored) {}
        });
    }

    public void refreshPlayerPet(Player player) {
        if (player != null && enabled) tickPlayer(player);
    }

    private void despawn(UUID playerUuid, String petId) {
        Map<String, PetDisplayEntity> displays = trackedEntities.get(playerUuid);
        PetDisplayEntity tracked = displays == null ? null : displays.remove(petId);
        if (tracked != null) tracked.remove();
    }

    public void despawn(UUID playerUuid) {
        if (playerUuid == null) return;
        Map<String, PetDisplayEntity> displays = trackedEntities.remove(playerUuid);
        if (displays != null) displays.values().forEach(PetDisplayEntity::remove);
    }

    public void despawnAll() {
        for (UUID uuid : trackedEntities.keySet()) despawn(uuid);
    }

    public PetDisplayEntity getTracked(UUID playerUuid) {
        PlayerPetData data = petManager.getPlayerData(playerUuid);
        return data == null ? null : getTracked(playerUuid, data.getActivePet());
    }

    public PetDisplayEntity getTracked(UUID playerUuid, String petId) {
        Map<String, PetDisplayEntity> displays = trackedEntities.get(playerUuid);
        return displays == null || petId == null ? null : displays.get(petId);
    }

    /**
     * Interpolates between two angles in degrees taking the shortest angular path.
     */
    public static float interpolateAngle(float from, float to, float alpha) {
        float diff = ((to - from + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f;
        return from + diff * alpha;
    }

    public static float normalizeAngle(float angle) {
        return ((angle % 360.0f) + 360.0f) % 360.0f;
    }

    public static float calculateDisplayYaw(float movementYaw, float modelYawOffset) {
        return normalizeAngle(movementYaw + modelYawOffset);
    }

    public static String formatHologramName(String format, String owner, Pet pet) {
        // Upgrade the old shipped label; custom owner/name formats stay available.
        if (format.equals("%player%&f's %pet%")) format = "&f%pet_short%";
        return format.replace("%player%", owner).replace("%pet_short%", pet.getDisplayName()).replace("%pet%", pet.getName());
    }

    public static boolean isPetEntity(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(COMPANION_KEY, PersistentDataType.BYTE);
    }

    public static boolean isNightTime(long worldTime) {
        long time = Math.floorMod(worldTime, 24000L);
        return time >= 12300L && time < 23850L;
    }

    /** Visual brightness only: never changes world blocks or the server light engine. */
    public static void applyNightLighting(Entity entity, boolean bright) {
        if (entity instanceof Display display) {
            Display.Brightness desired = bright ? FULL_BRIGHT : null;
            if (!java.util.Objects.equals(display.getBrightness(), desired)) display.setBrightness(desired);
        } else if (entity.isGlowing() != bright) {
            entity.setGlowing(bright);
        }
    }

    public static double[] calculateFollowerOffset(float playerYaw, double xOffset, double zOffset) {
        double radians = Math.toRadians(playerYaw);
        double sinYaw = Math.sin(radians);
        double cosYaw = Math.cos(radians);
        return new double[]{
                -cosYaw * xOffset + sinYaw * zOffset,
                -sinYaw * xOffset - cosYaw * zOffset
        };
    }

    private Location initialPetLocation(Player player, Pet pet, double sideOffset) {
        Location location = player.getLocation().clone().add(0.0, DEFAULT_EYE_HEIGHT + pet.getEntityYOffset(yOffset), 0.0);
        boolean combatPositioning = combatSafePositioning && isCombatTagged(player);
        double backOffset = combatPositioning ? Math.max(zOffset, combatBackOffset) : zOffset;
        double[] offset = calculateFollowerOffset(location.getYaw(), sideOffset, backOffset);
        location.add(offset[0], 0.0, offset[1]);
        location.setYaw(calculateDisplayYaw(location.getYaw(), pet.getEntityType() != null ? 0.0f : facingYawOffset));
        location.setPitch(0.0f);
        return location;
    }

    private boolean isCombatTagged(Player player) {
        return plugin.getPvpManager() != null
                && plugin.getPvpManager().getCombatTagManager() != null
                && plugin.getPvpManager().getCombatTagManager().isTagged(player.getUniqueId());
    }
}
