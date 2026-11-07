package dev.lyy.friendlycore.modules.auctions.webhooks;

import dev.lyy.friendlycore.FriendlyCore;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.economy.AuctionEconomyService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Sends Discord webhook embeds asynchronously for new auction house listings and completed purchases.
 */
public class AuctionDiscordWebhook {

    private final FriendlyCore plugin;
    private final AuctionEconomyService economyService;
    private boolean enabled = false;
    private String webhookUrl = "";

    public AuctionDiscordWebhook(FriendlyCore plugin, AuctionEconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        reload();
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/discord.yml");
        if (cfg != null) {
            this.enabled = cfg.getBoolean("enabled", false);
            this.webhookUrl = cfg.getString("webhook-url", "");
        }
    }

    public void sendListingWebhook(Player seller, AuctionItem item) {
        if (!enabled || webhookUrl == null || webhookUrl.isBlank()) return;
        String formattedPrice = economyService.format(item.getPrice());

        plugin.getScheduler().runAsync(() -> {
            FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/discord.yml");
            if (cfg == null) return;
            ConfigurationSection sec = cfg.getConfigurationSection("events.auction-start");
            if (sec == null || !sec.getBoolean("enabled", true)) return;

            String json = buildEmbedJson(sec, "New Auction Listing", "**{seller}** listed **{item}** for **{price}**",
                    "#55FF55", null, seller.getName(), formattedPrice, item.getItemStack());
            postToDiscord(webhookUrl, json);
        });
    }

    public void sendPurchaseWebhook(Player buyer, String sellerName, double price, ItemStack itemStack) {
        if (!enabled || webhookUrl == null || webhookUrl.isBlank()) return;
        String formattedPrice = economyService.format(price);

        plugin.getScheduler().runAsync(() -> {
            FileConfiguration cfg = plugin.getConfigService().get("modules/auctions/discord.yml");
            if (cfg == null) return;
            ConfigurationSection sec = cfg.getConfigurationSection("events.auction-buy");
            if (sec == null || !sec.getBoolean("enabled", true)) return;

            String json = buildEmbedJson(sec, "Auction Purchased", "**{buyer}** bought **{item}** from **{seller}** for **{price}**",
                    "#FFAA00", buyer.getName(), sellerName, formattedPrice, itemStack);
            postToDiscord(webhookUrl, json);
        });
    }

    private String buildEmbedJson(ConfigurationSection sec, String defaultTitle, String defaultDesc, String defaultColor,
                                  String buyer, String seller, String formattedPrice, ItemStack item) {
        String title = escapeJson(sec.getString("title", defaultTitle));
        String desc = sec.getString("description", defaultDesc);
        if (buyer != null) desc = desc.replace("{buyer}", buyer);
        if (seller != null) desc = desc.replace("{seller}", seller);
        if (formattedPrice != null) desc = desc.replace("{price}", formattedPrice);
        if (item != null) desc = desc.replace("{item}", formatItemName(item));
        int color = parseColor(sec.getString("color", defaultColor));

        return """
            {
                "embeds": [
                    {
                        "title": "%s",
                        "description": "%s",
                        "color": %d,
                        "timestamp": "%s"
                    }
                ]
            }
            """.formatted(title, escapeJson(desc), color, Instant.now().toString());
    }

    private String formatItemName(ItemStack item) {
        if (item == null) return "Air";
        String name = dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase.getItemDisplayName(item);
        return item.getAmount() > 1 && !name.startsWith(item.getAmount() + "x ") ? item.getAmount() + "x " + name : name;
    }

    private int parseColor(String hex) {
        if (hex == null || hex.isBlank()) return 0x55FF55;
        String clean = hex.replace("#", "");
        try {
            return Integer.parseInt(clean, 16);
        } catch (NumberFormatException e) {
            return 0x55FF55;
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private void postToDiscord(String webhook, String jsonPayload) {
        try {
            URL url = URI.create(webhook).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "FriendlyCore-Auctions");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                plugin.getLogger().warning("Discord webhook returned code: " + responseCode);
            }
            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send auction Discord webhook: " + e.getMessage());
        }
    }
}
