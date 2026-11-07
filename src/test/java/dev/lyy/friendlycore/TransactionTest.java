package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

public class TransactionTest {

    @Test
    public void testTransactionCreation() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        long now = System.currentTimeMillis();

        Transaction tx = new Transaction(1, sender, "SenderGuy", receiver, "ReceiverGuy", 250.0, Transaction.Type.PAYMENT, now);

        Assertions.assertEquals(1, tx.getId());
        Assertions.assertEquals(sender, tx.getSenderUuid());
        Assertions.assertEquals("SenderGuy", tx.getSenderName());
        Assertions.assertEquals(receiver, tx.getReceiverUuid());
        Assertions.assertEquals("ReceiverGuy", tx.getReceiverName());
        Assertions.assertEquals(250.0, tx.getAmount());
        Assertions.assertEquals(Transaction.Type.PAYMENT, tx.getType());
        Assertions.assertEquals(now, tx.getTimestamp());
    }

    @Test
    public void testSystemTransactionFallbacks() {
        Transaction tx = new Transaction(0, null, null, null, null, 100.0, Transaction.Type.SYSTEM, 0);

        Assertions.assertNull(tx.getSenderUuid());
        Assertions.assertEquals("System", tx.getSenderName());
        Assertions.assertNull(tx.getReceiverUuid());
        Assertions.assertEquals("System", tx.getReceiverName());
        Assertions.assertTrue(tx.getTimestamp() > 0);
    }
}
