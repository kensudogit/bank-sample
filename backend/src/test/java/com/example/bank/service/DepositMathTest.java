package com.example.bank.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 預金の計算：定期預金の単利（片端・切り捨て）、通貨の補助単位、両替の端数処理（買いは切り上げ・売りは切り捨て）。 */
class DepositMathTest {
    @Test
    void simpleInterestCountsDaysFromDepositToMaturityAndRoundsDown() {
        // 1,000,000 × 0.40% × 365 / 365 = 4,000
        assertEquals(new BigDecimal("4000"), DepositMath.simpleInterest(new BigDecimal("1000000"), new BigDecimal("0.40"),
                LocalDate.of(2025, 11, 20), LocalDate.of(2026, 11, 20)));
        // 1,000,000 × 0.2% × 100 / 365 = 547.9… → 547
        assertEquals(new BigDecimal("547"), DepositMath.simpleInterest(new BigDecimal("1000000"), new BigDecimal("0.2"),
                LocalDate.of(2026, 11, 20), LocalDate.of(2027, 2, 28)));
    }

    @Test
    void noInterestForZeroOrNegativePeriods() {
        LocalDate d = LocalDate.of(2026, 11, 20);
        assertEquals(BigDecimal.ZERO, DepositMath.simpleInterest(new BigDecimal("1000000"), BigDecimal.ONE, d, d));
        assertEquals(BigDecimal.ZERO, DepositMath.simpleInterest(new BigDecimal("1000000"), BigDecimal.ONE, d, d.minusDays(1)));
    }

    @Test
    void minorUnits() {
        assertEquals(0, DepositMath.minorDigits("JPY"));
        assertEquals(2, DepositMath.minorDigits("USD"));
        assertEquals(2, DepositMath.minorDigits("EUR"));
        assertEquals(new BigDecimal("1234"), DepositMath.toMinor("12.34", "USD"));
        assertEquals(new BigDecimal("1200"), DepositMath.toMinor("12", "USD"));
        assertEquals(new BigDecimal("100"), DepositMath.toMinor("100", "JPY"));
        assertEquals(new BigDecimal("12.34"), DepositMath.fromMinor(new BigDecimal("1234"), "USD"));
        assertEquals(new BigDecimal("100"), DepositMath.fromMinor(new BigDecimal("100"), "JPY"));
    }

    @Test
    void tooManyDecimalsAreRejectedInsteadOfRounded() {
        assertThrows(IllegalArgumentException.class, () -> DepositMath.toMinor("1.234", "USD"));
        assertThrows(IllegalArgumentException.class, () -> DepositMath.toMinor("1.5", "JPY"));
    }

    @Test
    void exchangeRounding() {
        // 12.34 USD × TTS 153.30 = 1,891.722 → 顧客から受け取る円は切り上げ 1,892
        assertEquals(new BigDecimal("1892"), DepositMath.yenToBuy(new BigDecimal("1234"), "USD", new BigDecimal("153.30")));
        // 12.34 USD × TTB 151.30 = 1,867.042 → 顧客に払う円は切り捨て 1,867
        assertEquals(new BigDecimal("1867"), DepositMath.yenFromSell(new BigDecimal("1234"), "USD", new BigDecimal("151.30")));
    }
}
