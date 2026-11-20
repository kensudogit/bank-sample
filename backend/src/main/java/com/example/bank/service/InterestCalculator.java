package com.example.bank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 預金利息と源泉分離課税の計算（DB に依存しない純粋な計算）。
 *
 * <ul>
 *   <li>利息 = Σ(日々の最終残高 × その日の年利%) ÷ 100 ÷ 365、1 円未満切捨て（うるう年も 365 日）</li>
 *   <li>計算期間は periodFrom〜periodTo の両端を含む（払戻日・決算日当日は含めない運用とし、呼び出し側が前日を periodTo とする）</li>
 *   <li>税（個人・源泉分離課税）: 国税 15.315%（所得税 15% + 復興特別所得税 0.315%）と地方税 5% をそれぞれ 1 円未満切捨て</li>
 * </ul>
 */
public final class InterestCalculator {
    public static final BigDecimal NATIONAL_TAX_RATE = new BigDecimal("0.15315");
    public static final BigDecimal LOCAL_TAX_RATE = new BigDecimal("0.05");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private InterestCalculator() {}

    /** 適用開始日つきの年利（%）。 */
    public static final class Rate {
        final LocalDate effectiveFrom;
        final BigDecimal annualPercent;

        public Rate(LocalDate effectiveFrom, BigDecimal annualPercent) {
            this.effectiveFrom = effectiveFrom;
            this.annualPercent = annualPercent;
        }
    }

    /** 取引による残高変化（取引日時と取引後残高）。 */
    public static final class BalanceChange {
        final LocalDateTime at;
        final BigDecimal balanceAfter;

        public BalanceChange(LocalDateTime at, BigDecimal balanceAfter) {
            this.at = at;
            this.balanceAfter = balanceAfter;
        }
    }

    /** 計算結果。 */
    public static final class Calculation {
        public final int days;
        public final BigDecimal balanceDays;
        public final BigDecimal grossInterest;
        public final BigDecimal nationalTax;
        public final BigDecimal localTax;

        Calculation(int days, BigDecimal balanceDays, BigDecimal grossInterest) {
            this.days = days;
            this.balanceDays = balanceDays;
            this.grossInterest = grossInterest;
            this.nationalTax = tax(grossInterest, NATIONAL_TAX_RATE);
            this.localTax = tax(grossInterest, LOCAL_TAX_RATE);
        }

        public BigDecimal netInterest() { return grossInterest.subtract(nationalTax).subtract(localTax); }
    }

    /**
     * @param openingBalance periodFrom 開始時点の残高
     * @param changes        期間内の残高変化（日時の昇順）。期間外のものは無視する
     * @param rates          年利（適用開始日の昇順）。最初の適用開始日より前の日は 0%
     */
    public static Calculation calculate(BigDecimal openingBalance, List<BalanceChange> changes, List<Rate> rates,
                                        LocalDate periodFrom, LocalDate periodTo) {
        if (periodTo.isBefore(periodFrom)) return new Calculation(0, BigDecimal.ZERO, BigDecimal.ZERO);

        BigDecimal balance = openingBalance;
        BigDecimal balanceDays = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;   // Σ 残高 × 年利%
        int changeIdx = 0;
        int rateIdx = -1;
        int days = 0;

        for (LocalDate day = periodFrom; !day.isAfter(periodTo); day = day.plusDays(1)) {
            // End-of-day balance: apply every change made on or before this day
            while (changeIdx < changes.size() && !changes.get(changeIdx).at.toLocalDate().isAfter(day)) {
                if (!changes.get(changeIdx).at.toLocalDate().isBefore(periodFrom)) {
                    balance = changes.get(changeIdx).balanceAfter;
                }
                changeIdx++;
            }
            while (rateIdx + 1 < rates.size() && !rates.get(rateIdx + 1).effectiveFrom.isAfter(day)) rateIdx++;
            BigDecimal rate = rateIdx < 0 ? BigDecimal.ZERO : rates.get(rateIdx).annualPercent;

            balanceDays = balanceDays.add(balance);
            weighted = weighted.add(balance.multiply(rate));
            days++;
        }
        BigDecimal gross = weighted.divide(HUNDRED, 10, RoundingMode.DOWN)
                .divide(DAYS_PER_YEAR, 0, RoundingMode.DOWN);
        return new Calculation(days, balanceDays, gross);
    }

    /** 税額（1 円未満切捨て）。 */
    public static BigDecimal tax(BigDecimal grossInterest, BigDecimal rate) {
        return grossInterest.multiply(rate).setScale(0, RoundingMode.DOWN);
    }
}
