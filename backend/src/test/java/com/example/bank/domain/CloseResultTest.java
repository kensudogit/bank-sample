package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 解約の結果：決算利息・払出額・払出先をそのまま保持する。 */
class CloseResultTest {
    @Test
    void exposesAllFields() {
        InterestResult interest = new InterestResult("1000001", "ORDINARY", "2026-01-01", "2026-11-20", 323,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, null);
        CloseResult r = new CloseResult("1000001", interest, new BigDecimal("5000"), "1000002", "2026-11-20 10:00");
        assertEquals("1000001", r.getAccountNo());
        assertSame(interest, r.getInterest());
        assertEquals(new BigDecimal("5000"), r.getPayoutAmount());
        assertEquals("1000002", r.getPayoutAccountNo());
        assertEquals("2026-11-20 10:00", r.getClosedAt());
    }
}
