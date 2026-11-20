package com.example.bank.support;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * ローカル JDBC トランザクション境界（1 業務操作 = 1 トランザクション）。
 *
 * <ul>
 *   <li>A/C/I/D: 業務処理全体を 1 つのトランザクションで実行し、例外（業務エラーを含む）なら全体をロールバックする</li>
 *   <li>一時的な障害（接続断・デッドロック・直列化エラー）がコミット前に起きた場合は、全体がロールバック済みなので
 *       最大 {@value #MAX_ATTEMPTS} 回まで新しい接続でやり直す。コミット中の障害は結果が不明なため再試行しない
 *       （呼び出し側の冪等キーで二重実行を防ぐ）</li>
 * </ul>
 */
public final class Transactions {
    public static final int MAX_ATTEMPTS = 3;
    private static final Logger LOG = LogManager.getLogger(Transactions.class);

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private Transactions() {}

    /** 例外（業務例外含む）発生時はロールバックして再送出する。一時的な障害はコミット前なら再試行する。 */
    public static <T> T inTransaction(DataSource dataSource, Work<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            boolean committing = false;
            try (Connection c = dataSource.getConnection()) {
                boolean autoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    T result = work.run(c);
                    committing = true;
                    c.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    if (!committing) rollbackQuietly(c, e);
                    throw e;
                } finally {
                    restoreQuietly(c, autoCommit);
                }
            } catch (SQLException e) {
                last = e;
                if (committing || !isTransient(e) || attempt == MAX_ATTEMPTS) {
                    throw new DataAccessException(committing ? "commit failed (outcome unknown)" : "database error", e);
                }
                LOG.warn("transient database error (attempt {}/{}), retrying: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoff(attempt);
            }
        }
        throw new DataAccessException("database error", last);
    }

    /** 読み取り専用処理。一時的な障害は再試行する。 */
    public static <T> T readOnly(DataSource dataSource, Work<T> work) {
        for (int attempt = 1; ; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                return work.run(c);
            } catch (SQLException e) {
                if (!isTransient(e) || attempt >= MAX_ATTEMPTS) throw new DataAccessException("database error", e);
                LOG.warn("transient database error on read (attempt {}/{}), retrying: {}", attempt, MAX_ATTEMPTS, e.toString());
                backoff(attempt);
            }
        }
    }

    /**
     * 再試行してよい一時的な障害か: 接続断（SQLState 08）、デッドロック・直列化失敗（40001, ORA-00060, ORA-08177）、
     * JDBC が一時的・回復可能と分類したもの。
     */
    public static boolean isTransient(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLTransientException || t instanceof SQLRecoverableException) return true;
            if (t instanceof SQLException) {
                SQLException s = (SQLException) t;
                String state = s.getSQLState();
                int code = s.getErrorCode();
                if (state != null && (state.startsWith("08") || state.equals("40001"))) return true;
                if (code == 60 || code == 8177 || code == 17002 || code == 17008 || code == 17410) return true;
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rollbackQuietly(Connection c, Exception original) {
        try {
            c.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private static void restoreQuietly(Connection c, boolean autoCommit) {
        try {
            c.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // the connection is broken; the pool will discard it
        }
    }
}
