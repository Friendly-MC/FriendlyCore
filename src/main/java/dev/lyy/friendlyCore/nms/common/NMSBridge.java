package dev.lyy.friendlycore.nms.common;

import dev.lyy.friendlycore.nms.common.chunk.ChunkScanner;
import dev.lyy.friendlycore.nms.common.container.ContainerInspector;
import dev.lyy.friendlycore.nms.common.display.DisplayEntityHandler;
import dev.lyy.friendlycore.nms.common.glow.GlowHandler;
import dev.lyy.friendlycore.nms.common.network.PacketHandler;
import dev.lyy.friendlycore.nms.common.pathfinder.PetPathfinderBridge;

/**
 * Unified bridge contract exposing platform-dependent NMS and network internals.
 */
public interface NMSBridge {

    /**
     * Get human-readable version identifier (e.g., "1.21.11 (v1_21_R4)", "26.2 (v26_2)", or "Bukkit-Fallback").
     */
    String getVersionName();

    /**
     * True if running on native direct NMS, false if running in generic Bukkit compatibility mode.
     */
    boolean isNative();

    PacketHandler getPacketHandler();

    GlowHandler getGlowHandler();

    DisplayEntityHandler getDisplayEntityHandler();

    ChunkScanner getChunkScanner();

    ContainerInspector getContainerInspector();

    PetPathfinderBridge getPetPathfinderBridge();
}
