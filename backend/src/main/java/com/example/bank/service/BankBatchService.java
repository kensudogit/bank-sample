package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.insertReturningId;
import static com.example.bank.repository.ProductRepository.query;
import static com.example.bank.service.LoanService.num;

/**
 * 銀行の日次・月次バッチ（夜間に前日分を処理する）。どのジョブも「その業務日の結果を消してから作り直す」ので、
 * 同じ日を何度実行しても結果は同じ（冪等）。照合で NG が出たジョブは、結果を保存したうえで失敗として報告する。
 * <ul>
 *   <li>{@link #dailySummary}: 日次取引集計（取引種別・通貨別の件数・金額）</li>
 *   <li>{@link #accrueInterest}: 預金利息の日次計上（その日の最終残高 × 年利 ÷ 365、前回決算からの累計 = 未払利息）</li>
 *   <li>{@link #feeCheck}: 手数料の計算・照合（料金表どおりか、明細に計上された手数料と一致するか）</li>
 *   <li>{@link #remittanceReconciliation}: 為替照合（他行振込の送信・決済・全銀データ・保留）</li>
 *   <li>{@link #generalLedger}: 会計仕訳集計（補助元帳から総勘定元帳の日計を作り、貸借・預金・貸出金を照合）</li>
 *   <li>{@link #monthEnd}: 月末締め（月末残高と月次集計。その月の照合 NG があれば EXCEPTION）</li>
 *   <li>{@link #reports}: 帳票出力（取引明細表・日計表、月初は月末残高一覧・月次集計表）</li>
 * </ul>
 */
public class BankBatchService {
    /** 入金側の取引種別（預金の貸方） */
    static final List<String> CREDIT_TX = Arrays.asList("DEPOSIT", "TRANSFER_IN", "INTEREST", "FX_IN", "LOAN_DISB", "REMIT_IN");
    /** 利息のつく円の預金種別（金利は interest_rate 表。定期は契約の金利） */
    static final List<String> INTEREST_TYPES = Arrays.asList("ORDINARY", "SAVINGS", "NOTICE", "TIME");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final BankBatchService DEFAULT = new BankBatchService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    /**
     * @param dataSource 接続の取得元
     * @param clock      本日の判定・記録日時の取得元
     */
    public BankBatchService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static BankBatchService getDefault() { return DEFAULT; }

    // 処理する業務日は本日より前（その日の取引が締まっている）でなければならない
    private void requireClosedDay(LocalDate day) {
        if (day == null || !day.isBefore(LocalDate.now(clock))) throw new BankException(ErrorCode.INVALID_BASE_DATE);
    }

    // ===================================================================== 日次取引集計

