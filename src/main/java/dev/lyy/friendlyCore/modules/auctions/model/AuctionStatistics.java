package dev.lyy.friendlycore.modules.auctions.model;

public record AuctionStatistics(long sold, long purchased, double earned, double spent) {
    public static final AuctionStatistics EMPTY = new AuctionStatistics(0, 0, 0.0, 0.0);
}
