package dev.lyy.friendlycore.modules.kits;

import dev.lyy.friendlycore.modules.kits.model.KitAction;
import dev.lyy.friendlycore.modules.kits.model.KitItem;
import dev.lyy.friendlycore.modules.kits.model.KitRequirements;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a saved kit containing items, requirements, actions, and display states.
 */
public class Kit {

    private String name;
    private long cooldownSeconds;
    private boolean permissionRequired;
    private String customPermission;
    private boolean oneTime;
    private boolean autoArmor;
    private boolean clearInventory;
    private boolean saveOriginalItems;
    private boolean allowPlaceholdersOnOriginalItems;

    private List<KitItem> items;
    private List<KitAction> claimActions;
    private List<KitAction> errorActions;
    private KitRequirements requirements;

    private KitItem displayItemDefault;
    private KitItem displayItemNoPermission;
    private KitItem displayItemCooldown;
    private KitItem displayItemOneTime;
    private KitItem displayItemOneTimeRequirements;

    public Kit(String name) {
        this.name = name;
        this.cooldownSeconds = 0;
        this.permissionRequired = true;
        this.customPermission = "friendlycore.kits." + name.toLowerCase();
        this.oneTime = false;
        this.autoArmor = false;
        this.clearInventory = false;
        this.saveOriginalItems = false;
        this.allowPlaceholdersOnOriginalItems = false;
        this.items = new ArrayList<>();
        this.claimActions = new ArrayList<>();
        this.errorActions = new ArrayList<>();
    }

    public Kit(String name, List<ItemStack> items) {
        this(name, items, "friendlycore.kits." + name.toLowerCase(), 0);
    }

    public Kit(String name, List<ItemStack> items, String permission, long cooldownSeconds) {
        this.name = name;
        this.cooldownSeconds = cooldownSeconds;
        this.permissionRequired = permission != null && !permission.isEmpty();
        this.customPermission = permission;
        this.oneTime = false;
        this.autoArmor = false;
        this.clearInventory = false;
        this.saveOriginalItems = false;
        this.allowPlaceholdersOnOriginalItems = false;
        this.items = new ArrayList<>();
        if (items != null) {
            for (ItemStack is : items) {
                if (is != null && !is.getType().isAir()) {
                    this.items.add(new KitItem(is.clone()));
                }
            }
        }
        this.claimActions = new ArrayList<>();
        this.errorActions = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public boolean isPermissionRequired() {
        return permissionRequired;
    }

    public void setPermissionRequired(boolean permissionRequired) {
        this.permissionRequired = permissionRequired;
    }

    public String getCustomPermission() {
        return customPermission;
    }

    public void setCustomPermission(String customPermission) {
        this.customPermission = customPermission;
    }

    public String getPermission() {
        if (!permissionRequired) return null;
        if (customPermission != null && !customPermission.isEmpty()) return customPermission;
        return "friendlycore.kits." + name.toLowerCase();
    }

    public boolean hasPermission(CommandSender sender) {
        if (!permissionRequired) return true;
        if (sender.hasPermission("friendlycore.admin") ||
            sender.hasPermission("friendlycore.kits.admin") ||
            sender.hasPermission("playerkits.admin") ||
            sender.hasPermission("playerkits.kit.*") ||
            sender.hasPermission("friendlycore.kits.*")) {
            return true;
        }
        if (customPermission != null && !customPermission.isEmpty() && sender.hasPermission(customPermission)) {
            return true;
        }
        String defaultPerm = "friendlycore.kits." + name.toLowerCase(java.util.Locale.ROOT);
        String pkPerm = "playerkits.kit." + name.toLowerCase(java.util.Locale.ROOT);
        return sender.hasPermission(defaultPerm) || sender.hasPermission(pkPerm);
    }

    public boolean isOneTime() {
        return oneTime;
    }

    public void setOneTime(boolean oneTime) {
        this.oneTime = oneTime;
    }

    public boolean isAutoArmor() {
        return autoArmor;
    }

    public void setAutoArmor(boolean autoArmor) {
        this.autoArmor = autoArmor;
    }

    public boolean isClearInventory() {
        return clearInventory;
    }

    public void setClearInventory(boolean clearInventory) {
        this.clearInventory = clearInventory;
    }

    public boolean isSaveOriginalItems() {
        return saveOriginalItems;
    }

    public void setSaveOriginalItems(boolean saveOriginalItems) {
        this.saveOriginalItems = saveOriginalItems;
    }

    public boolean isAllowPlaceholdersOnOriginalItems() {
        return allowPlaceholdersOnOriginalItems;
    }

    public void setAllowPlaceholdersOnOriginalItems(boolean allowPlaceholdersOnOriginalItems) {
        this.allowPlaceholdersOnOriginalItems = allowPlaceholdersOnOriginalItems;
    }

    public List<KitItem> getKitItems() {
        return Collections.unmodifiableList(items);
    }

    public void setKitItems(List<KitItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    /**
     * Backward-compatible helper returning unmodifiable list of ItemStacks.
     */
    public List<ItemStack> getItems() {
        List<ItemStack> list = new ArrayList<>();
        for (KitItem item : items) {
            if (item != null) {
                if (item.getOriginalItem() != null) {
                    list.add(item.getOriginalItem().clone());
                } else {
                    ItemStack is = KitItemParser.toItemStack(item, null, this);
                    if (is != null) list.add(is);
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    public List<KitAction> getClaimActions() {
        return claimActions != null ? Collections.unmodifiableList(claimActions) : List.of();
    }

    public void setClaimActions(List<KitAction> claimActions) {
        this.claimActions = claimActions != null ? new ArrayList<>(claimActions) : new ArrayList<>();
    }

    public List<KitAction> getErrorActions() {
        return errorActions != null ? Collections.unmodifiableList(errorActions) : List.of();
    }

    public void setErrorActions(List<KitAction> errorActions) {
        this.errorActions = errorActions != null ? new ArrayList<>(errorActions) : new ArrayList<>();
    }

    public KitRequirements getRequirements() {
        return requirements;
    }

    public void setRequirements(KitRequirements requirements) {
        this.requirements = requirements;
    }

    public KitItem getDisplayItemDefault() {
        return displayItemDefault;
    }

    public void setDisplayItemDefault(KitItem displayItemDefault) {
        this.displayItemDefault = displayItemDefault;
    }

    public KitItem getDisplayItemNoPermission() {
        return displayItemNoPermission;
    }

    public void setDisplayItemNoPermission(KitItem displayItemNoPermission) {
        this.displayItemNoPermission = displayItemNoPermission;
    }

    public KitItem getDisplayItemCooldown() {
        return displayItemCooldown;
    }

    public void setDisplayItemCooldown(KitItem displayItemCooldown) {
        this.displayItemCooldown = displayItemCooldown;
    }

    public KitItem getDisplayItemOneTime() {
        return displayItemOneTime;
    }

    public void setDisplayItemOneTime(KitItem displayItemOneTime) {
        this.displayItemOneTime = displayItemOneTime;
    }

    public KitItem getDisplayItemOneTimeRequirements() {
        return displayItemOneTimeRequirements;
    }

    public void setDisplayItemOneTimeRequirements(KitItem displayItemOneTimeRequirements) {
        this.displayItemOneTimeRequirements = displayItemOneTimeRequirements;
    }
}
