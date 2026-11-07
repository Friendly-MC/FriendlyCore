package dev.lyy.friendlycore.modules.kits.gui.editor;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.kits.Kit;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Central manager for the Kit Editor GUI suite and Folia-safe conversational chat prompts.
 */
public class KitEditorManager {

    private final FriendlyCore plugin;
    private final Map<UUID, KitChatPrompt> activePrompts = new ConcurrentHashMap<>();

    public KitEditorManager(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void openKitList(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitListEditorGui(plugin, this, player, page).open();
        });
    }

    public void openKitDetail(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitDetailEditorGui(plugin, this, player, kit).open();
        });
    }

    public void openItemsEditor(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitItemsEditorGui(plugin, this, player, kit).open();
        });
    }

    public void openDisplayEditor(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitDisplayEditorGui(plugin, this, player, kit).open();
        });
    }

    public void openActionsEditor(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitActionsEditorGui(plugin, this, player, kit).open();
        });
    }

    public void openRequirementsEditor(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitRequirementsEditorGui(plugin, this, player, kit).open();
        });
    }

    public void openDeleteConfirm(Player player, Kit kit) {
        if (player == null || !player.isOnline() || kit == null) return;
        plugin.getScheduler().runEntity(player, () -> {
            new KitDeleteConfirmGui(plugin, this, player, kit).open();
        });
    }

    public void startPrompt(Player player, String promptTitle, String currentValue,
                            Consumer<String> onInput, Runnable onCancel) {
        if (player == null || !player.isOnline()) return;

        KitChatPrompt prompt = new KitChatPrompt(player, promptTitle, currentValue, onInput, onCancel, 60000L);
        activePrompts.put(player.getUniqueId(), prompt);

        plugin.getScheduler().runEntity(player, () -> {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);

            Messages.send(plugin, player, "kits", "editor-kit-editor", "%prompt_title_formatted%", String.valueOf(promptTitle));
            if (currentValue != null && !currentValue.isEmpty()) {
                Messages.send(plugin, player, "kits", "editor-current", "%current_value%", String.valueOf(currentValue));
            }
            Messages.send(plugin, player, "kits", "editor-type-your-value-in-chat-or");
        });
    }

    public boolean handleChatInput(Player player, String message) {
        if (player == null) return false;
        KitChatPrompt prompt = activePrompts.get(player.getUniqueId());
        if (prompt == null) return false;

        if (prompt.isExpired()) {
            activePrompts.remove(player.getUniqueId());
            Messages.send(plugin, player, "kits", "editor-kit-editor-input-timed-out");
            if (prompt.getOnCancel() != null) {
                plugin.getScheduler().runEntity(player, prompt.getOnCancel()::run);
            }
            return true;
        }

        activePrompts.remove(player.getUniqueId());
        String trimmed = message != null ? message.trim() : "";

        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("/kit editor cancel")) {
            Messages.send(plugin, player, "kits", "editor-kit-editor-input-canceled");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            if (prompt.getOnCancel() != null) {
                plugin.getScheduler().runEntity(player, prompt.getOnCancel()::run);
            }
            return true;
        }

        plugin.getScheduler().runEntity(player, () -> {
            try {
                prompt.getOnInput().accept(trimmed);
            } catch (Exception e) {
                Messages.send(plugin, player, "kits", "editor-an-error-occurred", "%error%", String.valueOf(e.getMessage()));
                if (prompt.getOnCancel() != null) {
                    prompt.getOnCancel().run();
                }
            }
        });

        return true;
    }

    public boolean cancelPrompt(Player player) {
        if (player == null) return false;
        KitChatPrompt prompt = activePrompts.remove(player.getUniqueId());
        if (prompt != null) {
            Messages.send(plugin, player, "kits", "editor-kit-editor-input-canceled");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f);
            if (prompt.getOnCancel() != null) {
                plugin.getScheduler().runEntity(player, prompt.getOnCancel()::run);
            }
            return true;
        }
        return false;
    }

    public boolean hasActivePrompt(UUID uuid) {
        if (uuid == null) return false;
        KitChatPrompt prompt = activePrompts.get(uuid);
        if (prompt != null) {
            if (prompt.isExpired()) {
                activePrompts.remove(uuid);
                return false;
            }
            return true;
        }
        return false;
    }

    public void removePlayer(UUID uuid) {
        if (uuid != null) {
            activePrompts.remove(uuid);
        }
    }
}
