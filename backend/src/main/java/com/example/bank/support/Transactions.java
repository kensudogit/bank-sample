package com.example.bank.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** ローカル JDBC トランザクション境界（1 業務操作 = 1 トランザクション）。 */
public final class Transactions {

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private Transactions() {}

    /** 例外（業務例外含む）発生時はロールバックして再送出する。 */
    public static <T> T inTransaction(DataSource dataSource, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DataAccessException("database error", e);
        }
    }

    /** 読み取り専用処理。 */
    public static <T> T readOnly(DataSource dataSource, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new DataAccessException("database error", e);
        }
    }

    private static void rollbackQuietly(Connection c, Exception original) {
        try {
            c.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }
}
