package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 融資管理：貸倒引当金・返済シミュレーション・担保の再評価・条件変更（H2 / Oracle 互換モード）。 */
class LoanManagementServiceTest {
    private TestDatabase db;
    private MutableClock clock;
    private LoanService loans;
    private LoanManagementService mgmt;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        loans = new LoanService(() -> db, clock);
        mgmt = new LoanManagementService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static BigDecimal yen(long v) { return BigDecimal.valueOf(v); }

    private static void assertYen(long expected, Object actual) {
        BigDecimal a = actual instanceof BigDecimal ? (BigDecimal) actual : new BigDecimal(actual.toString());
        assertEquals(0, yen(expected).compareTo(a), "expected " + expected + " but was " + actual);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    private void sql(String statement) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    private static long id(Map<String, Object> m, String key) { return new BigDecimal(m.get(key).toString()).longValue(); }

    /** 住宅ローン 3,000 万円（不動産担保 4,500 万円）を実行して融資番号を返す。 */
    private long housingLoan() {
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU0001";
        a.product = "HOUSING";
        a.amount = yen(30_000_000);
        a.termMonths = 240;
        a.repaymentMethod = "EQUAL_PAYMENT";
        a.purpose = "自宅購入";
        a.annualIncome = yen(9_000_000);
        a.collateralValue = yen(45_000_000);
        long appId = id(loans.apply(a, "teller1"), "applicationId");
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "承認", null, "supervisor1");
        loans.addCollateral(appId, "REAL_ESTATE", "マンション 第1順位抵当権", yen(45_000_000), 1, null, "teller1");
        long loanId = id(loans.contract(appId, "1000001", 27, "teller1"), "loanId");
        loans.disburse(loanId, "teller1", key());
        return loanId;
    }

