package com.example.bank.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 融資の計算：月利息、元利均等の返済額、返済予定表（端数は最終回で調整）、遅延損害金、日割利息。 */
class LoanMathTest {
    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    @Test
    void monthlyInterestRoundsDown() {
        assertEquals(yen(1000), LoanMath.monthlyInterest(yen(1_000_000), new BigDecimal("1.2")));
        // 999,999 × 1.2% ÷ 12 = 999.999 → 999
        assertEquals(yen(999), LoanMath.monthlyInterest(yen(999_999), new BigDecimal("1.2")));
    }

    @Test
    void equalPayment() {
        // 1,000,000 円・年 12%・12 回 → 88,848.78… → 切り上げ 88,849
        assertEquals(yen(88_849), LoanMath.equalPayment(yen(1_000_000), new BigDecimal("12"), 12));
        // 金利 0 は元金を回数で割って切り上げ
        assertEquals(yen(100_000), LoanMath.equalPayment(yen(1_200_000), BigDecimal.ZERO, 12));
        assertEquals(yen(33_334), LoanMath.equalPayment(yen(100_000), BigDecimal.ZERO, 3));
        assertThrows(IllegalArgumentException.class, () -> LoanMath.equalPayment(yen(1), BigDecimal.ONE, 0));
    }

    @Test
    void equalPaymentScheduleRepaysExactlyThePrincipal() {
        List<LoanMath.Installment> s = LoanMath.schedule(yen(1_000_000), new BigDecimal("12"), 12, "EQUAL_PAYMENT");
        assertEquals(12, s.size());
        BigDecimal principal = s.stream().map(i -> i.principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(yen(1_000_000), principal);
        assertEquals(0, s.get(11).balanceAfter.signum());
        assertEquals(yen(10_000), s.get(0).interest);   // 1,000,000 × 1%
        assertEquals(yen(88_849), s.get(0).total());
        for (int k = 0; k < 12; k++) assertEquals(k + 1, s.get(k).no);
    }

    @Test
    void equalPrincipalScheduleAddsTheRemainderToTheFirstInstallment() {
        List<LoanMath.Installment> s = LoanMath.schedule(yen(100), BigDecimal.ZERO, 3, "EQUAL_PRINCIPAL");
        assertEquals(yen(34), s.get(0).principal);
        assertEquals(yen(33), s.get(1).principal);
        assertEquals(yen(33), s.get(2).principal);
        assertEquals(0, s.get(2).balanceAfter.signum());
    }

    @Test
    void unknownMethodIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LoanMath.schedule(yen(100), BigDecimal.ONE, 3, "BULLET"));
    }

    @Test
    void lateFeeAt14point6Percent() {
        // 36,500 × 14.6% × 10 / 365 = 146
        assertEquals(yen(146), LoanMath.lateFee(yen(36_500), 10));
        assertEquals(BigDecimal.ZERO, LoanMath.lateFee(yen(36_500), 0));
        assertEquals(BigDecimal.ZERO, LoanMath.lateFee(BigDecimal.ZERO, 10));
    }

    @Test
    void dailyInterestKeepsTwoDecimals() {
        assertEquals(new BigDecimal("1.00"), LoanMath.dailyInterest(yen(36_500), BigDecimal.ONE, 1));
        // 200,000 × 3% × 1 / 365 = 16.438… → 16.43
        assertEquals(new BigDecimal("16.43"), LoanMath.dailyInterest(yen(200_000), new BigDecimal("3"), 1));
        assertEquals(BigDecimal.ZERO, LoanMath.dailyInterest(yen(-1), BigDecimal.ONE, 1));
        assertEquals(BigDecimal.ZERO, LoanMath.dailyInterest(yen(100), BigDecimal.ONE, 0));
    }
}
