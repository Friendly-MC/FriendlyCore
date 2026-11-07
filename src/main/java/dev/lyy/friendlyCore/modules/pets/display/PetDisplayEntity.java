package dev.lyy.friendlycore.modules.pets.display;

import dev.lyy.friendlycore.modules.pets.model.Pet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the live spawned entity and animation/smoothing state for an active pet.
 */
public class PetDisplayEntity {

    private static final int MAX_IN_FLIGHT_UPDATES = 20;
    public static final double STATIONARY_TELEPORT_THRESHOLD_SQ = 0.0064;
    public static final double MOVING_TELEPORT_THRESHOLD_SQ = 0.0001;
    public static final float MOVING_ROTATION_THRESHOLD = 2.0f;
    public static final float STATIONARY_ROTATION_THRESHOLD = 15.0f;

    private final UUID playerUuid;
    private final Pet pet;
    private volatile Entity entity;
    private double smoothYOffset = 0.0;
    private float smoothYaw = 0.0f;
    private boolean initializedYaw = false;
    private boolean ownerMotionInitialized = false;
    private int ownerMovingTicks = 0;
    private double bobOffset = 0.0;
    private float lastOwnerYaw = 0.0f;
    private float lastTurningYaw = Float.NaN;
    private int turningTicks = 0;
    private volatile double lastOwnerX = Double.NaN;
    private volatile double lastOwnerY = Double.NaN;
    private volatile double lastOwnerZ = Double.NaN;
    private volatile long lastTeleportTick = 0L;
    private long lastLightingTick = 0L;
    private int cameraSafetyTicks = 0;
    private double cameraSafetyBlend = 0.0;
    private volatile World lastWorld;
    private volatile double lastX = 0.0;
    private volatile double lastY = 0.0;
    private volatile double lastZ = 0.0;
    private volatile float lastYaw = 0.0f;
    private volatile float lastPitch = 0.0f;
    private volatile boolean despawned = false;
    private final AtomicLong activeTeleportTicket = new AtomicLong(0L);
    private final AtomicLong teleportSequence = new AtomicLong(0L);
    private int inFlightTicks = 0;

    private double appliedScaleXZ = 1.0;
    private double appliedScaleY = 1.0;
    private double appliedPitch = 0.0;
    private double appliedRoll = 0.0;

    public PetDisplayEntity(UUID playerUuid, Pet pet, Entity entity) {
        this.playerUuid = playerUuid;
        this.pet = pet;
        this.entity = entity;
        if (entity != null) {
            Location loc = entity.getLocation();
            this.lastWorld = loc.getWorld();
            this.lastX = loc.getX();
            this.lastY = loc.getY();
            this.lastZ = loc.getZ();
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
        }
    }

