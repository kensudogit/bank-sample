package com.example.bank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 定期預金の利息・外貨両替の計算（DB に依存しない）。 */
public final class DepositMath {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private DepositMath() {}

    /** 単利: 元本 × 年利% × 日数 ÷ 365、1 円未満切捨て。 */
    public static BigDecimal simpleInterest(BigDecimal principal, BigDecimal annualPercent, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days <= 0) return BigDecimal.ZERO;
        return principal.multiply(annualPercent).multiply(BigDecimal.valueOf(days))
                .divide(HUNDRED.multiply(DAYS_PER_YEAR), 0, RoundingMode.DOWN);
    }

    /** 外貨の最小単位（セント）あたりの桁数。USD/EUR は 2。 */
    public static int minorDigits(String currency) {
        return "JPY".equals(currency) ? 0 : 2;
    }

    /** "123.45" → 12345（セント）。小数点以下の桁数が多すぎる場合は IllegalArgumentException。 */
    public static BigDecimal toMinor(String amount, String currency) {
        BigDecimal v = new BigDecimal(amount);
        int digits = minorDigits(currency);
        if (v.scale() > digits) throw new IllegalArgumentException("too many decimals");
        return v.movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY);
    }

    /** 12345（セント）→ 123.45 */
    public static BigDecimal fromMinor(BigDecimal minor, String currency) {
        return minor.movePointLeft(minorDigits(currency));
    }

    /**
     * 円 → 外貨（お客さまが外貨を買う）: 外貨額 × TTS、1 円未満切上げ（銀行が受け取る円）。
     *
     * @param foreignMinor 買う外貨額（セント）
     */
    public static BigDecimal yenToBuy(BigDecimal foreignMinor, String currency, BigDecimal tts) {
        return fromMinor(foreignMinor, currency).multiply(tts).setScale(0, RoundingMode.CEILING);
    }

    /** 外貨 → 円（お客さまが外貨を売る）: 外貨額 × TTB、1 円未満切捨て（銀行が支払う円）。 */
    public static BigDecimal yenFromSell(BigDecimal foreignMinor, String currency, BigDecimal ttb) {
        return fromMinor(foreignMinor, currency).multiply(ttb).setScale(0, RoundingMode.DOWN);
    }
}