    // 口座を空にして 2027-05-01 の夜間処理を流す（最も古い延滞は 2027-01-27 → 94 日 → 要管理先）
    private void makeDelinquent(long loanId) throws Exception {
        sql("UPDATE account SET balance = 0 WHERE account_no = '1000001'");
        clock.set(LocalDateTime.of(2027, 5, 1, 2, 0));
        loans.processDue(LocalDate.of(2027, 5, 1));
        assertEquals("SPECIAL", loans.getLoan(loanId).get("borrowerClass"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) { return (List<Map<String, Object>>) m.get(key); }

    @Test
    void provisionFormulaByBorrowerClass() {
        assertYen(150_000, LoanManagementService.requiredProvision("NORMAL", yen(30_000_000), yen(31_500_000)));
        assertYen(900_000, LoanManagementService.requiredProvision("WATCH", yen(30_000_000), yen(31_500_000)));
        assertYen(4_500_000, LoanManagementService.requiredProvision("SPECIAL", yen(30_000_000), yen(31_500_000)));
        // 破綻懸念先以下は非保全額（残高 − 保全額）に引当率
        assertYen(0, LoanManagementService.requiredProvision("DOUBTFUL", yen(30_000_000), yen(31_500_000)));
        assertYen(14_000_000, LoanManagementService.requiredProvision("DOUBTFUL", yen(30_000_000), yen(10_000_000)));
        assertYen(20_000_000, LoanManagementService.requiredProvision("DEFACTO", yen(30_000_000), yen(10_000_000)));
        assertYen(20_000_000, LoanManagementService.requiredProvision("BANKRUPT", yen(30_000_000), yen(10_000_000)));
        assertEquals("SPECIAL", LoanManagementService.worse("NORMAL", "SPECIAL"));
        assertEquals("DOUBTFUL", LoanManagementService.worse("DOUBTFUL", "SPECIAL"));
    }

    @Test
    void provisionRunIsIdempotentAndRecordsHistory() {
        long loanId = housingLoan();
        Map<String, Object> first = mgmt.provisionAll(null, "supervisor1");
        assertEquals(1, first.get("changed"));
        assertYen(150_000, first.get("totalProvision"));            // 正常先 0.5%
        Map<String, Object> again = mgmt.provisionAll(null, "supervisor1");
        assertEquals(0, again.get("changed"));
        Map<String, Object> m = mgmt.management(loanId);
        assertYen(150_000, m.get("provisionAmount"));
        assertEquals(1, list(m, "provisions").size());
        assertYen(30_000_000, m.get("outstanding"));
        assertYen(30_000_000, m.get("secured"));                     // 4,500 万 × 70% = 3,150 万 → 残高が上限
        assertYen(31_500_000, list(m, "collaterals").get(0).get("recognized"));
        assertError(ErrorCode.INVALID_BASE_DATE, () -> mgmt.provisionAll(LocalDate.of(2026, 11, 21), "s"));
    }

    @Test
    void delinquencyRevaluationAndWriteOffMoveTheProvision() throws Exception {
        long loanId = housingLoan();
        makeDelinquent(loanId);
        mgmt.provisionAll(null, "SYSTEM");
        Map<String, Object> special = mgmt.management(loanId);
        BigDecimal outstanding = (BigDecimal) special.get("outstanding");
        assertYen(outstanding.multiply(new BigDecimal("0.15")).setScale(0, java.math.RoundingMode.HALF_UP).longValue(),
                special.get("provisionAmount"));

        // 破綻懸念先：担保で全額保全されているので引当 0 に取り崩す
        loans.setBorrowerClass(loanId, "DOUBTFUL");
        mgmt.provisionAll(null, "SYSTEM");
        assertYen(0, mgmt.management(loanId).get("provisionAmount"));

        // 担保の価値が下がる（1,000 万円 → 保全 700 万円）と、非保全額の 70% を積み増す（その場で見直し）
        long collateralId = id(list(special, "collaterals").get(0), "collateralId");
        Map<String, Object> rev = mgmt.revalueCollateral(collateralId, yen(10_000_000), "鑑定評価の下落", "supervisor1");
        assertYen(45_000_000, rev.get("previousValue"));
        BigDecimal expected = outstanding.subtract(yen(7_000_000)).multiply(new BigDecimal("0.70"))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo((BigDecimal) rev.get("provisionChange")));
        Map<String, Object> m = mgmt.management(loanId);
        assertEquals(1, list(list(m, "collaterals").get(0), "revaluations").size());
        assertEquals(0, expected.compareTo((BigDecimal) m.get("provisionAmount")));

        // 償却：引当を取り崩し、不足分は損失（摘要に内訳）
        loans.writeOff(loanId, "回収不能", "supervisor1");
        Map<String, Object> after = mgmt.management(loanId);
        assertYen(0, after.get("provisionAmount"));
        assertTrue(String.valueOf(list(after, "provisions").get(0).get("reason")).contains("償却"));
        String memo = String.valueOf(list(loans.getLoan(loanId), "transactions").get(0).get("memo"));
        assertTrue(memo.contains("引当取崩 " + expected.toPlainString()), memo);
    }

    @Test
    void payingOffReleasesTheProvision() {
        long loanId = housingLoan();
        mgmt.provisionAll(null, "SYSTEM");
        // 1 回 1,000 万円・1 日 2,000 万円の上限内で 3 回に分けて完済する
        loans.prepay(loanId, yen(10_000_000), "teller1", key());
        loans.prepay(loanId, yen(10_000_000), "teller1", key());
        clock.set(LocalDateTime.of(2026, 11, 21, 10, 0));
        loans.prepay(loanId, yen(10_000_000), "teller1", key());
        Map<String, Object> m = mgmt.management(loanId);
        assertEquals("PAID_OFF", m.get("status"));
        assertYen(0, m.get("provisionAmount"));
        assertTrue(String.valueOf(list(m, "provisions").get(0).get("reason")).contains("完済"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulationComparesBothMethods() {
        Map<String, Object> s = mgmt.simulate(yen(1_000_000), new BigDecimal("2.0"), 12, "EQUAL_PAYMENT", 27);
        List<Map<String, Object>> rows = list(s, "schedule");
        assertEquals(12, rows.size());
        assertEquals("2026-12-28", rows.get(0).get("dueDate"));     // 12/27 は日曜 → 翌営業日
        BigDecimal principal = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) principal = principal.add((BigDecimal) r.get("principal"));
        assertYen(1_000_000, principal);
        assertYen(0, rows.get(11).get("balanceAfter"));
        List<Map<String, Object>> cmp = list(s, "comparison");
        BigDecimal annuity = (BigDecimal) cmp.get(0).get("totalInterest");
        BigDecimal linear = (BigDecimal) cmp.get(1).get("totalInterest");
        assertTrue(linear.compareTo(annuity) < 0, "元金均等の方が利息の総額は少ない");
        assertTrue(((BigDecimal) cmp.get(1).get("firstPayment")).compareTo((BigDecimal) cmp.get(1).get("lastPayment")) > 0);

        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.simulate(yen(0), BigDecimal.ONE, 12, "EQUAL_PAYMENT", null));
        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.simulate(yen(1_000), BigDecimal.ONE, 421, "EQUAL_PAYMENT", null));
        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.simulate(yen(1_000), new BigDecimal("20.5"), 12, "EQUAL_PAYMENT", null));
        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.simulate(yen(1_000), BigDecimal.ONE, 12, "REVOLVING", null));
        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.simulate(yen(1_000), BigDecimal.ONE, 12, "EQUAL_PAYMENT", 29));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleCuresDelinquencyButKeepsSpecialAttention() throws Exception {
        long loanId = housingLoan();
        makeDelinquent(loanId);
        Map<String, Object> before = loans.getLoan(loanId);
        BigDecimal outstanding = (BigDecimal) before.get("outstanding");
        BigDecimal rate = (BigDecimal) before.get("annualRate");

        assertError(ErrorCode.LOAN_RULE_VIOLATION,
                () -> mgmt.reschedule(loanId, 300, rate.add(BigDecimal.ONE), "引上げは不可", "supervisor1"));
        Map<String, Object> r = mgmt.reschedule(loanId, 300, null, "業況悪化による返済期間の延長", "supervisor1");
        assertEquals("SPECIAL", r.get("borrowerClass"));
        assertTrue(((BigDecimal) r.get("arrearsInterest")).signum() > 0);
        assertTrue(((BigDecimal) r.get("newPayment")).compareTo((BigDecimal) r.get("oldPayment")) < 0, "毎月の返済額が下がる");

        Map<String, Object> after = loans.getLoan(loanId);
        assertEquals("ACTIVE", after.get("status"));
        assertYen(0, after.get("daysPastDue"));
        List<Map<String, Object>> schedule = (List<Map<String, Object>>) after.get("schedule");
        BigDecimal newPrincipal = BigDecimal.ZERO;
        int open = 0;
        for (Map<String, Object> s : schedule) {
            if ("SCHEDULED".equals(s.get("status"))) {
                open++;
                newPrincipal = newPrincipal.add((BigDecimal) s.get("principalDue"));
            }
        }
        assertEquals(300, open);
        assertEquals(0, outstanding.compareTo(newPrincipal), "新しい予定表の元金合計 = 残高");
        assertFalse(schedule.stream().anyMatch(s -> "OVERDUE".equals(s.get("status"))));

        Map<String, Object> m = mgmt.management(loanId);
        assertYen(1, m.get("restructured"));
        assertEquals(1, list(m, "restructures").size());

        // 延滞がなくても夜間の判定で要管理先のまま（貸出条件緩和債権）
        sql("UPDATE account SET balance = 5000000 WHERE account_no = '1000001'");
        clock.set(LocalDateTime.of(2027, 5, 2, 2, 0));
        loans.processDue(LocalDate.of(2027, 5, 2));
        assertEquals("SPECIAL", loans.getLoan(loanId).get("borrowerClass"));
        assertError(ErrorCode.VALIDATION_ERROR, () -> mgmt.reschedule(loanId, 0, null, "x", "s"));
        assertError(ErrorCode.LOAN_NOT_FOUND, () -> mgmt.reschedule(999_999, 12, null, "x", "s"));
    }

    @Test
    void bankruptClassIsNotOverwrittenByTheNightlyRun() {
        long loanId = housingLoan();
        loans.setBorrowerClass(loanId, "BANKRUPT");
        clock.set(LocalDateTime.of(2026, 11, 21, 2, 0));
        loans.processDue(LocalDate.of(2026, 11, 21));
        assertEquals("BANKRUPT", loans.getLoan(loanId).get("borrowerClass"));
    }
}
