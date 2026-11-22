package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 情報系：勘定系のデータを日次スナップショット（DWH）に取り込み、経営管理（MIS）・顧客分析・帳票に使う。
 *
 * <h2>ETL（{@link #load}）</h2>
 * 業務日ごとに「その日の終わりの状態」を勘定系の明細から組み立てて dw_* 表に入れる。
 * <ul>
 *   <li>口座残高 = その日までの最後の明細の取引後残高（balance_after）。元帳検証で残高と明細の一致が保証されているので、
 *       過去日の残高も正確に再現できる（過去日の取込み＝バックフィルができる）</li>
 *   <li>貸出残高 = その日までの最後の融資取引の残高（outstanding_after）。当座貸越は返済口座のマイナス残高</li>
 *   <li>1 日分を 1 トランザクションで「削除 → 挿入」するので、同じ日を何度取り込んでも結果は同じ（冪等）</li>
 * </ul>
 * 外貨の円換算は取込時点の仲値を使う（過去の相場履歴は持たない簡略化）。債務者区分・格付も取込時点の値。
 *
 * <h2>参照</h2>
 * 経営ダッシュボード・顧客分析・帳票は dw_* 表だけを読むので、勘定系の更新処理（行ロック）と競合しない。
 * 例外は取引集計表で、明細を期間で集計する（参照のみ・ロックなし）。
 */
public class InformationService {
    /** 1 回の取込み（バックフィル）で指定できる最大日数 */
    public static final int MAX_LOAD_DAYS = 92;
    /** 帳票・ダッシュボードで指定できる最大の期間（日数） */
    public static final int MAX_RANGE_DAYS = 400;

    static final String LOYAL = "LOYAL";
    static final String GROWTH = "GROWTH";
    static final String AT_RISK = "AT_RISK";
    static final String LAPSED = "LAPSED";
    static final String STANDARD = "STANDARD";

    // お客さまの意思による取引（最終異動・取引頻度の判定に使う。利息・税・手数料は含めない）
    private static final List<String> CUSTOMER_TX = Arrays.asList("DEPOSIT", "WITHDRAWAL", "TRANSFER_OUT", "TRANSFER_IN",
            "DIRECT_DEBIT", "REMIT_OUT", "REMIT_IN", "FX_OUT", "FX_IN");
    // 入金側の取引種別（画面の creditTypes と同じ）
    private static final List<String> CREDIT_TX = Arrays.asList("DEPOSIT", "TRANSFER_IN", "INTEREST", "FX_IN", "LOAN_DISB",
            "REMIT_IN");
    private static final List<String> NPL_CLASSES = Arrays.asList("SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT");

    private static final InformationService DEFAULT = new InformationService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    /**
     * @param dataSource 接続の取得元
     * @param clock      本日の判定に使う時計
     */
    public InformationService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static InformationService getDefault() { return DEFAULT; }

    // ===================================================================== ETL

    /**
     * 指定期間の各業務日を取り込む（1 日 = 1 トランザクション。取込済みの日は入れ替える）。
     *
     * @param from 開始日（含む）
     * @param to   終了日（含む）。本日以降は不可（その日の取引が締まっていない）
     * @param by   実行者（夜間バッチは SYSTEM）
     * @return 日ごとの取込結果
     * @throws BankException 期間が不正・長すぎる・本日以降を含む場合（INVALID_PERIOD）
     */
    public List<Map<String, Object>> load(LocalDate from, LocalDate to, String by) {
        LocalDate today = LocalDate.now(clock);
        if (from == null || to == null || from.isAfter(to) || !to.isBefore(today)
                || ChronoUnit.DAYS.between(from, to) >= MAX_LOAD_DAYS) {
            throw new BankException(ErrorCode.INVALID_PERIOD);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            results.add(Transactions.inTransaction(dataSource.get(), c -> loadDay(c, day, by)));
        }
        return results;
    }

    /**
     * 夜間バッチ用：前日分を取り込む。DWH が空なら直近 30 日分をまとめて取り込む（初回の推移グラフ用）。
     *
     * @return 取り込んだ日数と最終日
     */
    public Map<String, Object> loadPreviousDay() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        boolean empty = Transactions.readOnly(dataSource.get(),
                c -> LoanService.num(query(c, "SELECT COUNT(*) AS n FROM dw_daily_kpi").get(0).get("n")).signum() == 0);
        LocalDate from = empty ? yesterday.minusDays(29) : yesterday;
        List<Map<String, Object>> r = load(from, yesterday, "SYSTEM");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", r.size());
        m.put("from", from.toString());
        m.put("to", yesterday.toString());
        m.put("last", r.get(r.size() - 1));
        return m;
    }

    // 1 日分の取込み：その日の行を消してから、口座 → 融資 → 顧客 → 経営指標の順に作る
    private Map<String, Object> loadDay(Connection c, LocalDate day, String by) throws SQLException {
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        for (String table : Arrays.asList("dw_account_daily", "dw_loan_daily", "dw_customer_daily", "dw_daily_kpi")) {
            execute(c, "DELETE FROM " + table + " WHERE snapshot_date = ?", day);
        }
        Map<String, BigDecimal> ttm = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT currency, ttm FROM fx_rate")) {
            ttm.put((String) r.get("currency"), LoanService.num(r.get("ttm")));
        }

        // 前日のスナップショットがあれば差分で取り込む（前日の値 + その日の明細だけ）。なければ全履歴から作り直す
        LocalDate prev = day.minusDays(1);
        boolean incremental = !query(c, "SELECT 1 FROM dw_daily_kpi WHERE snapshot_date = ?", prev).isEmpty();

        // ---- 口座残高：その日の最後の明細の取引後残高
        List<Map<String, Object>> accounts = query(c, "SELECT account_no, customer_id, account_type, currency, status, "
                + "opened_at, closed_at, dormant_at FROM account WHERE opened_at < ? "
                + "AND (closed_at IS NULL OR closed_at >= ?) ORDER BY account_no", end, start);
        Map<String, BigDecimal> lastBalance = new HashMap<>();
        if (incremental) {
            for (Map<String, Object> r : query(c, "SELECT account_no, balance FROM dw_account_daily WHERE snapshot_date = ?", prev)) {
                lastBalance.put((String) r.get("accountNo"), LoanService.num(r.get("balance")));
            }
            // 前日のスナップショットにない既存口座（通常はない）だけは、その口座の全履歴から求める
            List<Object> missing = new ArrayList<>();
            for (Map<String, Object> a : accounts) {
                String no = (String) a.get("accountNo");
                if (!lastBalance.containsKey(no) && !between(a.get("openedAt"), start, end)) missing.add(no);
            }
            if (!missing.isEmpty()) lastBalance.putAll(lastBalances(c, end, null, missing));
            lastBalance.putAll(lastBalances(c, end, start, Collections.emptyList()));
        } else {
            lastBalance.putAll(lastBalances(c, end, null, Collections.emptyList()));
        }
        List<Object[]> accountRows = new ArrayList<>();
        Map<String, BigDecimal> balanceByAccount = new HashMap<>();
        Map<String, List<Map<String, Object>>> accountsByCustomer = new HashMap<>();
        BigDecimal deposits = BigDecimal.ZERO, demand = BigDecimal.ZERO, time = BigDecimal.ZERO, fx = BigDecimal.ZERO;
        int accountCount = 0, active = 0, newAccounts = 0, closed = 0;
        for (Map<String, Object> a : accounts) {
            String no = (String) a.get("accountNo");
            String ccy = (String) a.get("currency");
            String type = (String) a.get("accountType");
            String status = statusAt(a, end);
            BigDecimal balance = "CLOSED".equals(status) ? BigDecimal.ZERO : lastBalance.getOrDefault(no, BigDecimal.ZERO);
            BigDecimal yen = toYen(balance, ccy, ttm);
            accountRows.add(new Object[]{day, no, a.get("customerId"), type, ccy, status, balance, yen});
            balanceByAccount.put(no, balance);
            Map<String, Object> held = new HashMap<>(a);
            held.put("status", status);
            held.put("yen", yen);
            accountsByCustomer.computeIfAbsent((String) a.get("customerId"), k -> new ArrayList<>()).add(held);
            if (between(a.get("openedAt"), start, end)) newAccounts++;
            if ("CLOSED".equals(status)) {
                closed++;
                continue;
            }
            accountCount++;
            if ("ACTIVE".equals(status)) active++;
            if (yen.signum() > 0) {
                deposits = deposits.add(yen);
                if ("TIME".equals(type)) time = time.add(yen);
                else demand = demand.add(yen);
                if (!"JPY".equals(ccy)) fx = fx.add(yen);
            }
        }
        batch(c, "INSERT INTO dw_account_daily (snapshot_date, account_no, customer_id, account_type, currency, status, "
                + "balance, balance_jpy) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", accountRows);

        // ---- 貸出残高：その日の最後の融資取引の残高（当座貸越は返済口座のマイナス残高）
        // 差分取込みでは前日の残高（スナップショットにない融資は残高 0）に、その日の融資取引を上書きする
        Map<Long, BigDecimal> lastOutstanding = new HashMap<>();
        if (incremental) {
            for (Map<String, Object> r : query(c, "SELECT loan_id, outstanding FROM dw_loan_daily WHERE snapshot_date = ?", prev)) {
                lastOutstanding.put(LoanService.num(r.get("loanId")).longValue(), LoanService.num(r.get("outstanding")));
            }
        }
        for (Map<String, Object> r : query(c, "SELECT loan_id, outstanding_after FROM (SELECT loan_id, outstanding_after, "
                + "ROW_NUMBER() OVER (PARTITION BY loan_id ORDER BY created_at DESC, loan_tx_id DESC) AS rn "
                + "FROM loan_transaction WHERE created_at < ?" + (incremental ? " AND created_at >= ?" : "") + ") x WHERE rn = 1",
                incremental ? new Object[]{end, start} : new Object[]{end})) {
            lastOutstanding.put(LoanService.num(r.get("loanId")).longValue(), LoanService.num(r.get("outstandingAfter")));
        }
        List<Map<String, Object>> loans = query(c, "SELECT loan_id, customer_id, product, grade, borrower_class, "
                + "repayment_account_no FROM loan WHERE executed_at IS NOT NULL AND executed_at < ? "
                + "AND (closed_at IS NULL OR closed_at >= ?)", end, start);
        List<Object[]> loanRows = new ArrayList<>();
        Map<String, BigDecimal> loansByCustomer = new HashMap<>();
        BigDecimal totalLoans = BigDecimal.ZERO, npl = BigDecimal.ZERO;
        for (Map<String, Object> l : loans) {
            long loanId = LoanService.num(l.get("loanId")).longValue();
            BigDecimal outstanding = "OVERDRAFT".equals(l.get("product"))
                    ? balanceByAccount.getOrDefault((String) l.get("repaymentAccountNo"), BigDecimal.ZERO).negate().max(BigDecimal.ZERO)
                    : lastOutstanding.getOrDefault(loanId, BigDecimal.ZERO);
            if (outstanding.signum() <= 0) continue;
            String cls = (String) l.get("borrowerClass");
            loanRows.add(new Object[]{day, loanId, l.get("customerId"), l.get("product"), l.get("grade"), cls, outstanding});
            loansByCustomer.merge((String) l.get("customerId"), outstanding, BigDecimal::add);
            totalLoans = totalLoans.add(outstanding);
            if (NPL_CLASSES.contains(cls)) npl = npl.add(outstanding);
        }
        batch(c, "INSERT INTO dw_loan_daily (snapshot_date, loan_id, customer_id, product, grade, borrower_class, outstanding) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", loanRows);

        // ---- 顧客：預かり資産・借入・保有商品数・RFM（最終異動からの日数 / 90 日の取引回数 / 預かり資産）
        Map<String, String> lastActivity = new HashMap<>();
        Map<String, Integer> frequency = new HashMap<>();
        String inTypes = placeholders(CUSTOMER_TX.size());
        Set<String> customerIds = new HashSet<>(accountsByCustomer.keySet());
        customerIds.addAll(loansByCustomer.keySet());
        if (incremental) {
            // 前日の最終取引日に、その日の取引だけを重ねる（前日のスナップショットにない顧客は、その顧客の全履歴から）
            Set<String> known = new HashSet<>();
            for (Map<String, Object> r : query(c, "SELECT customer_id, last_activity FROM dw_customer_daily "
                    + "WHERE snapshot_date = ?", prev)) {
                known.add((String) r.get("customerId"));
                if (r.get("lastActivity") != null) lastActivity.put((String) r.get("customerId"), (String) r.get("lastActivity"));
            }
            List<Object> missing = new ArrayList<>();
            for (String cid : customerIds) if (!known.contains(cid)) missing.add(cid);
            if (!missing.isEmpty()) lastActivity.putAll(lastActivities(c, inTypes, end, null, missing));
            lastActivity.putAll(lastActivities(c, inTypes, end, start, Collections.emptyList()));
        } else {
            lastActivity.putAll(lastActivities(c, inTypes, end, null, Collections.emptyList()));
        }
        List<Object> p2 = new ArrayList<>(CUSTOMER_TX);
        p2.add(end.minusDays(90));
        p2.add(end);
        for (Map<String, Object> r : query(c, "SELECT a.customer_id, COUNT(*) AS n FROM account_transaction t "
                + "JOIN account a ON a.account_no = t.account_no WHERE t.tx_type IN (" + inTypes + ") AND t.created_at >= ? "
                + "AND t.created_at < ? GROUP BY a.customer_id", p2.toArray())) {
            frequency.put((String) r.get("customerId"), LoanService.num(r.get("n")).intValue());
        }
        Map<String, String> customerTypes = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT customer_id, customer_type FROM customer WHERE created_at < ?", end)) {
            customerTypes.put((String) r.get("customerId"), (String) r.get("customerType"));
        }
        List<Object[]> customerRows = new ArrayList<>();
        Map<String, Integer> segmentCounts = new TreeMap<>();
        for (String cid : customerIds) {
            BigDecimal assets = BigDecimal.ZERO;
            Set<String> products = new HashSet<>();
            for (Map<String, Object> a : accountsByCustomer.getOrDefault(cid, Collections.<Map<String, Object>>emptyList())) {
                if ("CLOSED".equals(a.get("status"))) continue;
                products.add((String) a.get("accountType"));
                BigDecimal yen = (BigDecimal) a.get("yen");
                if (yen.signum() > 0) assets = assets.add(yen);
            }
            BigDecimal debt = loansByCustomer.getOrDefault(cid, BigDecimal.ZERO);
            int productCount = products.size() + (debt.signum() > 0 ? 1 : 0);
            String last = lastActivity.get(cid);
            LocalDate lastDate = last == null ? null : LocalDate.parse(last.substring(0, 10));
            Integer recency = lastDate == null ? null : (int) ChronoUnit.DAYS.between(lastDate, day);
            int freq = frequency.getOrDefault(cid, 0);
            int r = rScore(recency), f = fScore(freq), ms = mScore(assets);
            String segment = segment(r, ms);
            segmentCounts.merge(segment, 1, Integer::sum);
            customerRows.add(new Object[]{day, cid, customerTypes.getOrDefault(cid, "INDIVIDUAL"), assets, debt, productCount,
                    lastDate, recency, freq, r, f, ms, segment});
        }
        batch(c, "INSERT INTO dw_customer_daily (snapshot_date, customer_id, customer_type, total_assets, total_loans, "
                + "product_count, last_activity, recency_days, frequency_90d, r_score, f_score, m_score, segment) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", customerRows);

        // ---- 経営指標：残高（上で集計）と、その日のフロー（円口座の入出金・収益・費用）
        List<Object> p3 = new ArrayList<>(CREDIT_TX);
        p3.add(start);
        p3.add(end);
        Map<String, Object> flow = query(c, "SELECT COUNT(*) AS n, "
                + "COALESCE(SUM(CASE WHEN t.tx_type IN (" + placeholders(CREDIT_TX.size()) + ") THEN t.amount ELSE 0 END), 0) AS inflow, "
                + "COALESCE(SUM(t.amount), 0) AS total, "
                + "COALESCE(SUM(CASE WHEN t.tx_type = 'FEE' THEN t.amount ELSE 0 END), 0) AS fee, "
                + "COALESCE(SUM(CASE WHEN t.tx_type = 'INTEREST' THEN t.amount ELSE 0 END), 0) AS interest_paid, "
                + "COALESCE(SUM(CASE WHEN t.tx_type = 'OD_INTEREST' THEN t.amount ELSE 0 END), 0) AS od_interest "
                + "FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                + "WHERE a.currency = 'JPY' AND t.created_at >= ? AND t.created_at < ?", p3.toArray()).get(0);
        BigDecimal inflow = LoanService.num(flow.get("inflow"));
        BigDecimal outflow = LoanService.num(flow.get("total")).subtract(inflow);
        BigDecimal loanInterest = LoanService.num(query(c, "SELECT COALESCE(SUM(interest + late_fee), 0) AS v "
                + "FROM loan_transaction WHERE tx_type IN ('REPAY', 'PREPAY') AND created_at >= ? AND created_at < ?",
                start, end).get(0).get("v")).add(LoanService.num(flow.get("odInterest")));
        int txCount = LoanService.num(query(c, "SELECT COUNT(*) AS n FROM account_transaction WHERE created_at >= ? "
                + "AND created_at < ?", start, end).get(0).get("n")).intValue();
        execute(c, "INSERT INTO dw_daily_kpi (snapshot_date, total_deposits, demand_deposits, time_deposits, fx_deposits, "
                        + "total_loans, npl_amount, customer_count, account_count, active_accounts, new_accounts, closed_accounts, "
                        + "tx_count, inflow_amount, outflow_amount, loan_interest_income, fee_income, deposit_interest_paid, "
                        + "loaded_at, loaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                day, deposits, demand, time, fx, totalLoans, npl, customerRows.size(), accountCount, active, newAccounts, closed,
                txCount, inflow, outflow, loanInterest, LoanService.num(flow.get("fee")), LoanService.num(flow.get("interestPaid")),
                LocalDateTime.now(clock), by);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", day.toString());
        m.put("mode", incremental ? "incremental" : "full");
        m.put("accounts", accountRows.size());
        m.put("loans", loanRows.size());
        m.put("customers", customerRows.size());
        m.put("totalDeposits", deposits);
        m.put("totalLoans", totalLoans);
        m.put("segments", segmentCounts);
        return m;
    }

    /**
     * 口座ごとの最後の明細の取引後残高（end より前）。
     *
     * @param from     null でなければ from 以降の明細だけを見る（差分取込み）
     * @param accounts 空でなければこの口座だけ
     */
    private static Map<String, BigDecimal> lastBalances(Connection c, LocalDateTime end, LocalDateTime from, List<Object> accounts)
            throws SQLException {
        List<Object> params = new ArrayList<>();
        params.add(end);
        StringBuilder where = new StringBuilder("created_at < ?");
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(from);
        }
        if (!accounts.isEmpty()) {
            where.append(" AND account_no IN (").append(placeholders(accounts.size())).append(")");
            params.addAll(accounts);
        }
        Map<String, BigDecimal> m = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT account_no, balance_after FROM (SELECT account_no, balance_after, "
                + "ROW_NUMBER() OVER (PARTITION BY account_no ORDER BY created_at DESC, tx_id DESC) AS rn "
                + "FROM account_transaction WHERE " + where + ") x WHERE rn = 1", params.toArray())) {
            m.put((String) r.get("accountNo"), LoanService.num(r.get("balanceAfter")));
        }
        return m;
    }

    /**
     * 顧客ごとの最終取引日（お客さまの意思による取引、end より前。yyyy-MM-dd）。
     *
     * @param from      null でなければ from 以降の明細だけを見る（差分取込み）
     * @param customers 空でなければこの顧客だけ
     */
    private static Map<String, String> lastActivities(Connection c, String inTypes, LocalDateTime end, LocalDateTime from,
                                                      List<Object> customers) throws SQLException {
        List<Object> params = new ArrayList<>(CUSTOMER_TX);
        params.add(end);
        StringBuilder where = new StringBuilder("t.tx_type IN (" + inTypes + ") AND t.created_at < ?");
        if (from != null) {
            where.append(" AND t.created_at >= ?");
            params.add(from);
        }
        if (!customers.isEmpty()) {
            where.append(" AND a.customer_id IN (").append(placeholders(customers.size())).append(")");
            params.addAll(customers);
        }
        Map<String, String> m = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT a.customer_id, MAX(t.created_at) AS last_at FROM account_transaction t "
                + "JOIN account a ON a.account_no = t.account_no WHERE " + where + " GROUP BY a.customer_id", params.toArray())) {
            m.put((String) r.get("customerId"), ((String) r.get("lastAt")).substring(0, 10));
        }
        return m;
    }

    // その日の終わりの状態：解約・休眠はその時刻で判定し、それ以前なら利用可（凍結の履歴は持たないので現在の状態）
    private static String statusAt(Map<String, Object> a, LocalDateTime end) {
        if (before(a.get("closedAt"), end)) return "CLOSED";
        if (before(a.get("dormantAt"), end)) return "DORMANT";
        String now = (String) a.get("status");
        return "CLOSED".equals(now) || "DORMANT".equals(now) ? "ACTIVE" : now;
    }

    private static boolean before(Object iso, LocalDateTime end) {
        return iso != null && LocalDateTime.parse(normalize((String) iso)).isBefore(end);
    }

    private static boolean between(Object iso, LocalDateTime start, LocalDateTime end) {
        if (iso == null) return false;
        LocalDateTime t = LocalDateTime.parse(normalize((String) iso));
        return !t.isBefore(start) && t.isBefore(end);
    }

    // query() は秒精度の日時を "yyyy-MM-ddTHH:mm:ss" で、日付だけの列は "yyyy-MM-dd" で返す
    private static String normalize(String iso) {
        return iso.length() == 10 ? iso + "T00:00:00" : iso;
    }

    // 円換算（外貨は最小単位 → 通貨単位 × 仲値、円未満四捨五入）
    private static BigDecimal toYen(BigDecimal balance, String ccy, Map<String, BigDecimal> ttm) {
        if ("JPY".equals(ccy)) return balance;
        return DepositMath.fromMinor(balance, ccy).multiply(ttm.getOrDefault(ccy, BigDecimal.ZERO))
                .setScale(0, RoundingMode.HALF_UP);
    }

    /** R（最終異動からの日数）: 30 日以内 5 / 90 日 4 / 180 日 3 / 365 日 2 / それより前・異動なし 1。 */
    static int rScore(Integer recencyDays) {
        if (recencyDays == null) return 1;
        if (recencyDays <= 30) return 5;
        if (recencyDays <= 90) return 4;
        if (recencyDays <= 180) return 3;
        if (recencyDays <= 365) return 2;
        return 1;
    }

    /** F（直近 90 日の取引回数）: 20 回以上 5 / 10 回 4 / 5 回 3 / 1 回 2 / なし 1。 */
    static int fScore(int count) {
        if (count >= 20) return 5;
        if (count >= 10) return 4;
        if (count >= 5) return 3;
        if (count >= 1) return 2;
        return 1;
    }

    /** M（預かり資産・円換算）: 1,000 万円以上 5 / 300 万円 4 / 100 万円 3 / 10 万円 2 / それ未満 1。 */
    static int mScore(BigDecimal assets) {
        if (assets.compareTo(new BigDecimal("10000000")) >= 0) return 5;
        if (assets.compareTo(new BigDecimal("3000000")) >= 0) return 4;
        if (assets.compareTo(new BigDecimal("1000000")) >= 0) return 3;
        if (assets.compareTo(new BigDecimal("100000")) >= 0) return 2;
        return 1;
    }

    /**
     * セグメント：最近の取引（R）と資産規模（M）で分ける。
     * 優良（R≥4・M≥4）/ 成長（R≥4）/ 休眠リスク（R≤2・M≥3：資産はあるが取引が途絶えている）/ 離反（R≤2）/ 一般。
     */
    static String segment(int r, int m) {
        if (r >= 4 && m >= 4) return LOYAL;
        if (r >= 4) return GROWTH;
        if (r <= 2 && m >= 3) return AT_RISK;
        if (r <= 2) return LAPSED;
        return STANDARD;
    }

    // ===================================================================== DWH の状態

    /** @return 取込済みの期間・日数・最終取込（日時・実行者） */
    public Map<String, Object> status() {
        return Transactions.readOnly(dataSource.get(), this::status);
    }

    private Map<String, Object> status(Connection c) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>(query(c, "SELECT MIN(snapshot_date) AS first_date, "
                + "MAX(snapshot_date) AS last_date, COUNT(*) AS days FROM dw_daily_kpi").get(0));
        List<Map<String, Object>> last = query(c, "SELECT snapshot_date, loaded_at, loaded_by FROM dw_daily_kpi "
                + "ORDER BY loaded_at DESC FETCH FIRST 1 ROWS ONLY");
        m.put("lastLoad", last.isEmpty() ? null : last.get(0));
        m.put("today", LocalDate.now(clock).toString());
        return m;
    }

    // ===================================================================== 経営ダッシュボード（MIS）

    /**
     * 経営ダッシュボード：期間の推移と、最新日の残高構成・前日比・期間の損益（収益 − 費用）。
     *
     * @param from 開始日（null なら最新日の 29 日前）
     * @param to   終了日（null なら最新の取込日）
     * @return 集計結果（取込がなければ latest は null）
     */
    public Map<String, Object> dashboard(LocalDate from, LocalDate to) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status(c));
            LocalDate latestLoaded = latestDate(c, to);
            if (latestLoaded == null) {
                m.put("latest", null);
                m.put("series", Collections.emptyList());
                return m;
            }
            LocalDate end = to == null ? latestLoaded : to;
            LocalDate begin = from == null ? end.minusDays(29) : from;
            checkRange(begin, end);
            List<Map<String, Object>> series = query(c, "SELECT * FROM dw_daily_kpi WHERE snapshot_date BETWEEN ? AND ? "
                    + "ORDER BY snapshot_date", begin, end);
            m.put("from", begin.toString());
            m.put("to", end.toString());
            m.put("series", series);
            Map<String, Object> latest = series.isEmpty() ? null : series.get(series.size() - 1);
            m.put("latest", latest);
            if (latest == null) return m;
            LocalDate latestDate = LocalDate.parse((String) latest.get("snapshotDate"));
            List<Map<String, Object>> prev = query(c, "SELECT * FROM dw_daily_kpi WHERE snapshot_date < ? "
                    + "ORDER BY snapshot_date DESC FETCH FIRST 1 ROWS ONLY", latestDate);
            m.put("previous", prev.isEmpty() ? null : prev.get(0));
            m.put("first", series.get(0));

            // 期間の損益（概算）: 貸出金利息 + 手数料 − 預金利息
            BigDecimal loanInterest = BigDecimal.ZERO, fee = BigDecimal.ZERO, paid = BigDecimal.ZERO;
            for (Map<String, Object> r : series) {
                loanInterest = loanInterest.add(LoanService.num(r.get("loanInterestIncome")));
                fee = fee.add(LoanService.num(r.get("feeIncome")));
                paid = paid.add(LoanService.num(r.get("depositInterestPaid")));
            }
            Map<String, Object> profit = new LinkedHashMap<>();
            profit.put("loanInterestIncome", loanInterest);
            profit.put("feeIncome", fee);
            profit.put("depositInterestPaid", paid);
            profit.put("net", loanInterest.add(fee).subtract(paid));
            m.put("profit", profit);

            m.put("depositMix", query(c, "SELECT account_type, currency, COUNT(*) AS accounts, "
                    + "COALESCE(SUM(CASE WHEN balance_jpy > 0 THEN balance_jpy ELSE 0 END), 0) AS balance_jpy "
                    + "FROM dw_account_daily WHERE snapshot_date = ? AND status <> 'CLOSED' "
                    + "GROUP BY account_type, currency ORDER BY balance_jpy DESC", latestDate));
            m.put("loanMix", query(c, "SELECT product, COUNT(*) AS loans, SUM(outstanding) AS outstanding FROM dw_loan_daily "
                    + "WHERE snapshot_date = ? GROUP BY product ORDER BY outstanding DESC", latestDate));
            m.put("loanClassMix", query(c, "SELECT borrower_class, COUNT(*) AS loans, SUM(outstanding) AS outstanding "
                    + "FROM dw_loan_daily WHERE snapshot_date = ? GROUP BY borrower_class ORDER BY outstanding DESC", latestDate));
            m.put("segments", segmentSummary(c, latestDate));
            m.put("monthly", monthly(series));
            return m;
        });
    }

    // 月ごとの集計：残高は月内の最終日、フローは月内の合計
    private static List<Map<String, Object>> monthly(List<Map<String, Object>> series) {
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> r : series) {
            String month = ((String) r.get("snapshotDate")).substring(0, 7);
            Map<String, Object> mm = byMonth.get(month);
            if (mm == null) {
                mm = new LinkedHashMap<>();
                mm.put("month", month);
                mm.put("days", 0);
                for (String k : Arrays.asList("txCount", "inflowAmount", "outflowAmount", "loanInterestIncome", "feeIncome",
                        "depositInterestPaid", "newAccounts", "closedAccounts")) {
                    mm.put(k, BigDecimal.ZERO);
                }
                byMonth.put(month, mm);
            }
            mm.put("days", (Integer) mm.get("days") + 1);
            mm.put("endDate", r.get("snapshotDate"));
            for (String k : Arrays.asList("totalDeposits", "totalLoans", "nplAmount", "customerCount", "accountCount")) {
                mm.put(k, r.get(k));
            }
            for (String k : Arrays.asList("txCount", "inflowAmount", "outflowAmount", "loanInterestIncome", "feeIncome",
                    "depositInterestPaid", "newAccounts", "closedAccounts")) {
                mm.put(k, ((BigDecimal) mm.get(k)).add(LoanService.num(r.get(k))));
            }
        }
        return new ArrayList<>(byMonth.values());
    }

    private static List<Map<String, Object>> segmentSummary(Connection c, LocalDate date) throws SQLException {
        return query(c, "SELECT segment, COUNT(*) AS customers, SUM(total_assets) AS total_assets, "
                + "SUM(total_loans) AS total_loans FROM dw_customer_daily WHERE snapshot_date = ? GROUP BY segment "
                + "ORDER BY total_assets DESC", date);
    }

    // ===================================================================== 顧客分析（CRM）

    /**
     * 顧客分析：セグメント別の集計、RFM の分布、顧客一覧、クロスセル候補、離反リスク。
     *
     * @param date 基準日（null なら最新の取込日）
     * @return 分析結果（取込がなければ date は null）
     */
    public Map<String, Object> customerAnalytics(LocalDate date) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            LocalDate d = latestDate(c, date);
            m.put("date", d == null ? null : d.toString());
            if (d == null) {
                m.put("customers", Collections.emptyList());
                return m;
            }
            m.put("segments", segmentSummary(c, d));
            m.put("rDistribution", query(c, "SELECT r_score AS score, COUNT(*) AS customers FROM dw_customer_daily "
                    + "WHERE snapshot_date = ? GROUP BY r_score ORDER BY r_score DESC", d));
            m.put("mDistribution", query(c, "SELECT m_score AS score, COUNT(*) AS customers FROM dw_customer_daily "
                    + "WHERE snapshot_date = ? GROUP BY m_score ORDER BY m_score DESC", d));
            List<Map<String, Object>> customers = query(c, "SELECT d.customer_id, cu.name, d.customer_type, d.total_assets, "
                    + "d.total_loans, d.product_count, d.last_activity, d.recency_days, d.frequency_90d, d.r_score, d.f_score, "
                    + "d.m_score, d.segment FROM dw_customer_daily d JOIN customer cu ON cu.customer_id = d.customer_id "
                    + "WHERE d.snapshot_date = ? ORDER BY d.total_assets DESC, d.customer_id", d);
            m.put("customers", customers);
            m.put("crossSell", crossSell(c, d, customers));
            m.put("churnRisk", churnRisk(c, d, customers));
            return m;
        });
    }

    // クロスセル候補：保有していない商品のうち、資産規模・属性から提案しやすいもの
    private static List<Map<String, Object>> crossSell(Connection c, LocalDate d, List<Map<String, Object>> customers)
            throws SQLException {
        Map<String, Set<String>> held = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT DISTINCT customer_id, account_type FROM dw_account_daily "
                + "WHERE snapshot_date = ? AND status <> 'CLOSED'", d)) {
            held.computeIfAbsent((String) r.get("customerId"), k -> new HashSet<>()).add((String) r.get("accountType"));
        }
        Set<String> withMandate = new HashSet<>();
        for (Map<String, Object> r : query(c, "SELECT DISTINCT a.customer_id FROM direct_debit_mandate m "
                + "JOIN account a ON a.account_no = m.account_no WHERE m.status = 'ACTIVE'")) {
            withMandate.add((String) r.get("customerId"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> cu : customers) {
            String cid = (String) cu.get("customerId");
            Set<String> types = held.getOrDefault(cid, Collections.<String>emptySet());
            if (types.isEmpty()) continue;
            BigDecimal assets = LoanService.num(cu.get("totalAssets"));
            boolean corporate = "CORPORATE".equals(cu.get("customerType"));
            if (assets.compareTo(new BigDecimal("3000000")) >= 0 && !types.contains("TIME")) {
                out.add(offer(cu, "TIME", "定期預金", "預かり資産 300 万円以上で定期預金なし（金利を固定して運用）"));
            }
            if (assets.compareTo(new BigDecimal("5000000")) >= 0 && !types.contains("FOREIGN")) {
                out.add(offer(cu, "FOREIGN", "外貨預金", "預かり資産 500 万円以上で外貨預金なし（運用の分散）"));
            }
            if (!corporate && !types.contains("SAVINGS") && assets.compareTo(new BigDecimal("100000")) >= 0) {
                out.add(offer(cu, "SAVINGS", "貯蓄預金", "普通預金のみ（貯蓄用の口座を分けると金利が上がる）"));
            }
            if (corporate && !types.contains("CHECKING")) {
                out.add(offer(cu, "CHECKING", "当座預金", "法人で当座預金なし（手形・小切手、当座貸越）"));
            }
            if (!withMandate.contains(cid) && LoanService.num(cu.get("rScore")).intValue() >= 3) {
                out.add(offer(cu, "DIRECT_DEBIT", "口座振替", "取引はあるが口座振替の契約なし（公共料金の引落し）"));
            }
        }
        out.sort((a, b) -> LoanService.num(b.get("totalAssets")).compareTo(LoanService.num(a.get("totalAssets"))));
        return out;
    }

    private static Map<String, Object> offer(Map<String, Object> cu, String product, String label, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", cu.get("customerId"));
        m.put("name", cu.get("name"));
        m.put("segment", cu.get("segment"));
        m.put("totalAssets", cu.get("totalAssets"));
        m.put("product", product);
        m.put("productLabel", label);
        m.put("reason", reason);
        return m;
    }

    // 離反リスク：取引が途絶えている顧客と、30 日前より預かり資産が 30% 以上減った顧客
    private static List<Map<String, Object>> churnRisk(Connection c, LocalDate d, List<Map<String, Object>> customers)
            throws SQLException {
        Map<String, BigDecimal> before = new HashMap<>();
        for (Map<String, Object> r : query(c, "SELECT customer_id, total_assets FROM dw_customer_daily WHERE snapshot_date = ?",
                d.minusDays(30))) {
            before.put((String) r.get("customerId"), LoanService.num(r.get("totalAssets")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> cu : customers) {
            BigDecimal assets = LoanService.num(cu.get("totalAssets"));
            BigDecimal prev = before.get((String) cu.get("customerId"));
            String reason = null;
            if (prev != null && prev.compareTo(new BigDecimal("100000")) >= 0
                    && assets.compareTo(prev.multiply(new BigDecimal("0.7"))) <= 0) {
                reason = "30 日前の " + prev.toPlainString() + " 円から " + ratioText(prev.subtract(assets), prev) + " 減少";
            } else if (AT_RISK.equals(cu.get("segment"))) {
                Object days = cu.get("recencyDays");
                reason = days == null ? "お客さまの取引なし（資産あり）" : "最終取引から " + days + " 日（資産あり）";
            }
            if (reason == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(cu);
            m.put("assetsBefore", prev);
            m.put("reason", reason);
            out.add(m);
        }
        return out;
    }

    private static String ratioText(BigDecimal part, BigDecimal whole) {
        return part.multiply(new BigDecimal("100")).divide(whole, 0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    // ===================================================================== 帳票

    /** 帳票の種類 */
    public static final List<String> REPORT_TYPES = Collections.unmodifiableList(Arrays.asList(
            "DEPOSIT_BALANCE", "LOAN_BALANCE", "TX_SUMMARY", "KPI_TREND", "CUSTOMER_ASSETS"));

    /**
     * 帳票（表形式）。画面に表示し、そのまま CSV に出力できる形（列定義・行・合計）で返す。
     *
     * @param type 帳票の種類（{@link #REPORT_TYPES}）
     * @param date 基準日（残高系。null なら最新の取込日）
     * @param from 期間の開始日（推移・集計系。null なら基準日の 29 日前）
     * @param to   期間の終了日（推移・集計系。null なら基準日）
     * @return 帳票
     * @throws BankException 期間が不正な場合（INVALID_PERIOD）
     */
    public Map<String, Object> report(String type, LocalDate date, LocalDate from, LocalDate to) {
        return Transactions.readOnly(dataSource.get(), c -> {
            LocalDate d = latestDate(c, date);
            LocalDate end = to != null ? to : d != null ? d : LocalDate.now(clock).minusDays(1);
            LocalDate begin = from != null ? from : end.minusDays(29);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            List<Map<String, Object>> rows;
            List<Map<String, Object>> columns = new ArrayList<>();
            List<String> totals;
            switch (type) {
                case "DEPOSIT_BALANCE":
                    m.put("title", "預金残高表（科目別・通貨別）");
                    m.put("condition", d == null ? "取込データなし" : "基準日 " + d);
                    rows = d == null ? Collections.<Map<String, Object>>emptyList() : query(c, "SELECT account_type, currency, "
                            + "COUNT(*) AS accounts, SUM(balance) AS balance, SUM(balance_jpy) AS balance_jpy "
                            + "FROM dw_account_daily WHERE snapshot_date = ? AND status <> 'CLOSED' "
                            + "GROUP BY account_type, currency ORDER BY account_type, currency", d);
                    col(columns, "accountType", "預金種類", "accountType");
                    col(columns, "currency", "通貨", "text");
                    col(columns, "accounts", "口座数", "number");
                    col(columns, "balance", "残高（原通貨の最小単位）", "number");
                    col(columns, "balanceJpy", "残高（円換算）", "yen");
                    totals = Arrays.asList("accounts", "balanceJpy");
                    break;
                case "LOAN_BALANCE":
                    m.put("title", "貸出金残高表（商品別・債務者区分別）");
                    m.put("condition", d == null ? "取込データなし" : "基準日 " + d);
                    rows = d == null ? Collections.<Map<String, Object>>emptyList() : query(c, "SELECT product, borrower_class, "
                            + "COUNT(*) AS loans, SUM(outstanding) AS outstanding FROM dw_loan_daily WHERE snapshot_date = ? "
                            + "GROUP BY product, borrower_class ORDER BY product, borrower_class", d);
                    col(columns, "product", "商品", "product");
                    col(columns, "borrowerClass", "債務者区分", "borrowerClass");
                    col(columns, "loans", "件数", "number");
                    col(columns, "outstanding", "残高", "yen");
                    totals = Arrays.asList("loans", "outstanding");
                    break;
                case "TX_SUMMARY":
                    checkRange(begin, end);
                    m.put("title", "取引集計表（取引種別・通貨別）");
                    m.put("condition", "期間 " + begin + " 〜 " + end);
                    rows = query(c, "SELECT t.tx_type, a.currency, COUNT(*) AS tx_count, SUM(t.amount) AS amount "
                            + "FROM account_transaction t JOIN account a ON a.account_no = t.account_no "
                            + "WHERE t.created_at >= ? AND t.created_at < ? GROUP BY t.tx_type, a.currency "
                            + "ORDER BY t.tx_type, a.currency", begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
                    col(columns, "txType", "取引種別", "txType");
                    col(columns, "currency", "通貨", "text");
                    col(columns, "txCount", "件数", "number");
                    col(columns, "amount", "金額（原通貨の最小単位）", "number");
                    totals = Collections.singletonList("txCount");
                    break;
                case "KPI_TREND":
                    checkRange(begin, end);
                    m.put("title", "経営指標 日次推移表");
                    m.put("condition", "期間 " + begin + " 〜 " + end);
                    rows = query(c, "SELECT snapshot_date, total_deposits, total_loans, npl_amount, customer_count, account_count, "
                            + "new_accounts, closed_accounts, tx_count, inflow_amount, outflow_amount, loan_interest_income, "
                            + "fee_income, deposit_interest_paid FROM dw_daily_kpi WHERE snapshot_date BETWEEN ? AND ? "
                            + "ORDER BY snapshot_date", begin, end);
                    col(columns, "snapshotDate", "日付", "text");
                    col(columns, "totalDeposits", "預金残高", "yen");
                    col(columns, "totalLoans", "貸出金残高", "yen");
                    col(columns, "nplAmount", "不良債権", "yen");
                    col(columns, "customerCount", "顧客数", "number");
                    col(columns, "accountCount", "口座数", "number");
                    col(columns, "newAccounts", "新規口座", "number");
                    col(columns, "closedAccounts", "解約口座", "number");
                    col(columns, "txCount", "取引件数", "number");
                    col(columns, "inflowAmount", "入金額", "yen");
                    col(columns, "outflowAmount", "出金額", "yen");
                    col(columns, "loanInterestIncome", "貸出金利息", "yen");
                    col(columns, "feeIncome", "手数料", "yen");
                    col(columns, "depositInterestPaid", "預金利息", "yen");
                    totals = Arrays.asList("newAccounts", "closedAccounts", "txCount", "inflowAmount", "outflowAmount",
                            "loanInterestIncome", "feeIncome", "depositInterestPaid");
                    break;
                case "CUSTOMER_ASSETS":
                    m.put("title", "顧客別 預かり資産・RFM 一覧");
                    m.put("condition", d == null ? "取込データなし" : "基準日 " + d);
                    rows = d == null ? Collections.<Map<String, Object>>emptyList() : query(c, "SELECT d.customer_id, cu.name, "
                            + "d.customer_type, d.segment, d.total_assets, d.total_loans, d.product_count, d.last_activity, "
                            + "d.recency_days, d.frequency_90d, d.r_score, d.f_score, d.m_score FROM dw_customer_daily d "
                            + "JOIN customer cu ON cu.customer_id = d.customer_id WHERE d.snapshot_date = ? "
                            + "ORDER BY d.total_assets DESC, d.customer_id", d);
                    col(columns, "customerId", "顧客番号", "text");
                    col(columns, "name", "氏名", "text");
                    col(columns, "customerType", "区分", "customerType");
                    col(columns, "segment", "セグメント", "segment");
                    col(columns, "totalAssets", "預かり資産", "yen");
                    col(columns, "totalLoans", "借入残高", "yen");
                    col(columns, "productCount", "保有商品数", "number");
                    col(columns, "lastActivity", "最終取引日", "text");
                    col(columns, "recencyDays", "経過日数", "number");
                    col(columns, "frequency90d", "取引回数（90日）", "number");
                    col(columns, "rScore", "R", "number");
                    col(columns, "fScore", "F", "number");
                    col(columns, "mScore", "M", "number");
                    totals = Arrays.asList("totalAssets", "totalLoans");
                    break;
                default:
                    throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            Map<String, Object> sum = new LinkedHashMap<>();
            for (String k : totals) {
                BigDecimal s = BigDecimal.ZERO;
                for (Map<String, Object> r : rows) s = s.add(LoanService.num(r.get(k)));
                sum.put(k, s);
            }
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("totals", sum);
            m.put("generatedAt", LocalDateTime.now(clock).withNano(0).toString());
            return m;
        });
    }

    private static void col(List<Map<String, Object>> columns, String key, String label, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("kind", kind);
        columns.add(m);
    }

    // ===================================================================== helpers

    // 指定日（null なら最新）以前で取込済みの最新日
    private static LocalDate latestDate(Connection c, LocalDate asOf) throws SQLException {
        Object v = asOf == null
                ? query(c, "SELECT MAX(snapshot_date) AS d FROM dw_daily_kpi").get(0).get("d")
                : query(c, "SELECT MAX(snapshot_date) AS d FROM dw_daily_kpi WHERE snapshot_date <= ?", asOf).get(0).get("d");
        return v == null ? null : LocalDate.parse(((String) v).substring(0, 10));
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BankException(ErrorCode.INVALID_PERIOD);
        }
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.toString();
    }

    // 同じ INSERT をまとめて送る（1 日分の数千行を 1 往復ずつにしない）
    private static void batch(Connection c, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 0;
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    Object v = row[i];
                    if (v == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    else if (v instanceof LocalDate) ps.setDate(i + 1, Date.valueOf((LocalDate) v));
                    else if (v instanceof LocalDateTime) ps.setTimestamp(i + 1, Timestamp.valueOf((LocalDateTime) v));
                    else ps.setObject(i + 1, v);
                }
                ps.addBatch();
                if (++n % 500 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }
}
