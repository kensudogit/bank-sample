package com.example.bank.service;

import com.example.bank.support.Kana;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 預金・融資・為替の計算（DB なし）。 */
class BankingCalculationsTest {

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertYen(long expected, BigDecimal actual) {
        assertEquals(0, yen(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void timeDepositSimpleInterest() {
        // 1,000,000 x 0.40% x 365 / 365 = 4,000
        assertYen(4000, DepositMath.simpleInterest(yen(1_000_000), new BigDecimal("0.40"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        // 1,000,000 x 0.2% x 100 / 365 = 547.9 -> 547
        assertYen(547, DepositMath.simpleInterest(yen(1_000_000), new BigDecimal("0.2"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 11)));
    }

    @Test
    void foreignExchangeRounding() {
        assertYen(12345, DepositMath.toMinor("123.45", "USD"));
        assertYen(10000, DepositMath.toMinor("100", "USD"));
        assertThrows(IllegalArgumentException.class, () -> DepositMath.toMinor("1.234", "USD"));
        // Buy USD 100.00 at TTS 153.30 -> 15,330 yen; buy USD 0.01 at 153.30 -> 1.533 -> 2 yen (round up)
        assertYen(15330, DepositMath.yenToBuy(yen(10000), "USD", new BigDecimal("153.30")));
        assertYen(2, DepositMath.yenToBuy(yen(1), "USD", new BigDecimal("153.30")));
        // Sell USD 40.50 at TTB 151.30 -> 6,127.65 -> 6,127 yen (round down)
        assertYen(6127, DepositMath.yenFromSell(yen(4050), "USD", new BigDecimal("151.30")));
    }

    @Test
    void equalPaymentSchedule() {
        // 0% -> principal / n
        assertYen(100_000, LoanMath.equalPayment(yen(1_200_000), BigDecimal.ZERO, 12));
        // 12%/year, 1 month -> P x 1.01
        assertYen(1_212_000, LoanMath.equalPayment(yen(1_200_000), new BigDecimal("12"), 1));

        List<LoanMath.Installment> s = LoanMath.schedule(yen(30_000_000), new BigDecimal("1.2"), 240, "EQUAL_PAYMENT");
        assertEquals(240, s.size());
        BigDecimal principal = s.stream().map(i -> i.principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertYen(30_000_000, principal);
        assertYen(0, s.get(239).balanceAfter);
        BigDecimal payment = s.get(0).total();
        for (int k = 0; k < 239; k++) assertEquals(0, payment.compareTo(s.get(k).total()), "installment " + (k + 1));
        assertTrue(s.get(239).total().compareTo(payment) <= 0);
        // First month interest = 30,000,000 x 1.2% / 12 = 30,000
        assertYen(30_000, s.get(0).interest);
    }

    @Test
    void equalPrincipalSchedule() {
        List<LoanMath.Installment> s = LoanMath.schedule(yen(1_000_000), new BigDecimal("2.4"), 3, "EQUAL_PRINCIPAL");
        assertYen(333_334, s.get(0).principal);   // remainder on the first installment
        assertYen(333_333, s.get(1).principal);
        assertYen(333_333, s.get(2).principal);
        assertYen(2_000, s.get(0).interest);      // 1,000,000 x 2.4% / 12
        assertYen(1_333, s.get(1).interest);      // 666,666 x 0.2% = 1,333.3
        assertTrue(s.get(2).interest.compareTo(s.get(1).interest) < 0);
    }

    @Test
    void lateFeeAndDailyInterest() {
        // 100,000 x 14.6% x 30 / 365 = 1,200
        assertYen(1200, LoanMath.lateFee(yen(100_000), 30));
        assertYen(0, LoanMath.lateFee(yen(100_000), 0));
        assertEquals(0, new BigDecimal("1972.60").compareTo(LoanMath.dailyInterest(yen(200_000), new BigDecimal("12"), 30)));
    }

    @Test
    void creditScoringRules() {
        CreditScoring.Input card = new CreditScoring.Input();
        card.product = "CARD";
        card.amount = yen(2_000_000);
        card.annualIncome = yen(4_000_000);
        card.relationshipYears = 3;
        CreditScoring.Result r = CreditScoring.evaluate(card);
        assertEquals("DECLINE", r.recommendation);   // > 1/3 of income (総量規制)
        assertTrue(r.reasons.stream().anyMatch(x -> x.contains("総量規制")));

        card.amount = yen(500_000);
        card.annualIncome = yen(6_000_000);
        r = CreditScoring.evaluate(card);
        assertNotEquals("DECLINE", r.recommendation);
        assertTrue(r.rate.compareTo(CreditScoring.CARD_RATE_CAP) <= 0);

        CreditScoring.Input housing = new CreditScoring.Input();
        housing.product = "HOUSING";
        housing.amount = yen(30_000_000);
        housing.termMonths = 420;
        housing.repaymentMethod = "EQUAL_PAYMENT";
        housing.annualIncome = yen(9_000_000);
        housing.collateralValue = yen(45_000_000);
        housing.relationshipYears = 6;
        housing.ageAtMaturity = 81;
        assertEquals("DECLINE", CreditScoring.evaluate(housing).recommendation);   // age at maturity
        housing.ageAtMaturity = 66;
        CreditScoring.Result ok = CreditScoring.evaluate(housing);
        assertEquals("APPROVE", ok.recommendation);
        assertEquals("A", ok.grade);
        assertEquals(0, new BigDecimal("0.6667").compareTo(ok.ltv));
    }

    @Test
    void borrowerClassByDaysPastDue() {
        assertEquals("NORMAL", LoanService.borrowerClass(0));
        assertEquals("WATCH", LoanService.borrowerClass(30));
        assertEquals("SPECIAL", LoanService.borrowerClass(90));
        assertEquals("DOUBTFUL", LoanService.borrowerClass(200));
        assertEquals("DEFACTO", LoanService.borrowerClass(400));
    }

    @Test
    void zenginKanaAndRecordLayout() {
        assertEquals("ﾔﾏﾀﾞ ﾀﾛｳ", Kana.toZengin("ヤマダ タロウ"));
        assertEquals("ｶ)ｻﾝﾌﾟﾙｼﾖｳｼﾞ", Kana.toZengin("カ）サンプルショウジ"));   // small kana become large
        assertEquals("ﾔﾏﾀﾞﾀﾛｳ", Kana.normalizeName("ﾔﾏﾀﾞ　ﾀﾛｳ"));

        Map<String, Object> row = new HashMap<>();
        row.put("remitId", BigDecimal.valueOf(12));
        row.put("bankCode", "0005");
        row.put("bankNameKana", "ﾐﾂﾋﾞｼﾕ-ｴﾌｼﾞｴｲ");
        row.put("branchCode", "123");
        row.put("destAccountType", "1");
        row.put("destAccountNo", "7654321");
        row.put("destNameKana", "ｽｽﾞｷ ｲﾁﾛｳ");
        row.put("amount", BigDecimal.valueOf(50000));
        String file = PaymentService.zenginFile(Arrays.asList(row, row), LocalDate.of(2026, 11, 20));
        String[] lines = file.split("\r\n");
        assertEquals(5, lines.length);   // header, 2 data, trailer, end
        for (String l : lines) assertEquals(120, l.length(), l);
        assertTrue(lines[0].startsWith("1210"));
        assertTrue(lines[1].startsWith("20005"));
        assertEquals("8000002000000100000", lines[3].substring(0, 19));
        assertTrue(lines[4].startsWith("9"));
    }

    @Test
    void sanctionsNameMatching() {
        assertTrue(AmlService.nameSimilarity("Ivan Petrov Sampleov", "IVAN PETROV SAMPLEOV") >= 0.99);
        assertTrue(AmlService.nameSimilarity("SAMPLEOV, IVAN PETROV", "IVAN PETROV SAMPLEOV") >= 0.85);   // order
        assertTrue(AmlService.nameSimilarity("IVAN PETROV SAMPLEOFF", "IVAN PETROV SAMPLEOV") >= 0.85);  // spelling
        assertTrue(AmlService.nameSimilarity("JOHN SMITH", "IVAN PETROV SAMPLEOV") < 0.5);
        assertTrue(AmlService.nameSimilarity("ACME SHADOW TRADING", "ACME SHADOW TRADING LLC") >= 0.99);  // legal form
    }
}
