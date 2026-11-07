package dev.lyy.friendlycore.modules.settings.provider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Modern Java 1.21.6+ Native Dialog UI provider.
 * Automatically generates data-driven dialog definitions and displays native client dialog modals.
 * Falls back gracefully to LegacyChestGuiSettingsProvider if the datapack registry is not yet loaded.
 */
public class JavaDialogSettingsProvider implements SettingsMenuProvider {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private boolean commandChecked = false;
    private boolean dialogCommandSupported = false;
    private boolean dialogRegistryAvailable = true;
    private boolean loggedMissingRegistryWarning = false;

    public JavaDialogSettingsProvider(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        initDatapack();
    }

    @Override
    public String getName() {
        return "DIALOG";
    }

    @Override
    public boolean isSupported(Player player) {
        if (player == null || !player.isOnline()) return false;
        // If player is a Bedrock player, prioritize Bedrock forms
        if (plugin.getHookManager().getFloodgateHook().isBedrockPlayer(player)) {
            return false;
        }
        if (!dialogRegistryAvailable) {
            return false;
        }
        checkDialogCommandSupport();
        return dialogCommandSupported;
    }

    public void resetAvailability() {
        this.commandChecked = false;
        this.dialogCommandSupported = false;
        this.dialogRegistryAvailable = true;
        this.loggedMissingRegistryWarning = false;
    }

    private void checkDialogCommandSupport() {
        if (commandChecked) return;
        commandChecked = true;
        // Check if /dialog command is registered in server command map
        try {
            this.dialogCommandSupported = Bukkit.getHelpMap().getHelpTopic("/dialog") != null
                    || Bukkit.getCommandMap().getCommand("dialog") != null;
        } catch (Throwable t) {
            this.dialogCommandSupported = false;
        }
    }

    @Override
    public void openSettings(Player player) {
        if (!dialogRegistryAvailable) {
            new LegacyChestGuiSettingsProvider(plugin, settingsManager).openSettings(player);
            return;
        }

        try {
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " friendlycore:settings");
            if (!success) {
                fallbackToGui(player);
            }
        } catch (Throwable t) {
            fallbackToGui(player);
        }
    }

    private void fallbackToGui(Player player) {
        this.dialogRegistryAvailable = false;
        if (!loggedMissingRegistryWarning) {
            loggedMissingRegistryWarning = true;
            plugin.getLogger().info("[FriendlyCore] Native dialog 'friendlycore:settings' is not yet loaded in Minecraft's dialog registry (datapacks require server restart or '/reload' after initial creation). Seamlessly routing to interactive Chest GUI.");
        }
        new LegacyChestGuiSettingsProvider(plugin, settingsManager).openSettings(player);
    }

    /**
     * Creates and ensures the friendlycore_dialogs datapack exists in all worlds.
     */
    public void initDatapack() {
        plugin.getScheduler().runAsync(() -> {
            for (World world : Bukkit.getWorlds()) {
                File worldFolder = world.getWorldFolder();
                File datapacksFolder = new File(worldFolder, "datapacks");
                if (!datapacksFolder.exists()) {
                    datapacksFolder.mkdirs();
                }

                File packDir = new File(datapacksFolder, "friendlycore_dialogs");
                File dataDir = new File(packDir, "data" + File.separator + "friendlycore" + File.separator + "dialog");
                dataDir.mkdirs();

                File mcmeta = new File(packDir, "pack.mcmeta");
                if (!mcmeta.exists()) {
                    JsonObject meta = new JsonObject();
                    JsonObject pack = new JsonObject();
                    pack.addProperty("pack_format", 61); // 1.21.6 format
                    pack.addProperty("description", "FriendlyCore Native Dialog Menus");
                    meta.add("pack", pack);
                    try (FileWriter writer = new FileWriter(mcmeta, StandardCharsets.UTF_8)) {
                        gson.toJson(meta, writer);
                    } catch (IOException ignored) {}
                }

                File settingsJson = new File(dataDir, "settings.json");
                JsonObject dialog = new JsonObject();
                dialog.addProperty("type", "minecraft:multi_action");

                JsonObject title = new JsonObject();
                title.addProperty("text", "⚙ Player Settings");
                title.addProperty("color", "gold");
                title.addProperty("bold", true);
                dialog.add("title", title);

                JsonArray body = new JsonArray();
                JsonObject bodyMsg = new JsonObject();
                bodyMsg.addProperty("type", "minecraft:plain_message");
                JsonObject bodyText = new JsonObject();
                bodyText.addProperty("text", "Click any option below to toggle your personal server preferences:");
                bodyText.addProperty("color", "gray");
                bodyMsg.add("contents", bodyText);
                body.add(bodyMsg);
                dialog.add("body", body);

                JsonArray actions = new JsonArray();
                for (SettingType type : SettingType.values()) {
                    JsonObject btn = new JsonObject();
                    JsonObject label = new JsonObject();
                    label.addProperty("text", "Toggle " + type.getDisplayName());
                    label.addProperty("color", "yellow");
                    btn.add("label", label);

                    JsonObject action = new JsonObject();
                    action.addProperty("type", "minecraft:run_command");
                    action.addProperty("command", "/settings toggle " + type.getKey() + " --source=dialog");
                    btn.add("action", action);

                    actions.add(btn);
                }
                dialog.add("actions", actions);

                JsonObject exitAction = new JsonObject();
                JsonObject exitLabel = new JsonObject();
                exitLabel.addProperty("text", "Close");
                exitLabel.addProperty("color", "red");
                exitAction.add("label", exitLabel);
                dialog.add("exit_action", exitAction);

                try (FileWriter writer = new FileWriter(settingsJson, StandardCharsets.UTF_8)) {
                    gson.toJson(dialog, writer);
                } catch (IOException ignored) {}
            }

            // Attempt to enable the datapack if possible
            plugin.getScheduler().runGlobal(() -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "datapack enable \"file/friendlycore_dialogs\"");
                } catch (Throwable ignored) {}
            });
        });
    }
}
