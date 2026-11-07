package dev.lyy.friendlycore.modules.settings.provider;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.settings.SettingType;
import dev.lyy.friendlycore.modules.settings.SettingsManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Native Bedrock Form UI Provider for players joining via Geyser / Floodgate.
 * Uses Bedrock Cumulus / Floodgate Form API with switch toggles.
 */
public class BedrockSettingsProvider implements SettingsMenuProvider {

    private final FriendlyCore plugin;
    private final SettingsManager settingsManager;

    public BedrockSettingsProvider(FriendlyCore plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
    }

    @Override
    public String getName() {
        return "BEDROCK";
    }

    @Override
    public boolean isSupported(Player player) {
        if (player == null || !player.isOnline()) return false;
        return plugin.getHookManager().getFloodgateHook().isBedrockPlayer(player);
    }

    @Override
    public void openSettings(Player player) {
        try {
            // Check if Floodgate CustomForm API is available
            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Class<?> customFormBuilderClass = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder");
            Method builderMethod = customFormClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);

            Method titleMethod = customFormBuilderClass.getMethod("title", String.class);
            builder = titleMethod.invoke(builder, "§6§lPlayer Settings");

            Method toggleMethod = customFormBuilderClass.getMethod("toggle", String.class, boolean.class);

            List<SettingType> availableSettings = new ArrayList<>();
            for (SettingType type : SettingType.values()) {
                if (type.getPermission() != null && !player.hasPermission(type.getPermission())) {
                    continue;
                }
                availableSettings.add(type);
                boolean current = settingsManager.getSetting(player.getUniqueId(), type);
                builder = toggleMethod.invoke(builder, "§e" + type.getDisplayName() + "\n§7" + type.getDescription(), current);
            }

            Class<?> responseConsumerClass = Class.forName("org.geysermc.cumulus.util.FormImage"); // general check
            Method validResultHandlerMethod = null;
            for (Method m : customFormBuilderClass.getMethods()) {
                if (m.getName().equals("validResultHandler") && m.getParameterCount() == 1) {
                    validResultHandlerMethod = m;
                    break;
                }
            }

            if (validResultHandlerMethod != null) {
                // Register callback consumer via reflection proxy or lambda
                Consumer<Object> handler = response -> {
                    plugin.getScheduler().runEntity(player, () -> {
                        try {
                            Object customFormResponse = response;
                            try {
                                Method asCustomFormResponse = response.getClass().getMethod("asCustomFormResponse");
                                Object converted = asCustomFormResponse.invoke(response);
                                if (converted != null) {
                                    customFormResponse = converted;
                                }
                            } catch (NoSuchMethodException ignored) {
                            }

                            Method nextMethod = null;
                            for (Method m : customFormResponse.getClass().getMethods()) {
                                if (m.getName().equals("next") && m.getParameterCount() == 0) {
                                    nextMethod = m;
                                    break;
                                }
                            }

                            if (nextMethod != null) {
                                for (SettingType type : availableSettings) {
                                    Object nextValObj = nextMethod.invoke(customFormResponse);
                                    if (nextValObj instanceof Boolean nextVal) {
                                        boolean oldVal = settingsManager.getSetting(player.getUniqueId(), type);
                                        if (nextVal != oldVal) {
                                            settingsManager.setSetting(player.getUniqueId(), type, nextVal);
                                            Messages.send(plugin, player, "settings", nextVal ? "is-now-enabled" : "is-now-disabled", "%display_name_value%", type.getDisplayName());
                                        }
                                    }
                                }
                                if (settingsManager.getSetting(player.getUniqueId(), SettingType.SOUNDS)) {
                                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                                }
                            }
                        } catch (Throwable t) {
                            plugin.getLogger().log(Level.WARNING, "Failed to process Bedrock form response", t);
                        }
                    });
                };

                builder = validResultHandlerMethod.invoke(builder, handler);
            }

            Method buildMethod = customFormBuilderClass.getMethod("build");
            Object form = buildMethod.invoke(builder);

            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = floodgateApiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method getPlayer = floodgateApiClass.getMethod("getPlayer", UUID.class);
            Object floodgatePlayer = getPlayer.invoke(api, player.getUniqueId());

            if (floodgatePlayer != null) {
                Method sendForm = floodgatePlayer.getClass().getMethod("sendForm", Class.forName("org.geysermc.cumulus.form.Form"));
                sendForm.invoke(floodgatePlayer, form);
                return;
            }
        } catch (Throwable t) {
            // Fallback to legacy GUI if Floodgate Form API invocation fails
            plugin.getLogger().log(Level.FINE, "Bedrock Form API unavailable, falling back to GUI", t);
        }

        new LegacyChestGuiSettingsProvider(plugin, settingsManager).openSettings(player);
    }
}
