package dev.lyy.friendlycore.nms.common.network;

import org.bukkit.entity.Player;

/**
 * Raw packet injection, listener, and direct network metrics handler.
 */
public interface PacketHandler {

    /**
     * Get player's real-time network latency directly from connection internals.
     */
    int getPing(Player player);

    /**
     * Sends a raw packet or packet bundle to the player connection.
     */
    void sendPacket(Player player, Object packet);

    /**
     * Injects a channel duplex handler into the player's Netty pipeline.
     */
    void injectPlayer(Player player);

    /**
     * Removes the injected duplex handler on player quit.
     */
    void uninjectPlayer(Player player);
}
