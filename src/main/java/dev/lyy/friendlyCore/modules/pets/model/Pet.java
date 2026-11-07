package dev.lyy.friendlycore.modules.pets.model;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.common.ItemBuilder;
import dev.lyy.friendlycore.modules.pets.effects.PetCondition;
import dev.lyy.friendlycore.modules.pets.effects.PetEffect;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Represents a registered pet definition.
 */
public class Pet {

    public static final NamespacedKey PET_EGG_KEY = new NamespacedKey("friendlycore", "pet_egg");

    private final String id;
    private final String name;
    private String displayName;
    private final String description;
    private final String iconString;
    private final String entityTexture;
    private EntityType entityType;
    private Double entityScale;
    private Double entityYOffset;
    private final List<String> effectsDescription;
    private final PetSpawnEggConfig spawnEggConfig;
    private final List<PetEffect> effects;
    private final List<PetCondition> conditions;
    private final List<PetCondition> activateConditions;

    public Pet(String id, String name, String description, String iconString, String entityTexture,
               List<String> effectsDescription, PetSpawnEggConfig spawnEggConfig,
               List<PetEffect> effects, List<PetCondition> conditions, List<PetCondition> activateConditions) {
        this.id = id != null ? id.toLowerCase().trim() : "";
        this.name = name != null ? name : id;
        this.displayName = this.name;
        this.description = description != null ? description : "";
        this.iconString = iconString != null ? iconString : "player_head";
        this.entityTexture = entityTexture != null ? entityTexture : "";
        this.effectsDescription = effectsDescription != null ? new ArrayList<>(effectsDescription) : Collections.emptyList();
        this.spawnEggConfig = spawnEggConfig != null ? spawnEggConfig : PetSpawnEggConfig.fromConfig(null);
        this.effects = effects != null ? new ArrayList<>(effects) : Collections.emptyList();
        this.conditions = conditions != null ? new ArrayList<>(conditions) : Collections.emptyList();
        this.activateConditions = activateConditions != null ? new ArrayList<>(activateConditions) : Collections.emptyList();
    }

    public static Pet fromConfig(String id, FileConfiguration config) {
        if (config == null) return null;

        String name = config.getString("name", id);
        String description = config.getString("description", "");
        String iconString = config.getString("icon", "player_head");
        String entityTexture = config.getString("entity-texture", "");

        List<String> effectsDesc = new ArrayList<>();
        if (config.isList("effects-description")) {
            effectsDesc = config.getStringList("effects-description");
        } else if (config.isConfigurationSection("effects-description")) {
            ConfigurationSection sec = config.getConfigurationSection("effects-description");
            for (String k : sec.getKeys(false)) {
                if (sec.isList(k)) {
                    effectsDesc.addAll(sec.getStringList(k));
                } else if (sec.isString(k)) {
                    effectsDesc.add(sec.getString(k));
                }
            }
        }

        PetSpawnEggConfig spawnEgg = PetSpawnEggConfig.fromConfig(config.getConfigurationSection("spawn-egg"));

        List<PetEffect> effects = new ArrayList<>();
        if (config.isList("effects")) {
            List<Map<?, ?>> rawList = config.getMapList("effects");
            for (Map<?, ?> map : rawList) {
                // Construct a temporary dummy section or direct parse
                String effId = String.valueOf(map.get("id"));
                if (effId.isBlank() || effId.equals("null")) continue;

                Set<dev.lyy.friendlycore.modules.pets.effects.PetTrigger> triggers = EnumSet.noneOf(dev.lyy.friendlycore.modules.pets.effects.PetTrigger.class);
                Object trigs = map.get("triggers");
                if (trigs instanceof List<?> tl) {
                    for (Object t : tl) {
                        triggers.add(dev.lyy.friendlycore.modules.pets.effects.PetTrigger.fromString(String.valueOf(t)));
                    }
                } else if (map.containsKey("trigger")) {
                    triggers.add(dev.lyy.friendlycore.modules.pets.effects.PetTrigger.fromString(String.valueOf(map.get("trigger"))));
                }

                List<PetCondition> conds = new ArrayList<>();
                Object condObj = map.get("conditions");
                if (condObj instanceof List<?> cl) {
                    for (Object c : cl) {
                        if (c instanceof Map<?, ?> cm) {
                            String cId = String.valueOf(cm.get("id"));
                            boolean inv = cm.get("inverted") != null && Boolean.parseBoolean(String.valueOf(cm.get("inverted")));
                            Map<String, Object> cArgs = new HashMap<>();
                            for (Map.Entry<?, ?> e : cm.entrySet()) {
                                cArgs.put(String.valueOf(e.getKey()), e.getValue());
                            }
                            conds.add(new PetCondition(cId, inv, cArgs));
                        } else if (c instanceof String cs) {
                            PetCondition pc = PetCondition.fromString(cs);
                            if (pc != null) conds.add(pc);
                        }
                    }
                }

                Map<String, Object> args = new HashMap<>();
                Object argsObj = map.get("args");
                if (argsObj instanceof Map<?, ?> am) {
                    for (Map.Entry<?, ?> e : am.entrySet()) {
                        args.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!k.equals("id") && !k.equals("triggers") && !k.equals("trigger") && !k.equals("conditions") && !k.equals("args")) {
                        args.put(k, e.getValue());
                    }
                }

                effects.add(new PetEffect(effId, triggers, conds, args));
            }
        }

        List<PetCondition> conditions = parseConditions(config, "conditions");
        List<PetCondition> activateConditions = parseConditions(config, "activate-conditions");

        Pet pet = new Pet(id, name, description, iconString, entityTexture, effectsDesc, spawnEgg, effects, conditions, activateConditions);
        String displayName = config.getString("display-name");
        if (displayName != null && !displayName.isBlank()) pet.displayName = displayName.trim();
        String type = config.getString("entity-type", "DISPLAY").trim().toUpperCase(Locale.ROOT);
        pet.entityType = switch (type) {
            case "DISPLAY" -> null;
            case "ALLAY" -> EntityType.ALLAY;
            case "BLAZE" -> EntityType.BLAZE;
            default -> throw new IllegalArgumentException("entity-type must be DISPLAY, ALLAY or BLAZE: " + type);
        };
        pet.entityScale = displayNumber(config, "entity-scale", 0.0625, 16.0);
        pet.entityYOffset = displayNumber(config, "entity-y-offset", -16.0, 16.0);
        return pet;
    }

