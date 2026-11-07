package dev.lyy.friendlycore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OrderModuleHardeningTest {

    @Test
    @DisplayName("OrderActionType enum and orphaned methods are completely removed")
    void testDeadCodePruned() {
        assertFalse(Files.exists(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/enums/OrderActionType.java")),
                "OrderActionType.java must be deleted as dead code");

        assertFalse(Files.exists(Path.of("src/main/java/dev/lyy/friendlycore/modules/orders/enums/OrderActionType.java")),
                "OrderActionType.java must be deleted as dead code");
    }

    @Test
    @DisplayName("OrderManager: depositMoney returns boolean and callers guard against deposit failures")
    void testDepositMoneyGuarded() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/OrderManager.java"));

        assertTrue(content.contains("public boolean depositMoney(UUID uuid, double amount, String reason)"),
                "depositMoney must return boolean indicating success/failure");

        // deliverOrder must check deposit
        assertTrue(content.contains("boolean deposited = depositMoney(seller.getUniqueId(), payout"),
                "deliverOrder must check return value of depositMoney");

        // expireOrder must check deposit
        assertTrue(content.contains("boolean deposited = depositMoney(order.getCreatorId(), remainingRefund"),
                "expireOrder must check return value of depositMoney");

        // cancelOrder must check deposit
        assertTrue(content.contains("boolean deposited = depositMoney(order.getCreatorId(), refundAmount"),
                "cancelOrder must check return value of depositMoney");
    }

    @Test
    @DisplayName("OrderManager: refundItemsToSeller prevents item loss on fulfillment failure or disconnect")
    void testSellerItemRefundSafety() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/OrderManager.java"));

        assertTrue(content.contains("refundItemsToSeller(seller, seller.getUniqueId()"),
                "deliverOrder rollback must route through refundItemsToSeller");

        assertTrue(content.contains("storeDeliveryRefund(sellerUuid, itemTemplate"),
                "Offline or cancelled entity tasks must store refund into delivery collection box");
    }

    @Test
    @DisplayName("OrderManager: sendWebhook uses Google JsonObject/JsonArray instead of hand-rolled escaping")
    void testWebhookUsesGson() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/OrderManager.java"));

        assertTrue(content.contains("JsonObject payload = new JsonObject();"),
                "sendWebhook must use JsonObject");
        assertFalse(content.contains("private String escapeJson("),
                "Hand-rolled escapeJson method must be pruned");
    }

    @Test
    @DisplayName("SelectItemGui: enchantable check properly covers durability items without hardcoded string suffixes")
    void testEnchantableCheckComprehensive() throws Exception {
        String content = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/SelectItemGui.java"));

        assertTrue(content.contains("mat.getMaxDurability() > 0"),
                "SelectItemGui must check getMaxDurability() > 0 to cover armor, tools, weapons, elytra, and shields");
        assertFalse(content.contains("mat.name().endsWith(\"_SWORD\")"),
                "Hardcoded string suffix matching should be replaced with durability check");
    }

    @Test
    @DisplayName("DatabaseManager: dead updateOrder, loadOrderLogs, loadOrderHistory, and insertOrderPayout are pruned")
    void testDatabaseManagerCleaned() throws Exception {
        String dbManager = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/common/database/DatabaseManager.java"));

        assertFalse(dbManager.contains("updateOrder(dev.lyy.friendlycore.modules.orders.model.BuyOrder"),
                "updateOrder should be pruned from DatabaseManager");
        assertFalse(dbManager.contains("insertOrderPayout("),
                "insertOrderPayout should be pruned from DatabaseManager");
        assertFalse(dbManager.contains("loadOrderLogs("),
                "loadOrderLogs should be pruned from DatabaseManager");
        assertFalse(dbManager.contains("loadOrderHistory("),
                "loadOrderHistory should be pruned from DatabaseManager");
    }

    @Test
    @DisplayName("OrderInputManager: chat prompts replaced by multi-modal Dialog and Sign input system")
    void testOrderInputSystemModernized() throws Exception {
        assertFalse(Files.exists(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/listener/OrderInputPromptListener.java")),
                "OrderInputPromptListener.java must be pruned");

        Path inputManagerPath = Path.of("src/main/java/dev/lyy/friendlycore/modules/orders/input/OrderInputManager.java");
        assertTrue(Files.exists(inputManagerPath),
                "OrderInputManager.java must exist");

        String inputContent = Files.readString(inputManagerPath);
        assertTrue(inputContent.contains("openAmountInput("), "OrderInputManager must expose openAmountInput");
        assertTrue(inputContent.contains("openPriceInput("), "OrderInputManager must expose openPriceInput");
        assertTrue(inputContent.contains("openSearchInput("), "OrderInputManager must expose openSearchInput");
        assertTrue(inputContent.contains("openVirtualSign("), "OrderInputManager must support virtual signs");
        assertTrue(inputContent.contains("UncheckedSignChangeEvent"), "OrderInputManager must listen to UncheckedSignChangeEvent");

        String createGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/CreateOrderGui.java"));
        assertTrue(createGui.contains("openAmountInput("), "CreateOrderGui must use openAmountInput");
        assertTrue(createGui.contains("openPriceInput("), "CreateOrderGui must use openPriceInput");

        String marketGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/OrderMarketGui.java"));
        assertTrue(marketGui.contains("openSearchInput("), "OrderMarketGui must use openSearchInput");

        String selectGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/SelectItemGui.java"));
        assertTrue(selectGui.contains("openSearchInput("), "SelectItemGui must use openSearchInput");
    }

    @Test
    @DisplayName("Orders GUI: ItemBuilder hides vanilla attributes on order icons")
    void testItemBuilderHidesAttributes() throws Exception {
        String marketGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/OrderMarketGui.java"));
        assertTrue(marketGui.contains("flags(ItemFlag.HIDE_ATTRIBUTES)"), "OrderMarketGui must hide item attributes");

        String yourOrdersGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/YourOrdersGui.java"));
        assertTrue(yourOrdersGui.contains("flags(ItemFlag.HIDE_ATTRIBUTES)"), "YourOrdersGui must hide item attributes");

        String deliveryGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/OrderDeliveryConfirmGui.java"));
        assertTrue(deliveryGui.contains("flags(ItemFlag.HIDE_ATTRIBUTES)"), "OrderDeliveryConfirmGui must hide item attributes");

        String adminGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/AdminOrdersGui.java"));
        assertTrue(adminGui.contains("flags(ItemFlag.HIDE_ATTRIBUTES)"), "AdminOrdersGui must hide item attributes");

        String editGui = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/EditOrderGui.java"));
        assertTrue(editGui.contains("flags(ItemFlag.HIDE_ATTRIBUTES)"), "EditOrderGui must hide item attributes");
    }

    @Test
    @DisplayName("Orders GUI: formatTimeRemaining produces DonutSMP readable units and handles 0/negatives")
    void testOrderTimeFormatting() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        dev.lyy.friendlycore.modules.orders.OrderManager om =
                (dev.lyy.friendlycore.modules.orders.OrderManager) unsafe.allocateInstance(dev.lyy.friendlycore.modules.orders.OrderManager.class);

        // <= 0 must return "Expired"
        org.junit.jupiter.api.Assertions.assertEquals("Expired", om.formatTimeRemaining(0L));
        org.junit.jupiter.api.Assertions.assertEquals("Expired", om.formatTimeRemaining(-1000L));

        // Sub-second rounds up
        org.junit.jupiter.api.Assertions.assertEquals("1s", om.formatTimeRemaining(500L));
        org.junit.jupiter.api.Assertions.assertEquals("1s", om.formatTimeRemaining(1000L));
        org.junit.jupiter.api.Assertions.assertEquals("30s", om.formatTimeRemaining(30_000L));

        // Minutes & seconds
        long fortyFiveMinutesTwelveSec = (45 * 60L + 12L) * 1000L;
        org.junit.jupiter.api.Assertions.assertEquals("45m 12s", om.formatTimeRemaining(fortyFiveMinutesTwelveSec));

        // Hours & minutes
        long twoHoursThirtyMinutes = (2 * 3600L + 30 * 60L) * 1000L;
        org.junit.jupiter.api.Assertions.assertEquals("2h 30m", om.formatTimeRemaining(twoHoursThirtyMinutes));

        // Days & hours & minutes
        long sixDaysTwentyThreeHoursFiftyNineMin = (6 * 86400L + 23 * 3600L + 59 * 60L) * 1000L;
        org.junit.jupiter.api.Assertions.assertEquals("6d 23h 59m", om.formatTimeRemaining(sixDaysTwentyThreeHoursFiftyNineMin));

        long threeDaysFiveHours = (3 * 86400L + 5 * 3600L) * 1000L;
        org.junit.jupiter.api.Assertions.assertEquals("3d 5h", om.formatTimeRemaining(threeDaysFiveHours));
    }

    @Test
    @DisplayName("Orders GUI: formatPrice and formatAmount format numbers cleanly without raw trailing .0")
    void testOrderNumberFormatting() throws Exception {
        java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        dev.lyy.friendlycore.modules.orders.OrderManager om =
                (dev.lyy.friendlycore.modules.orders.OrderManager) unsafe.allocateInstance(dev.lyy.friendlycore.modules.orders.OrderManager.class);

        // Whole integers have commas and NO trailing .0
        org.junit.jupiter.api.Assertions.assertEquals("1,000", om.formatPrice(1000.0));
        org.junit.jupiter.api.Assertions.assertEquals("64,000", om.formatPrice(64000.0));
        org.junit.jupiter.api.Assertions.assertEquals("0", om.formatPrice(0.0));

        // Decimal prices keep up to 2 decimal places without trailing zeros
        org.junit.jupiter.api.Assertions.assertEquals("10.5", om.formatPrice(10.5));
        org.junit.jupiter.api.Assertions.assertEquals("10.25", om.formatPrice(10.25));

        // Amounts format with commas
        org.junit.jupiter.api.Assertions.assertEquals("64", om.formatAmount(64));
        org.junit.jupiter.api.Assertions.assertEquals("10,000", om.formatAmount(10000));

        // Short scale
        org.junit.jupiter.api.Assertions.assertEquals("1K", om.formatPriceShort(1000.0));
        org.junit.jupiter.api.Assertions.assertEquals("64K", om.formatPriceShort(64000.0));
        org.junit.jupiter.api.Assertions.assertEquals("1.5M", om.formatPriceShort(1500000.0));
    }

    @Test
    @DisplayName("Orders GUI: YourOrdersGui and OrderMarketGui use DonutSMP time and spacing")
    void testGuiEnhancementsParity() throws Exception {
        String yourOrdersContent = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/YourOrdersGui.java"));
        assertTrue(yourOrdersContent.contains("formatTimeRemaining"), "YourOrdersGui must use formatTimeRemaining");
        assertFalse(yourOrdersContent.contains("(remainingSec / 86400) + \"d \""), "YourOrdersGui must not use naive hardcoded days/hours");

        String marketContent = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/gui/OrderMarketGui.java"));
        assertTrue(marketContent.contains("articleWithoutSpace"), "OrderMarketGui must guard against double space with article");
        assertTrue(marketContent.contains("formattedExpirationTime"), "OrderMarketGui must support formattedExpirationTime");
    }

    @Test
    @DisplayName("Orders Announcement: OrderManager broadcasts announcements via actionbar and chat")
    void testOrderActionbarAnnouncement() throws Exception {
        String messagesContent = Files.readString(Path.of("src/main/resources/modules/orders/messages.yml"));
        assertTrue(messagesContent.contains("order_broadcast_actionbar:"),
                "messages.yml must contain order_broadcast_actionbar");

        String configContent = Files.readString(Path.of("src/main/resources/modules/orders/config.yml"));
        assertTrue(configContent.contains("actionbar: true"),
                "config.yml must configure actionbar announcements under announce");

        String textServiceContent = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/common/TextService.java"));
        assertTrue(textServiceContent.contains("public void broadcastActionBar("),
                "TextService must provide broadcastActionBar");

        String orderManagerContent = Files.readString(Path.of("src/main/java/dev/lyy/friendlyCore/modules/orders/OrderManager.java"));
        assertTrue(orderManagerContent.contains("announceOrder("),
                "OrderManager must expose announceOrder");
        assertTrue(orderManagerContent.contains("order_broadcast_actionbar"),
                "OrderManager must broadcast order_broadcast_actionbar to actionbar");
    }
}
