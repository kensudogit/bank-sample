package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import com.example.bank.support.BusinessCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 情報系：DWH への取込み（過去日の再現・冪等）、経営ダッシュボード、顧客分析、帳票（H2 / Oracle 互換モード）。 */
class InformationServiceTest {
    private TestDatabase db;
    private MutableClock clock;
    private BankService bank;
    private InformationService info;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        info = new InformationService(() -> db, clock);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private static void assertYen(long expected, Object actual) {
        BigDecimal a = actual instanceof BigDecimal ? (BigDecimal) actual : new BigDecimal(actual.toString());
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(a), "expected " + expected + " but was " + actual);
    }

    private static void assertError(ErrorCode expected, Executable call) {
        BankException e = assertThrows(BankException.class, call);
        assertEquals(expected, e.getCode());
    }

    // DWH の 1 値を直接読む（取込結果の検証用）
    private BigDecimal scalar(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() && rs.getBigDecimal(1) != null ? rs.getBigDecimal(1) : null;
        }
    }

    private LocalDate d(String iso) { return LocalDate.parse(iso); }

    @Test
    void snapshotRebuildsPastBalancesFromTheLedger() throws Exception {
        BigDecimal now = bank.getAccount("1000001").getBalance();
        bank.deposit("1000001", BigDecimal.valueOf(10_000), "a", key());   // 2026-11-20 10:00

        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        info.load(d("2026-11-19"), d("2026-11-20"), "tester");

        // 11/19 の終わりは入金前、11/20 の終わりは入金後の残高
        assertYen(now.longValue(), scalar("SELECT balance FROM dw_account_daily WHERE snapshot_date = DATE '2026-11-19' "
                + "AND account_no = '1000001'"));
        assertYen(now.longValue() + 10_000, scalar("SELECT balance FROM dw_account_daily "
                + "WHERE snapshot_date = DATE '2026-11-20' AND account_no = '1000001'"));
        // 11/20 のフロー：入金 1 件
        assertYen(10_000, scalar("SELECT inflow_amount FROM dw_daily_kpi WHERE snapshot_date = DATE '2026-11-20'"));
        assertYen(1, scalar("SELECT tx_count FROM dw_daily_kpi WHERE snapshot_date = DATE '2026-11-20'"));
        assertYen(0, scalar("SELECT tx_count FROM dw_daily_kpi WHERE snapshot_date = DATE '2026-11-19'"));
        // 預金残高の合計 = 口座スナップショットの正の円換算残高の合計
        assertEquals(0, scalar("SELECT SUM(balance_jpy) FROM dw_account_daily WHERE snapshot_date = DATE '2026-11-20' "
                + "AND balance_jpy > 0 AND status <> 'CLOSED'").compareTo(
                scalar("SELECT total_deposits FROM dw_daily_kpi WHERE snapshot_date = DATE '2026-11-20'")));
    }

    @Test
    void accountsOpenedLaterAreNotInEarlierSnapshots() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        // 最も古いサンプル口座は 2014 年 4 月の開設。その前の日には口座がない
        info.load(d("2014-03-31"), d("2014-03-31"), "tester");
        assertYen(0, scalar("SELECT COUNT(*) FROM dw_account_daily WHERE snapshot_date = DATE '2014-03-31'"));
        // 1000001 は 2026-01-05、1000004 は 2026-01-06 の開設
        info.load(d("2026-01-05"), d("2026-01-05"), "tester");
        assertYen(1, scalar("SELECT COUNT(*) FROM dw_account_daily WHERE snapshot_date = DATE '2026-01-05' "
                + "AND account_no = '1000001'"));
        assertYen(0, scalar("SELECT COUNT(*) FROM dw_account_daily WHERE snapshot_date = DATE '2026-01-05' "
                + "AND account_no = '1000004'"));
        assertTrue(scalar("SELECT new_accounts FROM dw_daily_kpi WHERE snapshot_date = DATE '2026-01-05'").intValue() >= 2);
    }

    @Test
    void reloadingADayReplacesItsRows() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        info.load(d("2026-11-20"), d("2026-11-20"), "tester");
        BigDecimal rows = scalar("SELECT COUNT(*) FROM dw_account_daily");
        BigDecimal deposits = scalar("SELECT total_deposits FROM dw_daily_kpi");
        List<Map<String, Object>> again = info.load(d("2026-11-20"), d("2026-11-20"), "tester2");
        assertEquals(1, again.size());
        assertEquals(0, rows.compareTo(scalar("SELECT COUNT(*) FROM dw_account_daily")));
        assertEquals(0, deposits.compareTo(scalar("SELECT total_deposits FROM dw_daily_kpi")));
        assertYen(1, scalar("SELECT COUNT(*) FROM dw_daily_kpi"));
    }

    @Test
    void loadRejectsTodayFutureReversedAndTooLongRanges() {
        assertError(ErrorCode.INVALID_PERIOD, () -> info.load(d("2026-11-20"), d("2026-11-20"), "t"));   // 本日
        assertError(ErrorCode.INVALID_PERIOD, () -> info.load(d("2026-11-19"), d("2026-11-18"), "t"));
        assertError(ErrorCode.INVALID_PERIOD, () -> info.load(d("2026-08-01"), d("2026-11-19"), "t"));   // 92 日超
        assertError(ErrorCode.INVALID_PERIOD, () -> info.load(null, d("2026-11-19"), "t"));
    }

    @Test
    void nightlyLoadBackfillsThirtyDaysWhenEmptyThenOnlyYesterday() throws Exception {
        Map<String, Object> first = info.loadPreviousDay();
        assertEquals(30, first.get("days"));
        assertEquals("2026-11-19", first.get("to"));
        clock.set(LocalDateTime.of(2026, 11, 21, 1, 30));
        Map<String, Object> second = info.loadPreviousDay();
        assertEquals(1, second.get("days"));
        assertYen(31, scalar("SELECT COUNT(*) FROM dw_daily_kpi"));
    }

    @Test
    void rfmScoresAndSegments() {
        assertEquals(5, InformationService.rScore(0));
        assertEquals(5, InformationService.rScore(30));
        assertEquals(4, InformationService.rScore(31));
        assertEquals(2, InformationService.rScore(365));
        assertEquals(1, InformationService.rScore(366));
        assertEquals(1, InformationService.rScore(null));
        assertEquals(1, InformationService.fScore(0));
        assertEquals(2, InformationService.fScore(1));
        assertEquals(5, InformationService.fScore(20));
        assertEquals(1, InformationService.mScore(BigDecimal.valueOf(99_999)));
        assertEquals(2, InformationService.mScore(BigDecimal.valueOf(100_000)));
        assertEquals(4, InformationService.mScore(BigDecimal.valueOf(3_000_000)));
        assertEquals(5, InformationService.mScore(BigDecimal.valueOf(10_000_000)));
        assertEquals("LOYAL", InformationService.segment(5, 4));
        assertEquals("GROWTH", InformationService.segment(4, 2));
        assertEquals("AT_RISK", InformationService.segment(2, 3));
        assertEquals("LAPSED", InformationService.segment(1, 1));
        assertEquals("STANDARD", InformationService.segment(3, 5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardShowsSeriesLatestPreviousAndProfit() {
        Map<String, Object> empty = info.dashboard(null, null);
        assertNull(empty.get("latest"));

        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        info.load(d("2026-11-18"), d("2026-11-20"), "tester");
        Map<String, Object> m = info.dashboard(null, null);
        List<Map<String, Object>> series = (List<Map<String, Object>>) m.get("series");
        assertFalse(series.isEmpty());
        assertEquals("2026-11-20", ((Map<String, Object>) m.get("latest")).get("snapshotDate"));
        assertEquals("2026-11-19", ((Map<String, Object>) m.get("previous")).get("snapshotDate"));
        assertNotNull(m.get("profit"));
        assertFalse(((List<?>) m.get("depositMix")).isEmpty());
        assertEquals(1, ((List<?>) m.get("monthly")).size());
        // 指定期間だけ
        Map<String, Object> range = info.dashboard(d("2026-11-19"), d("2026-11-20"));
        assertEquals(2, ((List<?>) range.get("series")).size());
        assertError(ErrorCode.INVALID_PERIOD, () -> info.dashboard(d("2026-11-20"), d("2026-11-19")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void customerAnalyticsFindsSegmentsCrossSellAndChurnRisk() {
        // 30 日前の取込み → 大口の出金 → 当日の取込み：預かり資産が 30% 以上減った顧客が離反リスクに出る
        clock.set(LocalDateTime.of(2026, 10, 21, 9, 0));
        info.load(d("2026-10-20"), d("2026-10-20"), "tester");
        BigDecimal before = bank.getAccount("1000002").getBalance();
        clock.set(LocalDateTime.of(2026, 11, 19, 10, 0));
        bank.withdraw("1000002", before.subtract(BigDecimal.valueOf(1_000)), "解約前の払出し", key());
        clock.set(LocalDateTime.of(2026, 11, 20, 9, 0));
        info.load(d("2026-11-19"), d("2026-11-19"), "tester");

        Map<String, Object> a = info.customerAnalytics(null);
        assertEquals("2026-11-19", a.get("date"));
        List<Map<String, Object>> customers = (List<Map<String, Object>>) a.get("customers");
        assertFalse(customers.isEmpty());
        int segmentTotal = 0;
        for (Map<String, Object> s : (List<Map<String, Object>>) a.get("segments")) {
            segmentTotal += new BigDecimal(s.get("customers").toString()).intValue();
        }
        assertEquals(customers.size(), segmentTotal);
        // CU0001 は直近に取引があり R が高い
        Map<String, Object> cu1 = customers.stream().filter(c -> "CU0001".equals(c.get("customerId"))).findFirst().get();
        assertEquals(5, new BigDecimal(cu1.get("rScore").toString()).intValue());
        assertTrue(((List<Map<String, Object>>) a.get("churnRisk")).stream()
                .anyMatch(c -> "CU0001".equals(c.get("customerId")) && String.valueOf(c.get("reason")).contains("減少")));
        assertFalse(((List<?>) a.get("crossSell")).isEmpty());
        // データがなければ空
        assertNull(info.customerAnalytics(d("2026-01-01")).get("date"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsReturnColumnsRowsAndTotals() {
        bank.deposit("1000001", BigDecimal.valueOf(5_000), "a", key());
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        info.load(d("2026-11-20"), d("2026-11-20"), "tester");
        for (String type : InformationService.REPORT_TYPES) {
            Map<String, Object> r = info.report(type, null, null, null);
            assertNotNull(r.get("title"), type);
            assertFalse(((List<?>) r.get("columns")).isEmpty(), type);
            assertNotNull(r.get("totals"), type);
        }
        Map<String, Object> tx = info.report("TX_SUMMARY", null, d("2026-11-20"), d("2026-11-20"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) tx.get("rows");
        assertTrue(rows.stream().anyMatch(x -> "DEPOSIT".equals(x.get("txType"))));
        assertYen(1, ((Map<String, Object>) tx.get("totals")).get("txCount"));

        Map<String, Object> dep = info.report("DEPOSIT_BALANCE", null, null, null);
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> x : (List<Map<String, Object>>) dep.get("rows")) sum = sum.add(new BigDecimal(x.get("balanceJpy").toString()));
        assertEquals(0, sum.compareTo((BigDecimal) ((Map<String, Object>) dep.get("totals")).get("balanceJpy")));
        assertError(ErrorCode.VALIDATION_ERROR, () -> info.report("UNKNOWN", null, null, null));
        assertError(ErrorCode.INVALID_PERIOD, () -> info.report("KPI_TREND", null, d("2025-01-01"), d("2026-11-20")));
    }

    @Test
    void statusShowsLoadedRange() {
        assertNull(info.status().get("lastDate"));
        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        info.load(d("2026-11-19"), d("2026-11-20"), "tester");
        Map<String, Object> s = info.status();
        assertEquals("2026-11-19", s.get("firstDate"));
        assertEquals("2026-11-20", s.get("lastDate"));
        assertYen(2, s.get("days"));
    }

    // 指定日のスナップショットを行の文字列にして並べる（取込日時・実行者は比較しない）
    private List<String> dump(String table, String date) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE snapshot_date = DATE '" + date + "' ORDER BY 2")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String col = md.getColumnLabel(i).toLowerCase();
                    if (col.equals("loaded_at") || col.equals("loaded_by")) continue;
                    Object v = rs.getObject(i);
                    sb.append(col).append('=').append(v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros().toPlainString() : v)
                            .append(';');
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    private static final List<String> DW_TABLES = java.util.Arrays.asList(
            "dw_account_daily", "dw_loan_daily", "dw_customer_daily", "dw_daily_kpi");

    /** 差分取込み（前日のスナップショット + その日の明細）は、全履歴からの作り直しと同じ結果になる。 */
    @Test
    void incrementalLoadMatchesFullRebuild() throws Exception {
        LoanService loans = new LoanService(() -> db, clock);
        // 11/19：入金・振込・住宅ローンの実行
        clock.set(LocalDateTime.of(2026, 11, 19, 10, 0));
        bank.deposit("1000001", BigDecimal.valueOf(50_000), "a", key());
        bank.transfer("1000003", "1000004", BigDecimal.valueOf(10_000), "b", key());
        LoanService.Application a = new LoanService.Application();
        a.customerId = "CU0001";
        a.product = "HOUSING";
        a.amount = BigDecimal.valueOf(20_000_000);
        a.termMonths = 240;
        a.repaymentMethod = "EQUAL_PAYMENT";
        a.annualIncome = BigDecimal.valueOf(9_000_000);
        a.collateralValue = BigDecimal.valueOf(40_000_000);
        long appId = new BigDecimal(loans.apply(a, "teller1").get("applicationId").toString()).longValue();
        loans.screen(appId, "teller1");
        loans.decide(appId, true, "承認", null, "supervisor1");
        loans.addCollateral(appId, "REAL_ESTATE", "土地", BigDecimal.valueOf(40_000_000), 1, null, "teller1");
        long loanId = new BigDecimal(loans.contract(appId, "1000001", 27, "teller1").get("loanId").toString()).longValue();
        loans.disburse(loanId, "teller1", key());
        // 11/20：出金・繰上返済・入金（前日の値に上書きされる口座と、変化しない口座がある）
        clock.set(LocalDateTime.of(2026, 11, 20, 11, 0));
        bank.withdraw("1000001", BigDecimal.valueOf(20_000), "c", key());
        loans.prepay(loanId, BigDecimal.valueOf(1_000_000), "teller1", key());
        bank.deposit("1000004", BigDecimal.valueOf(5_000), "d", key());

        clock.set(LocalDateTime.of(2026, 11, 21, 9, 0));
        List<Map<String, Object>> both = info.load(d("2026-11-19"), d("2026-11-20"), "tester");
        assertEquals("full", both.get(0).get("mode"));            // 11/18 のスナップショットがない
        assertEquals("incremental", both.get(1).get("mode"));     // 11/19 から差分で
        List<List<String>> incremental = new ArrayList<>();
        for (String t : DW_TABLES) incremental.add(dump(t, "2026-11-20"));
        assertFalse(incremental.get(1).isEmpty(), "融資のスナップショットがある");

        // 取込済みのデータを消して 11/20 だけを取り込む → 全履歴からの作り直し
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            for (String t : DW_TABLES) st.execute("DELETE FROM " + t);
        }
        List<Map<String, Object>> single = info.load(d("2026-11-20"), d("2026-11-20"), "tester");
        assertEquals("full", single.get(0).get("mode"));
        for (int i = 0; i < DW_TABLES.size(); i++) {
            assertEquals(incremental.get(i), dump(DW_TABLES.get(i), "2026-11-20"), DW_TABLES.get(i));
        }
    }

    /** まとめて読み込んだ休日カレンダーは、1 日ずつ照会するカレンダーと同じ営業日を返す（年末年始を含む）。 */
    @Test
    void bulkHolidayCalendarMatchesPerDayLookups() throws Exception {
        try (Connection c = db.getConnection()) {
            LocalDate from = LocalDate.of(2026, 11, 1);
            // 読み込む範囲を短くして、範囲を超えたときの追加読み込みも確かめる
            BusinessCalendar.Holidays h = BusinessCalendar.Holidays.load(c, from, from.plusMonths(1));
            for (LocalDate day = from; day.isBefore(LocalDate.of(2028, 1, 10)); day = day.plusDays(1)) {
                assertEquals(BusinessCalendar.onOrAfter(c, day), h.onOrAfter(day), day.toString());
            }
        }
    }
}