    /**
     * Detects abrupt camera rotation and briefly increases camera clearance.
     * Vertical movement deliberately does not trigger this
     * mode, allowing running jumps to remain smoothly interpolated.
     */
    public synchronized boolean updateCameraSafety(float ownerYaw,
                                                    float sharpTurnThreshold,
                                                    int holdTicks) {
        if (!ownerMotionInitialized) {
            ownerMotionInitialized = true;
            lastOwnerYaw = ownerYaw;
            return false;
        }

        float yawDelta = Math.abs(((ownerYaw - lastOwnerYaw + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f);
        lastOwnerYaw = ownerYaw;

        if (yawDelta >= sharpTurnThreshold) {
            cameraSafetyTicks = Math.max(cameraSafetyTicks, Math.max(1, holdTicks));
        }

        boolean active = cameraSafetyTicks > 0;
        if (active) {
            cameraSafetyTicks--;
        }
        return active;
    }

    public boolean isInFlightTeleport() {
        return activeTeleportTicket.get() != 0L;
    }

    public synchronized void setInFlightTeleport(boolean inFlight) {
        if (inFlight) {
            tryStartTeleportTicket();
        } else {
            activeTeleportTicket.set(0L);
            this.inFlightTicks = 0;
        }
    }

    public boolean tryStartTeleport() {
        return tryStartTeleportTicket() != 0L;
    }

    public synchronized long tryStartTeleportTicket() {
        long ticket = teleportSequence.incrementAndGet();
        return activeTeleportTicket.compareAndSet(0L, ticket) ? ticket : 0L;
    }

    public synchronized boolean completeTeleport(long ticket) {
        return completeTeleport(ticket, null, false);
    }

    public synchronized boolean completeTeleport(long ticket, Location target, boolean success) {
        if (ticket == 0L || !activeTeleportTicket.compareAndSet(ticket, 0L)) {
            return false;
        }
        if (success && target != null) {
            recordTarget(target);
        } else if (target != null) {
            invalidateTarget();
        }
        inFlightTicks = 0;
        return true;
    }

    public synchronized boolean checkInFlightTimeout() {
        long ticket = activeTeleportTicket.get();
        if (ticket == 0L) {
            inFlightTicks = 0;
            return false;
        }
        Entity ent = this.entity;
        if (ent == null || !ent.isValid() || despawned || ++inFlightTicks >= MAX_IN_FLIGHT_UPDATES) {
            activeTeleportTicket.compareAndSet(ticket, 0L);
            inFlightTicks = 0;
            return true;
        }
        return false;
    }

    public boolean updateOwnerMotion(Location ownerLoc) {
        if (hasOwnerMoved(ownerLoc)) {
            recordOwnerLocation(ownerLoc);
            ownerMovingTicks = 3;
        } else if (ownerMovingTicks > 0) {
            ownerMovingTicks--;
        }
        return ownerMovingTicks > 0;
    }

    public boolean updateOwnerTurning(float yaw) {
        float delta = Math.abs(((yaw - lastTurningYaw + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f);
        lastTurningYaw = yaw;
        if (delta >= 0.5f) turningTicks = 2;
        else if (turningTicks > 0) turningTicks--;
        return turningTicks > 0;
    }

    public double smoothBobOffset(double desired) {
        bobOffset += (desired - bobOffset) * 0.35;
        return bobOffset;
    }

    public double smoothCameraSafety(boolean active) {
        cameraSafetyBlend += ((active ? 1.0 : 0.0) - cameraSafetyBlend) * 0.35;
        return cameraSafetyBlend;
    }

    public boolean hasOwnerMoved(Location ownerLoc) {
        if (ownerLoc == null) return false;
        if (Double.isNaN(lastOwnerX)) {
            recordOwnerLocation(ownerLoc);
            return true;
        }
        double dx = ownerLoc.getX() - lastOwnerX;
        double dy = ownerLoc.getY() - lastOwnerY;
        double dz = ownerLoc.getZ() - lastOwnerZ;
        return dx * dx + dy * dy + dz * dz > 0.0016;
    }

    public void recordOwnerLocation(Location ownerLoc) {
        if (ownerLoc == null) return;
        lastOwnerX = ownerLoc.getX();
        lastOwnerY = ownerLoc.getY();
        lastOwnerZ = ownerLoc.getZ();
    }

    public boolean shouldTeleport(Location target, boolean ownerMoved) {
        if (target == null || target.getWorld() == null) return false;
        if (lastWorld == null || !lastWorld.equals(target.getWorld())) {
            return true;
        }
        double dx = target.getX() - lastX;
        double dy = target.getY() - lastY;
        double dz = target.getZ() - lastZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        return ownerMoved ? distSq > MOVING_TELEPORT_THRESHOLD_SQ : distSq > STATIONARY_TELEPORT_THRESHOLD_SQ;
    }

    public boolean shouldTeleport(Location target) {
        return shouldTeleport(target, true);
    }

    public synchronized boolean isFarFrom(Location owner, double maxDistanceSquared) {
        if (lastWorld == null || !lastWorld.equals(owner.getWorld())) return true;
        double dx = owner.getX() - lastX;
        double dy = owner.getY() - lastY;
        double dz = owner.getZ() - lastZ;
        return dx * dx + dy * dy + dz * dz > maxDistanceSquared;
    }

    public boolean shouldRotate(float desiredYaw, boolean ownerMoved) {
        float diff = Math.abs(((desiredYaw - lastYaw + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f);
        return ownerMoved ? diff > MOVING_ROTATION_THRESHOLD : diff > STATIONARY_ROTATION_THRESHOLD;
    }

    public boolean shouldRotate(float desiredYaw) {
        return shouldRotate(desiredYaw, true);
    }

    public long getLastTeleportTick() {
        return lastTeleportTick;
    }

    public boolean shouldUpdateLighting(long tick) {
        if (tick - lastLightingTick < 20L) return false;
        lastLightingTick = tick;
        return true;
    }

    public void setLastTeleportTick(long lastTeleportTick) {
        this.lastTeleportTick = lastTeleportTick;
    }

    public synchronized void recordTarget(Location target) {
        if (target == null || target.getWorld() == null) return;
        lastWorld = target.getWorld();
        lastX = target.getX();
        lastY = target.getY();
        lastZ = target.getZ();
        lastYaw = target.getYaw();
        lastPitch = target.getPitch();
    }

    public synchronized void invalidateTarget() {
        lastWorld = null;
    }

    public synchronized void remove() {
        this.despawned = true;
        this.activeTeleportTicket.set(0L);
        this.inFlightTicks = 0;
        Entity ent = this.entity;
        this.entity = null;
        if (ent != null && ent.isValid()) {
            try {
                ent.getScheduler().run(
                        dev.lyy.friendlycore.FriendlyCore.getInstance(),
                        task -> ent.remove(),
                        null
                );
            } catch (Throwable t) {
                try {
                    ent.remove();
                } catch (Throwable ignored) {}
            }
        }
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Pet getPet() {
        return pet;
    }

    public Entity getEntity() {
        return entity;
    }

    public synchronized void setEntity(Entity entity) {
        if (despawned) {
            // A queued region spawn may complete after unequip, reload or owner teleport.
            if (entity != null) entity.remove();
            return;
        }
        if (entity != null) recordTarget(entity.getLocation());
        this.entity = entity;
    }

    public double getSmoothYOffset() {
        return smoothYOffset;
    }

    public void setSmoothYOffset(double smoothYOffset) {
        this.smoothYOffset = smoothYOffset;
    }

    public float getSmoothYaw() {
        return smoothYaw;
    }

    public void setSmoothYaw(float smoothYaw) {
        this.smoothYaw = smoothYaw;
    }

    public boolean isInitializedYaw() {
        return initializedYaw;
    }

    public void setInitializedYaw(boolean initializedYaw) {
        this.initializedYaw = initializedYaw;
    }

    public boolean isDespawned() {
        return despawned;
    }

    public double getAppliedScaleXZ() {
        return appliedScaleXZ;
    }

    public void setAppliedScaleXZ(double appliedScaleXZ) {
        this.appliedScaleXZ = appliedScaleXZ;
    }

    public double getAppliedScaleY() {
        return appliedScaleY;
    }

    public void setAppliedScaleY(double appliedScaleY) {
        this.appliedScaleY = appliedScaleY;
    }

    public double getAppliedPitch() {
        return appliedPitch;
    }

    public void setAppliedPitch(double appliedPitch) {
        this.appliedPitch = appliedPitch;
    }

    public double getAppliedRoll() {
        return appliedRoll;
    }

    public void setAppliedRoll(double appliedRoll) {
        this.appliedRoll = appliedRoll;
    }
}
