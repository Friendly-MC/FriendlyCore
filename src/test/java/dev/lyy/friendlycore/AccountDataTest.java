package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.economy.AccountData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccountDataTest {

    @Test
    public void testInitialization() {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "Steve", 500.0);

        Assertions.assertEquals(uuid, account.getUuid());
        Assertions.assertEquals("Steve", account.getUsername());
        Assertions.assertEquals(500.0, account.getBalance());
        Assertions.assertTrue(account.isPayEnabled());
        Assertions.assertFalse(account.isDirty());
    }

    @Test
    public void testDepositAndWithdraw() {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "Alex", 100.0);

        Assertions.assertTrue(account.deposit(50.0));
        Assertions.assertEquals(150.0, account.getBalance());
        Assertions.assertTrue(account.isDirty());

        Assertions.assertTrue(account.withdraw(80.0));
        Assertions.assertEquals(70.0, account.getBalance());

        // Overdraft prevention
        Assertions.assertFalse(account.withdraw(100.0));
        Assertions.assertEquals(70.0, account.getBalance());

        // Zero / negative amounts
        Assertions.assertFalse(account.deposit(0));
        Assertions.assertFalse(account.deposit(-10));
        Assertions.assertFalse(account.withdraw(0));
        Assertions.assertFalse(account.withdraw(-10));
    }

    @Test
    public void testPayToggleAndUsername() {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "Player1", 0.0);

        account.setPayEnabled(false);
        Assertions.assertFalse(account.isPayEnabled());
        Assertions.assertTrue(account.isDirty());

        account.setDirty(false);
        account.setUsername("Player2");
        Assertions.assertEquals("Player2", account.getUsername());
        Assertions.assertTrue(account.isDirty());
    }

    @Test
    public void testConcurrentTransactions() throws InterruptedException {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "ThreadTester", 1000.0);

        int threads = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        account.deposit(1.0);
                        account.withdraw(1.0);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Assertions.assertEquals(1000.0, account.getBalance());
    }

    @Test
    public void testMaxBalanceCapAndRounding() {
        UUID uuid = UUID.randomUUID();
        AccountData account = new AccountData(uuid, "RichPlayer", 1.0E14);

        // Valid large deposit
        Assertions.assertTrue(account.deposit(100.0));
        Assertions.assertEquals(1.000000000001E14, account.getBalance());

        // Deposit exceeding MAX_BALANCE (1.0E15) rejected
        Assertions.assertFalse(account.deposit(2.0E15));

        // Has validations
        Assertions.assertTrue(account.has(0.0));
        Assertions.assertTrue(account.has(100.0));
        Assertions.assertFalse(account.has(-50.0));
        Assertions.assertFalse(account.has(Double.NaN));
        Assertions.assertFalse(account.has(Double.POSITIVE_INFINITY));
    }
}
