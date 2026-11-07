package dev.lyy.friendlycore.modules.orders.converter;

import dev.lyy.friendlycore.common.Messages;
import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.orders.enums.OrderState;
import dev.lyy.friendlycore.modules.orders.model.BuyOrder;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.logging.Level;

public class DonutOrderConverter {

    private final FriendlyCore plugin;

    public DonutOrderConverter(FriendlyCore plugin) {
        this.plugin = plugin;
    }

    public void convertAll(CommandSender sender) {
        plugin.getScheduler().runAsync(() -> {
            File donutOrderFolder = new File(plugin.getDataFolder().getParentFile(), "DonutOrder");
            File dbFile = new File(donutOrderFolder, "database.db");

            if (!dbFile.exists()) {
                dbFile = new File(donutOrderFolder, "orders.db");
            }

            if (!dbFile.exists()) {
                Messages.send(plugin, sender, "orders", "migrate-no-legacy-donutorder-sqlite-database-found");
                return;
            }

            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            int count = 0;

            try (Connection conn = DriverManager.getConnection(url)) {
                String selectSql = "SELECT * FROM orders";
                try (PreparedStatement ps = conn.prepareStatement(selectSql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            String idStr = rs.getString("order_id");
                            UUID orderId = idStr != null ? UUID.fromString(idStr) : UUID.randomUUID();
                            String creatorStr = rs.getString("creator_uuid");
                            UUID creatorUuid = creatorStr != null ? UUID.fromString(creatorStr) : UUID.randomUUID();
                            String playerName = rs.getString("player_name");
                            if (playerName == null) playerName = "Unknown";

                            byte[] itemBytes = rs.getBytes("item_bytes");
                            ItemStack item;
                            if (itemBytes != null && itemBytes.length > 0) {
                                item = ItemStack.deserializeBytes(itemBytes);
                            } else {
                                String matName = rs.getString("material");
                                Material mat = matName != null ? Material.matchMaterial(matName) : Material.STONE;
                                item = new ItemStack(mat != null ? mat : Material.STONE);
                            }

                            long totalAmount = rs.getLong("total_amount");
                            double pricePerItem = rs.getDouble("price_per_item");
                            long delivered = rs.getLong("delivered_amount");
                            long claimed = rs.getLong("claimed_amount");
                            double totalPaid = rs.getDouble("total_paid");
                            long creationTime = rs.getLong("creation_time");
                            long duration = rs.getLong("duration");
                            boolean cancelled = rs.getBoolean("cancelled");
                            int state = rs.getInt("state");

                            BuyOrder order = new BuyOrder(orderId, creatorUuid, playerName, item, totalAmount, pricePerItem, delivered, claimed, totalPaid, creationTime, duration, cancelled, OrderState.fromId(state));
                            plugin.getDatabaseManager().insertOrder(order).join();
                            plugin.getOrdersModule().getOrderManager().cacheOrder(order);
                            count++;
                        } catch (Exception ex) {
                            plugin.getLogger().log(Level.WARNING, "Failed to convert individual DonutOrder record", ex);
                        }
                    }
                }
                Messages.send(plugin, sender, "orders", "migrate-successfully-converted-legacy-donutorder-orders-into", "%count%", String.valueOf(count));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to read DonutOrder database", e);
                Messages.send(plugin, sender, "orders", "migrate-failed-to-convert-donutorder-data", "%error%", String.valueOf(e.getMessage()));
            }
        });
    }
}
