package dev.lyy.friendlycore.modules.pets.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for a pet's spawn egg and withdrawal pricing.
 */
public class PetSpawnEggConfig {

    private final boolean enabled;
    private final String item;
    private final String name;
    private final List<String> lore;
    private final boolean craftable;
    private final String recipePermission;
    private final boolean shapeless;
    private final List<String> recipe;
    private final boolean withdrawable;
    private final boolean withdrawPriceEnabled;
    private final String withdrawPriceType;
    private final double withdrawPriceValue;
    private final String withdrawPriceDisplay;

    public PetSpawnEggConfig(boolean enabled, String item, String name, List<String> lore,
                              boolean craftable, String recipePermission, boolean shapeless,
                              List<String> recipe, boolean withdrawable, boolean withdrawPriceEnabled,
                              String withdrawPriceType, double withdrawPriceValue, String withdrawPriceDisplay) {
        this.enabled = enabled;
        this.item = item != null ? item : "blaze_spawn_egg";
        this.name = name;
        this.lore = lore != null ? new ArrayList<>(lore) : Collections.emptyList();
        this.craftable = craftable;
        this.recipePermission = recipePermission;
        this.shapeless = shapeless;
        this.recipe = recipe != null ? new ArrayList<>(recipe) : Collections.emptyList();
        this.withdrawable = withdrawable;
        this.withdrawPriceEnabled = withdrawPriceEnabled;
        this.withdrawPriceType = withdrawPriceType != null ? withdrawPriceType : "coins";
        this.withdrawPriceValue = withdrawPriceValue;
        this.withdrawPriceDisplay = withdrawPriceDisplay != null ? withdrawPriceDisplay : "";
    }

    public static PetSpawnEggConfig fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new PetSpawnEggConfig(true, "blaze_spawn_egg", null, Collections.emptyList(),
                    false, null, false, Collections.emptyList(), true, false, "coins", 0, "");
        }
        boolean enabled = section.getBoolean("enabled", true);
        String item = section.getString("item", "blaze_spawn_egg");
        String name = section.getString("name");
        List<String> lore = section.getStringList("lore");
        boolean craftable = section.getBoolean("craftable", false);
        String recipePermission = section.getString("recipe-permission");
        boolean shapeless = section.getBoolean("shapeless", false);
        List<String> recipe = section.getStringList("recipe");
        boolean withdrawable = section.getBoolean("withdrawable", true);

        boolean priceEnabled = false;
        String priceType = "coins";
        double priceVal = 0.0;
        String priceDisplay = "";

        if (section.isConfigurationSection("withdraw-price")) {
            ConfigurationSection priceSec = section.getConfigurationSection("withdraw-price");
            priceEnabled = priceSec.getBoolean("enabled", false);
            priceType = priceSec.getString("type", "coins");
            priceVal = priceSec.getDouble("value", 0.0);
            priceDisplay = priceSec.getString("display", "");
        }

        return new PetSpawnEggConfig(enabled, item, name, lore, craftable, recipePermission, shapeless,
                recipe, withdrawable, priceEnabled, priceType, priceVal, priceDisplay);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getItem() {
        return item;
    }

    public String getName() {
        return name;
    }

    public List<String> getLore() {
        return Collections.unmodifiableList(lore);
    }

    public boolean isCraftable() {
        return craftable;
    }

    public String getRecipePermission() {
        return recipePermission;
    }

    public boolean isShapeless() {
        return shapeless;
    }

    public List<String> getRecipe() {
        return Collections.unmodifiableList(recipe);
    }

    public boolean isWithdrawable() {
        return withdrawable;
    }

    public boolean isWithdrawPriceEnabled() {
        return withdrawPriceEnabled;
    }

    public String getWithdrawPriceType() {
        return withdrawPriceType;
    }

    public double getWithdrawPriceValue() {
        return withdrawPriceValue;
    }

    public String getWithdrawPriceDisplay() {
        return withdrawPriceDisplay;
    }
}
