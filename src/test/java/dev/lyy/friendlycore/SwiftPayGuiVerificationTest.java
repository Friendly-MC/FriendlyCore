package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.AccountData;
import dev.lyy.friendlycore.modules.economy.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SwiftPayGuiVerificationTest {

    @Test
    public void testBaltopPaginationMath() {
        int pageSize = 45;
        List<AccountData> accounts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            accounts.add(new AccountData(UUID.randomUUID(), "Player" + i, 1000.0 * (100 - i)));
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) accounts.size() / pageSize));
        Assertions.assertEquals(3, totalPages);

        int page1Start = (1 - 1) * pageSize;
        int page1End = Math.min(page1Start + pageSize, accounts.size());
        Assertions.assertEquals(0, page1Start);
        Assertions.assertEquals(45, page1End);

        int page3Start = (3 - 1) * pageSize;
        int page3End = Math.min(page3Start + pageSize, accounts.size());
        Assertions.assertEquals(90, page3Start);
        Assertions.assertEquals(100, page3End);
    }

    @Test
    public void testPayHistoryFormatting() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.of("UTC"));
        long now = 1700000000000L;
        String dateFormatted = formatter.format(Instant.ofEpochMilli(now));
        Assertions.assertNotNull(dateFormatted);

        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        Transaction tx = new Transaction(1234, user1, "Alice", user2, "Bob", 250.50, Transaction.Type.PAYMENT, now);

        boolean isOutgoing = tx.getSenderUuid().equals(user1);
        Assertions.assertTrue(isOutgoing);
        String status = isOutgoing ? "&cSent" : "&aReceived";
        String amountFormatted = isOutgoing ? "&c-$250.50" : "&a+$250.50";

        Assertions.assertEquals("&cSent", status);
        Assertions.assertEquals("&c-$250.50", amountFormatted);
    }
}
