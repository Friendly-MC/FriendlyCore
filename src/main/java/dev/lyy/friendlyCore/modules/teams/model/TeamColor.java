package dev.lyy.friendlycore.modules.teams.model;

import org.bukkit.Material;

import java.util.Objects;
import java.util.Locale;

/**
 * Represents a customizable color scheme for team tags and formatting.
 */
public class TeamColor {

    private final String key;
    private final String name;
    private final Material material;
    private final String colorCode;
    private final String permission;

    public TeamColor(String key, String name, Material material, String colorCode, String permission) {
        this.key = key != null ? key.toLowerCase(Locale.ROOT) : "white";
        this.name = name != null ? name : "White";
        this.material = material != null ? material : Material.WHITE_WOOL;
        this.colorCode = colorCode != null ? colorCode : "&f";
        this.permission = permission != null ? permission : "";
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public Material getMaterial() {
        return material;
    }

    public String getColorCode() {
        return colorCode;
    }

    public String getPermission() {
        return permission;
    }

    public static TeamColor fromHex(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return null;
        return new TeamColor(value, "Custom " + value, Material.NAME_TAG, "<color:" + value + ">", "");
    }

    public boolean hasPermission(org.bukkit.entity.Player player) {
        if (player == null) return false;
        String permissionKey = key.startsWith("#") ? "custom" : key;
        return player.hasPermission("friendlycore.teams.color." + permissionKey)
                || player.hasPermission("zelteams.color." + permissionKey)
                || (!permission.isBlank() && player.hasPermission(permission))
                || player.hasPermission("zelteams.color.*")
                || player.hasPermission("friendlycore.teams.color.*");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeamColor teamColor)) return false;
        return Objects.equals(key, teamColor.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
