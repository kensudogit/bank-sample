package com.example.bank.support;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** トランザクション境界：コミット・ロールバック、一時的な障害の再試行、コミット中の障害は再試行しない。 */
class TransactionsTest {
    private TestDatabase db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.create();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tx_probe (v NUMBER)");
        }
    }

    private int count() throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tx_probe")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void insert(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO tx_probe VALUES (1)");
        }
    }

    @Test
    void commitsOnSuccessWithAutoCommitOff() throws SQLException {
        int r = Transactions.inTransaction(db, c -> {
            assertFalse(c.getAutoCommit());
            insert(c);
            return 42;
        });
        assertEquals(42, r);
        assertEquals(1, count());
    }

    @Test
    void rollsBackOnBusinessException() throws SQLException {
        assertThrows(BankException.class, () -> Transactions.inTransaction(db, c -> {
            insert(c);
            throw new BankException(ErrorCode.INSUFFICIENT_FUNDS);
        }));
        assertEquals(0, count());
    }

    @Test
    void permanentSqlErrorIsWrappedWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        DataAccessException e = assertThrows(DataAccessException.class, () -> Transactions.inTransaction(db, c -> {
            calls.incrementAndGet();
            throw new SQLException("syntax", "42000");
        }));
        assertEquals(1, calls.get());
        assertEquals("database error", e.getMessage());
    }

    @Test
    void transientErrorBeforeCommitIsRetried() throws SQLException {
        AtomicInteger calls = new AtomicInteger();
        int r = Transactions.inTransaction(db, c -> {
            insert(c);
            if (calls.incrementAndGet() == 1) throw new SQLException("deadlock", "40001");
            return calls.get();
        });
        assertEquals(2, r);
        // 1 回目の INSERT はロールバックされている
        assertEquals(1, count());
    }

    @Test
    void transientErrorGivesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DataAccessException.class, () -> Transactions.inTransaction(db, c -> {
            calls.incrementAndGet();
            throw new SQLTransientConnectionException("down");
        }));
        assertEquals(Transactions.MAX_ATTEMPTS, calls.get());
    }

    @Test
    void failureDuringCommitIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        DataSource failingCommit = new FailingCommitDataSource(db);
        DataAccessException e = assertThrows(DataAccessException.class, () -> Transactions.inTransaction(failingCommit, c -> {
            calls.incrementAndGet();
            return null;
        }));
        // コミットされたか分からないので、やり直すと二重記帳の恐れがある
        assertEquals(1, calls.get());
        assertEquals("commit failed (outcome unknown)", e.getMessage());
    }

    @Test
    void readOnlyRetriesTransientErrors() {
        AtomicInteger calls = new AtomicInteger();
        String r = Transactions.readOnly(db, c -> {
            if (calls.incrementAndGet() < 3) throw new SQLRecoverableException("reconnect");
            return "ok";
        });
        assertEquals("ok", r);
        assertEquals(3, calls.get());
    }

    @Test
    void readOnlyWrapsPermanentErrors() {
        assertThrows(DataAccessException.class, () -> Transactions.readOnly(db, c -> {
            throw new SQLException("bad", "42000");
        }));
    }

    @Test
    void transientClassification() {
        assertTrue(Transactions.isTransient(new SQLException("x", "08006")));
        assertTrue(Transactions.isTransient(new SQLException("x", "40001")));
        assertTrue(Transactions.isTransient(new SQLException("ORA-00060", "61000", 60)));
        assertTrue(Transactions.isTransient(new SQLException("ORA-08177", "72000", 8177)));
        assertTrue(Transactions.isTransient(new SQLTransientConnectionException("x")));
        assertTrue(Transactions.isTransient(new SQLRecoverableException("x")));
        SQLException wrapped = new SQLException("outer", "HY000");
        wrapped.initCause(new SQLException("inner", "08001"));
        assertTrue(Transactions.isTransient(wrapped));
        assertFalse(Transactions.isTransient(new SQLException("unique", "23000", 1)));
        assertFalse(Transactions.isTransient(new SQLException("no state")));
    }

    /** コミットだけが接続断で失敗する接続を返す。 */
    private static final class FailingCommitDataSource implements DataSource {
        private final DataSource delegate;

        FailingCommitDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, args) -> {
                        if ("commit".equals(m.getName())) throw new SQLRecoverableException("connection reset", "08006");
                        try {
                            return m.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
