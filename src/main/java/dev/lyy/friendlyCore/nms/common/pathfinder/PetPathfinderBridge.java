package dev.lyy.friendlycore.nms.common.pathfinder;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Direct NMS pathfinding goal injector for pet companions and combat NPCs.
 */
public interface PetPathfinderBridge {

    /**
     * Injects follow-owner and teleportation AI goals into the entity.
     */
    void setFollowOwnerGoal(Mob mob, Player owner, double speed, float startDistance, float stopDistance);

    /**
     * Clears all default vanilla AI goals (e.g. panic, wander, attack) from the mob.
     */
    void clearVanillaGoals(Mob mob);

    /**
     * Commands the entity pathfinder to navigate directly to a target coordinate.
     */
    boolean navigateTo(Mob mob, Location location, double speed);
}
