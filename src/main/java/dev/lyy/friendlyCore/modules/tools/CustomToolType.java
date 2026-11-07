package dev.lyy.friendlycore.modules.tools;

/**
 * Identifies the functional type of a custom tool item.
 */
public enum CustomToolType {
    DRILL_PICKAXE,
    DRILL_SHOVEL,
    TREE_CHOPPER;

    public boolean isDrill() {
        return this == DRILL_PICKAXE || this == DRILL_SHOVEL;
    }

    public boolean isChopper() {
        return this == TREE_CHOPPER;
    }
}
