package dev.lyy.friendlycore.modules.pets.effects;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/**
 * Encapsulates an individual pet effect configuration and execution parameters.
 */
public class PetEffect {

    private final String id;
    private final Set<PetTrigger> triggers;
    private final List<PetCondition> conditions;
    private final Map<String, Object> args;

    public PetEffect(String id, Set<PetTrigger> triggers, List<PetCondition> conditions, Map<String, Object> args) {
        this.id = id != null ? id.toLowerCase().trim() : "";
        this.triggers = (triggers != null && !triggers.isEmpty()) ? EnumSet.copyOf(triggers) : EnumSet.of(getDefaultTriggerForEffect(this.id));
        this.conditions = conditions != null ? new ArrayList<>(conditions) : Collections.emptyList();
        this.args = args != null ? new HashMap<>(args) : Collections.emptyMap();
    }

    public static PetEffect fromConfig(ConfigurationSection section) {
        if (section == null) return null;
        String id = section.getString("id", "");
        if (id.isBlank()) return null;

        Set<PetTrigger> triggers = EnumSet.noneOf(PetTrigger.class);
        List<String> trigList = section.getStringList("triggers");
        if (trigList.isEmpty() && section.contains("trigger")) {
            trigList = List.of(section.getString("trigger"));
        }
        for (String t : trigList) {
            triggers.add(PetTrigger.fromString(t));
        }
        if (triggers.isEmpty()) {
            triggers.add(getDefaultTriggerForEffect(id));
        }

        List<PetCondition> conditions = new ArrayList<>();
        if (section.isList("conditions")) {
            List<?> rawList = section.getList("conditions");
            if (rawList != null) {
                for (Object o : rawList) {
                    if (o instanceof Map<?, ?> map) {
                        String cId = String.valueOf(map.get("id"));
                        boolean inv = map.get("inverted") != null && Boolean.parseBoolean(String.valueOf(map.get("inverted")));
                        Map<String, Object> cArgs = new HashMap<>();
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            cArgs.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        conditions.add(new PetCondition(cId, inv, cArgs));
                    } else if (o instanceof String str) {
                        PetCondition cond = PetCondition.fromString(str);
                        if (cond != null) conditions.add(cond);
                    }
                }
            }
        } else if (section.isConfigurationSection("conditions")) {
            ConfigurationSection condSec = section.getConfigurationSection("conditions");
            for (String key : condSec.getKeys(false)) {
                if (condSec.isConfigurationSection(key)) {
                    PetCondition cond = PetCondition.fromConfig(condSec.getConfigurationSection(key));
                    if (cond != null) conditions.add(cond);
                }
            }
        }

        Map<String, Object> args = new HashMap<>();
        ConfigurationSection argsSec = section.getConfigurationSection("args");
        if (argsSec != null) {
            for (String key : argsSec.getKeys(false)) {
                args.put(key, argsSec.get(key));
            }
        }
        for (String key : section.getKeys(false)) {
            if (!key.equals("id") && !key.equals("triggers") && !key.equals("trigger") && !key.equals("conditions") && !key.equals("args")) {
                args.put(key, section.get(key));
            }
        }

        return new PetEffect(id, triggers, conditions, args);
    }

    private static PetTrigger getDefaultTriggerForEffect(String id) {
        String s = id.toLowerCase();
        if (s.contains("bow") || s.contains("arrow") || s.contains("projectile")) {
            return PetTrigger.BOW_ATTACK;
        }
        if (s.contains("take_damage") || s.contains("defense") || s.contains("resistance") || s.equals("damage_reduction")) {
            return PetTrigger.TAKE_DAMAGE;
        }
        if (s.contains("attack") || s.contains("ignite") || s.contains("lifesteal") || s.contains("bleed") || s.contains("give_health")) {
            return PetTrigger.MELEE_ATTACK;
        }
        if (s.contains("kill") || s.contains("mob")) {
            return PetTrigger.KILL;
        }
        return PetTrigger.PASSIVE;
    }

    public boolean hasTrigger(PetTrigger trigger) {
        return triggers.contains(trigger);
    }

    public String getId() {
        return id;
    }

    public Set<PetTrigger> getTriggers() {
        return Collections.unmodifiableSet(triggers);
    }

    public List<PetCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public String getStringArg(String key) {
        Object val = args.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    public double getDoubleArg(String key, double def) {
        Object val = args.get(key);
        if (val == null) return def;
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        return parseDoubleExpression(String.valueOf(val), def);
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

    /**
     * Evaluates simple expressions or formulas like "1 + 0.25", "10 / 2", or "%level% * 0.01 + 1" (with level=1).
     */
    public static double parseDoubleExpression(String expr, double def) {
        if (expr == null || expr.isBlank()) return def;
        try {
            String sanitized = expr.replace("%level%", "1").replace(" ", "");
            return evaluateSimpleMath(sanitized);
        } catch (Exception e) {
            return def;
        }
    }

    private static double evaluateSimpleMath(String expr) {
        try {
            return Double.parseDouble(expr);
        } catch (NumberFormatException ignored) {}

        // Simple tokenizer for basic operations (+, -, *, /)
        try {
            return new Object() {
                int pos = -1, ch;

                void nextChar() {
                    ch = (++pos < expr.length()) ? expr.charAt(pos) : -1;
                }

                boolean eat(int charToEat) {
                    while (ch == ' ') nextChar();
                    if (ch == charToEat) {
                        nextChar();
                        return true;
                    }
                    return false;
                }

                double parse() {
                    nextChar();
                    double x = parseExpression();
                    if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                    return x;
                }

                double parseExpression() {
                    double x = parseTerm();
                    for (;;) {
                        if (eat('+')) x += parseTerm();
                        else if (eat('-')) x -= parseTerm();
                        else return x;
                    }
                }

                double parseTerm() {
                    double x = parseFactor();
                    for (;;) {
                        if (eat('*')) x *= parseFactor();
                        else if (eat('/')) {
                            double divisor = parseFactor();
                            x = divisor == 0 ? 0 : x / divisor;
                        } else return x;
                    }
                }

                double parseFactor() {
                    if (eat('+')) return parseFactor();
                    if (eat('-')) return -parseFactor();
                    double x;
                    int startPos = this.pos;
                    if (eat('(')) {
                        x = parseExpression();
                        eat(')');
                    } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                        while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                        x = Double.parseDouble(expr.substring(startPos, this.pos));
                    } else {
                        throw new RuntimeException("Unexpected: " + (char) ch);
                    }
                    return x;
                }
            }.parse();
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
