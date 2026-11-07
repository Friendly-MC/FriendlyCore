package dev.lyy.friendlycore.nms.common.display;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.Collection;

/**
 * Handles client-side virtual Display entities and holograms via raw packets without world-ticking overhead.
 */
public interface DisplayEntityHandler {

    /**
     * Spawns a synthetic client-side TextDisplay packet entity.
     *
     * @return The synthetic virtual entity ID.
     */
    int spawnVirtualTextDisplay(Collection<? extends Player> viewers, Location location, Component text, Vector3f scale, boolean billboard);

    /**
     * Updates the text and transformation of an existing virtual TextDisplay.
     */
    void updateVirtualTextDisplay(Collection<? extends Player> viewers, int entityId, Component text, Vector3f scale, int interpolationTicks);

    /**
     * Teleports the virtual display entity smoothly.
     */
    void moveVirtualDisplay(Collection<? extends Player> viewers, int entityId, Location newLocation);

    /**
     * Destroys the synthetic display entity for viewers.
     */
    void destroyVirtualEntity(Collection<? extends Player> viewers, int entityId);
}
