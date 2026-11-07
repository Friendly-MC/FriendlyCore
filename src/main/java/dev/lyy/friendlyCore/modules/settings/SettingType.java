package dev.lyy.friendlycore.modules.settings;

import org.bukkit.Material;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Enumeration of all player configurable settings in FriendlyCore.
 */
public enum SettingType {

    SCOREBOARD("sb", "Scoreboard", "Toggle sidebar scoreboard visibility", Material.OAK_SIGN, true, null),
    CHAT("chat", "Global Chat", "Toggle visibility of public chat messages", Material.WRITABLE_BOOK, true, null),
    TPA("tpa", "Teleport Requests", "Accept or deny incoming teleport requests", Material.ENDER_PEARL, true, "friendlycore.tpa"),
    PRIVATE_MESSAGES("pm", "Private Messages", "Allow or block direct /msg whispers", Material.PAPER, true, null),
    NIGHT_VISION("nv", "Night Vision", "Toggle infinite night vision", Material.GOLDEN_CARROT, false, "friendlycore.nightvision"),
    PAY_NOTIFICATIONS("pay", "Pay Alerts", "Receive notifications when sent money", Material.EMERALD, true, null),
    SOUNDS("sounds", "UI Sounds", "Toggle menu and interaction sound effects", Material.JUKEBOX, true, null);

    private final String key;
    private final String displayName;
    private final String description;
    private final Material icon;
    private final boolean defaultValue;
    private final String permission;

    private static final Map<String, SettingType> BY_KEY = new HashMap<>();

    static {
        for (SettingType type : values()) {
            BY_KEY.put(type.key.toLowerCase(), type);
            BY_KEY.put(type.name().toLowerCase(), type);
        }
        BY_KEY.put("scoreboard", SCOREBOARD);
        BY_KEY.put("globalchat", CHAT);
        BY_KEY.put("teleport", TPA);
        BY_KEY.put("tpas", TPA);
        BY_KEY.put("msg", PRIVATE_MESSAGES);
        BY_KEY.put("whisper", PRIVATE_MESSAGES);
        BY_KEY.put("privatemessages", PRIVATE_MESSAGES);
        BY_KEY.put("nightvision", NIGHT_VISION);
        BY_KEY.put("payalerts", PAY_NOTIFICATIONS);
        BY_KEY.put("payments", PAY_NOTIFICATIONS);
        BY_KEY.put("sound", SOUNDS);
        BY_KEY.put("guisounds", SOUNDS);
    }

    SettingType(String key, String displayName, String description, Material icon, boolean defaultValue, String permission) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.defaultValue = defaultValue;
        this.permission = permission;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Material getIcon() {
        return icon;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    public String getPermission() {
        return permission;
    }

    public static SettingType fromKey(String key) {
        if (key == null) return null;
        return BY_KEY.get(key.toLowerCase());
    }

    public static Map<String, SettingType> getAllKeys() {
        return Collections.unmodifiableMap(BY_KEY);
    }
}
