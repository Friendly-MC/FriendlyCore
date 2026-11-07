package dev.lyy.friendlycore.modules.auctions;

import dev.lyy.friendlycore.common.redis.CrossServerPacket;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionActionType;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionItemState;
import dev.lyy.friendlycore.modules.auctions.enums.AuctionSorting;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionExpressionEvaluator;
import dev.lyy.friendlycore.modules.auctions.gui.AuctionMenuBase;
import dev.lyy.friendlycore.modules.auctions.model.AuctionItem;
import dev.lyy.friendlycore.modules.auctions.model.AuctionUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AuctionModuleTest {

    @Test
    @DisplayName("AuctionItem expiration and deletion timing calculations")
    public void testAuctionItemTiming() {
        UUID seller = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long oneDayAgo = now - 86400000L;
        long eightDaysAgo = now - (8 * 86400000L);

        // Active item
        AuctionItem active = new AuctionItem(1, null, seller, "Player1", 100.0, now);
        assertEquals(AuctionItemState.ACTIVE, active.getItemState());
        assertFalse(active.isExpired(86400000L));
        assertFalse(active.isDeleted(86400000L, 604800000L));
        assertTrue(active.getRemainingExpiryMillis(86400000L) > 0);

        // Expired item (1 day ago)
        AuctionItem expired = new AuctionItem(2, null, seller, "Player1", 100.0, oneDayAgo - 1000L);
        assertTrue(expired.isExpired(86400000L));
        assertEquals(0, expired.getRemainingExpiryMillis(86400000L));
        assertFalse(expired.isDeleted(86400000L, 604800000L));

        // Deleted item (8 days ago)
        AuctionItem deleted = new AuctionItem(3, null, seller, "Player1", 100.0, eightDaysAgo - 1000L);
        assertTrue(deleted.isExpired(86400000L));
        assertTrue(deleted.isDeleted(86400000L, 604800000L));
        assertEquals(0, deleted.getRemainingDeletionMillis(86400000L, 604800000L));

        // Mark removed concurrency flag
        assertFalse(active.isRemoved());
        assertTrue(active.markRemoved());
        assertTrue(active.isRemoved());
        assertFalse(active.markRemoved()); // Already removed
    }

    @Test
    @DisplayName("AuctionSorting cycle next and previous navigation")
    public void testAuctionSortingNavigation() {
        AuctionSorting sorting = AuctionSorting.NEWEST;
        assertEquals(AuctionSorting.OLDEST, sorting.next());
        assertEquals(AuctionSorting.CHEAPEST, sorting.next().next());
        assertEquals(AuctionSorting.PRICIEST, sorting.next().next().next());
        assertEquals(AuctionSorting.NEWEST, sorting.next().next().next().next());

        assertEquals(AuctionSorting.PRICIEST, sorting.previous());
        assertEquals(AuctionSorting.CHEAPEST, sorting.previous().previous());
    }

    @Test
    @DisplayName("AuctionActionType names and parsing")
    public void testActionTypes() {
        assertEquals("Purchase", AuctionActionType.PURCHASE.getDisplayName());
        assertEquals("Create", AuctionActionType.CREATE_AUCTION.getDisplayName());
        assertEquals("Cancel (Self)", AuctionActionType.CANCEL_SELF.getDisplayName());
        assertEquals("Cancel (Admin)", AuctionActionType.CANCEL_OTHER.getDisplayName());
        assertEquals("Admin Clone/Take", AuctionActionType.TAKE_ITEM.getDisplayName());
    }

    @Test
    @DisplayName("AuctionUser state and extra limit management")
    public void testAuctionUser() {
        UUID uuid = UUID.randomUUID();
        AuctionUser user = new AuctionUser(uuid, "Steve", 3);

        assertEquals(uuid, user.getUuid());
        assertEquals("Steve", user.getUsername());
        assertEquals(3, user.getExtraLimit());
        assertEquals("all", user.getSelectedCategory());
        assertEquals(AuctionSorting.NEWEST, user.getSelectedSorting());
        assertNull(user.getSearchFilter());

        user.setExtraLimit(5);
        assertEquals(5, user.getExtraLimit());

        long loadVersion = user.getExtraLimitVersion();
        user.setExtraLimit(8);
        user.applyLoadedExtraLimit(2, loadVersion);
        assertEquals(8, user.getExtraLimit(), "A late database load must not overwrite a newer admin update");

        user.setSelectedCategory("weapons");
        assertEquals("weapons", user.getSelectedCategory());

        user.setSelectedSorting(AuctionSorting.CHEAPEST);
        assertEquals(AuctionSorting.CHEAPEST, user.getSelectedSorting());

        user.setSearchFilter("Diamond");
        assertEquals("Diamond", user.getSearchFilter());
    }

    @Test
    @DisplayName("CrossServerPacket Auction Action Types")
    public void testAuctionPackets() {
        CrossServerPacket insert = new CrossServerPacket("s1", CrossServerPacket.Action.AUCTION_ITEM_INSERT, null, null, 101, "101");
        assertEquals(CrossServerPacket.Action.AUCTION_ITEM_INSERT, insert.getAction());
        assertEquals(101, (int) insert.getAmount());

        UUID target = UUID.randomUUID();
        CrossServerPacket payout = new CrossServerPacket("s1", CrossServerPacket.Action.AUCTION_PAYOUT, target, "Alex", 250.0, "Sold item #101");
        assertEquals(CrossServerPacket.Action.AUCTION_PAYOUT, payout.getAction());
        assertEquals(target, payout.getTargetUuid());
        assertEquals("Alex", payout.getTargetName());
        assertEquals(250.0, payout.getAmount());
    }

    @Test
    @DisplayName("AuctionExpressionEvaluator condition checks")
    public void testExpressionEvaluator() {
        assertTrue(AuctionExpressionEvaluator.eval("1 > 0"));
        assertFalse(AuctionExpressionEvaluator.eval("1 > 2"));
        assertTrue(AuctionExpressionEvaluator.eval("1 < 2"));
        assertFalse(AuctionExpressionEvaluator.eval("2 < 1"));
        assertTrue(AuctionExpressionEvaluator.eval("2 >= 2"));
        assertTrue(AuctionExpressionEvaluator.eval("3 >= 2"));
        assertFalse(AuctionExpressionEvaluator.eval("1 >= 2"));
        assertTrue(AuctionExpressionEvaluator.eval("2 <= 2"));
        assertTrue(AuctionExpressionEvaluator.eval("1 <= 2"));
        assertFalse(AuctionExpressionEvaluator.eval("3 <= 2"));

        assertTrue(AuctionExpressionEvaluator.eval("true == true"));
        assertTrue(AuctionExpressionEvaluator.eval("false == false"));
        assertFalse(AuctionExpressionEvaluator.eval("true == false"));
        assertTrue(AuctionExpressionEvaluator.eval("true != false"));

        assertTrue(AuctionExpressionEvaluator.eval("newest == newest"));
        assertFalse(AuctionExpressionEvaluator.eval("newest == oldest"));
        assertTrue(AuctionExpressionEvaluator.eval("\"newest\" == \"newest\""));
        assertTrue(AuctionExpressionEvaluator.eval("'weapons' == 'weapons'"));

        assertTrue(AuctionExpressionEvaluator.eval("true"));
        assertFalse(AuctionExpressionEvaluator.eval("false"));
        assertTrue(AuctionExpressionEvaluator.eval(""));
        assertTrue(AuctionExpressionEvaluator.eval(null));
    }

    @Test
    @DisplayName("AuctionMenuBase slot string parsing")
    public void testSlotStringParsing() {
        // Single slot
        List<Integer> s1 = AuctionMenuBase.parseSlotString("4", 54);
        assertEquals(List.of(4), s1);

        // Range
        List<Integer> s2 = AuctionMenuBase.parseSlotString("0-4", 54);
        assertEquals(List.of(0, 1, 2, 3, 4), s2);

        // Stride / Column
        List<Integer> s3 = AuctionMenuBase.parseSlotString("10|28", 54);
        assertEquals(List.of(10, 19, 28), s3);

        // Border (6 rows = 54 slots)
        List<Integer> border = AuctionMenuBase.parseSlotString("border", 54);
        assertTrue(border.contains(0));
        assertTrue(border.contains(8));
        assertTrue(border.contains(45));
        assertTrue(border.contains(53));
        assertTrue(border.contains(9));
        assertTrue(border.contains(17));
        assertFalse(border.contains(10));
        assertFalse(border.contains(22));
    }

    @Test
    @DisplayName("Auction events instantiate and handle cancellable states correctly")
    public void testAuctionEvents() {
        dev.lyy.friendlycore.modules.auctions.events.AuctionPreSellEvent preSell =
                new dev.lyy.friendlycore.modules.auctions.events.AuctionPreSellEvent(null, null, 150.0);
        assertEquals(150.0, preSell.getPrice());
        assertFalse(preSell.isCancelled());
        preSell.setCancelled(true);
        assertTrue(preSell.isCancelled());
        preSell.setPrice(200.0);
        assertEquals(200.0, preSell.getPrice());

        dev.lyy.friendlycore.modules.auctions.events.AuctionPrePurchaseEvent prePurchase =
                new dev.lyy.friendlycore.modules.auctions.events.AuctionPrePurchaseEvent(null, null);
        assertFalse(prePurchase.isCancelled());
        prePurchase.setCancelled(true);
        assertTrue(prePurchase.isCancelled());

        assertNotNull(new dev.lyy.friendlycore.modules.auctions.events.AuctionLoadEvent().getHandlers());
        assertNotNull(new dev.lyy.friendlycore.modules.auctions.events.AuctionItemRenderEvent(null, null).getHandlers());
    }

    @Test
    @DisplayName("Dynamic slot calculation accurately finds unoccupied slots")
    public void testDynamicSlotCalculation() {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("rows", 5);
        yaml.set("page-size", 27);
        yaml.set("filler.slot", List.of("0-8", "36-44"));
        yaml.set("item.slot", 4);
        yaml.set("close.slot", 40);

        List<Integer> slots = AuctionMenuBase.calculateAvailableItemSlots(yaml, 45, 27);
        assertEquals(27, slots.size());
        assertEquals(9, slots.get(0));
        assertEquals(35, slots.get(slots.size() - 1));
        assertFalse(slots.contains(4));
        assertFalse(slots.contains(0));
        assertFalse(slots.contains(8));
        assertFalse(slots.contains(40));
    }

    @Test
    @DisplayName("CrossServerPacket AUCTION_USER_LIMIT_SYNC serialization and action verification")
    public void testAuctionUserLimitSyncPacket() {
        UUID playerUuid = UUID.randomUUID();
        CrossServerPacket packet = new CrossServerPacket("server-1", CrossServerPacket.Action.AUCTION_USER_LIMIT_SYNC,
                playerUuid, "TestPlayer", 10, "10");
        assertEquals(CrossServerPacket.Action.AUCTION_USER_LIMIT_SYNC, packet.getAction());
        assertEquals(playerUuid, packet.getTargetUuid());
        assertEquals("TestPlayer", packet.getTargetName());
        assertEquals(10, (int) packet.getAmount());
    }

    @Test
    @DisplayName("AuctionItem search keywords optimization test")
    public void testSearchKeywordsOptimization() {
        UUID seller = UUID.randomUUID();
        AuctionItem item = new AuctionItem(42, null, seller, "Notch", 500.0, System.currentTimeMillis());
        assertTrue(item.getSearchKeywords().contains("notch"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine.matches(item, "notch"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine.matches(item, "NOTCH"));
        assertFalse(dev.lyy.friendlycore.modules.auctions.search.AuctionSearchEngine.matches(item, "herobrine"));
    }

    @Test
    @DisplayName("Verify auction messages.yml configuration integrity and required AxAuctions keys")
    public void testMessagesConfigurationIntegrity() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in, "messages.yml must exist in classpath");
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(15, yaml.getInt("version"), "AxAuctions lang.yml version must be 15");
        assertFalse(yaml.getStringList("help").isEmpty(), "help list must not be empty");
        assertFalse(yaml.getStringList("admin-help").isEmpty(), "admin-help list must not be empty");
        assertNotNull(yaml.getString("container"), "container key must be present");
        assertNotNull(yaml.getString("date-format"), "date-format must be present");
        assertNotNull(yaml.getString("admin-action.take-item"), "admin-action.take-item must be present");
        assertNotNull(yaml.getString("admin-action.removed-info"), "admin-action.removed-info must be present");
        assertNotNull(yaml.getString("admin-action.cancelled-info"), "admin-action.cancelled-info must be present");
        assertNotNull(yaml.getString("sell.invalid-price"), "sell.invalid-price must be present");
        assertNotNull(yaml.getString("commands.player-only"), "commands.player-only must be present");
        assertNotNull(yaml.getString("commands.no-permission"), "commands.no-permission must be present");
        assertNotNull(yaml.getString("limit-changed"), "limit-changed must be present");

        // Verify sign and chat input templates match AxAuctions lang.yml
        java.util.List<String> signLines = yaml.getStringList("input.sign");
        assertEquals(4, signLines.size(), "input.sign must have 4 lines");
        assertEquals("", signLines.get(0), "First line must be empty for player input");
        assertEquals("-----------", signLines.get(1));
        assertEquals("Write in the first", signLines.get(2));
        assertEquals("line to search!", signLines.get(3));

        assertFalse(yaml.getStringList("input.chat").isEmpty(), "input.chat must not be empty");
    }

    @Test
    @DisplayName("Verify auction config.yml contains search-mode and disable-sign-input")
    public void testConfigSearchSettings() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/config.yml");
        assertNotNull(in, "config.yml must exist in classpath");
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(yaml.contains("search-mode"), "config.yml must contain search-mode");
        assertEquals("sign", yaml.getString("search-mode").toLowerCase(java.util.Locale.ROOT));
        assertTrue(yaml.contains("disable-sign-input"), "config.yml must contain disable-sign-input");
        assertFalse(yaml.getBoolean("disable-sign-input"));
        assertEquals(10.0, yaml.getDouble("min-price"));
        assertEquals(1, yaml.getInt("default-listing-limit"));
        assertEquals("main", yaml.getString("default-menu"));
        assertEquals(1, yaml.getInt("timer-format"));
        assertEquals("yyyy/MM/dd HH:mm:ss", yaml.getString("date-format"));
        assertEquals(100L, yaml.getLong("gui-refresh-cooldown-milliseconds"));
        assertFalse(yaml.getBoolean("only-give-money-on-join"));
        assertEquals(3, yaml.getInt("delay-money-give-seconds"));
        assertFalse(yaml.contains("tax-percent"), "tax configuration must stay removed");
        assertEquals(2, yaml.getMapList("blacklist-items").size());

        java.io.InputStream pluginIn = getClass().getClassLoader().getResourceAsStream("plugin.yml");
        assertNotNull(pluginIn);
        org.bukkit.configuration.file.YamlConfiguration pluginYaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(pluginIn, java.nio.charset.StandardCharsets.UTF_8));
        for (String permission : java.util.List.of("open", "search", "view", "history", "deleted", "help")) {
            assertTrue(pluginYaml.getBoolean("permissions.axauctions." + permission + ".default"), permission);
        }
        String pluginText;
        try (java.io.InputStream textIn = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(textIn);
            pluginText = new String(textIn.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        assertTrue(pluginText.contains("  axauctions.limit.1:"));
        for (String permission : java.util.List.of("forceopen", "history.admin", "history.admin.take",
                "deleted.admin", "deleted.admin.take", "logs.admin", "limit", "convert", "reload")) {
            String key = "axauctions." + permission;
            assertTrue(pluginText.contains("  " + key + ":"), permission);
        }

        java.io.InputStream categoriesIn = getClass().getClassLoader()
                .getResourceAsStream("modules/auctions/categories.yml");
        assertNotNull(categoriesIn);
        org.bukkit.configuration.file.YamlConfiguration categories =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(categoriesIn, java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(categories.getBoolean("enabled"));
        assertNotNull(categories.getConfigurationSection("tools"));
        assertNull(categories.getConfigurationSection("categories"),
                "AxAuctions 2.7.8 category sections live at the YAML root");
    }

    @Test
    @DisplayName("Verify NumberFormatter.parseAmount compact notation and error handling")
    public void testCompactNumberParsing() {
        assertEquals(10000.0, dev.lyy.friendlycore.common.NumberFormatter.parseAmount("10k"));
        assertEquals(1500000.0, dev.lyy.friendlycore.common.NumberFormatter.parseAmount("1.5m"));
        assertEquals(2500000000.0, dev.lyy.friendlycore.common.NumberFormatter.parseAmount("2.5b"));
        assertEquals(1000000000000.0, dev.lyy.friendlycore.common.NumberFormatter.parseAmount("1t"));
        assertEquals(100.5, dev.lyy.friendlycore.common.NumberFormatter.parseAmount("100.50"));

        assertThrows(NumberFormatException.class, () -> dev.lyy.friendlycore.common.NumberFormatter.parseAmount(""));
        assertThrows(NumberFormatException.class, () -> dev.lyy.friendlycore.common.NumberFormatter.parseAmount("abc"));
        assertThrows(NumberFormatException.class, () -> dev.lyy.friendlycore.common.NumberFormatter.parseAmount(null));
    }

    @Test
    @DisplayName("AuctionSafetyValidator.matchesWildcard null safety and case insensitivity")
    public void testWildcardMatchingRobustness() {
        assertFalse(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard(null, "*"));
        assertFalse(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard(null, "sword"));
        assertFalse(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("sword", null));
        assertFalse(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard(null, null));

        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Diamond Sword", "*sword*"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Diamond Sword", "*SWORD*"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("DIAMOND_SWORD", "diamond_sword"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("diamond_sword", "DIAMOND_SWORD"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Excalibur", "excalibur"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Infinity Blade", "*blade"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Infinity Blade", "infinity*"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Any", "*"));
        assertTrue(dev.lyy.friendlycore.modules.auctions.safety.AuctionSafetyValidator.matchesWildcard("Any", "**"));
    }

    @Test
    @DisplayName("Auction DTO Java 21 records accessor parity and defaults")
    public void testAuctionDtoRecordsParity() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry history =
                new dev.lyy.friendlycore.modules.auctions.model.AuctionHistoryEntry(
                        1, null, seller, null, buyer, null, 100.0, 1000L
                );
        assertEquals(1, history.id());
        assertEquals(1, history.getId());
        assertNull(history.itemStack());
        assertNull(history.getItemStack());
        assertEquals("Unknown", history.sellerName());
        assertEquals("Unknown", history.getSellerName());
        assertEquals("Unknown", history.buyerName());
        assertEquals("Unknown", history.getBuyerName());
        assertEquals(100.0, history.price());
        assertEquals(100.0, history.getPrice());
        assertEquals(1000L, history.soldTime());
        assertEquals(1000L, history.getSoldTime());

        dev.lyy.friendlycore.modules.auctions.model.AuctionPayout payout =
                new dev.lyy.friendlycore.modules.auctions.model.AuctionPayout(5, seller, 250.0, "Sold item", 2000L);
        assertEquals(5, payout.id());
        assertEquals(5, payout.getId());
        assertEquals(seller, payout.userUuid());
        assertEquals(seller, payout.getUserUuid());
        assertEquals(250.0, payout.amount());
        assertEquals(250.0, payout.getAmount());
        assertEquals("Sold item", payout.reason());
        assertEquals("Sold item", payout.getReason());
        assertEquals(2000L, payout.createdAt());
        assertEquals(2000L, payout.getCreatedAt());

        dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry log =
                new dev.lyy.friendlycore.modules.auctions.model.AuctionLogEntry(
                        10, seller, null, AuctionActionType.PURCHASE, null, 3000L
                );
        assertEquals(10, log.id());
        assertEquals(10, log.getId());
        assertEquals(seller, log.actorUuid());
        assertEquals(seller, log.getActorUuid());
        assertEquals("Unknown", log.actorName());
        assertEquals("Unknown", log.getActorName());
        assertEquals(AuctionActionType.PURCHASE, log.actionType());
        assertEquals(AuctionActionType.PURCHASE, log.getActionType());
        assertEquals("", log.extra());
        assertEquals("", log.getExtra());
        assertEquals(3000L, log.timestamp());
        assertEquals(3000L, log.getTimestamp());
    }

    @Test
    @DisplayName("Verify clean item display name formatting without duplicate amount")
    public void testCleanItemDisplayName() {
        assertEquals("Air", AuctionMenuBase.getItemDisplayName(null));

        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack() {
            @Override
            public org.bukkit.Material getType() {
                return org.bukkit.Material.DIAMOND;
            }

            @Override
            public boolean hasItemMeta() {
                return false;
            }
        };
        assertEquals("Diamond", AuctionMenuBase.getItemDisplayName(item));

        org.bukkit.inventory.ItemStack single = new org.bukkit.inventory.ItemStack() {
            @Override
            public org.bukkit.Material getType() {
                return org.bukkit.Material.GOLDEN_APPLE;
            }

            @Override
            public boolean hasItemMeta() {
                return false;
            }
        };
        assertEquals("Golden Apple", AuctionMenuBase.getItemDisplayName(single));
    }

    @Test
    @DisplayName("Verify formatEnumName human readable title casing")
    public void testFormatEnumName() {
        assertEquals("Diamond Sword", AuctionMenuBase.formatEnumName("DIAMOND_SWORD"));
        assertEquals("Golden Apple", AuctionMenuBase.formatEnumName("GOLDEN_APPLE"));
        assertEquals("Netherite Chestplate", AuctionMenuBase.formatEnumName("NETHERITE_CHESTPLATE"));
        assertEquals("", AuctionMenuBase.formatEnumName(null));
        assertEquals("", AuctionMenuBase.formatEnumName(""));
    }

    @Test
    @DisplayName("Verify hasPagePlaceholder detects both curly braces and percent signs")
    public void testHasPagePlaceholder() {
        assertTrue(AuctionMenuBase.hasPagePlaceholder("Auctions ({current_page}/{max_page})"));
        assertTrue(AuctionMenuBase.hasPagePlaceholder("Auctions ({currentPage}/{maxPages})"));
        assertTrue(AuctionMenuBase.hasPagePlaceholder("Auctions (%current_page%/%max_page%)"));
        assertTrue(AuctionMenuBase.hasPagePlaceholder("Page %page%"));
        assertFalse(AuctionMenuBase.hasPagePlaceholder("Auctions Categories"));
        assertFalse(AuctionMenuBase.hasPagePlaceholder("Confirm Purchase"));
        assertFalse(AuctionMenuBase.hasPagePlaceholder(null));
    }

    @Test
    @DisplayName("Verify sell.success message contains %item% placeholder in messages.yml")
    public void testSellSuccessPlaceholder() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        String sellSuccess = yaml.getString("sell.success");
        assertNotNull(sellSuccess);
        assertTrue(sellSuccess.contains("%item%"), "sell.success must contain %item% placeholder");
        assertTrue(sellSuccess.contains("%amount%"), "sell.success must contain %amount% placeholder");
        assertTrue(sellSuccess.contains("%price%"), "sell.success must contain %price% placeholder");

        // Simulate substitution
        String rendered = sellSuccess
                .replace("%amount%", "64")
                .replace("%item%", "Diamond")
                .replace("%price%", "1,000,000.00");
        assertTrue(rendered.contains("64x Diamond"));
        assertFalse(rendered.contains("64x 64x Diamond"));
    }

    @Test
    @DisplayName("Verify DonutSMP lore templates contain bullet icons and clean cues")
    public void testDonutSmpLoreTemplates() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        List<String> itemLore = yaml.getStringList("item-lore");
        assertTrue(itemLore.stream().anyMatch(line -> line.contains("▪") && line.contains("Price:")), "item-lore must contain bullet price");
        assertTrue(itemLore.stream().anyMatch(line -> line.contains("▪") && line.contains("Seller:")), "item-lore must contain bullet seller");
        assertTrue(itemLore.stream().anyMatch(line -> line.contains("▪") && line.contains("Expires in:")), "item-lore must contain bullet expiry");
        assertTrue(itemLore.stream().anyMatch(line -> line.contains("Click to purchase!")), "item-lore must contain clean purchase cue");

        List<String> ownLore = yaml.getStringList("item-lore-own");
        assertTrue(ownLore.stream().anyMatch(line -> line.contains("▪") && line.contains("Price:")), "item-lore-own must contain bullet price");
        assertTrue(ownLore.stream().anyMatch(line -> line.contains("▪") && line.contains("Expires in:")), "item-lore-own must contain bullet expiry");
        assertTrue(ownLore.stream().anyMatch(line -> line.contains("Click to cancel listing!")), "item-lore-own must contain clean cancel cue");
    }

    @Test
    @DisplayName("Verify formatEnumName acronym and title-case handling")
    public void testFormatEnumNameAcronyms() {
        assertEquals("TNT", AuctionMenuBase.formatEnumName("TNT"));
        assertEquals("XP Bottle", AuctionMenuBase.formatEnumName("XP_BOTTLE"));
        assertEquals("Diamond Sword", AuctionMenuBase.formatEnumName("DIAMOND_SWORD"));
        assertEquals("Golden Apple", AuctionMenuBase.formatEnumName("GOLDEN_APPLE"));
        assertEquals("", AuctionMenuBase.formatEnumName(null));
        assertEquals("", AuctionMenuBase.formatEnumName(""));
    }

    static class FakeAirItem extends org.bukkit.inventory.ItemStack {
        @Override
        public org.bukkit.Material getType() {
            return org.bukkit.Material.AIR;
        }
    }

    @Test
    @DisplayName("Verify getItemDisplayName fallback handling for null and air")
    public void testGetItemDisplayNameFallbacks() throws Exception {
        assertEquals("Air", AuctionMenuBase.getItemDisplayName(null));
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        org.bukkit.inventory.ItemStack air = (org.bukkit.inventory.ItemStack) unsafe.allocateInstance(FakeAirItem.class);
        assertEquals("Air", AuctionMenuBase.getItemDisplayName(air));
    }

    @Test
    @DisplayName("Verify formatTimeRemaining ceiling calculation and sleek DonutSMP style")
    public void testFormatTimeRemainingCalculations() {
        // Less than 1 second remaining must show 1s, never premature 0s
        assertEquals("1s", AuctionMenuBase.formatTimeRemaining(500L, 1, "d", "h", "m", "s", "Expired"));
        assertEquals("1s", AuctionMenuBase.formatTimeRemaining(1000L, 1, "d", "h", "m", "s", "Expired"));
        // Expired when <= 0
        assertEquals("Expired", AuctionMenuBase.formatTimeRemaining(0L, 1, "d", "h", "m", "s", "Expired"));
        assertEquals("Expired", AuctionMenuBase.formatTimeRemaining(-500L, 1, "d", "h", "m", "s", "Expired"));
        // 23 hours 59 minutes
        long almostOneDay = (23 * 3600L + 59 * 60L + 35L) * 1000L;
        assertEquals("23h 59m", AuctionMenuBase.formatTimeRemaining(almostOneDay, 1, "d", "h", "m", "s", "Expired"));
        // 1 day 4 hours
        long oneDayFourHours = (86400L + 4 * 3600L) * 1000L;
        assertEquals("1d 4h", AuctionMenuBase.formatTimeRemaining(oneDayFourHours, 1, "d", "h", "m", "s", "Expired"));
        // 45 minutes 12 seconds
        long fortyFiveMinutes = (45 * 60L + 12L) * 1000L;
        assertEquals("45m 12s", AuctionMenuBase.formatTimeRemaining(fortyFiveMinutes, 1, "d", "h", "m", "s", "Expired"));
    }

    @Test
    @DisplayName("Verify my-items.expired and my-items.deleted templates in messages.yml")
    public void testExpiredAndDeletedLifecycleMessages() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        String expiredMsg = yaml.getString("my-items.expired");
        assertNotNull(expiredMsg);
        assertTrue(expiredMsg.contains("%amount%"), "my-items.expired must contain %amount%");
        assertTrue(expiredMsg.contains("%item%"), "my-items.expired must contain %item%");
        assertTrue(expiredMsg.contains("%date%"), "my-items.expired must contain %date%");

        String deletedMsg = yaml.getString("my-items.deleted");
        assertNotNull(deletedMsg);
        assertTrue(deletedMsg.contains("%amount%"), "my-items.deleted must contain %amount%");
        assertTrue(deletedMsg.contains("%item%"), "my-items.deleted must contain %item%");
        assertTrue(deletedMsg.contains("%date%"), "my-items.deleted must contain %date%");
    }

    @Test
    @DisplayName("Verify history and deleted item lore templates contain sleek DonutSMP indicators")
    public void testHistoryAndDeletedLoreFormatting() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in);
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        for (String key : List.of("history-item-lore", "history-admin-item-lore", "deleted-gui-item-lore", "deleted-gui-admin-item-lore")) {
            List<String> lore = yaml.getStringList(key);
            assertFalse(lore.isEmpty(), key + " must not be empty");
            assertTrue(lore.stream().anyMatch(line -> line.contains("▪") && line.contains("Price:")), key + " must contain bullet price");
            assertTrue(lore.stream().anyMatch(line -> line.contains("▪") && line.contains("Seller:")), key + " must contain bullet seller");
            assertTrue(lore.stream().anyMatch(line -> line.contains("%container%")), key + " must contain %container%");
        }
    }

    @Test
    @DisplayName("AuctionManager: formatPriceClean and formatPriceShort format numbers accurately")
    public void testAuctionNumberFormatting() {
        // Clean formatting without trailing .00 for whole numbers
        assertEquals("0", AuctionManager.formatPriceClean(0.0));
        assertEquals("1,000", AuctionManager.formatPriceClean(1000.0));
        assertEquals("10,000", AuctionManager.formatPriceClean(10000.0));
        assertEquals("64,000", AuctionManager.formatPriceClean(64000.0));
        assertEquals("1,000,000", AuctionManager.formatPriceClean(1000000.0));
        assertEquals("10.5", AuctionManager.formatPriceClean(10.5));
        assertEquals("10.25", AuctionManager.formatPriceClean(10.25));
        assertEquals("0", AuctionManager.formatPriceClean(Double.NaN));
        assertEquals("0", AuctionManager.formatPriceClean(Double.POSITIVE_INFINITY));

        // Short compact formatting (1k, 10k, 1.5M, 1B, 1T)
        assertEquals("0", AuctionManager.formatPriceShort(0.0));
        assertEquals("10.5", AuctionManager.formatPriceShort(10.5));
        assertEquals("500", AuctionManager.formatPriceShort(500.0));
        assertEquals("1k", AuctionManager.formatPriceShort(1000.0));
        assertEquals("10k", AuctionManager.formatPriceShort(10000.0));
        assertEquals("64k", AuctionManager.formatPriceShort(64000.0));
        assertEquals("1.5M", AuctionManager.formatPriceShort(1500000.0));
        assertEquals("1B", AuctionManager.formatPriceShort(1000000000.0));
        assertEquals("1T", AuctionManager.formatPriceShort(1000000000000.0));
        assertEquals("-1.5k", AuctionManager.formatPriceShort(-1500.0));
        assertEquals("0", AuctionManager.formatPriceShort(Double.NaN));
        assertEquals("0", AuctionManager.formatPriceShort(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("Auction config and messages contain action-bar announcements and short-price settings")
    public void testActionBarAndBroadcastConfigurationIntegrity() {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("modules/auctions/messages.yml");
        assertNotNull(in);
        org.bukkit.configuration.file.YamlConfiguration msgs =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));

        String sellActionBar = msgs.getString("sell.action-bar-broadcast");
        assertNotNull(sellActionBar, "sell.action-bar-broadcast must be configured");
        assertTrue(sellActionBar.contains("%player%"), "sell.action-bar-broadcast must contain %player%");
        assertTrue(sellActionBar.contains("%amount%"), "sell.action-bar-broadcast must contain %amount%");
        assertTrue(sellActionBar.contains("%item%"), "sell.action-bar-broadcast must contain %item%");
        assertTrue(sellActionBar.contains("%price%"), "sell.action-bar-broadcast must contain %price%");

        String notifySellerActionBar = msgs.getString("buy.notify-seller-action-bar");
        assertNotNull(notifySellerActionBar, "buy.notify-seller-action-bar must be configured");
        assertTrue(notifySellerActionBar.contains("%player%"), "buy.notify-seller-action-bar must contain %player%");
        assertTrue(notifySellerActionBar.contains("%amount%"), "buy.notify-seller-action-bar must contain %amount%");
        assertTrue(notifySellerActionBar.contains("%item%"), "buy.notify-seller-action-bar must contain %item%");
        assertTrue(notifySellerActionBar.contains("%price%"), "buy.notify-seller-action-bar must contain %price%");

        assertNotNull(msgs.getString("buy.action-bar-purchase"), "buy.action-bar-purchase must be configured");
        assertNotNull(msgs.getString("buy.action-bar-broadcast"), "buy.action-bar-broadcast must be present");

        java.io.InputStream cfgIn = getClass().getClassLoader().getResourceAsStream("modules/auctions/config.yml");
        assertNotNull(cfgIn);
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(cfgIn, java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(cfg.contains("short-price-format"), "config.yml must contain short-price-format");
        assertFalse(cfg.getBoolean("short-price-format"), "short-price-format should default to false");
        assertTrue(cfg.contains("use-short-prices-in-gui"), "config.yml must contain use-short-prices-in-gui");
        assertFalse(cfg.getBoolean("use-short-prices-in-gui"), "use-short-prices-in-gui should default to false");
    }
}
