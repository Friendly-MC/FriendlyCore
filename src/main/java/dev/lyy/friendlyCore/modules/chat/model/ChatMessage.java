package dev.lyy.friendlycore.modules.chat.model;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Encapsulates a chat message during its lifecycle in the chat pipeline.
 */
public class ChatMessage {

    private final Player sender;
    private final UUID senderUuid;
    private final String senderName;
    private String rawMessage;
    private Component message;
    private ChatChannel channel;
    private boolean cancelled;
    private String cancelReason;
    private final Set<UUID> mentions;
    private int infractionPoints;
    private final Set<String> flaggedWords;
    private final java.util.List<RenderSnapshot> createdSnapshots;

    public ChatMessage(Player sender, String rawMessage, Component message, ChatChannel channel) {
        this.sender = sender;
        this.senderUuid = sender != null ? sender.getUniqueId() : null;
        this.senderName = sender != null ? sender.getName() : "CONSOLE";
        this.rawMessage = rawMessage;
        this.message = message;
        this.channel = channel != null ? channel : ChatChannel.PUBLIC;
        this.cancelled = false;
        this.cancelReason = null;
        this.mentions = new HashSet<>();
        this.infractionPoints = 0;
        this.flaggedWords = new HashSet<>();
        this.createdSnapshots = new java.util.ArrayList<>();
    }

    public Player getSender() {
        return sender;
    }

    public UUID getSenderUuid() {
        return senderUuid;
    }

    public String getSenderName() {
        return senderName;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public void setRawMessage(String rawMessage) {
        this.rawMessage = rawMessage;
    }

    public Component getMessage() {
        return message;
    }

    public void setMessage(Component message) {
        this.message = message;
    }

    public ChatChannel getChannel() {
        return channel;
    }

    public void setChannel(ChatChannel channel) {
        this.channel = channel;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public void cancel(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Set<UUID> getMentions() {
        return mentions;
    }

    public void addMention(UUID uuid) {
        if (uuid != null) {
            this.mentions.add(uuid);
        }
    }

    public int getInfractionPoints() {
        return infractionPoints;
    }

    public void addInfractionPoints(int points) {
        this.infractionPoints += points;
    }

    public Set<String> getFlaggedWords() {
        return flaggedWords;
    }

    public void addFlaggedWord(String word) {
        if (word != null && !word.isEmpty()) {
            this.flaggedWords.add(word);
        }
    }

    public java.util.List<RenderSnapshot> getCreatedSnapshots() {
        return createdSnapshots;
    }

    public void addCreatedSnapshot(RenderSnapshot snapshot) {
        if (snapshot != null) {
            this.createdSnapshots.add(snapshot);
        }
    }
}
