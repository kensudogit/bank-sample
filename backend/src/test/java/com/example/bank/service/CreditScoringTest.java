package com.example.bank.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 融資の審査：点数と格付、形式要件による謝絶（返済比率・総量規制・完済時年齢・担保）、金利（格付の上乗せ・上限）。 */
class CreditScoringTest {
    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static CreditScoring.Input card(long amount, long income) {
        CreditScoring.Input in = new CreditScoring.Input();
        in.product = "CARD";
        in.amount = yen(amount);
        in.annualIncome = yen(income);
        in.relationshipYears = 5;
        return in;
    }

    private static CreditScoring.Input housing() {
        CreditScoring.Input in = new CreditScoring.Input();
        in.product = "HOUSING";
        in.amount = yen(30_000_000);
        in.termMonths = 240;
        in.repaymentMethod = "EQUAL_PAYMENT";
        in.annualIncome = yen(9_000_000);
        in.collateralValue = yen(45_000_000);
        in.relationshipYears = 3;
        in.ageAtMaturity = 65;
        return in;
    }

    @Test
    void goodCardApplicationIsApprovedWithGradeSpread() {
        // 返済比率 = 500,000 × 36% / 6,000,000 = 3% → 40 点、無担保 10、年収 12、取引 5 年 10 → 72 点（B）
        CreditScoring.Result r = CreditScoring.evaluate(card(500_000, 6_000_000));
        assertEquals(72, r.score);
        assertEquals("B", r.grade);
        assertEquals("APPROVE", r.recommendation);
        assertEquals(new BigDecimal("0.0300"), r.dti);
        assertNull(r.ltv);
        assertEquals(0, new BigDecimal("12.300").compareTo(r.rate));
        assertEquals(yen(180_000), r.annualRepayment);
    }

    @Test
    void cardOverOneThirdOfIncomeIsDeclined() {
        CreditScoring.Result r = CreditScoring.evaluate(card(3_000_000, 6_000_000));
        assertEquals("DECLINE", r.recommendation);
        assertTrue(r.reasons.stream().anyMatch(s -> s.contains("総量規制")));
    }

    @Test
    void corporateCardIsNotSubjectToTheTotalVolumeRule() {
        CreditScoring.Input in = card(3_000_000, 60_000_000);
        in.corporate = true;
        assertFalse(CreditScoring.evaluate(in).reasons.stream().anyMatch(s -> s.contains("総量規制")));
    }

    @Test
    void zeroIncomeIsAlwaysOverTheRepaymentRatio() {
        CreditScoring.Result r = CreditScoring.evaluate(card(100_000, 0));
        assertEquals(new BigDecimal("9.9999"), r.dti);
        assertEquals("DECLINE", r.recommendation);
    }

    @Test
    void delinquencyLowersTheScoreButNotBelowZero() {
        CreditScoring.Input in = card(500_000, 6_000_000);
        in.delinquentLoans = 1;
        CreditScoring.Result r = CreditScoring.evaluate(in);
        assertEquals(42, r.score);
        assertEquals("D", r.grade);
        assertEquals("REVIEW", r.recommendation);
        assertTrue(r.reasons.contains("延滞中の融資あり"));

        CreditScoring.Input worst = card(100_000, 0);
        worst.relationshipYears = 0;
        worst.delinquentLoans = 2;
        assertEquals(0, CreditScoring.evaluate(worst).score);
    }

    @Test
    void cardRateNeverExceedsTheCap() {
        CreditScoring.Input in = card(100_000, 0);
        in.delinquentLoans = 1;
        CreditScoring.Result r = CreditScoring.evaluate(in);
        assertEquals("E", r.grade);
        assertTrue(r.rate.compareTo(CreditScoring.CARD_RATE_CAP) <= 0);
    }

    @Test
    void housingLoanRules() {
        CreditScoring.Result ok = CreditScoring.evaluate(housing());
        assertEquals("APPROVE", ok.recommendation);
        assertEquals(new BigDecimal("0.6667"), ok.ltv);

        CreditScoring.Input old = housing();
        old.ageAtMaturity = 81;
        assertEquals("DECLINE", CreditScoring.evaluate(old).recommendation);

        CreditScoring.Input noCollateral = housing();
        noCollateral.collateralValue = BigDecimal.ZERO;
        CreditScoring.Result r = CreditScoring.evaluate(noCollateral);
        assertEquals("DECLINE", r.recommendation);
        assertTrue(r.reasons.contains("担保評価額なし"));
    }

    @Test
    void overdraftIgnoresTheRepaymentRatioLimit() {
        CreditScoring.Input in = new CreditScoring.Input();
        in.product = "OVERDRAFT";
        in.amount = yen(5_000_000);
        in.annualIncome = yen(1);
        in.relationshipYears = 5;
        CreditScoring.Result r = CreditScoring.evaluate(in);
        assertFalse(r.reasons.contains("返済比率が 40% を超過"));
    }
}
