package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.BusinessCalendar;
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
 * 融資の管理：貸倒引当金・返済シミュレーション・担保の再評価・条件変更（貸出条件緩和）。
 *
 * <h2>貸倒引当金（簡略化した自己査定）</h2>
 * 債務者区分ごとに必要な引当額を求め、前回との差額を積み増し・取り崩す（同じ日に何度実行しても結果は同じ）。
 * <table>
 *   <tr><th>区分</th><th>引当率</th><th>対象</th></tr>
 *   <tr><td>正常先</td><td>0.5%</td><td>貸出残高</td></tr>
 *   <tr><td>要注意先</td><td>3%</td><td>貸出残高</td></tr>
 *   <tr><td>要管理先</td><td>15%</td><td>貸出残高</td></tr>
 *   <tr><td>破綻懸念先</td><td>70%</td><td>非保全額（残高 − 保全額）</td></tr>
 *   <tr><td>実質破綻先・破綻先</td><td>100%</td><td>非保全額</td></tr>
 * </table>
 * 保全額は担保の評価額に掛目を掛けた額（不動産 70%・預金 100%・有価証券 60%・保証会社 100%・個人保証 50%）で、
 * 貸出残高が上限。完済・償却した融資の引当は全額取り崩す。
 *
 * <h2>条件変更</h2>
 * 残りの元金を新しい回数（と、より低い金利）で返済し直す。延滞していた回の未払利息は新しい 1 回目に上乗せし、
 * 延滞は解消する。条件を緩和した債権は要管理先以下として扱う（夜間の延滞判定でも要管理先より良い区分にしない）。
 */
public class LoanManagementService {
    /** 債務者区分の良い順（後ろほど悪い） */
    static final List<String> CLASS_ORDER = Arrays.asList("NORMAL", "WATCH", "SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT");
    /** 返済シミュレーション・条件変更で指定できる最大回数（35 年） */
    public static final int MAX_MONTHS = 420;
    private static final BigDecimal MAX_SIMULATION_AMOUNT = new BigDecimal("1000000000");
    private static final BigDecimal MAX_RATE = new BigDecimal("20");

    private static final Map<String, BigDecimal> RATE = new HashMap<>();
    private static final Map<String, BigDecimal> HAIRCUT = new HashMap<>();
    static {
        RATE.put("NORMAL", new BigDecimal("0.005"));
        RATE.put("WATCH", new BigDecimal("0.03"));
        RATE.put("SPECIAL", new BigDecimal("0.15"));
        RATE.put("DOUBTFUL", new BigDecimal("0.70"));
        RATE.put("DEFACTO", BigDecimal.ONE);
        RATE.put("BANKRUPT", BigDecimal.ONE);
        HAIRCUT.put("REAL_ESTATE", new BigDecimal("0.70"));
        HAIRCUT.put("DEPOSIT", BigDecimal.ONE);
        HAIRCUT.put("SECURITIES", new BigDecimal("0.60"));
        HAIRCUT.put("GUARANTEE_CO", BigDecimal.ONE);
        HAIRCUT.put("GUARANTOR", new BigDecimal("0.50"));
    }

    private static final LoanManagementService DEFAULT = new LoanManagementService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;

    /**
     * @param dataSource 接続の取得元
     * @param clock      本日・計上日時の取得元
     */
    public LoanManagementService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static LoanManagementService getDefault() { return DEFAULT; }

    // ===================================================================== 貸倒引当金

    /** 区分ごとの引当率（破綻懸念先以下は非保全額に対する率）。 */
    static BigDecimal provisionRate(String borrowerClass) {
        return RATE.getOrDefault(borrowerClass == null ? "NORMAL" : borrowerClass, RATE.get("NORMAL"));
    }

    /** 破綻懸念先以下は非保全額、それより良い区分は残高全体に引当率を掛ける。 */
    static boolean unsecuredBasis(String borrowerClass) {
        return CLASS_ORDER.indexOf(borrowerClass) >= CLASS_ORDER.indexOf("DOUBTFUL");
    }

