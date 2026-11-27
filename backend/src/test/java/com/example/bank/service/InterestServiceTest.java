package com.example.bank.service;

import com.example.bank.domain.InterestResult;
import com.example.bank.domain.SettlementSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 預金利息：試算（書き込まない）、決算（税引後を入金・同じ期間は一度だけ）、一括決算（解約済みは対象外）。 */
class InterestServiceTest {
    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private TestDatabase db;
    private BankService bank;
    private InterestService interest;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        interest = new InterestService(() -> db, clock);
    }

    @Test
    void previewDoesNotWrite() {
        // 1000004：2026-01-06 から 120,000 円。2026-01-06〜2026-02-28 の 54 日で 35 円、税 6 円
        InterestResult p = interest.preview("1000004", MARCH_1);
        assertFalse(p.isSettled());
        assertEquals("2026-01-06", p.getPeriodFrom());
        assertEquals("2026-02-28", p.getPeriodTo());
        assertEquals(54, p.getDays());
        assertEquals(0, new BigDecimal("35").compareTo(p.getGrossInterest()));
        assertEquals(0, new BigDecimal("29").compareTo(p.getNetInterest()));
        assertEquals(0, new BigDecimal("120000").compareTo(bank.getAccount("1000004").getBalance()));
    }

    @Test
    void settleCreditsNetInterestOnce() {
        InterestResult s = interest.settle("1000004", MARCH_1);
        assertTrue(s.isSettled());
        assertEquals(0, new BigDecimal("120029").compareTo(bank.getAccount("1000004").getBalance()));
        assertEquals("TAX", bank.getRecentTransactions("1000004").get(0).getTxType());
        assertEquals("INTEREST", bank.getRecentTransactions("1000004").get(1).getTxType());
        assertFalse(interest.settle("1000004", MARCH_1).isSettled());
        assertEquals(0, new BigDecimal("120029").compareTo(bank.getAccount("1000004").getBalance()));
    }

    @Test
    void settleAllSkipsClosedAccounts() {
        new AccountLifecycleService(() -> db, new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0)))
                .close("1000003", null, UUID.randomUUID().toString());
        SettlementSummary summary = interest.settleAll(MARCH_1);
        assertEquals(MARCH_1.toString(), summary.getAsOf());
        assertTrue(summary.getResults().stream().noneMatch(r -> "1000003".equals(r.getAccountNo())));
        assertTrue(summary.getSettledCount() >= 1);
        assertTrue(summary.getTotalGross().compareTo(summary.getTotalNet()) >= 0);
    }
}
