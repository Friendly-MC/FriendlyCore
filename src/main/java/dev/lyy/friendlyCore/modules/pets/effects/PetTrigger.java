package dev.lyy.friendlycore.modules.pets.effects;

/**
 * Triggers that execute pet effects.
 */
public enum PetTrigger {
    MELEE_ATTACK,
    BOW_ATTACK,
    TAKE_DAMAGE,
    KILL,
    MOVE,
    WIN_RAID,
    PASSIVE;

    public static PetTrigger fromString(String str) {
        if (str == null || str.isBlank()) return PASSIVE;
        String s = str.trim().toUpperCase().replace("-", "_");
        switch (s) {
            case "MELEE_ATTACK":
            case "MELEE":
            case "ATTACK":
                return MELEE_ATTACK;
            case "BOW_ATTACK":
            case "PROJECTILE_ATTACK":
            case "BOW":
            case "RANGED":
                return BOW_ATTACK;
            case "TAKE_DAMAGE":
            case "DAMAGE_TAKEN":
            case "HURT":
                return TAKE_DAMAGE;
            case "KILL":
            case "KILL_ENTITY":
            case "KILL_MOB":
            case "DEATH":
                return KILL;
            case "MOVE":
            case "MOVEMENT":
            case "SPRINT":
            case "SWIM":
                return MOVE;
            case "WIN_RAID":
            case "RAID_WIN":
            case "RAID_VICTORY":
                return WIN_RAID;
            case "PASSIVE":
            case "STATIC":
            case "TICK":
            default:
                return PASSIVE;
        }
    }
}
