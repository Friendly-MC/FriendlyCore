package dev.lyy.friendlycore.modules.kits.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal Kit Item model holding item definitions, meta, and custom attributes.
 */
public class KitItem {

    public enum EquipmentSlot {
        HEAD,
        CHEST,
        LEGS,
        FEET,
        OFF_HAND
    }

    private String id;
    private int amount = 1;
    private String name;
    private List<String> lore;
    private short durability = 0;
    private int customModelData = 0;
    private KitItemCustomModelComponentData customModelComponentData;
    private int color = 0;
    private List<String> enchants;
    private List<String> flags;
    private List<String> bookEnchants;
    private List<String> nbt;
    private List<String> attributes;
    private List<String> canPlace;
    private List<String> canDestroy;

    private KitItemSkullData skullData;
    private KitItemPotionData potionData;
    private KitItemFireworkData fireworkData;
    private KitItemBannerData bannerData;
    private KitItemBookData bookData;
    private KitItemTrimData trimData;

    private boolean hideTooltip = false;
    private String tooltipStyle;
    private String model;

    private boolean offhand = false;
    private EquipmentSlot equipmentSlot;
    private int previewSlot = -1;

    private ItemStack originalItem;

    public KitItem() {}

    public KitItem(String id) {
        this.id = id;
    }

