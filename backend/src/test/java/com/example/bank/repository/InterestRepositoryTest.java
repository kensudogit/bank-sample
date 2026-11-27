package com.example.bank.repository;

import com.example.bank.service.InterestCalculator;
import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 金利と利息決算の DB アクセス：種別の金利の履歴、最後に決算した期間、決算の登録。 */
class InterestRepositoryTest {
    private final InterestRepository repo = new InterestRepository();
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
    void ratesForAccountType() throws SQLException {
        // 普通預金は 2010 年と 2025 年の 2 件（適用開始日の昇順）
        assertEquals(2, repo.rates(c, "ORDINARY").size());
        assertTrue(repo.rates(c, "NO_SUCH_TYPE").isEmpty());
    }

    @Test
    void settlementIsRecordedAndLastPeriodIsReturned() throws SQLException {
        assertNull(repo.lastSettledTo(c, "1000001"));
        List<InterestCalculator.Rate> rates = repo.rates(c, "ORDINARY");
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        InterestCalculator.Calculation calc = InterestCalculator.calculate(new BigDecimal("1000000"),
                Collections.<InterestCalculator.BalanceChange>emptyList(), rates, from, to);
        repo.insertSettlement(c, "1000001", from, to, calc, LocalDateTime.of(2026, 7, 1, 1, 0));
        assertEquals(to, repo.lastSettledTo(c, "1000001"));
        assertNull(repo.lastSettledTo(c, "1000002"));
    }
}
