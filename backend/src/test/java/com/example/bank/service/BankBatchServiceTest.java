package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 銀行バッチ：日次取引集計・利息の日次計上・手数料・為替照合・会計仕訳集計・月末締め・帳票・残高証明・再実行。 */
class BankBatchServiceTest {
    private TestDatabase db;
    private MutableClock clock;
    private BankService bank;
    private PaymentService payments;
    private LoanService loans;
    private BankBatchService batch;

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        payments = new PaymentService(() -> db, clock);
        loans = new LoanService(() -> db, clock);
        batch = new BankBatchService(() -> db, clock);
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

    private BigDecimal scalar(String q) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        }
    }

    /** 11/20 の取引：入金・振込・出金・他行宛振込（手数料つき）・住宅ローン（300 万円）の実行。 */
    private long dayOfBusiness() {
        bank.deposit("1000001", yen(100_000), "入金", key());
        bank.transfer("1000001", "1000003", yen(20_000), "振込", key());
        bank.withdraw("1000003", yen(5_000), "出金", key());
        payments.sendDomestic("1000001", "0005", "123", "1", "7654321", "スズキ イチロウ", yen(50_000), key(), "teller1");
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU0001";
        a.product = "HOUSING";
        a.amount = yen(3_000_000);
        a.termMonths = 120;
        a.repaymentMethod = "EQUAL_PAYMENT";
        a.purpose = "リフォーム";
        a.annualIncome = yen(9_000_000);
        a.collateralValue = yen(10_000_000);
        long appId = new BigDecimal(loans.apply(a, "teller1").get("applicationId").toString()).longValue();
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "承認", null, "supervisor1");
        loans.addCollateral(appId, "REAL_ESTATE", "自宅 第1順位抵当権", yen(10_000_000), 1, null, "teller1");
        long loanId = new BigDecimal(loans.contract(appId, "1000001", 27, "teller1").get("loanId").toString()).longValue();
        loans.disburse(loanId, "teller1", key());
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        return loanId;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String k) { return (List<Map<String, Object>>) m.get(k); }

    @Test
    void dailySummaryCountsTheDayAndIsIdempotent() throws Exception {
        dayOfBusiness();
        Map<String, Object> r = batch.dailySummary(DAY);
        assertTrue(((Integer) r.get("txCount")) >= 7, String.valueOf(r));   // 入金・振込 2・出金・振込 2（金額・手数料）・融資実行
        assertYen(100_000 + 20_000 + 3_000_000, r.get("inflowJpy"));
        BigDecimal rows = scalar("SELECT COUNT(*) FROM batch_daily_summary");
        batch.dailySummary(DAY);
        assertEquals(0, rows.compareTo(scalar("SELECT COUNT(*) FROM batch_daily_summary")));
        assertYen(1, scalar("SELECT tx_count FROM batch_daily_summary WHERE tx_type = 'FEE'"));
        assertError(ErrorCode.INVALID_BASE_DATE, () -> batch.dailySummary(LocalDate.of(2026, 11, 21)));   // 本日は締まっていない
    }

    @Test
    void interestAccruesDailyAndCumulates() throws Exception {
        dayOfBusiness();
        batch.accrueInterest(DAY);
        BigDecimal balance = bank.getAccount("1000002").getBalance();   // 貯蓄 0.25%
        BigDecimal expected = balance.multiply(new BigDecimal("0.25")).divide(new BigDecimal("36500"), 4, RoundingMode.DOWN);
        assertEquals(0, expected.compareTo(scalar("SELECT daily_interest FROM deposit_interest_accrual "
                + "WHERE business_date = DATE '2026-11-20' AND account_no = '1000002'")));
        clock.set(LocalDateTime.of(2026, 11, 22, 9, 0));
        batch.accrueInterest(DAY.plusDays(1));
        assertEquals(0, expected.multiply(new BigDecimal("2")).compareTo(scalar("SELECT cumulative FROM deposit_interest_accrual "
                + "WHERE business_date = DATE '2026-11-21' AND account_no = '1000002'")));
        // 再実行しても累計は二重にならない
        batch.accrueInterest(DAY.plusDays(1));
        assertEquals(0, expected.multiply(new BigDecimal("2")).compareTo(scalar("SELECT cumulative FROM deposit_interest_accrual "
                + "WHERE business_date = DATE '2026-11-21' AND account_no = '1000002'")));
    }

    @Test
    void feeAndRemittanceChecksPassAndWarnAboutPendingTransfers() throws Exception {
        dayOfBusiness();
        Map<String, Object> fee = batch.feeCheck(DAY);
        assertEquals(1, fee.get("domestic"));
        assertYen(440, fee.get("feeIncome"));
        Map<String, Object> remit = batch.remittanceReconciliation(DAY);
        assertYen(50_000, remit.get("remitOut"));
        assertEquals(0, remit.get("ng"));
        assertYen(0, scalar("SELECT COUNT(*) FROM batch_check WHERE result = 'NG'"));

        // 料金表と違う手数料が記録されていたら NG：結果は残り、ジョブは失敗として報告される
        sql("UPDATE domestic_remittance SET fee = 330 WHERE direction = 'OUT'");
        assertThrows(IllegalStateException.class, () -> batch.feeCheck(DAY));
        assertTrue(scalar("SELECT COUNT(*) FROM batch_check WHERE check_group = 'FEE' AND result = 'NG'").intValue() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void generalLedgerBalancesAndReconcilesWithSubLedgers() throws Exception {
        dayOfBusiness();
        Map<String, Object> gl = batch.generalLedger(DAY);
        assertEquals(0, ((BigDecimal) gl.get("debit")).compareTo((BigDecimal) gl.get("credit")));
        assertEquals(0, gl.get("ng"));
        assertYen(3_000_000, scalar("SELECT debit FROM gl_daily WHERE gl_code = '1300'"));   // 貸出金（融資実行）
        assertYen(440, scalar("SELECT credit FROM gl_daily WHERE gl_code = '4200'"));        // 手数料収入
        BigDecimal clearingDr = scalar("SELECT debit FROM gl_daily WHERE gl_code = '1350'");
        BigDecimal clearingCr = scalar("SELECT credit FROM gl_daily WHERE gl_code = '1350'");
        assertEquals(0, clearingDr.compareTo(clearingCr), "融資決済勘定は相殺される");
        Map<String, Object> daily = batch.daily(DAY);
        assertFalse(list(daily, "ledger").isEmpty());
        assertTrue(list(daily, "checks").stream().allMatch(c -> !"NG".equals(c.get("result"))));

        // 明細の取引後残高が改ざんされると、預金科目と口座残高の照合が NG になる
        sql("UPDATE account_transaction SET balance_after = balance_after + 1 WHERE tx_id = (SELECT MAX(tx_id) FROM account_transaction "
                + "WHERE account_no = '1000003')");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> batch.generalLedger(DAY));
        assertTrue(e.getMessage().contains("会計照合"));
        assertYen(1, scalar("SELECT COUNT(*) FROM batch_check WHERE check_group = 'LEDGER' AND result = 'NG'"));
    }

    @Test
    void monthEndClosesTheMonthAndFlagsExceptions() throws Exception {
        dayOfBusiness();
        clock.set(LocalDateTime.of(2026, 12, 1, 2, 0));
        Map<String, Object> m = batch.monthEnd(YearMonth.of(2026, 11), "SYSTEM");
        assertEquals("CLOSED", m.get("status"));
        assertTrue(((BigDecimal) m.get("totalDeposits")).signum() > 0);
        assertYen(3_000_000, m.get("totalLoans"));
        assertYen(440, m.get("feeIncome"));
        assertError(ErrorCode.INVALID_BASE_DATE, () -> batch.monthEnd(YearMonth.of(2026, 12), "SYSTEM"));

        sql("INSERT INTO batch_check (business_date, check_group, check_item, result, detail, checked_at) "
                + "VALUES (DATE '2026-11-20', 'LEDGER', 'テスト', 'NG', 'x', CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class, () -> batch.monthEnd(YearMonth.of(2026, 11), "SYSTEM"));
        assertEquals("EXCEPTION", batch.monthCloses().get(0).get("status"));
    }

    @Test
    void reportsAreCsvWithBomAndFormulaProtection() throws Exception {
        dayOfBusiness();
        batch.dailySummary(DAY);
        batch.generalLedger(DAY);
        List<Map<String, Object>> made = batch.reports(DAY, "SYSTEM");
        assertEquals(Arrays.asList("TX_JOURNAL", "DAILY_BALANCE"), Arrays.asList(made.get(0).get("reportType"), made.get(1).get("reportType")));
        Map<String, Object> journal = batch.report(((Number) made.get(0).get("reportId")).longValue());
        String csv = (String) journal.get("content");
        assertTrue(csv.startsWith("﻿取引ID,日時,口座番号"), csv.substring(0, 30));
        assertTrue(csv.contains("1000001"));
        // 作り直すと置き換わる
        batch.reports(DAY, "SYSTEM");
        assertYen(2, scalar("SELECT COUNT(*) FROM report_output"));
        // 月末日は月次の帳票も作る
        clock.set(LocalDateTime.of(2026, 12, 1, 2, 0));
        batch.monthEnd(YearMonth.of(2026, 11), "SYSTEM");
        assertEquals(4, batch.reports(LocalDate.of(2026, 11, 30), "SYSTEM").size());
        assertEquals("'=SUM(A1)", BankBatchService.csvLine(Arrays.<Object>asList("=SUM(A1)")).trim().replace("\"", ""));
        assertEquals("1,\"a,b\",", BankBatchService.csvLine(Arrays.<Object>asList(yen(1), "a,b", null)).trim());
    }

    @Test
    void balanceCertificateUsesTheLedgerBalanceOfTheDay() {
        BigDecimal before = bank.getAccount("1000003").getBalance();
        bank.deposit("1000003", yen(7_000), "入金", key());
        assertYen(before.longValue(), batch.balanceCertificate("1000003", DAY.minusDays(1)).get("balance"));
        assertYen(before.longValue() + 7_000, batch.balanceCertificate("1000003", DAY).get("balance"));
        assertError(ErrorCode.INVALID_BASE_DATE, () -> batch.balanceCertificate("1000003", DAY.plusDays(1)));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND, () -> batch.balanceCertificate("9999999", DAY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedJobCanBeRerunAfterTheCauseIsFixed() throws Exception {
        dayOfBusiness();
        sql("UPDATE domestic_remittance SET fee = 330 WHERE direction = 'OUT'");
        clock.set(LocalDateTime.of(2026, 11, 21, 1, 30));
        JobRunner jobs = new JobRunner(() -> db, clock);
        assertTrue(jobs.runDue().contains(JobRunner.FEE_CHECK));
        assertEquals("FAILURE", jobs.recentRuns(50).stream().filter(r -> JobRunner.FEE_CHECK.equals(r.get("jobName")))
                .findFirst().get().get("outcome"));
        // 成功したジョブは再実行できない
        assertError(ErrorCode.INVALID_BASE_DATE, () -> jobs.rerun(JobRunner.DAILY_SUMMARY, LocalDate.of(2026, 11, 21), "supervisor1"));
        assertError(ErrorCode.VALIDATION_ERROR, () -> jobs.rerun("NO_SUCH_JOB", LocalDate.of(2026, 11, 21), "supervisor1"));
        // 原因を直して再実行すると成功する
        sql("UPDATE domestic_remittance SET fee = 440 WHERE direction = 'OUT'");
        Map<String, Object> r = jobs.rerun(JobRunner.FEE_CHECK, LocalDate.of(2026, 11, 21), "supervisor1");
        assertEquals("SUCCESS", r.get("outcome"));

        Map<String, Object> catalog = jobs.catalog();
        List<Map<String, Object>> items = (List<Map<String, Object>>) catalog.get("items");
        assertEquals(10, items.size());
        assertEquals("手数料計算", items.get(5).get("business"));
        Map<String, Object> feeJob = ((List<Map<String, Object>>) items.get(5).get("jobs")).get(0);
        assertEquals("SUCCESS", ((Map<String, Object>) feeJob.get("last")).get("outcome"));
    }
}
