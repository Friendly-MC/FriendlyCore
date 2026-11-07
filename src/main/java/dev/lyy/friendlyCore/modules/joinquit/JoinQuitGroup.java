package dev.lyy.friendlycore.modules.joinquit;

/**
 * Configuration model defining custom join/quit formats, sounds, and permissions for a specific group.
 */
public record JoinQuitGroup(
        String id,
        String permission,
        int priority,
        String joinMessage,
        String quitMessage,
        String joinSound,
        String quitSound,
        String crossServerJoinMessage,
        String crossServerQuitMessage
) {
    public JoinQuitGroup(String id, String permission, int priority,
                         String joinMessage, String quitMessage,
                         String joinSound, String quitSound) {
        this(id, permission, priority, joinMessage, quitMessage, joinSound, quitSound, null, null);
    }

    public boolean hasPermission() {
        return permission != null && !permission.isEmpty();
    }
}
