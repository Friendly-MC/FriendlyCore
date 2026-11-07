package dev.lyy.friendlycore.modules.kits;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.model.KitAction;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.metadata.FixedMetadataValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * High-performance, Folia-safe Kit Action Executor.
 */
public final class KitActionExecutor {

    private final FriendlyCore plugin;

    public KitActionExecutor(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void executeActions(Player player, List<KitAction> actions, boolean beforeItems, Kit kit) {
        if (player == null || actions == null || actions.isEmpty()) return;

        for (KitAction action : actions) {
            if (action == null) continue;
            if (action.isExecuteBeforeItems() == beforeItems) {
                executeAction(player, action.getAction(), kit);
            }
        }
    }

    public void executeAction(Player player, String rawAction, Kit kit) {
        if (player == null || rawAction == null || rawAction.trim().isEmpty()) return;

        String formatted = formatPlaceholders(rawAction, player, kit);
        String actionTrimmed = formatted.trim();

        if (actionTrimmed.equalsIgnoreCase("close_inventory")) {
            plugin.getScheduler().runEntity(player, player::closeInventory);
            return;
        }

        int colonIndex = actionTrimmed.indexOf(':');
        String type;
        String param = "";
        if (colonIndex != -1) {
            type = actionTrimmed.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
            param = actionTrimmed.substring(colonIndex + 1).trim();
        } else {
            type = actionTrimmed.toLowerCase(Locale.ROOT);
        }

        final String actionParam = param;

        switch (type) {
            case "console_command" -> {
                plugin.getScheduler().runGlobal(() -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), actionParam);
                });
            }
            case "player_command" -> {
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        player.performCommand(actionParam);
                    }
                });
            }
            case "message" -> {
                plugin.getTextService().sendMessage(player, actionParam, null);
            }
            case "actionbar" -> {
                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        player.sendActionBar(plugin.getTextService().parse(actionParam));
                    }
                });
            }
            case "title" -> {
                // format: fadeIn;stay;fadeOut;title;subtitle
                String[] parts = actionParam.split(";", 5);
                int fadeIn = parts.length > 0 ? parseInteger(parts[0], 10) : 10;
                int stay = parts.length > 1 ? parseInteger(parts[1], 40) : 40;
                int fadeOut = parts.length > 2 ? parseInteger(parts[2], 10) : 10;
                String mainTitle = parts.length > 3 ? parts[3] : "";
                String subTitle = parts.length > 4 ? parts[4] : "";
                if (mainTitle.equalsIgnoreCase("none")) mainTitle = "";
                if (subTitle.equalsIgnoreCase("none")) subTitle = "";

                Title title = Title.title(
                        plugin.getTextService().parse(mainTitle),
                        plugin.getTextService().parse(subTitle),
                        Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))
                );

                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        player.showTitle(title);
                    }
                });
            }
            case "playsound" -> {
                // format: SOUND;volume;pitch
                String[] parts = actionParam.split(";");
                String soundName = parts.length > 0 ? parts[0] : "";
                float volume = parts.length > 1 ? parseFloat(parts[1], 1.0f) : 1.0f;
                float pitch = parts.length > 2 ? parseFloat(parts[2], 1.0f) : 1.0f;

                plugin.getScheduler().runEntity(player, () -> {
                    if (player.isOnline()) {
                        try {
                            Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
                            player.playSound(player.getLocation(), sound, volume, pitch);
                        } catch (IllegalArgumentException e) {
                            player.playSound(player.getLocation(), soundName.toLowerCase(Locale.ROOT), volume, pitch);
                        }
                    }
                });
            }
            case "firework" -> {
                plugin.getScheduler().runEntity(player, () -> {
                    if (!player.isOnline()) return;
                    Location loc = player.getLocation();
                    Firework firework = (Firework) loc.getWorld().spawnEntity(loc, EntityType.FIREWORK_ROCKET);
                    FireworkMeta meta = firework.getFireworkMeta();

                    List<Color> colors = new ArrayList<>();
                    List<Color> fadeColors = new ArrayList<>();
                    FireworkEffect.Type fType = FireworkEffect.Type.BALL;
                    int power = 0;

                    for (String part : actionParam.split(" ")) {
                        if (part.startsWith("colors:")) {
                            String[] cNames = part.substring(7).split(",");
                            for (String cn : cNames) colors.add(parseColor(cn));
                        } else if (part.startsWith("fade:")) {
                            String[] cNames = part.substring(5).split(",");
                            for (String cn : cNames) fadeColors.add(parseColor(cn));
                        } else if (part.startsWith("type:")) {
                            try {
                                fType = FireworkEffect.Type.valueOf(part.substring(5).toUpperCase(Locale.ROOT));
                            } catch (Exception ignored) {}
                        } else if (part.startsWith("power:")) {
                            power = parseInteger(part.substring(6), 0);
                        }
                    }

                    if (colors.isEmpty()) colors.add(Color.YELLOW);

                    FireworkEffect effect = FireworkEffect.builder()
                            .flicker(false)
                            .with(fType)
                            .withColor(colors)
                            .withFade(fadeColors)
                            .build();

                    meta.addEffect(effect);
                    meta.setPower(power);
                    firework.setFireworkMeta(meta);
                    firework.setMetadata("friendlycore_no_damage", new FixedMetadataValue(plugin, true));
                });
            }
        }
    }

    private String formatPlaceholders(String text, Player player, Kit kit) {
        if (text == null) return "";
        if (player != null) {
            text = text.replace("%player%", player.getName())
                    .replace("%player_name%", player.getName());
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
        }
        if (kit != null) {
            text = text.replace("%kit%", kit.getName())
                    .replace("%kit_name%", kit.getName());
        }
        return text;
    }

    private static Color parseColor(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "AQUA" -> Color.AQUA;
            case "BLACK" -> Color.BLACK;
            case "BLUE" -> Color.BLUE;
            case "FUCHSIA" -> Color.FUCHSIA;
            case "GRAY" -> Color.GRAY;
            case "GREEN" -> Color.GREEN;
            case "LIME" -> Color.LIME;
            case "MAROON" -> Color.MAROON;
            case "NAVY" -> Color.NAVY;
            case "OLIVE" -> Color.OLIVE;
            case "ORANGE" -> Color.ORANGE;
            case "PURPLE" -> Color.PURPLE;
            case "RED" -> Color.RED;
            case "SILVER" -> Color.SILVER;
            case "TEAL" -> Color.TEAL;
            case "WHITE" -> Color.WHITE;
            case "YELLOW" -> Color.YELLOW;
            default -> Color.WHITE;
        };
    }

    private static int parseInteger(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
