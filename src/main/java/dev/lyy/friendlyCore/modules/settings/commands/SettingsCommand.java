package dev.lyy.friendlycore.modules.settings.commands;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Command executor for /settings and /options.
 */
public class SettingsCommand implements CommandExecutor, TabCompleter {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    public SettingsCommand(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

    private String getMessage(String key, String fallback) {
        FileConfiguration msgs = plugin.getConfigService().getSettingsMessages();
        if (msgs != null && msgs.contains(key)) {
            return msgs.getString(key);
        }
        return fallback;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                long start = System.currentTimeMillis();
                java.util.List<String> problems = plugin.reloadModule("settings");
                long duration = System.currentTimeMillis() - start;
                String reloadMsg = getMessage("reloaded", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ѕᴇᴛᴛɪɴɢѕ ᴄᴏɴғɪɢᴜʀᴀᴛɪᴏɴ ʀᴇʟᴏᴀᴅᴇᴅ ɪɴ <color:#E7B85C>%time%ᴍѕ<color:#D8D0C3>.");
                if (!problems.isEmpty()) {
                    plugin.sendReloadResult(sender, "settings", problems, duration);
                    return true;
                }
                plugin.getTextService().sendMessage(sender, reloadMsg, Map.of("%time%", String.valueOf(duration)));
                return true;
            }
            String onlyPlayers = getMessage("players-only", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ ᴄᴀɴ ᴏɴʟʏ ʙᴇ ᴜѕᴇᴅ ʙʏ ᴘʟᴀʏᴇʀѕ.");
            plugin.getTextService().sendMessage(sender, onlyPlayers);
            return true;
        }

        if (args.length == 0) {
            settingsManager.getUiRouter().open(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "toggle" -> {
                if (args.length < 2) {
                    String usage = getMessage("usage", "<gradient:#FFB703:#E7B85C><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜѕᴀɢᴇ: <color:#E7B85C>/%command% toggle <setting><color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, usage, Map.of("%command%", label));
                    return true;
                }
                SettingType type = SettingType.fromKey(args[1]);
                if (type == null) {
                    String unknown = getMessage("unknown-setting", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜɴᴋɴᴏᴡɴ ѕᴇᴛᴛɪɴɢ: <color:#E7B85C>%setting%<color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, unknown, Map.of("%setting%", args[1]));
                    return true;
                }
                if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                    String noPerm = getMessage("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴛᴏɢɢʟᴇ <color:#E7B85C>%setting%<color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, noPerm, Map.of("%setting%", type.getDisplayName()));
                    return true;
                }

                boolean next = settingsManager.toggleSetting(player.getUniqueId(), type);
                String template = next
                        ? getMessage("setting-enabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#8DE969><bold>ᴇɴᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.")
                        : getMessage("setting-disabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#FF4D5A><bold>ᴅɪѕᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.");

                plugin.getTextService().sendMessage(player, template, Map.of("%setting%", type.getDisplayName()));

                if (settingsManager.getSetting(player.getUniqueId(), SettingType.SOUNDS)) {
                    player.playSound(player.getLocation(), next ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, next ? 2f : 0.8f);
                }

                // If opened from a dialog, re-open dialog to show updated state
                if (args.length > 2 && args[2].equalsIgnoreCase("--source=dialog")) {
                    settingsManager.getUiRouter().getDialogProvider().openSettings(player);
                }
                return true;
            }

            case "set" -> {
                if (args.length < 3) {
                    String usage = getMessage("usage", "<gradient:#FFB703:#E7B85C><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜѕᴀɢᴇ: <color:#E7B85C>/%command% set <setting> <true|false><color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, usage, Map.of("%command%", label));
                    return true;
                }
                SettingType type = SettingType.fromKey(args[1]);
                if (type == null) {
                    String unknown = getMessage("unknown-setting", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜɴᴋɴᴏᴡɴ ѕᴇᴛᴛɪɴɢ: <color:#E7B85C>%setting%<color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, unknown, Map.of("%setting%", args[1]));
                    return true;
                }
                if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                    String noPerm = getMessage("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴛᴏɢɢʟᴇ <color:#E7B85C>%setting%<color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, noPerm, Map.of("%setting%", type.getDisplayName()));
                    return true;
                }
                boolean value = Boolean.parseBoolean(args[2]);
                settingsManager.setSetting(player.getUniqueId(), type, value);
                String setTemplate = getMessage("setting-set", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ѕᴇᴛ ᴛᴏ %state%<color:#D8D0C3>.");
                String stateStr = value ? "<color:#8DE969><bold>ᴇɴᴀʙʟᴇᴅ</bold></color>" : "<color:#FF4D5A><bold>ᴅɪѕᴀʙʟᴇᴅ</bold></color>";
                plugin.getTextService().sendMessage(player, setTemplate, Map.of("%setting%", type.getDisplayName(), "%state%", stateStr));
                return true;
            }

            case "get" -> {
                if (args.length < 2) {
                    String usage = getMessage("usage", "<gradient:#FFB703:#E7B85C><bold>⚠ ᴡᴀʀɴɪɴɢ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜѕᴀɢᴇ: <color:#E7B85C>/%command% get <setting><color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, usage, Map.of("%command%", label));
                    return true;
                }
                SettingType type = SettingType.fromKey(args[1]);
                if (type == null) {
                    String unknown = getMessage("unknown-setting", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ᴜɴᴋɴᴏᴡɴ ѕᴇᴛᴛɪɴɢ: <color:#E7B85C>%setting%<color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, unknown, Map.of("%setting%", args[1]));
                    return true;
                }
                boolean current = settingsManager.getSetting(player.getUniqueId(), type);
                String statusTemplate = getMessage("setting-status", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting%<color:#D8D0C3>: %status%");
                String statusStr = current ? "<color:#8DE969><bold>ᴇɴᴀʙʟᴇᴅ</bold></color>" : "<color:#FF4D5A><bold>ᴅɪѕᴀʙʟᴇᴅ</bold></color>";
                plugin.getTextService().sendMessage(player, statusTemplate, Map.of("%setting%", type.getDisplayName(), "%status%", statusStr));
                return true;
            }

            case "gui", "chest" -> {
                settingsManager.getUiRouter().getChestGuiProvider().openSettings(player);
                return true;
            }

            case "dialog" -> {
                settingsManager.getUiRouter().getDialogProvider().openSettings(player);
                return true;
            }

            case "bedrock", "form" -> {
                settingsManager.getUiRouter().getBedrockProvider().openSettings(player);
                return true;
            }

            case "chat", "cli" -> {
                settingsManager.getUiRouter().getChatProvider().openSettings(player);
                return true;
            }

            case "reload" -> {
                if (!player.hasPermission("friendlycore.settings.admin")) {
                    String noPerm = getMessage("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴜѕᴇ ᴛʜɪѕ ᴄᴏᴍᴍᴀɴᴅ.");
                    plugin.getTextService().sendMessage(player, noPerm, Map.of("%setting%", "admin reload"));
                    return true;
                }
                long start = System.currentTimeMillis();
                java.util.List<String> problems = plugin.reloadModule("settings");
                settingsManager.getUiRouter().getDialogProvider().resetAvailability();
                long duration = System.currentTimeMillis() - start;
                String reloadMsg = getMessage("reloaded", "<gradient:#7BD88F:#B9FBC0><bold>✔ ѕᴜᴄᴄᴇѕѕ</bold></gradient> <dark_gray>» <color:#D8D0C3>ѕᴇᴛᴛɪɴɢѕ ᴄᴏɴғɪɢᴜʀᴀᴛɪᴏɴ ʀᴇʟᴏᴀᴅᴇᴅ ɪɴ <color:#E7B85C>%time%ᴍѕ<color:#D8D0C3>.");
                if (!problems.isEmpty()) {
                    plugin.sendReloadResult(player, "settings", problems, duration);
                    return true;
                }
                plugin.getTextService().sendMessage(player, reloadMsg, Map.of("%time%", String.valueOf(duration)));
                return true;
            }

            default -> {
                SettingType type = SettingType.fromKey(sub);
                if (type != null) {
                    if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                        String noPerm = getMessage("no-permission", "<gradient:#FF4D5A:#FF9A9A><bold>✘ ᴇʀʀᴏʀ</bold></gradient> <dark_gray>» <color:#D8D0C3>ʏᴏᴜ ᴅᴏ ɴᴏᴛ ʜᴀᴠᴇ ᴘᴇʀᴍɪѕѕɪᴏɴ ᴛᴏ ᴛᴏɢɢʟᴇ <color:#E7B85C>%setting%<color:#D8D0C3>.");
                        plugin.getTextService().sendMessage(player, noPerm, Map.of("%setting%", type.getDisplayName()));
                        return true;
                    }
                    boolean next = settingsManager.toggleSetting(player.getUniqueId(), type);
                    String template = next
                            ? getMessage("setting-enabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#8DE969><bold>ᴇɴᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.")
                            : getMessage("setting-disabled", "<gradient:#6EDBFF:#A7F3FF><bold>⚙ ѕᴇᴛᴛɪɴɢѕ</bold></gradient> <dark_gray>» <color:#E7B85C>%setting% <color:#D8D0C3>ɪѕ ɴᴏᴡ <color:#FF4D5A><bold>ᴅɪѕᴀʙʟᴇᴅ</bold></color><color:#D8D0C3>.");
                    plugin.getTextService().sendMessage(player, template, Map.of("%setting%", type.getDisplayName()));
                    if (settingsManager.getSetting(player.getUniqueId(), SettingType.SOUNDS)) {
                        player.playSound(player.getLocation(), next ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 1f, next ? 2f : 0.8f);
                    }
                    return true;
                }
                settingsManager.getUiRouter().open(player);
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = Arrays.asList("toggle", "set", "get", "gui", "dialog", "bedrock", "chat", "reload");
            for (String s : subs) {
                if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            }
            for (SettingType type : SettingType.values()) {
                if (type.getKey().startsWith(args[0].toLowerCase())) {
                    completions.add(type.getKey());
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("get"))) {
            for (SettingType type : SettingType.values()) {
                if (type.getKey().startsWith(args[1].toLowerCase())) {
                    completions.add(type.getKey());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            if ("true".startsWith(args[2].toLowerCase())) completions.add("true");
            if ("false".startsWith(args[2].toLowerCase())) completions.add("false");
        }
        return completions;
    }
}
