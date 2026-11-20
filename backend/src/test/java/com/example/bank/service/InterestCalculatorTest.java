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

/** 預金利息と源泉分離課税の計算（{@link InterestCalculator}）の単体テスト（DB なし）。 */
class InterestCalculatorTest {
    private static final List<Rate> RATE_0_2 =
            Collections.singletonList(new Rate(LocalDate.of(2025, 1, 1), new BigDecimal("0.20")));

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    /** 残高一定の利息は 1 円未満切捨て、国税と地方税はそれぞれ切捨て。 */
    @Test
    void constantBalanceIsTruncatedToYenAndTaxedSeparately() {
        // 1,000,000 × 0.2% × 31 ÷ 365 = 169.86… → 169 円
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

    /** 積数は日々の最終残高で計算し、日中の一時的な増減は影響しない。 */
    @Test
    void usesEndOfDayBalanceAndIgnoresIntradayChanges() {
        // 1 日目: 100,000 円 → 2 日目の途中で 0 円になり 200,000 円に戻る（2 日目の最終残高は 200,000 円）
        // Day 1: 100,000 -> during day 2 goes to 0 and back to 200,000 (end of day 2 = 200,000)
        List<BalanceChange> changes = Arrays.asList(
                new BalanceChange(LocalDateTime.of(2026, 1, 2, 9, 0), yen(0)),
                new BalanceChange(LocalDateTime.of(2026, 1, 2, 15, 0), yen(200_000)));
        Calculation c = InterestCalculator.calculate(yen(100_000), changes, RATE_0_2,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3));
        assertEquals(0, yen(100_000 + 200_000 + 200_000).compareTo(c.balanceDays));
    }

    /** 金利の変更は適用開始日から日単位で反映される。 */
    @Test
    void appliesRateChangesByDay() {
        List<Rate> rates = Arrays.asList(
                new Rate(LocalDate.of(2025, 1, 1), new BigDecimal("0.10")),
                new Rate(LocalDate.of(2026, 1, 11), new BigDecimal("0.30")));
        // 36,500,000 円で 0.1% を 10 日 + 0.3% を 10 日 = 1,000 円 + 3,000 円
        // 10 days at 0.1% + 10 days at 0.3% on 36,500,000 = 1,000 + 3,000
        Calculation c = InterestCalculator.calculate(yen(36_500_000), Collections.<BalanceChange>emptyList(), rates,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20));
        assertEquals(0, yen(4_000).compareTo(c.grossInterest));
    }

    /** 最初の適用開始日より前は 0%、計算期間がなければ日数 0。 */
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

    /** 少額の利息は税額が 1 円未満となり 0 円になる。 */
    @Test
    void smallInterestHasNoTax() {
        // 利息 4 円: 国税 0.61 → 0 円、地方税 0.2 → 0 円
        // 4 yen: national 0.61 -> 0, local 0.2 -> 0
        assertEquals(0, BigDecimal.ZERO.compareTo(InterestCalculator.tax(yen(4), InterestCalculator.NATIONAL_TAX_RATE)));
        assertEquals(0, yen(1).compareTo(InterestCalculator.tax(yen(7), InterestCalculator.NATIONAL_TAX_RATE)));
    }
}