    /**
     * 日次取引集計：その日の取引を種別・通貨ごとに件数と金額で集計する。
     *
     * @param day 業務日（本日より前）
     * @return 件数・入金額・出金額（円口座）と種別数
     */
    public Map<String, Object> dailySummary(LocalDate day) {
        requireClosedDay(day);
        return Transactions.inTransaction(dataSource.get(), c -> {
            execute(c, "DELETE FROM batch_daily_summary WHERE business_date = ?", day);
            List<Map<String, Object>> rows = query(c, "SELECT t.tx_type, a.currency, COUNT(*) AS n, SUM(t.amount) AS amount "
                    + "FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE t.created_at >= ? AND t.created_at < ? GROUP BY t.tx_type, a.currency",
                    day.atStartOfDay(), day.plusDays(1).atStartOfDay());
            int count = 0;
            BigDecimal in = BigDecimal.ZERO, out = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) {
                execute(c, "INSERT INTO batch_daily_summary (business_date, tx_type, currency, tx_count, amount) VALUES (?, ?, ?, ?, ?)",
                        day, r.get("txType"), r.get("currency"), r.get("n"), r.get("amount"));
                count += num(r.get("n")).intValue();
                if ("JPY".equals(r.get("currency"))) {
                    if (CREDIT_TX.contains(r.get("txType"))) in = in.add(num(r.get("amount")));
                    else out = out.add(num(r.get("amount")));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("txCount", count);
            m.put("types", rows.size());
            m.put("inflowJpy", in);
            m.put("outflowJpy", out);
            return m;
        });
    }

    // ===================================================================== 預金利息の日次計上

    /**
     * 預金利息の日次計上：利息のつく円預金（普通・貯蓄・通知・定期）について、その日の最終残高 × 年利 ÷ 365 を計上する。
     * 累計は前回の利息決算の翌日から（定期は預入日・継続日から）。決算時の利息計算（日々の最終残高の合計 × 年利 ÷ 365）と
     * 同じ考え方なので、累計の円未満切捨てが次回の決算利息の見込みになる。
     *
     * @param day 業務日（本日より前）
     * @return 口座数・当日の利息・未払利息の累計
     */
    public Map<String, Object> accrueInterest(LocalDate day) {
        requireClosedDay(day);
        return Transactions.inTransaction(dataSource.get(), c -> {
            execute(c, "DELETE FROM deposit_interest_accrual WHERE business_date = ?", day);
            LocalDateTime end = day.plusDays(1).atStartOfDay();
            Map<String, BigDecimal> balance = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT t.account_no, t.balance_after FROM account_transaction t "
                    + "WHERE t.tx_id = (SELECT MAX(t2.tx_id) FROM account_transaction t2 WHERE t2.account_no = t.account_no "
                    + "AND t2.created_at < ?)", end)) {
                balance.put((String) r.get("accountNo"), num(r.get("balanceAfter")));
            }
            Map<String, BigDecimal> rate = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT r.account_type, r.annual_rate FROM interest_rate r WHERE r.effective_from = "
                    + "(SELECT MAX(r2.effective_from) FROM interest_rate r2 WHERE r2.account_type = r.account_type "
                    + "AND r2.effective_from <= ?)", day)) {
                rate.put((String) r.get("accountType"), num(r.get("annualRate")));
            }
            Map<String, Map<String, Object>> prev = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT account_no, cumulative FROM deposit_interest_accrual WHERE business_date = ?",
                    day.minusDays(1))) {
                prev.put((String) r.get("accountNo"), r);
            }
            Map<String, LocalDate> settledTo = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT account_no, MAX(period_to) AS d FROM interest_settlement GROUP BY account_no")) {
                settledTo.put((String) r.get("accountNo"), LocalDate.parse(((String) r.get("d")).substring(0, 10)));
            }
            List<Map<String, Object>> accounts = query(c, "SELECT a.account_no, a.account_type, td.annual_rate AS time_rate, "
                    + "td.start_date FROM account a LEFT JOIN time_deposit td ON td.account_no = a.account_no AND td.status = 'ACTIVE' "
                    + "WHERE a.currency = 'JPY' AND a.status IN ('ACTIVE', 'FROZEN', 'DORMANT') AND a.opened_at < ? "
                    + "AND a.account_type IN ('ORDINARY', 'SAVINGS', 'NOTICE', 'TIME') ORDER BY a.account_no", end);
            int n = 0;
            BigDecimal daily = BigDecimal.ZERO, cumulative = BigDecimal.ZERO;
            for (Map<String, Object> a : accounts) {
                String no = (String) a.get("accountNo");
                String type = (String) a.get("accountType");
                BigDecimal r = "TIME".equals(type) ? (a.get("timeRate") == null ? null : num(a.get("timeRate"))) : rate.get(type);
                if (r == null) continue;
                BigDecimal bal = balance.getOrDefault(no, BigDecimal.ZERO).max(BigDecimal.ZERO);
                BigDecimal interest = bal.multiply(r).divide(HUNDRED.multiply(DAYS_PER_YEAR), 4, RoundingMode.DOWN);
                // 累計は前日の累計に足す。決算した期間の翌日・定期の預入（継続）日からは 0 から数え直す
                LocalDate periodStart = settledTo.containsKey(no) ? settledTo.get(no).plusDays(1) : null;
                if ("TIME".equals(type) && a.get("startDate") != null) periodStart = LocalDate.parse(((String) a.get("startDate")).substring(0, 10));
                boolean continuing = prev.containsKey(no) && (periodStart == null || day.isAfter(periodStart));
                BigDecimal before = continuing ? num(prev.get(no).get("cumulative")) : BigDecimal.ZERO;
                BigDecimal total = before.add(interest);
                execute(c, "INSERT INTO deposit_interest_accrual (business_date, account_no, account_type, balance, annual_rate, "
                        + "daily_interest, cumulative) VALUES (?, ?, ?, ?, ?, ?, ?)", day, no, type, bal, r, interest, total);
                n++;
                daily = daily.add(interest);
                cumulative = cumulative.add(total);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("accounts", n);
            m.put("dailyInterest", daily.setScale(2, RoundingMode.DOWN));
            m.put("accruedInterest", cumulative.setScale(0, RoundingMode.DOWN));
            return m;
        });
    }

    // ===================================================================== 手数料の計算・照合

    /**
     * 手数料の計算・照合：他行宛振込（3 万円未満 330 円・以上 440 円）と海外送金（4,000 円）の手数料が料金表どおりか、
     * 明細（FEE）に計上された手数料の合計と一致するかを確かめる。
     *
     * @param day 業務日（本日より前）
     * @return 種類別の件数・金額と不一致件数（NG ならジョブは失敗）
     */
    public Map<String, Object> feeCheck(LocalDate day) {
        requireClosedDay(day);
        Map<String, Object> result = Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime start = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay();
            execute(c, "DELETE FROM fee_summary WHERE business_date = ?", day);
            execute(c, "DELETE FROM batch_check WHERE business_date = ? AND check_group = 'FEE'", day);
            List<Map<String, Object>> domestic = query(c, "SELECT amount, fee FROM domestic_remittance WHERE direction = 'OUT' "
                    + "AND created_at >= ? AND created_at < ?", start, end);
            BigDecimal dCharged = BigDecimal.ZERO, dExpected = BigDecimal.ZERO;
            int dMismatch = 0;
            for (Map<String, Object> r : domestic) {
                BigDecimal expected = PaymentService.domesticFee(num(r.get("amount")));
                dCharged = dCharged.add(num(r.get("fee")));
                dExpected = dExpected.add(expected);
                if (expected.compareTo(num(r.get("fee"))) != 0) dMismatch++;
            }
            List<Map<String, Object>> foreign = query(c, "SELECT fee_jpy FROM foreign_remittance WHERE created_at >= ? AND created_at < ?",
                    start, end);
            BigDecimal fCharged = BigDecimal.ZERO;
            int fMismatch = 0;
            for (Map<String, Object> r : foreign) {
                fCharged = fCharged.add(num(r.get("feeJpy")));
                if (PaymentService.FOREIGN_FEE.compareTo(num(r.get("feeJpy"))) != 0) fMismatch++;
            }
            BigDecimal fExpected = PaymentService.FOREIGN_FEE.multiply(BigDecimal.valueOf(foreign.size()));
            insertFee(c, day, "DOMESTIC_TRANSFER", domestic.size(), dCharged, dExpected, dMismatch);
            insertFee(c, day, "FOREIGN_REMITTANCE", foreign.size(), fCharged, fExpected, fMismatch);
            BigDecimal ledgerFee = num(query(c, "SELECT COALESCE(SUM(amount), 0) AS v FROM account_transaction "
                    + "WHERE tx_type = 'FEE' AND created_at >= ? AND created_at < ?", start, end).get(0).get("v"));
            LocalDateTime now = LocalDateTime.now(clock);
            check(c, day, "FEE", "他行振込手数料が料金表どおり", dMismatch == 0 ? "OK" : "NG", 0, dMismatch,
                    domestic.size() + " 件中 " + dMismatch + " 件が不一致", now);
            check(c, day, "FEE", "海外送金手数料が料金表どおり", fMismatch == 0 ? "OK" : "NG", 0, fMismatch,
                    foreign.size() + " 件中 " + fMismatch + " 件が不一致", now);
            BigDecimal sum = dCharged.add(fCharged);
            check(c, day, "FEE", "明細の手数料（FEE）合計 = 振込・送金の手数料合計", sum.compareTo(ledgerFee) == 0 ? "OK" : "NG",
                    sum.longValue(), ledgerFee.longValue(), "振込・送金 " + sum.toPlainString() + " 円 / 明細 " + ledgerFee.toPlainString() + " 円", now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("domestic", domestic.size());
            m.put("foreign", foreign.size());
            m.put("feeIncome", ledgerFee);
            m.put("mismatches", dMismatch + fMismatch);
            m.put("ng", ngCount(c, day, "FEE"));
            return m;
        });
        failIfNg(result, "手数料照合");
        return result;
    }

    private static void insertFee(Connection c, LocalDate day, String type, int count, BigDecimal charged, BigDecimal expected,
                                  int mismatch) throws SQLException {
        execute(c, "INSERT INTO fee_summary (business_date, fee_type, fee_count, charged_amount, expected_amount, mismatch_count) "
                + "VALUES (?, ?, ?, ?, ?, ?)", day, type, count, charged, expected, mismatch);
    }

    // ===================================================================== 為替照合

    /**
     * 為替照合：他行宛振込の送信・決済の状況と全銀データ、口座の明細を突き合わせる。
     * <ul>
     *   <li>NG: 全銀データの件数・金額が、そのデータに含めた振込の件数・金額と一致しない</li>
     *   <li>NG: 口座の明細（円口座の REMIT_OUT）合計が、その日受け付けた他行宛振込・海外送金（円口座）の合計と一致しない</li>
     *   <li>WARN: 振込指定日を過ぎても未送信（PENDING）・送信済みで未決済（SENT）の振込、保留中の被仕向・海外送金</li>
     * </ul>
     *
     * @param day 業務日（本日より前）
     * @return 照合の件数（NG ならジョブは失敗）
     */
    public Map<String, Object> remittanceReconciliation(LocalDate day) {
        requireClosedDay(day);
        Map<String, Object> result = Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime start = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay();
            LocalDateTime now = LocalDateTime.now(clock);
            execute(c, "DELETE FROM batch_check WHERE business_date = ? AND check_group = 'REMITTANCE'", day);
            // 全銀データと振込の突合（その日に作成したデータ）
            int batches = 0, batchNg = 0;
            for (Map<String, Object> b : query(c, "SELECT z.batch_id, z.record_count, z.total_amount, "
                    + "(SELECT COUNT(*) FROM domestic_remittance r WHERE r.batch_id = z.batch_id) AS n, "
                    + "(SELECT COALESCE(SUM(amount), 0) FROM domestic_remittance r WHERE r.batch_id = z.batch_id) AS amount "
                    + "FROM zengin_batch z WHERE z.created_at >= ? AND z.created_at < ?", start, end)) {
                batches++;
                boolean ok = num(b.get("recordCount")).compareTo(num(b.get("n"))) == 0
                        && num(b.get("totalAmount")).compareTo(num(b.get("amount"))) == 0;
                if (!ok) {
                    batchNg++;
                    check(c, day, "REMITTANCE", "全銀データ No." + b.get("batchId"), "NG", num(b.get("totalAmount")).longValue(),
                            num(b.get("amount")).longValue(), "データ " + b.get("recordCount") + " 件 / 振込 " + b.get("n") + " 件", now);
                }
            }
            check(c, day, "REMITTANCE", "全銀データの件数・金額 = 振込の件数・金額", batchNg == 0 ? "OK" : "NG", 0, batchNg,
                    batches + " データ中 " + batchNg + " データが不一致", now);
            // 口座の明細（円口座の REMIT_OUT）と受付データの突合
            BigDecimal ledger = num(query(c, "SELECT COALESCE(SUM(t.amount), 0) AS v FROM account_transaction t JOIN account a "
                    + "ON a.account_no = t.account_no WHERE t.tx_type = 'REMIT_OUT' AND a.currency = 'JPY' "
                    + "AND t.created_at >= ? AND t.created_at < ?", start, end).get(0).get("v"));
            BigDecimal domestic = num(query(c, "SELECT COALESCE(SUM(amount), 0) AS v FROM domestic_remittance WHERE direction = 'OUT' "
                    + "AND created_at >= ? AND created_at < ?", start, end).get(0).get("v"));
            BigDecimal foreign = num(query(c, "SELECT COALESCE(SUM(f.debit_amount), 0) AS v FROM foreign_remittance f JOIN account a "
                    + "ON a.account_no = f.account_no WHERE a.currency = 'JPY' AND f.created_at >= ? AND f.created_at < ?",
                    start, end).get(0).get("v"));
            BigDecimal expected = domestic.add(foreign);
            check(c, day, "REMITTANCE", "送金の明細（REMIT_OUT）= 他行宛振込 + 海外送金", expected.compareTo(ledger) == 0 ? "OK" : "NG",
                    expected.longValue(), ledger.longValue(), "受付 " + expected.toPlainString() + " 円 / 明細 " + ledger.toPlainString() + " 円", now);
            // 滞留（警告）
            int pending = count(c, "SELECT COUNT(*) AS n FROM domestic_remittance WHERE direction = 'OUT' AND status = 'PENDING' "
                    + "AND value_date <= ?", day);
            int unsettled = count(c, "SELECT COUNT(*) AS n FROM domestic_remittance WHERE direction = 'OUT' AND status = 'SENT' "
                    + "AND value_date < ?", day);
            int heldIn = count(c, "SELECT COUNT(*) AS n FROM domestic_remittance WHERE direction = 'IN' AND status = 'HELD'");
            int heldForeign = count(c, "SELECT COUNT(*) AS n FROM foreign_remittance WHERE status = 'HELD'");
            check(c, day, "REMITTANCE", "振込指定日を過ぎた未送信の振込", pending == 0 ? "OK" : "WARN", 0, pending, pending + " 件", now);
            check(c, day, "REMITTANCE", "送信済みで未決済の振込（指定日の翌日以降）", unsettled == 0 ? "OK" : "WARN", 0, unsettled,
                    unsettled + " 件", now);
            check(c, day, "REMITTANCE", "保留中の被仕向振込", heldIn == 0 ? "OK" : "WARN", 0, heldIn, heldIn + " 件", now);
            check(c, day, "REMITTANCE", "保留中の海外送金（制裁・高リスク国）", heldForeign == 0 ? "OK" : "WARN", 0, heldForeign,
                    heldForeign + " 件", now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("zenginBatches", batches);
            m.put("remitOut", ledger);
            m.put("warnings", pending + unsettled + heldIn + heldForeign);
            m.put("ng", ngCount(c, day, "REMITTANCE"));
            return m;
        });
        failIfNg(result, "為替照合");
        return result;
    }

    // ===================================================================== 会計仕訳集計

    /**
     * 会計仕訳集計：補助元帳（円口座の明細・融資取引・引当の履歴）を勘定科目に仕訳し、総勘定元帳の日計を作る。
     * 照合：その日の借方合計 = 貸方合計、融資決済勘定（口座側と融資側）が 0 で相殺、
     * 預金科目の残高（全期間の累計）= 円口座の残高合計、貸出金科目の残高 = 融資残高の合計（当座貸越を除く）。
     *
     * @param day 業務日（本日より前）
     * @return 科目数・借方合計・照合結果（NG ならジョブは失敗）
     */
    public Map<String, Object> generalLedger(LocalDate day) {
        requireClosedDay(day);
        Map<String, Object> result = Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime start = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay();
            LocalDateTime now = LocalDateTime.now(clock);
            execute(c, "DELETE FROM gl_daily WHERE business_date = ?", day);
            execute(c, "DELETE FROM batch_check WHERE business_date = ? AND check_group = 'LEDGER'", day);
            Map<String, BigDecimal[]> gl = new TreeMap<>();
            for (Map<String, Object> r : query(c, "SELECT t.tx_type, CASE WHEN t.counterparty_account_no IS NULL THEN 0 ELSE 1 END "
                    + "AS internal, SUM(t.amount) AS amount FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE a.currency = 'JPY' AND t.created_at >= ? AND t.created_at < ? GROUP BY t.tx_type, "
                    + "CASE WHEN t.counterparty_account_no IS NULL THEN 0 ELSE 1 END", start, end)) {
                String type = (String) r.get("txType");
                BigDecimal amount = num(r.get("amount"));
                String counter = counterAccount(type, num(r.get("internal")).intValue() == 1);
                if (CREDIT_TX.contains(type)) {
                    post(gl, counter, amount, BigDecimal.ZERO);
                    post(gl, "2000", BigDecimal.ZERO, amount);
                } else {
                    post(gl, "2000", amount, BigDecimal.ZERO);
                    post(gl, counter, BigDecimal.ZERO, amount);
                }
            }
            for (Map<String, Object> r : query(c, "SELECT tx_type, SUM(principal) AS p, SUM(interest) AS i, SUM(late_fee) AS f "
                    + "FROM loan_transaction WHERE created_at >= ? AND created_at < ? GROUP BY tx_type", start, end)) {
                String type = (String) r.get("txType");
                BigDecimal p = num(r.get("p")), i = num(r.get("i")), f = num(r.get("f"));
                if ("DISBURSE".equals(type) || "DRAW".equals(type)) {
                    post(gl, "1300", p, BigDecimal.ZERO);
                    post(gl, "1350", BigDecimal.ZERO, p);
                } else if ("REPAY".equals(type) || "PREPAY".equals(type)) {
                    post(gl, "1350", p.add(i).add(f), BigDecimal.ZERO);
                    post(gl, "1300", BigDecimal.ZERO, p);
                    post(gl, "4100", BigDecimal.ZERO, i);
                    post(gl, "4110", BigDecimal.ZERO, f);
                } else if ("WRITE_OFF".equals(type)) {
                    post(gl, "5110", p, BigDecimal.ZERO);
                    post(gl, "1300", BigDecimal.ZERO, p);
                }
            }
            for (Map<String, Object> r : query(c, "SELECT COALESCE(SUM(CASE WHEN change_amount > 0 THEN change_amount ELSE 0 END), 0) AS up, "
                    + "COALESCE(SUM(CASE WHEN change_amount < 0 THEN -change_amount ELSE 0 END), 0) AS down FROM loan_provision "
                    + "WHERE created_at >= ? AND created_at < ?", start, end)) {
                post(gl, "5100", num(r.get("up")), BigDecimal.ZERO);
                post(gl, "1390", num(r.get("down")), num(r.get("up")));
                post(gl, "4900", BigDecimal.ZERO, num(r.get("down")));
            }
            BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
            int posted = 0;
            for (Map.Entry<String, BigDecimal[]> e : gl.entrySet()) {
                if (e.getValue()[0].signum() == 0 && e.getValue()[1].signum() == 0) continue;
                posted++;
                execute(c, "INSERT INTO gl_daily (business_date, gl_code, debit, credit) VALUES (?, ?, ?, ?)",
                        day, e.getKey(), e.getValue()[0], e.getValue()[1]);
                debit = debit.add(e.getValue()[0]);
                credit = credit.add(e.getValue()[1]);
            }
            check(c, day, "LEDGER", "日計の借方合計 = 貸方合計", debit.compareTo(credit) == 0 ? "OK" : "NG", debit.longValue(),
                    credit.longValue(), "借方 " + debit.toPlainString() + " / 貸方 " + credit.toPlainString(), now);
            BigDecimal[] clearing = gl.getOrDefault("1350", new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            check(c, day, "LEDGER", "融資決済勘定が相殺（口座の明細 = 融資取引）", clearing[0].compareTo(clearing[1]) == 0 ? "OK" : "NG",
                    clearing[0].longValue(), clearing[1].longValue(), "融資側 " + clearing[0].toPlainString() + " / 口座側 "
                            + clearing[1].toPlainString(), now);
            // 預金：全期間の明細から求めた科目残高と、口座残高の合計（補助元帳 = 総勘定元帳）
            List<Object> p = new ArrayList<>(CREDIT_TX);
            p.add(end);
            BigDecimal depositGl = num(query(c, "SELECT COALESCE(SUM(CASE WHEN t.tx_type IN (" + marks(CREDIT_TX.size())
                    + ") THEN t.amount ELSE -t.amount END), 0) AS v FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE a.currency = 'JPY' AND t.created_at < ?", p.toArray()).get(0).get("v"));
            // 補助元帳の 2 つの持ち方を突き合わせる：明細の金額の累計（元帳）と、各口座のその日の最後の取引後残高の合計
            BigDecimal balances = num(query(c, "SELECT COALESCE(SUM(t.balance_after), 0) AS v FROM account_transaction t "
                    + "JOIN account a ON a.account_no = t.account_no WHERE a.currency = 'JPY' AND t.tx_id = (SELECT MAX(t2.tx_id) "
                    + "FROM account_transaction t2 WHERE t2.account_no = t.account_no AND t2.created_at < ?)", end).get(0).get("v"));
            check(c, day, "LEDGER", "預金科目の残高 = 口座残高の合計（円）", depositGl.compareTo(balances) == 0 ? "OK" : "NG",
                    depositGl.longValue(), balances.longValue(), "元帳 " + depositGl.toPlainString() + " / 口座 " + balances.toPlainString(), now);
            BigDecimal loanGl = num(query(c, "SELECT COALESCE(SUM(CASE WHEN tx_type IN ('DISBURSE', 'DRAW') THEN principal "
                    + "WHEN tx_type IN ('REPAY', 'PREPAY', 'WRITE_OFF') THEN -principal ELSE 0 END), 0) AS v FROM loan_transaction "
                    + "WHERE created_at < ?", end).get(0).get("v"));
            BigDecimal outstanding = num(query(c, "SELECT COALESCE(SUM(x.outstanding_after), 0) AS v FROM loan_transaction x "
                    + "WHERE x.loan_tx_id = (SELECT MAX(y.loan_tx_id) FROM loan_transaction y WHERE y.loan_id = x.loan_id "
                    + "AND y.created_at < ?)", end).get(0).get("v"));
            check(c, day, "LEDGER", "貸出金科目の残高 = 融資残高の合計", loanGl.compareTo(outstanding) == 0 ? "OK" : "NG",
                    loanGl.longValue(), outstanding.longValue(), "元帳 " + loanGl.toPlainString() + " / 融資 " + outstanding.toPlainString(), now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("accounts", posted);
            m.put("debit", debit);
            m.put("credit", credit);
            m.put("depositBalance", depositGl);
            m.put("loanBalance", loanGl);
            m.put("ng", ngCount(c, day, "LEDGER"));
            return m;
        });
        failIfNg(result, "会計照合");
        return result;
    }

    /** 口座の明細の相手科目。振込・解約払戻しで相手口座があるものは預金同士の振替。 */
    static String counterAccount(String txType, boolean internal) {
        switch (txType) {
            case "DEPOSIT":
            case "WITHDRAWAL": return "1000";
            case "TRANSFER_IN":
            case "TRANSFER_OUT": return "2000";
            case "CLOSE_PAYOUT": return internal ? "2000" : "1000";
            case "INTEREST": return "5000";
            case "TAX": return "2600";
            case "FX_IN":
            case "FX_OUT": return "1900";
            case "LOAN_DISB":
            case "LOAN_REPAY": return "1350";
            case "FEE": return "4200";
            case "OD_INTEREST": return "4100";
            default: return "1100";   // REMIT_IN / REMIT_OUT / DIRECT_DEBIT：他行との為替決済
        }
    }

    private static void post(Map<String, BigDecimal[]> gl, String code, BigDecimal debit, BigDecimal credit) {
        BigDecimal[] v = gl.computeIfAbsent(code, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        v[0] = v[0].add(debit);
        v[1] = v[1].add(credit);
    }

    // ===================================================================== 月末締め

    /**
     * 月末締め：前月の月末残高・月次の取引と収益・未払利息をまとめる。その月の照合（為替・会計・手数料）に NG が
     * 残っていれば EXCEPTION として記録し、ジョブは失敗にする（原因を直して再実行すると置き換わる）。
     *
     * @param month 締める月（今月より前）
     * @param by    実行者
     * @return 月次の集計
     */
    public Map<String, Object> monthEnd(YearMonth month, String by) {
        if (!month.isBefore(YearMonth.from(LocalDate.now(clock)))) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        Map<String, Object> result = Transactions.inTransaction(dataSource.get(), c -> {
            LocalDate first = month.atDay(1), last = month.atEndOfMonth();
            LocalDateTime start = first.atStartOfDay(), end = last.plusDays(1).atStartOfDay();
            execute(c, "DELETE FROM month_close WHERE close_month = ?", month.toString());
            Map<String, BigDecimal> balance = new HashMap<>();
            for (Map<String, Object> r : query(c, "SELECT t.account_no, t.balance_after FROM account_transaction t "
                    + "WHERE t.tx_id = (SELECT MAX(t2.tx_id) FROM account_transaction t2 WHERE t2.account_no = t.account_no "
                    + "AND t2.created_at < ?)", end)) {
                balance.put((String) r.get("accountNo"), num(r.get("balanceAfter")));
            }
            BigDecimal deposits = BigDecimal.ZERO;
            int accounts = 0;
            for (Map<String, Object> a : query(c, "SELECT account_no FROM account WHERE currency = 'JPY' AND opened_at < ? "
                    + "AND (closed_at IS NULL OR closed_at >= ?)", end, end)) {
                accounts++;
                deposits = deposits.add(balance.getOrDefault((String) a.get("accountNo"), BigDecimal.ZERO).max(BigDecimal.ZERO));
            }
            BigDecimal loans = num(query(c, "SELECT COALESCE(SUM(CASE WHEN tx_type IN ('DISBURSE', 'DRAW') THEN principal "
                    + "WHEN tx_type IN ('REPAY', 'PREPAY', 'WRITE_OFF') THEN -principal ELSE 0 END), 0) AS v FROM loan_transaction "
                    + "WHERE created_at < ?", end).get(0).get("v"));
            List<Object> p = new ArrayList<>(CREDIT_TX);
            p.add(start);
            p.add(end);
            Map<String, Object> flow = query(c, "SELECT COUNT(*) AS n, "
                    + "COALESCE(SUM(CASE WHEN t.tx_type IN (" + marks(CREDIT_TX.size()) + ") THEN t.amount ELSE 0 END), 0) AS inflow, "
                    + "COALESCE(SUM(t.amount), 0) AS total, "
                    + "COALESCE(SUM(CASE WHEN t.tx_type = 'FEE' THEN t.amount ELSE 0 END), 0) AS fee, "
                    + "COALESCE(SUM(CASE WHEN t.tx_type = 'INTEREST' THEN t.amount ELSE 0 END), 0) AS paid, "
                    + "COALESCE(SUM(CASE WHEN t.tx_type = 'OD_INTEREST' THEN t.amount ELSE 0 END), 0) AS od "
                    + "FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE a.currency = 'JPY' AND t.created_at >= ? AND t.created_at < ?", p.toArray()).get(0);
            BigDecimal inflow = num(flow.get("inflow"));
            BigDecimal loanInterest = num(query(c, "SELECT COALESCE(SUM(interest + late_fee), 0) AS v FROM loan_transaction "
                    + "WHERE tx_type IN ('REPAY', 'PREPAY') AND created_at >= ? AND created_at < ?", start, end).get(0).get("v"))
                    .add(num(flow.get("od")));
            BigDecimal accrued = num(query(c, "SELECT COALESCE(SUM(cumulative), 0) AS v FROM deposit_interest_accrual "
                    + "WHERE business_date = ?", last).get(0).get("v")).setScale(0, RoundingMode.DOWN);
            int ng = count(c, "SELECT COUNT(*) AS n FROM batch_check WHERE result = 'NG' AND business_date >= ? AND business_date <= ?",
                    first, last);
            String status = ng == 0 ? "CLOSED" : "EXCEPTION";
            execute(c, "INSERT INTO month_close (close_month, month_end, total_deposits, total_loans, account_count, tx_count, "
                            + "inflow_amount, outflow_amount, fee_income, loan_interest, interest_paid, accrued_interest, ng_checks, status, "
                            + "closed_by, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    month.toString(), last, deposits, loans, accounts, flow.get("n"), inflow, num(flow.get("total")).subtract(inflow),
                    flow.get("fee"), loanInterest, flow.get("paid"), accrued, ng, status, by, LocalDateTime.now(clock));
            Map<String, Object> m = new LinkedHashMap<>(query(c, "SELECT * FROM month_close WHERE close_month = ?", month.toString()).get(0));
            m.put("ng", ng);
            return m;
        });
        failIfNg(result, "月末締め（" + month + " の照合 NG）");
        return result;
    }

    // ===================================================================== 帳票出力

    /**
     * 帳票出力：日次は取引明細表・日計表（取引種別・勘定科目別）、月初は前月の月末残高一覧・月次集計表を CSV で作る。
     *
     * @param day 業務日（本日より前）
     * @param by  実行者
     * @return 作成した帳票（種類・ファイル名・行数）
     */
    public List<Map<String, Object>> reports(LocalDate day, String by) {
        requireClosedDay(day);
        return Transactions.inTransaction(dataSource.get(), c -> {
            List<Map<String, Object>> made = new ArrayList<>();
            LocalDateTime start = day.atStartOfDay(), end = day.plusDays(1).atStartOfDay();
            List<Map<String, Object>> tx = query(c, "SELECT t.tx_id, t.created_at, t.account_no, cu.name, a.currency, t.tx_type, "
                    + "t.amount, t.balance_after, t.counterparty_account_no, t.memo FROM account_transaction t "
                    + "JOIN account a ON a.account_no = t.account_no JOIN customer cu ON cu.customer_id = a.customer_id "
                    + "WHERE t.created_at >= ? AND t.created_at < ? ORDER BY t.tx_id", start, end);
            made.add(save(c, "TX_JOURNAL", day, "取引明細表 " + day, "tx_journal_" + day + ".csv",
                    Arrays.asList("取引ID", "日時", "口座番号", "名義", "通貨", "取引種別", "金額", "取引後残高", "相手口座", "摘要"),
                    tx, Arrays.asList("txId", "createdAt", "accountNo", "name", "currency", "txType", "amount", "balanceAfter",
                            "counterpartyAccountNo", "memo"), by));
            List<Map<String, Object>> daily = query(c, "SELECT '取引' AS section, tx_type AS item, currency, tx_count, amount, "
                    + "NULL AS debit, NULL AS credit FROM batch_daily_summary WHERE business_date = ? "
                    + "UNION ALL SELECT '勘定科目' AS section, g.gl_code || ' ' || a.name AS item, 'JPY' AS currency, NULL AS tx_count, "
                    + "NULL AS amount, g.debit, g.credit FROM gl_daily g JOIN gl_account a ON a.gl_code = g.gl_code "
                    + "WHERE g.business_date = ? ORDER BY 1, 2", day, day);
            made.add(save(c, "DAILY_BALANCE", day, "日計表 " + day, "daily_" + day + ".csv",
                    Arrays.asList("区分", "項目", "通貨", "件数", "金額", "借方", "貸方"), daily,
                    Arrays.asList("section", "item", "currency", "txCount", "amount", "debit", "credit"), by));
            if (day.equals(YearMonth.from(day).atEndOfMonth())) {
                Map<String, BigDecimal> balance = new HashMap<>();
                for (Map<String, Object> r : query(c, "SELECT t.account_no, t.balance_after FROM account_transaction t "
                        + "WHERE t.tx_id = (SELECT MAX(t2.tx_id) FROM account_transaction t2 WHERE t2.account_no = t.account_no "
                        + "AND t2.created_at < ?)", end)) {
                    balance.put((String) r.get("accountNo"), num(r.get("balanceAfter")));
                }
                List<Map<String, Object>> accounts = query(c, "SELECT a.account_no, a.customer_id, cu.name, a.account_type, a.currency, "
                        + "a.status FROM account a JOIN customer cu ON cu.customer_id = a.customer_id WHERE a.opened_at < ? "
                        + "AND (a.closed_at IS NULL OR a.closed_at >= ?) ORDER BY a.account_no", end, end);
                for (Map<String, Object> a : accounts) a.put("balance", balance.getOrDefault((String) a.get("accountNo"), BigDecimal.ZERO));
                made.add(save(c, "MONTH_BALANCES", day, "月末残高一覧 " + YearMonth.from(day), "month_balances_" + YearMonth.from(day) + ".csv",
                        Arrays.asList("口座番号", "顧客番号", "名義", "種類", "通貨", "状態", "月末残高"), accounts,
                        Arrays.asList("accountNo", "customerId", "name", "accountType", "currency", "status", "balance"), by));
                List<Map<String, Object>> close = query(c, "SELECT * FROM month_close WHERE close_month = ?", YearMonth.from(day).toString());
                made.add(save(c, "MONTH_SUMMARY", day, "月次集計表 " + YearMonth.from(day), "month_summary_" + YearMonth.from(day) + ".csv",
                        Arrays.asList("月", "月末", "預金残高", "貸出金残高", "口座数", "取引件数", "入金額", "出金額", "手数料", "貸出金利息",
                                "預金利息", "未払利息", "照合NG", "状態"), close,
                        Arrays.asList("closeMonth", "monthEnd", "totalDeposits", "totalLoans", "accountCount", "txCount", "inflowAmount",
                                "outflowAmount", "feeIncome", "loanInterest", "interestPaid", "accruedInterest", "ngChecks", "status"), by));
            }
            return made;
        });
    }

    // 帳票を CSV にして保存する（同じ種類・日付は置き換え）
    private Map<String, Object> save(Connection c, String type, LocalDate day, String title, String file, List<String> header,
                                     List<Map<String, Object>> rows, List<String> keys, String by) throws SQLException {
        StringBuilder sb = new StringBuilder("﻿");   // Excel が UTF-8 と判定できるよう BOM を付ける
        sb.append(csvLine(new ArrayList<Object>(header)));
        for (Map<String, Object> r : rows) {
            List<Object> cells = new ArrayList<>();
            for (String k : keys) cells.add(r.get(k));
            sb.append(csvLine(cells));
        }
        execute(c, "DELETE FROM report_output WHERE report_type = ? AND business_date = ?", type, day);
        long id = insertReturningId(c, "INSERT INTO report_output (report_type, business_date, title, file_name, row_count, content, "
                + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "report_id", type, day, title, file, rows.size(),
                sb.toString(), by, LocalDateTime.now(clock));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportId", id);
        m.put("reportType", type);
        m.put("fileName", file);
        m.put("rows", rows.size());
        return m;
    }

    /** CSV の 1 行（数値以外は必要に応じて引用符で囲み、表計算ソフトの数式として解釈される先頭文字を無効化する）。 */
    static String csvLine(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            Object v = cells.get(i);
            if (v == null) continue;
            if (v instanceof Number) {
                sb.append(v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : v.toString());
                continue;
            }
            String s = v.toString();
            if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.startsWith("'")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            sb.append(s);
        }
        return sb.append("\r\n").toString();
    }

    // ===================================================================== 照会

    /** 業務日のバッチ結果（取引集計・利息計上・手数料・照合・総勘定元帳の日計）。 */
    public Map<String, Object> daily(LocalDate day) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", day.toString());
            m.put("summary", query(c, "SELECT * FROM batch_daily_summary WHERE business_date = ? ORDER BY tx_type, currency", day));
            m.put("interest", query(c, "SELECT account_type, COUNT(*) AS accounts, SUM(balance) AS balance, SUM(daily_interest) AS daily, "
                    + "SUM(cumulative) AS cumulative FROM deposit_interest_accrual WHERE business_date = ? GROUP BY account_type "
                    + "ORDER BY account_type", day));
            m.put("fees", query(c, "SELECT * FROM fee_summary WHERE business_date = ? ORDER BY fee_type", day));
            m.put("checks", query(c, "SELECT * FROM batch_check WHERE business_date = ? ORDER BY check_group, check_item", day));
            m.put("ledger", query(c, "SELECT g.gl_code, a.name, a.category, g.debit, g.credit FROM gl_daily g "
                    + "JOIN gl_account a ON a.gl_code = g.gl_code WHERE g.business_date = ? ORDER BY g.gl_code", day));
            return m;
        });
    }

    /** 月末締めの一覧（新しい順に 24 か月）。 */
    public List<Map<String, Object>> monthCloses() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT * FROM month_close ORDER BY close_month DESC "
                + "FETCH FIRST 24 ROWS ONLY"));
    }

    /** 帳票の一覧（本文を除く、新しい順に 100 件）。 */
    public List<Map<String, Object>> reportList() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT report_id, report_type, business_date, title, file_name, "
                + "row_count, created_by, created_at FROM report_output ORDER BY business_date DESC, report_id DESC FETCH FIRST 100 ROWS ONLY"));
    }

    /** 帳票の本文（CSV）。 */
    public Map<String, Object> report(long reportId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT * FROM report_output WHERE report_id = ?", reportId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return rows.get(0);
        });
    }

    /**
     * 残高証明書：指定日の終わりの残高（明細の取引後残高から求める）と口座・名義人の情報。
     *
     * @param accountNo 口座番号
     * @param date      証明する日（本日以前）
     * @return 証明書の内容
     */
    public Map<String, Object> balanceCertificate(String accountNo, LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(clock))) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT a.account_no, a.account_type, a.currency, a.opened_at, a.closed_at, a.status, "
                    + "cu.customer_id, cu.name, cu.address FROM account a JOIN customer cu ON cu.customer_id = a.customer_id "
                    + "WHERE a.account_no = ?", accountNo);
            if (rows.isEmpty()) throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, accountNo);
            Map<String, Object> m = rows.get(0);
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            if (LocalDateTime.parse(normalize((String) m.get("openedAt"))).isAfter(end)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
            List<Map<String, Object>> last = query(c, "SELECT balance_after FROM account_transaction WHERE tx_id = (SELECT MAX(tx_id) "
                    + "FROM account_transaction WHERE account_no = ? AND created_at < ?)", accountNo, end);
            m.put("asOf", date.toString());
            m.put("balance", last.isEmpty() ? BigDecimal.ZERO : num(last.get(0).get("balanceAfter")));
            m.put("issuedAt", LocalDateTime.now(clock).withNano(0).toString());
            return m;
        });
    }

    private static String normalize(String iso) { return iso.length() == 10 ? iso + "T00:00:00" : iso; }

    // ===================================================================== helpers

    private static void check(Connection c, LocalDate day, String group, String item, String result, long expected, long actual,
                              String detail, LocalDateTime now) throws SQLException {
        execute(c, "INSERT INTO batch_check (business_date, check_group, check_item, result, expected, actual, detail, checked_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", day, group, item, result, expected, actual, detail, now);
    }

    private static int ngCount(Connection c, LocalDate day, String group) throws SQLException {
        return count(c, "SELECT COUNT(*) AS n FROM batch_check WHERE business_date = ? AND check_group = ? AND result = 'NG'", day, group);
    }

    private static int count(Connection c, String sql, Object... params) throws SQLException {
        return num(query(c, sql, params).get(0).get("n")).intValue();
    }

    private static String marks(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.toString();
    }

    // 照合の結果は保存したうえで、NG があればジョブを失敗として報告する（業務処理画面と監査ログに出る）
    private static void failIfNg(Map<String, Object> result, String what) {
        Object ng = result.get("ng");
        if (ng != null && num(ng).intValue() > 0) {
            throw new IllegalStateException(what + "：NG " + ng + " 件（業務日 " + result.getOrDefault("date", result.get("closeMonth")) + "）");
        }
    }
}
