package com.example.bank.support;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** DB 例外のラッパー：メッセージと元の例外を保持する非検査例外。 */
class DataAccessExceptionTest {
    @Test
    void keepsMessageAndCause() {
        SQLException cause = new SQLException("boom");
        DataAccessException e = new DataAccessException("database error", cause);
        assertEquals("database error", e.getMessage());
        assertSame(cause, e.getCause());
        assertTrue(e instanceof RuntimeException);
    }
}
