package dev.lyy.friendlycore.modules.essentials.model;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Tracks the state and context of an active Ender Chest inspection session.
 */
public class EnderchestSession {

    public enum SessionType {
        LOCAL_ONLINE,
        CROSS_SERVER,
        OFFLINE
    }

    private final UUID viewerUuid;
    private final UUID targetUuid;
    private final String targetName;
    private final SessionType sessionType;
    private final String targetServer;
    private final boolean editable;
    private final Inventory inventory;
    /**
     * True only if the viewer has actually moved/placed items inside the clone.
     * Write-back to the target on close is skipped when false to prevent a stale
     * clone from clobbering items the target added after the session was opened.
     */
    private volatile boolean modifiedByViewer = false;
    /** What the GUI showed after the last sync; viewer edits are diffed against it. Never mutated in place. */
    private volatile ItemStack[] baseline;

    public EnderchestSession(UUID viewerUuid, UUID targetUuid, String targetName,
                             SessionType sessionType, String targetServer, boolean editable, Inventory inventory) {
        this.viewerUuid = viewerUuid;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.sessionType = sessionType;
        this.targetServer = targetServer;
        this.editable = editable;
        this.inventory = inventory;
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public SessionType getSessionType() {
        return sessionType;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public boolean isEditable() {
        return editable;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public boolean isModifiedByViewer() {
        return modifiedByViewer;
    }

    public void setModifiedByViewer(boolean modifiedByViewer) {
        this.modifiedByViewer = modifiedByViewer;
    }

    public ItemStack[] getBaseline() {
        return baseline;
    }

    public void setBaseline(ItemStack[] baseline) {
        this.baseline = baseline;
    }
}