    /**
     * 必要な引当額（円未満四捨五入）。
     *
     * @param borrowerClass 債務者区分
     * @param outstanding   貸出残高
     * @param secured       保全額（残高を上限に丸めてから使う）
     * @return 必要な引当額
     */
    static BigDecimal requiredProvision(String borrowerClass, BigDecimal outstanding, BigDecimal secured) {
        BigDecimal base = unsecuredBasis(borrowerClass) ? outstanding.subtract(secured.min(outstanding)).max(BigDecimal.ZERO)
                : outstanding;
        return base.multiply(provisionRate(borrowerClass)).setScale(0, RoundingMode.HALF_UP);
    }

    /** 区分のうち悪い方。 */
    static String worse(String a, String b) {
        return CLASS_ORDER.indexOf(a) >= CLASS_ORDER.indexOf(b) ? a : b;
    }

    /**
     * 全融資の引当を見直す（融資ごとに短いトランザクション。完済・償却済みで引当が残っているものは取り崩す）。
     * 同じ日に何度実行しても、必要額が変わらなければ何も記帳しない。
     *
     * @param asOf 基準日（null は本日）
     * @param by   実行者
     * @return 件数・引当総額・増減・区分別の引当額
     */
    public Map<String, Object> provisionAll(LocalDate asOf, String by) {
        LocalDate day = asOf == null ? LocalDate.now(clock) : asOf;
        if (day.isAfter(LocalDate.now(clock))) throw new BankException(ErrorCode.INVALID_BASE_DATE);
        List<Map<String, Object>> loans = Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT loan_id FROM loan "
                + "WHERE status IN ('ACTIVE', 'DELINQUENT') OR provision_amount > 0 ORDER BY loan_id"));
        int changed = 0;
        BigDecimal delta = BigDecimal.ZERO;
        for (Map<String, Object> l : loans) {
            long loanId = num(l.get("loanId")).longValue();
            BigDecimal d = Transactions.inTransaction(dataSource.get(), c -> provisionLoan(c, loanId, day, "定例見直し", by,
                    LocalDateTime.now(clock)));
            if (d.signum() != 0) {
                changed++;
                delta = delta.add(d);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", day.toString());
        m.put("loans", loans.size());
        m.put("changed", changed);
        m.put("totalChange", delta);
        m.putAll(Transactions.readOnly(dataSource.get(), this::summary));
        return m;
    }

    /** 引当の総額と区分別の内訳（リスク管理画面用）。 */
    public Map<String, Object> provisionSummary() {
        return Transactions.readOnly(dataSource.get(), this::summary);
    }

    private Map<String, Object> summary(Connection c) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> byClass = query(c, "SELECT borrower_class, COUNT(*) AS loans, SUM(provision_amount) AS provision "
                + "FROM loan WHERE status IN ('ACTIVE', 'DELINQUENT') GROUP BY borrower_class");
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : byClass) total = total.add(num(r.get("provision")));
        byClass.sort((a, b) -> CLASS_ORDER.indexOf(a.get("borrowerClass")) - CLASS_ORDER.indexOf(b.get("borrowerClass")));
        m.put("totalProvision", total);
        m.put("byClass", byClass);
        List<Map<String, Object>> last = query(c, "SELECT created_at, created_by FROM loan_provision ORDER BY provision_id DESC "
                + "FETCH FIRST 1 ROWS ONLY");
        m.put("lastChange", last.isEmpty() ? null : last.get(0));
        return m;
    }

    /**
     * 1 件の融資の引当を見直す（呼び出し側のトランザクションで実行。融資行をロックする）。
     *
     * @return 引当額の増減（変化なしは 0）
     */
    static BigDecimal provisionLoan(Connection c, long loanId, LocalDate asOf, String reason, String by, LocalDateTime now)
            throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM loan WHERE loan_id = ? FOR UPDATE", loanId);
        if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(loanId));
        Map<String, Object> loan = rows.get(0);
        String status = (String) loan.get("status");
        boolean open = "ACTIVE".equals(status) || "DELINQUENT".equals(status);
        String cls = (String) loan.get("borrowerClass");
        BigDecimal outstanding = open ? outstanding(c, loan) : BigDecimal.ZERO;
        BigDecimal secured = open ? secured(c, num(loan.get("applicationId")).longValue()).min(outstanding) : BigDecimal.ZERO;
        BigDecimal required = open ? requiredProvision(cls, outstanding, secured) : BigDecimal.ZERO;
        BigDecimal previous = num(loan.get("provisionAmount"));
        BigDecimal change = required.subtract(previous);
        if (change.signum() == 0) return BigDecimal.ZERO;
        String why = !open ? ("WRITTEN_OFF".equals(status) ? "償却による取崩し" : "完済による戻入")
                : change.signum() > 0 ? reason + "（積増し）" : reason + "（取崩し）";
        execute(c, "UPDATE loan SET provision_amount = ?, provision_rate = ?, provision_updated_at = ? WHERE loan_id = ?",
                required, provisionRate(cls), now, loanId);
        insertReturningId(c, "INSERT INTO loan_provision (loan_id, as_of, borrower_class, outstanding, secured, provision_rate, "
                + "required, previous, change_amount, reason, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "provision_id", loanId, asOf, cls, outstanding, secured, provisionRate(cls), required, previous, change,
                why, by, now);
        return change;
    }

    // 貸出残高（当座貸越は返済口座のマイナス残高）
    private static BigDecimal outstanding(Connection c, Map<String, Object> loan) throws SQLException {
        if (!"OVERDRAFT".equals(loan.get("product"))) return num(loan.get("outstanding"));
        List<Map<String, Object>> acc = query(c, "SELECT balance FROM account WHERE account_no = ?", loan.get("repaymentAccountNo"));
        return acc.isEmpty() ? BigDecimal.ZERO : num(acc.get(0).get("balance")).negate().max(BigDecimal.ZERO);
    }

    // 保全額 = Σ（担保の評価額 × 掛目）
    private static BigDecimal secured(Connection c, long applicationId) throws SQLException {
        BigDecimal s = BigDecimal.ZERO;
        for (Map<String, Object> k : query(c, "SELECT collateral_type, appraisal_value FROM loan_collateral WHERE application_id = ?",
                applicationId)) {
            BigDecimal haircut = HAIRCUT.getOrDefault((String) k.get("collateralType"), BigDecimal.ZERO);
            s = s.add(num(k.get("appraisalValue")).multiply(haircut));
        }
        return s.setScale(0, RoundingMode.DOWN);
    }

    // ===================================================================== 返済シミュレーション

    /**
     * 返済シミュレーション（DB には何も書かない）。指定した方式の返済予定表と、元利均等・元金均等の比較を返す。
     *
     * @param principal 借入額
     * @param rate      年利（%）
     * @param months    返済回数
     * @param method    EQUAL_PAYMENT（元利均等）/ EQUAL_PRINCIPAL（元金均等）
     * @param paymentDay 毎月の返済日（1〜28。null は本日の日付、29 日以降は 28 日）
     * @return 予定表と合計
     * @throws BankException 範囲外の入力（VALIDATION_ERROR）
     */
    public Map<String, Object> simulate(BigDecimal principal, BigDecimal rate, int months, String method, Integer paymentDay) {
        if (principal == null || principal.signum() <= 0 || principal.compareTo(MAX_SIMULATION_AMOUNT) > 0
                || rate == null || rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0 || months < 1 || months > MAX_MONTHS
                || !("EQUAL_PAYMENT".equals(method) || "EQUAL_PRINCIPAL".equals(method))
                || (paymentDay != null && (paymentDay < 1 || paymentDay > 28))) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        LocalDate today = LocalDate.now(clock);
        int day = paymentDay != null ? paymentDay : Math.min(today.getDayOfMonth(), 28);
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            // 休日は期間分を 1 回で読み込む（420 回なら 420 回の照会を 1 回に）
            BusinessCalendar.Holidays holidays = BusinessCalendar.Holidays.load(c, today, today.plusMonths(months + 1));
            for (LoanMath.Installment i : LoanMath.schedule(principal, rate, months, method)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("no", i.no);
                r.put("dueDate", LoanService.dueDate(holidays, today, i.no, day).toString());
                r.put("principal", i.principal);
                r.put("interest", i.interest);
                r.put("total", i.total());
                r.put("balanceAfter", i.balanceAfter);
                rows.add(r);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("principal", principal);
            m.put("annualRate", rate);
            m.put("months", months);
            m.put("method", method);
            m.put("paymentDay", day);
            m.put("schedule", rows);
            m.putAll(totals(LoanMath.schedule(principal, rate, months, method)));
            List<Map<String, Object>> comparison = new ArrayList<>();
            for (String mth : Arrays.asList("EQUAL_PAYMENT", "EQUAL_PRINCIPAL")) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("method", mth);
                t.putAll(totals(LoanMath.schedule(principal, rate, months, mth)));
                comparison.add(t);
            }
            m.put("comparison", comparison);
            return m;
        });
    }

    private static Map<String, Object> totals(List<LoanMath.Installment> plan) {
        BigDecimal interest = BigDecimal.ZERO, total = BigDecimal.ZERO, max = BigDecimal.ZERO;
        for (LoanMath.Installment i : plan) {
            interest = interest.add(i.interest);
            total = total.add(i.total());
            max = max.max(i.total());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalInterest", interest);
        m.put("totalPayment", total);
        m.put("firstPayment", plan.get(0).total());
        m.put("lastPayment", plan.get(plan.size() - 1).total());
        m.put("maxPayment", max);
        return m;
    }

    // ===================================================================== 担保の再評価

    /**
     * 担保を再評価する（評価額を更新し、履歴を残す）。実行済みの融資の担保なら、その場で引当を見直す。
     *
     * @param collateralId 担保番号
     * @param newValue     新しい評価額（0 以上）
     * @param reason       再評価の理由（鑑定・路線価の改定など）
     * @param by           実行者
     * @return 担保・再評価履歴・引当の増減
     */
    public Map<String, Object> revalueCollateral(long collateralId, BigDecimal newValue, String reason, String by) {
        if (newValue == null || newValue.signum() < 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            List<Map<String, Object>> found = query(c, "SELECT application_id FROM loan_collateral WHERE collateral_id = ?", collateralId);
            if (found.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, "担保 " + collateralId);
            long appId = num(found.get(0).get("applicationId")).longValue();
            // ロック順は「融資 → 担保」（引当の見直しも融資行を先にロックする）
            List<Map<String, Object>> loans = query(c, "SELECT loan_id, status FROM loan WHERE application_id = ? FOR UPDATE", appId);
            Map<String, Object> k = query(c, "SELECT * FROM loan_collateral WHERE collateral_id = ? FOR UPDATE", collateralId).get(0);
            BigDecimal previous = num(k.get("appraisalValue"));
            LocalDateTime now = LocalDateTime.now(clock);
            execute(c, "UPDATE loan_collateral SET appraisal_value = ?, revalued_at = ? WHERE collateral_id = ?",
                    newValue, now, collateralId);
            insertReturningId(c, "INSERT INTO collateral_revaluation (collateral_id, previous_value, new_value, reason, revalued_by, "
                    + "revalued_at) VALUES (?, ?, ?, ?, ?, ?)", "revaluation_id", collateralId, previous, newValue, reason, by, now);
            BigDecimal change = BigDecimal.ZERO;
            if (!loans.isEmpty()) {
                String st = (String) loans.get(0).get("status");
                if ("ACTIVE".equals(st) || "DELINQUENT".equals(st)) {
                    change = provisionLoan(c, num(loans.get(0).get("loanId")).longValue(), now.toLocalDate(), "担保再評価", by, now);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("collateral", query(c, "SELECT * FROM loan_collateral WHERE collateral_id = ?", collateralId).get(0));
            m.put("previousValue", previous);
            m.put("provisionChange", change);
            m.put("loanId", loans.isEmpty() ? null : loans.get(0).get("loanId"));
            return m;
        });
    }

    // ===================================================================== 条件変更

    /**
     * 条件変更（貸出条件の緩和）：残りの元金を新しい回数・金利で返済し直す。
     *
     * @param loanId    融資番号
     * @param newMonths 残りの返済回数（1〜{@value #MAX_MONTHS}）
     * @param newRate   新しい年利（%）。null は据え置き。現在より高くはできない
     * @param reason    理由（業況悪化・返済計画の見直しなど）
     * @param by        承認した役席
     * @return 条件変更の内容（変更前後の毎月の返済額・満期など）
     * @throws BankException 融資が見つからない（LOAN_NOT_FOUND）、対象外の状態・商品（LOAN_STATE_INVALID / LOAN_RULE_VIOLATION）、
     *                       入力が範囲外（VALIDATION_ERROR）の場合
     */
    public Map<String, Object> reschedule(long loanId, int newMonths, BigDecimal newRate, String reason, String by) {
        if (newMonths < 1 || newMonths > MAX_MONTHS || (newRate != null && newRate.signum() < 0)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT * FROM loan WHERE loan_id = ? FOR UPDATE", loanId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(loanId));
            Map<String, Object> loan = rows.get(0);
            String status = (String) loan.get("status");
            if (!"ACTIVE".equals(status) && !"DELINQUENT".equals(status)) throw new BankException(ErrorCode.LOAN_STATE_INVALID, status);
            String method = (String) loan.get("repaymentMethod");
            if ("REVOLVING".equals(method)) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "証書貸付のみ");
            BigDecimal oldRate = num(loan.get("annualRate"));
            BigDecimal rate = newRate == null ? oldRate : newRate;
            if (rate.compareTo(oldRate) > 0) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "条件変更で金利は引き上げられません");
            BigDecimal outstanding = num(loan.get("outstanding"));
            if (outstanding.signum() <= 0) throw new BankException(ErrorCode.LOAN_STATE_INVALID, "残高なし");

            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            List<Map<String, Object>> unpaid = query(c, "SELECT * FROM loan_schedule WHERE loan_id = ? "
                    + "AND status IN ('SCHEDULED', 'PARTIAL', 'OVERDUE') ORDER BY installment_no", loanId);
            BigDecimal arrearsInterest = BigDecimal.ZERO;
            BigDecimal oldPayment = null;
            for (Map<String, Object> s : unpaid) {
                boolean pastDue = !LocalDate.parse((String) s.get("dueDate")).isAfter(today);
                if (pastDue) arrearsInterest = arrearsInterest.add(num(s.get("interestDue")).subtract(num(s.get("interestPaid"))));
                else if (oldPayment == null) oldPayment = num(s.get("principalDue")).add(num(s.get("interestDue")));
            }
            int nextNo = num(query(c, "SELECT COALESCE(MAX(installment_no), 0) AS n FROM loan_schedule WHERE loan_id = ?", loanId)
                    .get(0).get("n")).intValue() + 1;
            // 元の未払いの回は取り消す（一部入金済みの回は入金額を残したまま）。延滞の未払利息は新しい 1 回目に上乗せする
            execute(c, "UPDATE loan_schedule SET status = 'CANCELLED' WHERE loan_id = ? "
                    + "AND status IN ('SCHEDULED', 'PARTIAL', 'OVERDUE')", loanId);
            int day = num(loan.get("paymentDay")).intValue();
            List<LoanMath.Installment> plan = LoanMath.schedule(outstanding, rate, newMonths, method);
            BusinessCalendar.Holidays holidays = BusinessCalendar.Holidays.load(c, today, today.plusMonths(newMonths + 1));
            LocalDate maturity = null;
            BigDecimal newPayment = null;
            for (LoanMath.Installment i : plan) {
                LocalDate due = LoanService.dueDate(holidays, today, i.no, day);
                maturity = due;
                BigDecimal interest = i.no == 1 ? i.interest.add(arrearsInterest) : i.interest;
                if (i.no == 2 || (newPayment == null && plan.size() == 1)) newPayment = i.total();
                execute(c, "INSERT INTO loan_schedule (loan_id, installment_no, due_date, principal_due, interest_due) "
                        + "VALUES (?, ?, ?, ?, ?)", loanId, nextNo + i.no - 1, due, i.principal, interest);
            }
            String oldClass = (String) loan.get("borrowerClass");
            // 貸出条件緩和債権は要管理先以下（もっと悪い区分ならそのまま）
            String cls = worse(oldClass, "SPECIAL");
            int dpd = num(loan.get("daysPastDue")).intValue();
            execute(c, "UPDATE loan SET annual_rate = ?, maturity_date = ?, status = 'ACTIVE', days_past_due = 0, "
                    + "borrower_class = ?, restructured = 1, restructure_count = restructure_count + 1 WHERE loan_id = ?",
                    rate, maturity, cls, loanId);
            insertReturningId(c, "INSERT INTO loan_restructure (loan_id, outstanding, arrears_interest, old_rate, new_rate, "
                    + "old_remaining, new_remaining, old_payment, new_payment, old_maturity, new_maturity, days_past_due, reason, "
                    + "approved_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "restructure_id",
                    loanId, outstanding, arrearsInterest, oldRate, rate, unpaid.size(), newMonths, oldPayment,
                    newPayment == null ? plan.get(0).total() : newPayment,
                    loan.get("maturityDate") == null ? null : LocalDate.parse(((String) loan.get("maturityDate")).substring(0, 10)),
                    maturity, dpd, reason, by, now);
            BigDecimal provisionChange = provisionLoan(c, loanId, today, "条件変更", by, now);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanId", loanId);
            m.put("outstanding", outstanding);
            m.put("arrearsInterest", arrearsInterest);
            m.put("oldRate", oldRate);
            m.put("newRate", rate);
            m.put("oldRemaining", unpaid.size());
            m.put("newRemaining", newMonths);
            m.put("oldPayment", oldPayment);
            m.put("newPayment", newPayment == null ? plan.get(0).total() : newPayment);
            m.put("newMaturity", maturity == null ? null : maturity.toString());
            m.put("borrowerClass", cls);
            m.put("provisionChange", provisionChange);
            return m;
        });
    }

    // ===================================================================== 照会

    /**
     * 融資の管理情報（行員向け）：引当の履歴・条件変更の履歴・担保の再評価履歴。
     *
     * @param loanId 融資番号
     * @return 管理情報
     */
    public Map<String, Object> management(long loanId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT loan_id, application_id, borrower_class, provision_amount, "
                    + "provision_rate, provision_updated_at, restructured, restructure_count, outstanding, status, product "
                    + "FROM loan WHERE loan_id = ?", loanId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(loanId));
            Map<String, Object> loan = rows.get(0);
            long appId = num(loan.get("applicationId")).longValue();
            Map<String, Object> m = new LinkedHashMap<>(loan);
            BigDecimal outstanding = outstanding(c, loan);
            BigDecimal secured = secured(c, appId).min(outstanding);
            String cls = (String) loan.get("borrowerClass");
            m.put("outstanding", outstanding);
            m.put("secured", secured);
            m.put("unsecured", outstanding.subtract(secured));
            m.put("unsecuredBasis", unsecuredBasis(cls));
            m.put("requiredProvision", requiredProvision(cls, outstanding, secured));
            m.put("provisions", query(c, "SELECT * FROM loan_provision WHERE loan_id = ? ORDER BY provision_id DESC "
                    + "FETCH FIRST 50 ROWS ONLY", loanId));
            m.put("restructures", query(c, "SELECT * FROM loan_restructure WHERE loan_id = ? ORDER BY restructure_id DESC", loanId));
            List<Map<String, Object>> collaterals = query(c, "SELECT * FROM loan_collateral WHERE application_id = ? "
                    + "ORDER BY collateral_id", appId);
            Map<String, List<Map<String, Object>>> history = new TreeMap<>();
            for (Map<String, Object> r : query(c, "SELECT v.* FROM collateral_revaluation v JOIN loan_collateral k "
                    + "ON k.collateral_id = v.collateral_id WHERE k.application_id = ? ORDER BY v.revaluation_id DESC", appId)) {
                history.computeIfAbsent(String.valueOf(r.get("collateralId")), x -> new ArrayList<>()).add(r);
            }
            for (Map<String, Object> k : collaterals) {
                BigDecimal haircut = HAIRCUT.getOrDefault((String) k.get("collateralType"), BigDecimal.ZERO);
                k.put("haircut", haircut);
                k.put("recognized", num(k.get("appraisalValue")).multiply(haircut).setScale(0, RoundingMode.DOWN));
                k.put("revaluations", history.getOrDefault(String.valueOf(k.get("collateralId")), new ArrayList<>()));
            }
            m.put("collaterals", collaterals);
            return m;
        });
    }
}
