package dev.lyy.friendlycore.modules.pets.effects;

import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Condition evaluator for pet activation and trigger execution.
 */
public class PetCondition {

    private final String id;
    private final boolean inverted;
    private final Map<String, Object> args;

    public PetCondition(String id, boolean inverted, Map<String, Object> args) {
        this.id = id != null ? id.toLowerCase().trim() : "";
        this.inverted = inverted;
        this.args = args != null ? new HashMap<>(args) : Collections.emptyMap();
    }

    public static PetCondition fromConfig(ConfigurationSection section) {
        if (section == null) return null;
        String id = section.getString("id", "");
        if (id.isBlank()) return null;
        boolean inverted = section.getBoolean("inverted", false) || section.getBoolean("not", false);
        Map<String, Object> args = new HashMap<>();
        ConfigurationSection argsSec = section.getConfigurationSection("args");
        if (argsSec != null) {
            for (String key : argsSec.getKeys(false)) {
                args.put(key, argsSec.get(key));
            }
        }
        for (String key : section.getKeys(false)) {
            if (!key.equals("id") && !key.equals("inverted") && !key.equals("not") && !key.equals("args")) {
                args.put(key, section.get(key));
            }
        }
        return new PetCondition(id, inverted, args);
    }

    public static PetCondition fromString(String shorthand) {
        if (shorthand == null || shorthand.isBlank()) return null;
        String trimmed = shorthand.trim();
        boolean not = false;
        if (trimmed.startsWith("!")) {
            not = true;
            trimmed = trimmed.substring(1).trim();
        }
        return new PetCondition(trimmed, not, Collections.emptyMap());
    }

    public boolean test(Player player) {
        if (player == null || !player.isOnline()) return false;
        boolean result = evaluate(player);
        return inverted ? !result : result;
    }

    private boolean evaluate(Player player) {
        switch (id) {
            case "in_water":
                return player.isInWater() || player.isSwimming();
            case "in_rain":
                return player.isInRain();
            case "in_lava":
                return player.isInLava();
            case "is_sprinting":
                return player.isSprinting();
            case "is_sneaking":
                return player.isSneaking();
            case "is_flying":
                return player.isFlying();
            case "is_gliding":
                return player.isGliding();
            case "on_fire":
                return player.getFireTicks() > 0;
            case "is_night": {
                World world = player.getWorld();
                long time = world.getTime();
                return time >= 13000 && time <= 23000;
            }
            case "is_day": {
                World world = player.getWorld();
                long time = world.getTime();
                return time < 13000 || time > 23000;
            }
            case "is_stormy": {
                World world = player.getWorld();
                return world.hasStorm();
            }
            case "is_thundering": {
                World world = player.getWorld();
                return world.isThundering();
            }
            case "has_permission": {
                String perm = getStringArg("permission");
                if (perm == null) perm = getStringArg("value");
                return perm == null || player.hasPermission(perm);
            }
            case "in_world": {
                String world = getStringArg("world");
                if (world == null) world = getStringArg("value");
                return world != null && player.getWorld().getName().equalsIgnoreCase(world);
            }
            case "health_below": {
                double target = getDoubleArg("health", 10.0);
                return player.getHealth() < target;
            }
            case "health_above": {
                double target = getDoubleArg("health", 10.0);
                return player.getHealth() > target;
            }
            case "health_percent_below": {
                double target = getDoubleArg("percent", 50.0);
                AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                return (player.getHealth() / maxHealth) * 100.0 < target;
            }
            default:
                return true;
        }
    }

    public String getStringArg(String key) {
        Object val = args.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    public double getDoubleArg(String key, double def) {
        Object val = args.get(key);
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        if (val instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public int getIntArg(String key, int def) {
        return (int) Math.round(getDoubleArg(key, def));
    }

    public boolean getBooleanArg(String key, boolean def) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    public String getId() {
        return id;
    }

    public boolean isInverted() {
        return inverted;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PetCondition that)) return false;
        return inverted == that.inverted && Objects.equals(id, that.id) && Objects.equals(args, that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, inverted, args);
    }
}
