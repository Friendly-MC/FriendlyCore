package dev.lyy.friendlycore;

import dev.lyy.friendlycore.modules.teams.storage.TeamsDao;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;

import static org.junit.jupiter.api.Assertions.*;

class TeamsDaoTransientLockTest {

    @Test
    void testNullReturnsFalse() {
        assertFalse(TeamsDao.isTransientLockException(null));
    }

    @Test
    void testSQLTransactionRollbackExceptionReturnsTrue() {
        assertTrue(TeamsDao.isTransientLockException(new SQLTransactionRollbackException("Deadlock detected")));
    }

    @Test
    void testLockWaitTimeoutErrorCode1205ReturnsTrue() {
        SQLException ex = new SQLException("Lock wait timeout exceeded; try restarting transaction", "HY000", 1205);
        assertTrue(TeamsDao.isTransientLockException(ex));
    }

    @Test
    void testDeadlockErrorCode1213ReturnsTrue() {
        SQLException ex = new SQLException("Deadlock found when trying to get lock; try restarting transaction", "40001", 1213);
        assertTrue(TeamsDao.isTransientLockException(ex));
    }

    @Test
    void testSQLState40001ReturnsTrue() {
        SQLException ex = new SQLException("Serialization failure", "40001", 0);
        assertTrue(TeamsDao.isTransientLockException(ex));
    }

    @Test
    void testSQLState41000ReturnsTrue() {
        SQLException ex = new SQLException("Timeout", "41000", 0);
        assertTrue(TeamsDao.isTransientLockException(ex));
    }

    @Test
    void testNestedTransientLockExceptionReturnsTrue() {
        SQLException cause = new SQLException("Lock wait timeout", "HY000", 1205);
        SQLException wrapper = new SQLException("Failed batch operation", "HY000", 0, cause);
        assertTrue(TeamsDao.isTransientLockException(wrapper));
    }

    @Test
    void testNextExceptionTransientLockExceptionReturnsTrue() {
        SQLException cause = new SQLException("Lock wait timeout", "HY000", 1205);
        SQLException wrapper = new SQLException("Batch update failed", "HY000", 0);
        wrapper.setNextException(cause);
        assertTrue(TeamsDao.isTransientLockException(wrapper));
    }

    @Test
    void testNonTransientExceptionReturnsFalse() {
        SQLException syntaxError = new SQLException("You have an error in your SQL syntax", "42000", 1064);
        assertFalse(TeamsDao.isTransientLockException(syntaxError));

        SQLException duplicateKey = new SQLException("Duplicate entry 'test' for key 'PRIMARY'", "23000", 1062);
        assertFalse(TeamsDao.isTransientLockException(duplicateKey));
    }

    @Test
    void testBankMutationRecordFailureSentinel() {
        TeamsDao.BankMutation failure = new TeamsDao.BankMutation(false, -1.0);
        assertFalse(failure.success());
        assertEquals(-1.0, failure.balance(), 0.001);

        TeamsDao.BankMutation success = new TeamsDao.BankMutation(true, 500.0);
        assertTrue(success.success());
        assertEquals(500.0, success.balance(), 0.001);
    }
}
