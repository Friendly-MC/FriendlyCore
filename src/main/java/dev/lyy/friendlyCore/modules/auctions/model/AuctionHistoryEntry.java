package dev.lyy.friendlycore.modules.auctions.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Represents an immutable completed auction transaction log record.
 */
public record AuctionHistoryEntry(
        int id,
        ItemStack itemStack,
        UUID sellerUuid,
        String sellerName,
        UUID buyerUuid,
        String buyerName,
        double price,
        long soldTime
) {

    public AuctionHistoryEntry {
        itemStack = itemStack != null ? itemStack.clone() : null;
        sellerName = sellerName != null ? sellerName : "Unknown";
        buyerName = buyerName != null ? buyerName : "Unknown";
    }

    public int getId() {
        return id;
    }

    @Override
    public ItemStack itemStack() {
        return itemStack != null ? itemStack.clone() : null;
    }

    public ItemStack getItemStack() {
        return itemStack();
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public UUID getBuyerUuid() {
        return buyerUuid;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public double getPrice() {
        return price;
    }

    public long getSoldTime() {
        return soldTime;
    }
}
