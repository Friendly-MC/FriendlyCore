package dev.lyy.friendlycore.modules.kits.model;

/**
 * Represents an action executed during kit claiming or error handling.
 */
public class KitAction {

    private String action;
    private KitItem displayItem;
    private boolean executeBeforeItems;
    private boolean countAsItem;

    public KitAction(String action) {
        this(action, null, false, false);
    }

    public KitAction(String action, KitItem displayItem, boolean executeBeforeItems, boolean countAsItem) {
        this.action = action;
        this.displayItem = displayItem;
        this.executeBeforeItems = executeBeforeItems;
        this.countAsItem = countAsItem;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public KitItem getDisplayItem() {
        return displayItem;
    }

    public void setDisplayItem(KitItem displayItem) {
        this.displayItem = displayItem;
    }

    public boolean isExecuteBeforeItems() {
        return executeBeforeItems;
    }

    public void setExecuteBeforeItems(boolean executeBeforeItems) {
        this.executeBeforeItems = executeBeforeItems;
    }

    public boolean isCountAsItem() {
        return countAsItem;
    }

    public void setCountAsItem(boolean countAsItem) {
        this.countAsItem = countAsItem;
    }

    public KitAction clone() {
        KitItem clonedItem = displayItem != null ? displayItem.clone() : null;
        return new KitAction(action, clonedItem, executeBeforeItems, countAsItem);
    }
}
