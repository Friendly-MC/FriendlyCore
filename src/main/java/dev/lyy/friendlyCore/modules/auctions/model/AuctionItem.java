package dev.lyy.friendlycore.modules.auctions.model;

import dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an auction house listing in memory, including item data, pricing,
 * seller metadata, lifecycle state, and thread-safe removal state.
 */
public class AuctionItem {

    private final int id;
    private final ItemStack itemStack;
    private final UUID sellerUuid;
    private final String sellerName;
    private final double price;
    private volatile long startTime;
    private final AtomicBoolean removed = new AtomicBoolean(false);
    private volatile AuctionItemState itemState;

    public AuctionItem(int id, ItemStack itemStack, UUID sellerUuid, String sellerName, double price, long startTime) {
        this.id = id;
        this.itemStack = itemStack != null ? itemStack.clone() : null;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.price = price;
        this.startTime = startTime;
        this.itemState = AuctionItemState.ACTIVE;
    }

    public int getId() {
        return id;
    }

    public ItemStack getItemStack() {
        return itemStack != null ? itemStack.clone() : null;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName != null ? sellerName : "Unknown";
    }

    public double getPrice() {
        return price;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public AuctionItemState getItemState() {
        return itemState;
    }

    public void setItemState(AuctionItemState itemState) {
        this.itemState = itemState;
    }

    public boolean isExpired(long expireDurationMillis) {
        return System.currentTimeMillis() > (startTime + expireDurationMillis);
    }

    public boolean isDeleted(long expireDurationMillis, long deletionDurationMillis) {
        return System.currentTimeMillis() > (startTime + expireDurationMillis + deletionDurationMillis);
    }

    public long getRemainingExpiryMillis(long expireDurationMillis) {
        return Math.max(0, (startTime + expireDurationMillis) - System.currentTimeMillis());
    }

    public long getRemainingDeletionMillis(long expireDurationMillis, long deletionDurationMillis) {
        return Math.max(0, (startTime + expireDurationMillis + deletionDurationMillis) - System.currentTimeMillis());
    }

    public void makeExpired(long expireDurationMillis) {
        this.startTime = System.currentTimeMillis() - expireDurationMillis - 1000L;
        this.itemState = AuctionItemState.EXPIRED;
    }

    public boolean markRemoved() {
        return !removed.getAndSet(true);
    }

    public boolean unmarkRemoved() {
        return removed.getAndSet(false);
    }

    public boolean isRemoved() {
        return removed.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuctionItem that = (AuctionItem) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    private volatile String searchKeywords;

    public String getSearchKeywords() {
        if (searchKeywords == null) {
            StringBuilder sb = new StringBuilder();
            if (sellerName != null) sb.append(sellerName.toLowerCase(java.util.Locale.ROOT)).append(" ");
            if (itemStack != null) {
                sb.append(itemStack.getType().name().toLowerCase(java.util.Locale.ROOT)).append(" ");
                sb.append(itemStack.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')).append(" ");
                if (itemStack.hasItemMeta()) {
                    var meta = itemStack.getItemMeta();
                    if (meta.hasDisplayName()) {
                        sb.append(meta.getDisplayName().toLowerCase(java.util.Locale.ROOT)).append(" ");
                        if (meta.displayName() != null) {
                            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                    .serialize(meta.displayName()).toLowerCase(java.util.Locale.ROOT);
                            sb.append(plain).append(" ");
                        }
                    }
                    if (meta.hasLore()) {
                        java.util.List<String> lore = meta.getLore();
                        if (lore != null) {
                            for (String l : lore) sb.append(l.toLowerCase(java.util.Locale.ROOT)).append(" ");
                        }
                    }
                    if (meta.hasEnchants()) {
                        for (var entry : meta.getEnchants().entrySet()) {
                            String enchKey = entry.getKey().getKey().getKey().toLowerCase(java.util.Locale.ROOT);
                            sb.append(enchKey).append(" ");
                            sb.append(enchKey.replace('_', ' ')).append(" ");
                            sb.append(enchKey.replace('_', ' ')).append(" ").append(entry.getValue()).append(" ");
                        }
                    }
                    if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm && esm.hasStoredEnchants()) {
                        for (var entry : esm.getStoredEnchants().entrySet()) {
                            String enchKey = entry.getKey().getKey().getKey().toLowerCase(java.util.Locale.ROOT);
                            sb.append(enchKey).append(" ");
                            sb.append(enchKey.replace('_', ' ')).append(" ");
                            sb.append(enchKey.replace('_', ' ')).append(" ").append(entry.getValue()).append(" ");
                        }
                    }
                }
            }
            searchKeywords = sb.toString();
        }
        return searchKeywords;
    }
}