    public KitItem(ItemStack originalItem) {
        this.originalItem = originalItem != null ? originalItem.clone() : null;
        if (this.originalItem != null) {
            this.id = this.originalItem.getType().name();
            this.amount = this.originalItem.getAmount();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore != null ? new ArrayList<>(lore) : null;
    }

    public short getDurability() {
        return durability;
    }

    public void setDurability(short durability) {
        this.durability = durability;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(int customModelData) {
        this.customModelData = customModelData;
    }

    public KitItemCustomModelComponentData getCustomModelComponentData() {
        return customModelComponentData;
    }

    public void setCustomModelComponentData(KitItemCustomModelComponentData customModelComponentData) {
        this.customModelComponentData = customModelComponentData;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public List<String> getEnchants() {
        return enchants;
    }

    public void setEnchants(List<String> enchants) {
        this.enchants = enchants != null ? new ArrayList<>(enchants) : null;
    }

    public List<String> getFlags() {
        return flags;
    }

    public void setFlags(List<String> flags) {
        this.flags = flags != null ? new ArrayList<>(flags) : null;
    }

    public List<String> getBookEnchants() {
        return bookEnchants;
    }

    public void setBookEnchants(List<String> bookEnchants) {
        this.bookEnchants = bookEnchants != null ? new ArrayList<>(bookEnchants) : null;
    }

    public List<String> getNbt() {
        return nbt;
    }

    public void setNbt(List<String> nbt) {
        this.nbt = nbt != null ? new ArrayList<>(nbt) : null;
    }

    public List<String> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<String> attributes) {
        this.attributes = attributes != null ? new ArrayList<>(attributes) : null;
    }

    public List<String> getCanPlace() {
        return canPlace;
    }

    public void setCanPlace(List<String> canPlace) {
        this.canPlace = canPlace != null ? new ArrayList<>(canPlace) : null;
    }

    public List<String> getCanDestroy() {
        return canDestroy;
    }

    public void setCanDestroy(List<String> canDestroy) {
        this.canDestroy = canDestroy != null ? new ArrayList<>(canDestroy) : null;
    }

    public KitItemSkullData getSkullData() {
        return skullData;
    }

    public void setSkullData(KitItemSkullData skullData) {
        this.skullData = skullData;
    }

    public KitItemPotionData getPotionData() {
        return potionData;
    }

    public void setPotionData(KitItemPotionData potionData) {
        this.potionData = potionData;
    }

    public KitItemFireworkData getFireworkData() {
        return fireworkData;
    }

    public void setFireworkData(KitItemFireworkData fireworkData) {
        this.fireworkData = fireworkData;
    }

    public KitItemBannerData getBannerData() {
        return bannerData;
    }

    public void setBannerData(KitItemBannerData bannerData) {
        this.bannerData = bannerData;
    }

    public KitItemBookData getBookData() {
        return bookData;
    }

    public void setBookData(KitItemBookData bookData) {
        this.bookData = bookData;
    }

    public KitItemTrimData getTrimData() {
        return trimData;
    }

    public void setTrimData(KitItemTrimData trimData) {
        this.trimData = trimData;
    }

    public boolean isHideTooltip() {
        return hideTooltip;
    }

    public void setHideTooltip(boolean hideTooltip) {
        this.hideTooltip = hideTooltip;
    }

    public String getTooltipStyle() {
        return tooltipStyle;
    }

    public void setTooltipStyle(String tooltipStyle) {
        this.tooltipStyle = tooltipStyle;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isOffhand() {
        return offhand || equipmentSlot == EquipmentSlot.OFF_HAND;
    }

    public void setOffhand(boolean offhand) {
        this.offhand = offhand;
        if (offhand) {
            this.equipmentSlot = EquipmentSlot.OFF_HAND;
        } else if (this.equipmentSlot == EquipmentSlot.OFF_HAND) {
            this.equipmentSlot = null;
        }
    }

    public EquipmentSlot getEquipmentSlot() {
        return equipmentSlot;
    }

    public void setEquipmentSlot(EquipmentSlot equipmentSlot) {
        this.equipmentSlot = equipmentSlot;
        this.offhand = equipmentSlot == EquipmentSlot.OFF_HAND;
    }

    public int getPreviewSlot() {
        return previewSlot;
    }

    public void setPreviewSlot(int previewSlot) {
        this.previewSlot = previewSlot;
    }

    public ItemStack getOriginalItem() {
        return originalItem;
    }

    public void setOriginalItem(ItemStack originalItem) {
        this.originalItem = originalItem != null ? originalItem.clone() : null;
        if (this.originalItem != null) {
            this.id = this.originalItem.getType().name();
            this.amount = this.originalItem.getAmount();
        }
    }

    public KitItem clone() {
        KitItem copy = new KitItem();
        copy.id = this.id;
        copy.amount = this.amount;
        copy.name = this.name;
        copy.lore = this.lore != null ? new ArrayList<>(this.lore) : null;
        copy.durability = this.durability;
        copy.customModelData = this.customModelData;
        copy.customModelComponentData = this.customModelComponentData != null ? this.customModelComponentData.clone() : null;
        copy.color = this.color;
        copy.enchants = this.enchants != null ? new ArrayList<>(this.enchants) : null;
        copy.flags = this.flags != null ? new ArrayList<>(this.flags) : null;
        copy.bookEnchants = this.bookEnchants != null ? new ArrayList<>(this.bookEnchants) : null;
        copy.nbt = this.nbt != null ? new ArrayList<>(this.nbt) : null;
        copy.attributes = this.attributes != null ? new ArrayList<>(this.attributes) : null;
        copy.canPlace = this.canPlace != null ? new ArrayList<>(this.canPlace) : null;
        copy.canDestroy = this.canDestroy != null ? new ArrayList<>(this.canDestroy) : null;
        copy.skullData = this.skullData != null ? this.skullData.clone() : null;
        copy.potionData = this.potionData != null ? this.potionData.clone() : null;
        copy.fireworkData = this.fireworkData != null ? this.fireworkData.clone() : null;
        copy.bannerData = this.bannerData != null ? this.bannerData.clone() : null;
        copy.bookData = this.bookData != null ? this.bookData.clone() : null;
        copy.trimData = this.trimData != null ? this.trimData.clone() : null;
        copy.hideTooltip = this.hideTooltip;
        copy.tooltipStyle = this.tooltipStyle;
        copy.model = this.model;
        copy.offhand = this.offhand;
        copy.equipmentSlot = this.equipmentSlot;
        copy.previewSlot = this.previewSlot;
        copy.originalItem = this.originalItem != null ? this.originalItem.clone() : null;
        return copy;
    }
}
