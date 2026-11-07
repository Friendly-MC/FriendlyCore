package dev.lyy.friendlycore.nms.common.glow;

import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Handles per-viewer entity glowing and outline coloring via client-side packets.
 */
public interface GlowHandler {

    /**
     * Sends synthetic entity metadata & team packets to make target entity glow a specific color for the viewer.
     */
    void setFakeGlow(Player viewer, Entity target, ChatColor color, boolean glowing);

    /**
     * Broadcasts fake glow to a collection of viewers.
     */
    void broadcastFakeGlow(Collection<? extends Player> viewers, Entity target, ChatColor color, boolean glowing);

    /**
     * Clears all fake glowing overrides for the given viewer.
     */
    void clearFakeGlow(Player viewer, Entity target);
}
