package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 利息の計算結果：平均残高（切り捨て）、税と手取り、決算済みのコピー。 */
class InterestResultTest {
    private static InterestResult result(int days, String balanceDays, boolean settled) {
        return new InterestResult("1000001", "ORDINARY", "2026-01-01", "2026-06-30", days, new BigDecimal(balanceDays),
                new BigDecimal("1000"), new BigDecimal("153"), new BigDecimal("50"), settled, "note");
    }

    @Test
    void averageBalanceRoundsDownAndIsZeroForZeroDays() {
        assertEquals(new BigDecimal("333"), result(3, "1000", false).getAverageBalance());
        assertEquals(BigDecimal.ZERO, result(0, "1000", false).getAverageBalance());
    }

    @Test
    void taxAndNetInterest() {
        InterestResult r = result(181, "0", false);
        assertEquals(new BigDecimal("203"), r.getTax());
        assertEquals(new BigDecimal("797"), r.getNetInterest());
    }

    @Test
    void asSettledCopiesAndMarksSettled() {
        InterestResult r = result(181, "18100000", false);
        InterestResult s = r.asSettled();
        assertFalse(r.isSettled());
        assertTrue(s.isSettled());
        assertEquals(r.getAccountNo(), s.getAccountNo());
        assertEquals(r.getAccountType(), s.getAccountType());
        assertEquals(r.getPeriodFrom(), s.getPeriodFrom());
        assertEquals(r.getPeriodTo(), s.getPeriodTo());
        assertEquals(r.getDays(), s.getDays());
        assertEquals(r.getBalanceDays(), s.getBalanceDays());
        assertEquals(r.getGrossInterest(), s.getGrossInterest());
        assertEquals(r.getNationalTax(), s.getNationalTax());
        assertEquals(r.getLocalTax(), s.getLocalTax());
        assertEquals("note", s.getNote());
    }
}
