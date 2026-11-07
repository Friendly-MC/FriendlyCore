package dev.lyy.friendlycore.modules.kits.gui.editor;

import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Represents an active chat input prompt session for Kit Editor configurations.
 */
public class KitChatPrompt {

    private final Player player;
    private final String promptTitle;
    private final String currentValue;
    private final Consumer<String> onInput;
    private final Runnable onCancel;
    private final long expiresAt;

    public KitChatPrompt(Player player, String promptTitle, String currentValue,
                         Consumer<String> onInput, Runnable onCancel, long timeoutMillis) {
        this.player = player;
        this.promptTitle = promptTitle;
        this.currentValue = currentValue != null ? currentValue : "";
        this.onInput = onInput;
        this.onCancel = onCancel;
        this.expiresAt = System.currentTimeMillis() + timeoutMillis;
    }

    public Player getPlayer() {
        return player;
    }

    public String getPromptTitle() {
        return promptTitle;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public Consumer<String> getOnInput() {
        return onInput;
    }

    public Runnable getOnCancel() {
        return onCancel;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
