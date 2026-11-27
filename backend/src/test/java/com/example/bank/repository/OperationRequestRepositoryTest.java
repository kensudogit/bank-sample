package com.example.bank.repository;

import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** 冪等キーの登録：同じキーは 1 回だけ。業務と同じトランザクションなので、ロールバックすれば再登録できる。 */
class OperationRequestRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private final OperationRequestRepository repo = new OperationRequestRepository();

    @Test
    void secondRegistrationOfTheSameKeyIsRejected() throws SQLException {
        try (Connection c = TestDatabase.create().getConnection()) {
            assertTrue(repo.register(c, "key-aaaaaaaaaaaaaaaa", "TRANSFER", NOW));
            assertFalse(repo.register(c, "key-aaaaaaaaaaaaaaaa", "TRANSFER", NOW));
            assertTrue(repo.register(c, "key-bbbbbbbbbbbbbbbb", "TRANSFER", NOW));
        }
    }

    @Test
    void rollbackFreesTheKey() throws SQLException {
        try (Connection c = TestDatabase.create().getConnection()) {
            c.setAutoCommit(false);
            assertTrue(repo.register(c, "key-cccccccccccccccc", "DEPOSIT", NOW));
            c.rollback();
            assertTrue(repo.register(c, "key-cccccccccccccccc", "DEPOSIT", NOW));
            c.commit();
        }
    }
}
