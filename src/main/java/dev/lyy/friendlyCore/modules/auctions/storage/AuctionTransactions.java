package dev.lyy.friendlycore.modules.auctions.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** SQL is the authority for ownership; cache flags never decide who receives an item. */
public final class AuctionTransactions {
    private AuctionTransactions() {}

    public static dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics statistics(Connection connection, java.util.UUID uuid) throws SQLException {
        if (uuid == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(price), 0) FROM friendlycore_auction_history")) {
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    long count = rs.getLong(1);
                    double total = rs.getDouble(2);
                    return new dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics(count, count, total, total);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SUM(CASE WHEN seller_uuid = ? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN buyer_uuid = ? THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN seller_uuid = ? THEN price ELSE 0 END), "
                + "SUM(CASE WHEN buyer_uuid = ? THEN price ELSE 0 END) "
                + "FROM friendlycore_auction_history WHERE seller_uuid = ? OR buyer_uuid = ?")) {
            for (int i = 1; i <= 6; i++) statement.setString(i, uuid.toString());
            try (var rs = statement.executeQuery()) {
                rs.next();
                return new dev.lyy.friendlycore.modules.auctions.model.AuctionStatistics(
                        rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4));
            }
        }
    }

    private static boolean lockItem(Connection connection, int id) throws SQLException {
        // SQLite serializes writers instead of supporting SELECT FOR UPDATE.
        if (connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite")) return true;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM friendlycore_auction_items WHERE id = ? FOR UPDATE")) {
            statement.setInt(1, id);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    /** Commit the sale, its history and the full seller payout together. */
    public static boolean purchase(Connection connection, int id, double price, long earliestStart,
                                   String buyerUuid, String buyerName) throws SQLException {
        if (!Double.isFinite(price) || price <= 0.0) return false;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!lockItem(connection, id)) {
                connection.rollback();
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friendlycore_auction_history (item_bytes, seller_uuid, seller_name, buyer_uuid, buyer_name, price, sold_time) "
                    + "SELECT item_bytes, seller_uuid, seller_name, ?, ?, price, ? FROM friendlycore_auction_items "
                    + "WHERE id = ? AND price = ? AND start_time > ? AND seller_uuid <> ?")) {
                statement.setString(1, buyerUuid);
                statement.setString(2, buyerName);
                statement.setLong(3, System.currentTimeMillis());
                statement.setInt(4, id);
                statement.setDouble(5, price);
                statement.setLong(6, earliestStart);
                statement.setString(7, buyerUuid);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friendlycore_auction_payouts (user_uuid, amount, reason, created_at) "
                    + "SELECT seller_uuid, price, ?, ? FROM friendlycore_auction_items WHERE id = ?")) {
                statement.setString(1, "Auction #" + id + " sold");
                statement.setLong(2, System.currentTimeMillis());
                statement.setInt(3, id);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM friendlycore_auction_items WHERE id = ?")) {
                statement.setInt(1, id);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            restoreAutoCommit(connection, autoCommit);
        }
    }

    /**
     * Claim a user's queued payouts. Only rows this connection actually deleted are returned,
     * so two servers delivering at once can never deposit the same payout twice.
     */
    public static java.util.List<dev.lyy.friendlycore.modules.auctions.model.AuctionPayout> claimPayouts(
            Connection connection, java.util.UUID uuid) throws SQLException {
        java.util.List<dev.lyy.friendlycore.modules.auctions.model.AuctionPayout> candidates = new java.util.ArrayList<>();
        java.util.List<dev.lyy.friendlycore.modules.auctions.model.AuctionPayout> claimed = new java.util.ArrayList<>();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, amount, reason, created_at FROM friendlycore_auction_payouts WHERE user_uuid = ? ORDER BY id ASC")) {
                select.setString(1, uuid.toString());
                try (var rs = select.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(new dev.lyy.friendlycore.modules.auctions.model.AuctionPayout(
                                rs.getInt(1), uuid, rs.getDouble(2), rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM friendlycore_auction_payouts WHERE id = ? AND user_uuid = ?")) {
                for (var payout : candidates) {
                    delete.setInt(1, payout.getId());
                    delete.setString(2, uuid.toString());
                    if (delete.executeUpdate() == 1) claimed.add(payout);
                }
            }
            connection.commit();
            return claimed;
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            restoreAutoCommit(connection, autoCommit);
        }
    }

    /** A committed result must not turn into a failure (and a buyer refund) because cleanup threw. */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // The pool discards or resets a broken connection.
        }
    }

    public static boolean archive(Connection connection, int id) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!lockItem(connection, id)) {
                connection.rollback();
                return false;
            }
            // Copy and delete under one transaction, so failed archival cannot destroy a listing.
            int copied;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO friendlycore_auction_deleted (item_bytes, seller_uuid, seller_name, price, start_time, deleted_time) "
                    + "SELECT item_bytes, seller_uuid, seller_name, price, start_time, ? FROM friendlycore_auction_items WHERE id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setInt(2, id);
                copied = statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM friendlycore_auction_items WHERE id = ?")) {
                statement.setInt(1, id);
                if (copied != 1 || statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            restoreAutoCommit(connection, autoCommit);
        }
    }
}
