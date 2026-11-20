package com.example.bank.service;

import com.example.bank.service.InterestCalculator.BalanceChange;
import com.example.bank.service.InterestCalculator.Calculation;
import com.example.bank.service.InterestCalculator.Rate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterestCalculatorTest {
    private static final List<Rate> RATE_0_2 =
            Collections.singletonList(new Rate(LocalDate.of(2025, 1, 1), new BigDecimal("0.20")));

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    @Test
    void constantBalanceIsTruncatedToYenAndTaxedSeparately() {
        // 1,000,000 x 0.2% x 31 / 365 = 169.86... -> 169
        Calculation c = InterestCalculator.calculate(yen(1_000_000), Collections.<BalanceChange>emptyList(), RATE_0_2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertEquals(31, c.days);
        assertEquals(0, yen(31_000_000).compareTo(c.balanceDays));
        assertEquals(0, yen(169).compareTo(c.grossInterest));
        assertEquals(0, yen(25).compareTo(c.nationalTax));   // 169 x 15.315% = 25.88
        assertEquals(0, yen(8).compareTo(c.localTax));       // 169 x 5% = 8.45
        assertEquals(0, yen(136).compareTo(c.netInterest()));
    }

    @Test
    void usesEndOfDayBalanceAndIgnoresIntradayChanges() {
        // Day 1: 100,000 -> during day 2 goes to 0 and back to 200,000 (end of day 2 = 200,000)
        List<BalanceChange> changes = Arrays.asList(
                new BalanceChange(LocalDateTime.of(2026, 1, 2, 9, 0), yen(0)),
                new BalanceChange(LocalDateTime.of(2026, 1, 2, 15, 0), yen(200_000)));
        Calculation c = InterestCalculator.calculate(yen(100_000), changes, RATE_0_2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        assertEquals(0, yen(100_000 + 200_000 + 200_000).compareTo(c.balanceDays));
    }

    @Test
    void appliesRateChangesByDay() {
        List<Rate> rates = Arrays.asList(
                new Rate(LocalDate.of(2025, 1, 1), new BigDecimal("0.10")),
                new Rate(LocalDate.of(2026, 1, 11), new BigDecimal("0.30")));
        // 10 days at 0.1% + 10 days at 0.3% on 36,500,000 = 1,000 + 3,000
        Calculation c = InterestCalculator.calculate(yen(36_500_000), Collections.<BalanceChange>emptyList(), rates,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20));
        assertEquals(0, yen(4_000).compareTo(c.grossInterest));
    }

    @Test
    void noRateBeforeFirstEffectiveDateAndEmptyPeriod() {
        Calculation before = InterestCalculator.calculate(yen(1_000_000), Collections.<BalanceChange>emptyList(),
                RATE_0_2, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        assertEquals(0, BigDecimal.ZERO.compareTo(before.grossInterest));
        assertEquals(366, before.days);

        Calculation empty = InterestCalculator.calculate(yen(1_000_000), Collections.<BalanceChange>emptyList(),
                RATE_0_2, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1));
        assertEquals(0, empty.days);
        assertEquals(0, BigDecimal.ZERO.compareTo(empty.grossInterest));
    }

    @Test
    void smallInterestHasNoTax() {
        // 4 yen: national 0.61 -> 0, local 0.2 -> 0
        assertEquals(0, BigDecimal.ZERO.compareTo(InterestCalculator.tax(yen(4), InterestCalculator.NATIONAL_TAX_RATE)));
        assertEquals(0, yen(1).compareTo(InterestCalculator.tax(yen(7), InterestCalculator.NATIONAL_TAX_RATE)));
    }
}