    private static Double displayNumber(FileConfiguration config, String key, double min, double max) {
        if (!config.contains(key)) return null;
        if (!config.isDouble(key) && !config.isInt(key) && !config.isLong(key)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        double value = config.getDouble(key);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static List<PetCondition> parseConditions(FileConfiguration config, String path) {
        List<PetCondition> list = new ArrayList<>();
        if (config.isList(path)) {
            List<?> rawList = config.getList(path);
            if (rawList != null) {
                for (Object o : rawList) {
                    if (o instanceof Map<?, ?> map) {
                        String cId = String.valueOf(map.get("id"));
                        boolean inv = map.get("inverted") != null && Boolean.parseBoolean(String.valueOf(map.get("inverted")));
                        Map<String, Object> cArgs = new HashMap<>();
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            cArgs.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        list.add(new PetCondition(cId, inv, cArgs));
                    } else if (o instanceof String str) {
                        PetCondition cond = PetCondition.fromString(str);
                        if (cond != null) list.add(cond);
                    }
                }
            }
        }
        return list;
    }

    public boolean canActivate(Player player) {
        if (player == null) return false;
        for (PetCondition condition : activateConditions) {
            if (!condition.test(player)) {
                return false;
            }
        }
        return true;
    }

    public boolean areConditionsMet(Player player) {
        if (player == null) return false;
        for (PetCondition condition : conditions) {
            if (!condition.test(player)) {
                return false;
            }
        }
        return true;
    }

    public ItemStack createSpawnEgg() {
        if (!spawnEggConfig.isEnabled()) return null;

        String itemStr = spawnEggConfig.getItem();
        Material mat = Material.matchMaterial(itemStr.split(" ")[0].toUpperCase());
        if (mat == null) mat = Material.BLAZE_SPAWN_EGG;

        ItemBuilder builder = new ItemBuilder(mat);
        if (mat == Material.PLAYER_HEAD) {
            String skullTex = null;
            if (itemStr.contains("texture:")) {
                skullTex = itemStr.substring(itemStr.indexOf("texture:") + "texture:".length()).split(" ")[0].trim();
            } else if (this.entityTexture != null && !this.entityTexture.isBlank() && !this.entityTexture.startsWith("item:")) {
                skullTex = this.entityTexture;
            }
            if (skullTex != null && !skullTex.isBlank()) {
                builder.skullTexture(skullTex);
            }
        }

        String displayName = spawnEggConfig.getName();
        if (displayName == null || displayName.isBlank()) {
            displayName = this.name + " &fPet Spawn Egg";
        }
        displayName = displayName.replace("%pet%", this.name).replace("%description%", this.description);
        builder.name(FriendlyCore.getInstance().getTextService().toComponent(displayName));

        List<Component> loreComponents = new ArrayList<>();
        List<String> rawLore = spawnEggConfig.getLore();
        if (rawLore.isEmpty()) {
            rawLore = List.of(
                    "",
                    "&8&oPlace on the ground to",
                    "&8&ounlock the " + this.name + "&8&o pet!"
            );
        }
        for (String line : rawLore) {
            String formatted = line.replace("%pet%", this.name).replace("%description%", this.description);
            loreComponents.add(FriendlyCore.getInstance().getTextService().toComponent(formatted));
        }
        builder.lore(loreComponents);
        builder.pdcString(PET_EGG_KEY, this.id);

        if (spawnEggConfig.getItem().contains("hide_enchants") || spawnEggConfig.getItem().contains("unbreaking")) {
            builder.glow(true);
        }

        return builder.build();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Optional menu/nameplate override; otherwise preserve the configured name and formatting. */
    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getIconString() {
        return iconString;
    }

    public String getEntityTexture() {
        return entityTexture;
    }

    public EntityType getEntityType() {
        return entityType;
    }

    public double getEntityScale(double defaultScale) {
        return entityScale != null ? entityScale : (entityType != null ? 0.5 : defaultScale);
    }

    /** Offset from the owner's standing eye height; native mob origins are at their feet. */
    public double getEntityYOffset(double defaultOffset) {
        return entityYOffset != null ? entityYOffset : (entityType != null ? -1.35 : defaultOffset);
    }

    public List<String> getEffectsDescription() {
        return Collections.unmodifiableList(effectsDescription);
    }

    public PetSpawnEggConfig getSpawnEggConfig() {
        return spawnEggConfig;
    }

    public List<PetEffect> getEffects() {
        return Collections.unmodifiableList(effects);
    }

    public List<PetCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    public List<PetCondition> getActivateConditions() {
        return Collections.unmodifiableList(activateConditions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pet pet)) return false;
        return Objects.equals(id, pet.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
