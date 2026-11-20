package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.insertReturningId;
import static com.example.bank.repository.ProductRepository.query;

/**
 * リスク管理（信用・流動性・為替）と顧客管理（CRM: 顧客 360°・接触履歴）。
 *
 * <p>信用リスク: EAD = 貸出残高 + 未使用枠 × 50%（掛目）。LGD は担保でカバーされる部分 10%、無担保部分 45%。
 * PD は格付ごとの値を債務者区分で補正（要注意先 ×2、要管理先 20%、破綻懸念先 50%、実質破綻先・破綻先 100%）。
 * 期待損失 EL = PD × LGD × EAD。不良債権比率 = 要管理先以下の残高 ÷ 総残高。
 */
public class RiskService {
    private static final BigDecimal CCF = new BigDecimal("0.5");
    private static final BigDecimal LGD_SECURED = new BigDecimal("0.10");
    private static final BigDecimal LGD_UNSECURED = new BigDecimal("0.45");

    private static final RiskService DEFAULT = new RiskService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final CustomerRepository customers = new CustomerRepository();
    private final AccountRepository accounts = new AccountRepository();

    public RiskService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static RiskService getDefault() { return DEFAULT; }

    public Map<String, Object> dashboard() {
        return Transactions.readOnly(dataSource.get(), c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asOf", LocalDateTime.now(clock).withNano(0).toString());
            m.put("credit", credit(c));
            m.put("liquidity", liquidity(c));
            m.put("fx", fx(c));
            return m;
        });
    }

    private Map<String, Object> credit(Connection c) throws SQLException {
        List<Map<String, Object>> loans = query(c, "SELECT l.loan_id, l.customer_id, cu.name AS customer_name, l.product, "
                + "l.outstanding, l.credit_limit, l.grade, l.borrower_class, l.repayment_account_no, l.application_id, "
                + "a.balance AS account_balance, (SELECT COALESCE(SUM(appraisal_value), 0) FROM loan_collateral k "
                + "WHERE k.application_id = l.application_id AND k.collateral_type IN ('REAL_ESTATE', 'DEPOSIT', 'SECURITIES')) "
                + "AS collateral FROM loan l JOIN customer cu ON cu.customer_id = l.customer_id "
                + "JOIN account a ON a.account_no = l.repayment_account_no WHERE l.status IN ('ACTIVE', 'DELINQUENT')");
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalEad = BigDecimal.ZERO;
        BigDecimal totalEl = BigDecimal.ZERO;
        BigDecimal npl = BigDecimal.ZERO;
        Map<String, Map<String, Object>> byGrade = bucket(Arrays.asList("A", "B", "C", "D", "E"));
        Map<String, Map<String, Object>> byProduct = bucket(Arrays.asList("HOUSING", "BUSINESS", "SYNDICATED", "CARD", "OVERDRAFT"));
        Map<String, Map<String, Object>> byClass = bucket(Arrays.asList("NORMAL", "WATCH", "SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT"));
        Map<String, BigDecimal> byBorrower = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> l : loans) {
            String product = (String) l.get("product");
            BigDecimal outstanding = "OVERDRAFT".equals(product)
                    ? LoanService.num(l.get("accountBalance")).negate().max(BigDecimal.ZERO)
                    : LoanService.num(l.get("outstanding"));
            BigDecimal limit = LoanService.num(l.get("creditLimit"));
            BigDecimal undrawn = limit.signum() > 0 ? limit.subtract(outstanding).max(BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal ead = outstanding.add(undrawn.multiply(CCF));
            BigDecimal collateral = LoanService.num(l.get("collateral"));
            BigDecimal secured = collateral.min(ead);
            BigDecimal lgdAmount = secured.multiply(LGD_SECURED).add(ead.subtract(secured).multiply(LGD_UNSECURED));
            String cls = (String) l.get("borrowerClass");
            BigDecimal pd = pd((String) l.get("grade"), cls);
            BigDecimal el = lgdAmount.multiply(pd).setScale(0, RoundingMode.HALF_UP);

            totalOutstanding = totalOutstanding.add(outstanding);
            totalEad = totalEad.add(ead);
            totalEl = totalEl.add(el);
            if (Arrays.asList("SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT").contains(cls)) npl = npl.add(outstanding);
            add(byGrade.get(l.get("grade")), outstanding, ead, el);
            add(byProduct.get(product), outstanding, ead, el);
            add(byClass.get(cls), outstanding, ead, el);
            String cid = (String) l.get("customerId");
            byBorrower.merge(cid, ead, BigDecimal::add);
            names.put(cid, (String) l.get("customerName"));
        }
        List<Map<String, Object>> top = new ArrayList<>();
        byBorrower.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue())).limit(5).forEach(e -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("customerId", e.getKey());
            t.put("customerName", names.get(e.getKey()));
            t.put("ead", e.getValue());
            t.put("share", ratio(e.getValue(), byBorrower.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
            top.add(t);
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanCount", loans.size());
        m.put("totalOutstanding", totalOutstanding);
        m.put("totalEad", totalEad);
        m.put("expectedLoss", totalEl);
        m.put("nplAmount", npl);
        m.put("nplRatio", ratio(npl, totalOutstanding));
        m.put("byGrade", new ArrayList<>(byGrade.values()));
        m.put("byProduct", new ArrayList<>(byProduct.values()));
        m.put("byClass", new ArrayList<>(byClass.values()));
        m.put("topBorrowers", top);
        return m;
    }

    /** 格付の PD を債務者区分で補正する。 */
    static BigDecimal pd(String grade, String cls) {
        BigDecimal base = CreditScoring.PD.getOrDefault(grade, new BigDecimal("0.05"));
        switch (cls == null ? "NORMAL" : cls) {
            case "WATCH": return base.multiply(new BigDecimal("2")).min(BigDecimal.ONE);
            case "SPECIAL": return new BigDecimal("0.20").max(base);
            case "DOUBTFUL": return new BigDecimal("0.50");
            case "DEFACTO":
            case "BANKRUPT": return BigDecimal.ONE;
            default: return base;
        }
    }

    private Map<String, Object> liquidity(Connection c) throws SQLException {
        BigDecimal usd = ttm(c, "USD");
        BigDecimal eur = ttm(c, "EUR");
        List<Map<String, Object>> byType = query(c, "SELECT account_type, currency, COUNT(*) AS accounts, "
                + "COALESCE(SUM(CASE WHEN balance > 0 THEN balance ELSE 0 END), 0) AS balance FROM account "
                + "WHERE status <> 'CLOSED' GROUP BY account_type, currency ORDER BY account_type, currency");
        BigDecimal deposits = BigDecimal.ZERO;
        BigDecimal demand = BigDecimal.ZERO;
        for (Map<String, Object> r : byType) {
            String ccy = (String) r.get("currency");
            BigDecimal bal = LoanService.num(r.get("balance"));
            BigDecimal yen = "JPY".equals(ccy) ? bal : DepositMath.fromMinor(bal, ccy).multiply("USD".equals(ccy) ? usd : eur)
                    .setScale(0, RoundingMode.HALF_UP);
            r.put("yenEquivalent", yen);
            deposits = deposits.add(yen);
            if (!"TIME".equals(r.get("accountType"))) demand = demand.add(yen);
        }
        BigDecimal loans = LoanService.num(query(c, "SELECT COALESCE(SUM(outstanding), 0) AS v FROM loan "
                + "WHERE status IN ('ACTIVE', 'DELINQUENT') AND product <> 'OVERDRAFT'").get(0).get("v"))
                .add(LoanService.num(query(c, "SELECT COALESCE(SUM(-balance), 0) AS v FROM account WHERE balance < 0")
                        .get(0).get("v")));
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> maturing = query(c, "SELECT COUNT(*) AS n, COALESCE(SUM(principal), 0) AS amount FROM time_deposit "
                + "WHERE status = 'ACTIVE' AND maturity_date <= ?", today.plusDays(30)).get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalDeposits", deposits);
        m.put("demandDeposits", demand);
        m.put("totalLoans", loans);
        m.put("loanToDepositRatio", ratio(loans, deposits));
        m.put("timeDepositsMaturing30d", maturing);
        m.put("byType", byType);
        return m;
    }

    private Map<String, Object> fx(Connection c) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String ccy : Arrays.asList("USD", "EUR")) {
            BigDecimal minor = LoanService.num(query(c, "SELECT COALESCE(SUM(balance), 0) AS v FROM account WHERE currency = ? "
                    + "AND status <> 'CLOSED'", ccy).get(0).get("v"));
            BigDecimal amount = DepositMath.fromMinor(minor, ccy);
            BigDecimal rate = ttm(c, ccy);
            BigDecimal yen = amount.multiply(rate).setScale(0, RoundingMode.HALF_UP);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("currency", ccy);
            // Customer deposits are a liability in foreign currency: an unhedged short position for the bank
            r.put("position", amount.negate());
            r.put("ttm", rate);
            r.put("yenEquivalent", yen.negate());
            r.put("pnlIfYenWeakens1pct", yen.multiply(new BigDecimal("-0.01")).setScale(0, RoundingMode.HALF_UP));
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("positions", rows);
        return m;
    }

    // ===================== CRM =====================

    /** 顧客 360°: 基本情報、預金、融資、申込、口座振替、送金、接触履歴、預かり資産の合計と顧客区分。 */
    public Map<String, Object> customer360(String customerId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            Customer customer = customers.findById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            Map<String, Object> m = new LinkedHashMap<>();
            Customer full = customer.withAccounts(accounts.findByCustomer(c, customerId));
            m.put("customer", full);
            BigDecimal usd = ttm(c, "USD");
            BigDecimal eur = ttm(c, "EUR");
            BigDecimal assets = BigDecimal.ZERO;
            for (com.example.bank.domain.Account a : full.getAccounts()) {
                if ("CLOSED".equals(a.getStatus()) || a.getBalance().signum() <= 0) continue;
                assets = assets.add(a.isJpy() ? a.getBalance()
                        : DepositMath.fromMinor(a.getBalance(), a.getCurrency()).multiply("USD".equals(a.getCurrency()) ? usd : eur)
                        .setScale(0, RoundingMode.HALF_UP));
            }
            BigDecimal debt = LoanService.num(query(c, "SELECT COALESCE(SUM(outstanding), 0) AS v FROM loan WHERE customer_id = ? "
                    + "AND status IN ('ACTIVE', 'DELINQUENT')", customerId).get(0).get("v"));
            m.put("totalAssets", assets);
            m.put("totalLoans", debt);
            m.put("segment", assets.compareTo(new BigDecimal("10000000")) >= 0 ? "プライベート"
                    : assets.compareTo(new BigDecimal("3000000")) >= 0 ? "ゴールド" : "スタンダード");
            m.put("loans", query(c, "SELECT loan_id, product, principal, outstanding, credit_limit, status, borrower_class, "
                    + "days_past_due FROM loan WHERE customer_id = ? ORDER BY loan_id DESC", customerId));
            m.put("applications", query(c, "SELECT application_id, product, requested_amount, status, grade, applied_at "
                    + "FROM loan_application WHERE customer_id = ? ORDER BY application_id DESC", customerId));
            m.put("mandates", query(c, "SELECT m.mandate_id, m.account_no, b.name AS biller_name, m.payer_number, m.status "
                    + "FROM direct_debit_mandate m JOIN biller b ON b.biller_code = m.biller_code JOIN account a "
                    + "ON a.account_no = m.account_no WHERE a.customer_id = ? ORDER BY m.mandate_id DESC", customerId));
            m.put("timeDeposits", query(c, "SELECT t.account_no, t.principal, t.annual_rate, t.term_months, t.maturity_date, "
                    + "t.maturity_instruction, t.status FROM time_deposit t JOIN account a ON a.account_no = t.account_no "
                    + "WHERE a.customer_id = ? ORDER BY t.maturity_date", customerId));
            m.put("amlAlerts", query(c, "SELECT alert_id, rule_code, score, status, created_at FROM aml_alert WHERE customer_id = ? "
                    + "ORDER BY alert_id DESC FETCH FIRST 20 ROWS ONLY", customerId));
            m.put("notes", query(c, "SELECT * FROM customer_note WHERE customer_id = ? ORDER BY note_id DESC "
                    + "FETCH FIRST 100 ROWS ONLY", customerId));
            return m;
        });
    }

    public List<Map<String, Object>> addNote(String customerId, String type, String content, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            if (!customers.findById(c, customerId).isPresent()) throw new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId);
            insertReturningId(c, "INSERT INTO customer_note (customer_id, note_type, content, created_by, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)", "note_id", customerId, type, content, by, LocalDateTime.now(clock));
            return query(c, "SELECT * FROM customer_note WHERE customer_id = ? ORDER BY note_id DESC FETCH FIRST 100 ROWS ONLY",
                    customerId);
        });
    }

    // ===================== helpers =====================

    private static BigDecimal ttm(Connection c, String ccy) throws SQLException {
        List<Map<String, Object>> r = query(c, "SELECT ttm FROM fx_rate WHERE currency = ?", ccy);
        return r.isEmpty() ? BigDecimal.ZERO : LoanService.num(r.get(0).get("ttm"));
    }

    private static Map<String, Map<String, Object>> bucket(List<String> keys) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (String k : keys) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("key", k);
            b.put("count", 0);
            b.put("outstanding", BigDecimal.ZERO);
            b.put("ead", BigDecimal.ZERO);
            b.put("expectedLoss", BigDecimal.ZERO);
            m.put(k, b);
        }
        return m;
    }

    private static void add(Map<String, Object> b, BigDecimal outstanding, BigDecimal ead, BigDecimal el) {
        if (b == null) return;
        b.put("count", (Integer) b.get("count") + 1);
        b.put("outstanding", ((BigDecimal) b.get("outstanding")).add(outstanding));
        b.put("ead", ((BigDecimal) b.get("ead")).add(ead));
        b.put("expectedLoss", ((BigDecimal) b.get("expectedLoss")).add(el));
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, 4, RoundingMode.HALF_UP);
    }
}
