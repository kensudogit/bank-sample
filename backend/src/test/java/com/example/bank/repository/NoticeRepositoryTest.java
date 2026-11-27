package com.example.bank.repository;

import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 通知預金の払出し通知：登録、使える通知の消し込み（小さい金額から）、取り消し。 */
class NoticeRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0);
    private static final LocalDate AVAILABLE = LocalDate.of(2026, 11, 27);
    private final NoticeRepository repo = new NoticeRepository();
    private Connection c;

    @BeforeEach
    void setUp() throws SQLException {
        c = TestDatabase.create().getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        c.close();
    }

    @Test
    void consumeOnlyFromTheAvailableDateAndForEnoughAmount() throws SQLException {
        repo.insert(c, "1000001", new BigDecimal("10000"), NOW, AVAILABLE);
        assertFalse(repo.consume(c, "1000001", new BigDecimal("5000"), AVAILABLE.minusDays(1)));
        assertFalse(repo.consume(c, "1000001", new BigDecimal("20000"), AVAILABLE));
        assertTrue(repo.consume(c, "1000001", new BigDecimal("5000"), AVAILABLE));
        // 使った通知は二度使えない
        assertFalse(repo.consume(c, "1000001", new BigDecimal("5000"), AVAILABLE));
    }

    @Test
    void consumeUsesTheSmallestSufficientNotice() throws SQLException {
        long big = repo.insert(c, "1000001", new BigDecimal("50000"), NOW, AVAILABLE);
        long small = repo.insert(c, "1000001", new BigDecimal("10000"), NOW, AVAILABLE);
        assertTrue(repo.consume(c, "1000001", new BigDecimal("8000"), AVAILABLE));
        List<Map<String, Object>> list = repo.list(c, "1000001");
        assertEquals("USED", status(list, small));
        assertEquals("OPEN", status(list, big));
    }

    @Test
    void cancelOnlyOpenNoticesOfTheAccount() throws SQLException {
        long id = repo.insert(c, "1000001", new BigDecimal("10000"), NOW, AVAILABLE);
        assertEquals(0, repo.cancel(c, "1000002", id));
        assertEquals(1, repo.cancel(c, "1000001", id));
        assertEquals(0, repo.cancel(c, "1000001", id));
        assertEquals("CANCELLED", status(repo.list(c, "1000001"), id));
        assertFalse(repo.consume(c, "1000001", new BigDecimal("1"), AVAILABLE));
    }

    private static String status(List<Map<String, Object>> list, long id) {
        return list.stream().filter(m -> ((Number) m.get("noticeId")).longValue() == id).map(m -> (String) m.get("status"))
                .findFirst().orElseThrow(AssertionError::new);
    }
}
