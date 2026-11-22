package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.OperationRequestRepository;
import com.example.bank.repository.TransactionRepository;
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
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.insertReturningId;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 融資業務: 申込 → 与信審査 → 承認 → 担保・保証設定 → 契約 → 実行（貸付金計上）→ 返済・利息 → 延滞管理・債権回収。
 *
 * <ul>
 *   <li>住宅ローン（個人・不動産担保・元利均等/元金均等）、事業融資（法人）、シンジケートローン（法人・協調融資）</li>
 *   <li>カードローン（個人・リボルビング、総量規制）、当座貸越（法人・当座預金の極度額）</li>
 *   <li>承認は役席、かつ申込の受付者とは別の人（職務分離）</li>
 *   <li>夜間バッチで約定返済の口座引落し、延滞日数・債務者区分の更新、カードローン利息の日割計上</li>
 *   <li>債務者区分: 延滞 0 日 正常先 / 1〜89 日 要注意先 / 90〜179 日 要管理先 / 180〜364 日 破綻懸念先 / 365 日〜 実質破綻先</li>
 * </ul>
 */
public class LoanService {
    /** シンジケートローンの参加行のうち当行を表す名称 */
    public static final String OUR_BANK = "永劫銀行";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final LoanService DEFAULT = new LoanService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final CustomerRepository customers = new CustomerRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();

    /**
     * @param dataSource 接続の取得元
     * @param clock      契約日・返済日・延滞日数の判定に使う時計
     */
    public LoanService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static LoanService getDefault() { return DEFAULT; }

    /** 融資申込の入力。 */
    public static final class Application {
        // 項目は Action が検証済みの値を詰めて渡す（入れ物なので public フィールドにしている）
        public String customerId;
        public String product;
        public BigDecimal amount;
        public int termMonths;
        public String repaymentMethod;
        public String purpose;
        public BigDecimal annualIncome;
        public BigDecimal existingDebtAnnual = BigDecimal.ZERO;
        public BigDecimal collateralValue = BigDecimal.ZERO;
        /** シンジケートローンの組成総額（当行分は amount） */
        public BigDecimal syndicateTotal;
    }

    // ===================== application / screening / decision =====================

    /**
     * 融資を申し込む（状態 APPLIED）。
     *
     * @param a         申込内容
     * @param appliedBy 受付した利用者（後の決裁者と同じ人にはできない）
     * @return 申込の内容
     * @throws BankException 本人確認未了・個人／法人の商品区分違い・金額や期間の範囲外など
     */
    public Map<String, Object> apply(Application a, String appliedBy) {
        // 商品ごとの金額・期間の範囲は DB に触れる前に確かめる
        validateProduct(a);
        return Transactions.inTransaction(dataSource.get(), c -> {
            Customer customer = customers.findById(c, a.customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, a.customerId));
            if (!customer.isVerified()) throw new BankException(ErrorCode.KYC_NOT_VERIFIED, a.customerId);
            boolean corporateProduct = "BUSINESS".equals(a.product) || "OVERDRAFT".equals(a.product)
                    || "SYNDICATED".equals(a.product);
            if (corporateProduct && !customer.isCorporate()) throw new BankException(ErrorCode.CORPORATE_ONLY);
            if (!corporateProduct && customer.isCorporate()) {
                throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "個人向け商品");
            }
            long id = insertReturningId(c, "INSERT INTO loan_application (customer_id, product, requested_amount, term_months, "
                    + "repayment_method, purpose, annual_income, existing_debt_annual, collateral_value, syndicate_total, "
                    + "status, applied_by, applied_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?)",
                    "application_id", a.customerId, a.product, a.amount, a.termMonths, a.repaymentMethod, a.purpose,
                    a.annualIncome, a.existingDebtAnnual, a.collateralValue, a.syndicateTotal, appliedBy,
                    LocalDateTime.now(clock));
            return applicationView(c, id);
        });
    }

    // 商品ごとに期間（月）と金額（円）の範囲を確かめる
    private static void validateProduct(Application a) {
        if (a.product == null || !CreditScoring.BASE_RATE.containsKey(a.product) || a.amount == null
                || a.amount.signum() <= 0 || a.amount.scale() > 0 || a.annualIncome == null || a.annualIncome.signum() < 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        boolean revolving = "CARD".equals(a.product) || "OVERDRAFT".equals(a.product);
        if (revolving) {
            a.repaymentMethod = "REVOLVING";
            a.termMonths = 12;   // 契約期間 1 年（自動更新）
        } else if (!"EQUAL_PAYMENT".equals(a.repaymentMethod) && !"EQUAL_PRINCIPAL".equals(a.repaymentMethod)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        long amount = a.amount.longValue();
        switch (a.product) {
            case "HOUSING":
                range(a.termMonths, 60, 420, amount, 1_000_000L, 100_000_000L);
                if (a.collateralValue == null || a.collateralValue.signum() <= 0) {
                    throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "住宅ローンは担保評価額が必要");
                }
                break;
            case "BUSINESS":
                range(a.termMonths, 6, 120, amount, 1_000_000L, 500_000_000L);
                break;
            case "SYNDICATED":
                range(a.termMonths, 12, 120, amount, 10_000_000L, 5_000_000_000L);
                if (a.syndicateTotal == null || a.syndicateTotal.compareTo(a.amount) <= 0) {
                    throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "組成総額は当行分より大きいこと");
                }
                break;
            case "CARD":
                range(12, 12, 12, amount, 100_000L, 5_000_000L);
                break;
            case "OVERDRAFT":
                range(12, 12, 12, amount, 1_000_000L, 100_000_000L);
                break;
            default:
                throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // 期間・金額が商品の範囲外なら LOAN_RULE_VIOLATION
    private static void range(int term, int minTerm, int maxTerm, long amount, long minAmount, long maxAmount) {
        if (term < minTerm || term > maxTerm) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "期間 " + minTerm + "〜" + maxTerm + " か月");
        if (amount < minAmount || amount > maxAmount) {
            throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "金額 " + String.format("%,d", minAmount) + "〜" + String.format("%,d", maxAmount) + " 円");
        }
    }

    /** 与信審査（スコアリング）。担保を追加した後は再審査できる。 */
    public Map<String, Object> screen(long applicationId, String screenedBy) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            // 申込行をロックし、審査・決裁・担保追加などの状態遷移が同時に走って矛盾するのを防ぐ
            Map<String, Object> app = lockApplication(c, applicationId);
            requireStatus(app, "APPLIED", "SCREENED");
            String customerId = (String) app.get("customerId");
            Customer customer = customers.findById(c, customerId).orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            LocalDate today = LocalDate.now(clock);

            CreditScoring.Input in = new CreditScoring.Input();
            in.product = (String) app.get("product");
            in.amount = (BigDecimal) app.get("requestedAmount");
            in.termMonths = num(app.get("termMonths")).intValue();
            in.repaymentMethod = (String) app.get("repaymentMethod");
            in.annualIncome = (BigDecimal) app.get("annualIncome");
            in.existingDebtAnnual = (BigDecimal) app.get("existingDebtAnnual");
            BigDecimal registered = num(query(c, "SELECT COALESCE(SUM(appraisal_value), 0) AS v FROM loan_collateral "
                    + "WHERE application_id = ? AND collateral_type IN ('REAL_ESTATE', 'DEPOSIT', 'SECURITIES')", applicationId)
                    .get(0).get("v"));
            // 登録済みの担保（不動産・預金・有価証券）の合計と、申込時の申告額の大きい方で担保掛目を計算する
            in.collateralValue = registered.max((BigDecimal) app.get("collateralValue"));
            in.relationshipYears = Period.between(LocalDateTime.parse(customer.getCreatedAt()).toLocalDate(), today).getYears();
            in.delinquentLoans = num(query(c, "SELECT COUNT(*) AS n FROM loan WHERE customer_id = ? AND status = 'DELINQUENT'",
                    customerId).get(0).get("n")).intValue();
            in.corporate = customer.isCorporate();
            if (!customer.isCorporate() && customer.getBirthDate() != null) {
                in.ageAtMaturity = Period.between(LocalDate.parse(customer.getBirthDate()), today.plusMonths(in.termMonths)).getYears();
            }
            CreditScoring.Result r = CreditScoring.evaluate(in);
            execute(c, "UPDATE loan_application SET status = 'SCREENED', score = ?, grade = ?, dti = ?, ltv = ?, "
                    + "proposed_rate = ?, recommendation = ?, screening_note = ?, screened_by = ?, screened_at = ? "
                    + "WHERE application_id = ?", r.score, r.grade, r.dti, r.ltv, r.rate, r.recommendation,
                    clip(String.join(" / ", r.reasons), 400), screenedBy, LocalDateTime.now(clock), applicationId);
            return applicationView(c, applicationId);
        });
    }

    /**
     * 承認・謝絶（役席、申込の受付者以外）。推奨が謝絶（DECLINE）の案件を承認する場合は理由（例外承認）が必要。
     *
     * @param rate 承認金利（null は審査の提示金利）
     */
    public Map<String, Object> decide(long applicationId, boolean approve, String note, BigDecimal rate, String decidedBy) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> app = lockApplication(c, applicationId);
            requireStatus(app, "SCREENED");
            // 受付者と決裁者を分け、1 人で申込から承認まで完結できないようにする（職務分離）
            if (decidedBy != null && decidedBy.equals(app.get("appliedBy"))) {
                throw new BankException(ErrorCode.SEGREGATION_OF_DUTIES);
            }
            if (approve && "DECLINE".equals(app.get("recommendation")) && (note == null || note.trim().isEmpty())) {
                throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "謝絶推奨の案件を承認する理由");
            }
            BigDecimal finalRate = rate != null ? rate : (BigDecimal) app.get("proposedRate");
            // 利息制限法の上限を超える金利では承認させない
            if (finalRate.signum() < 0 || finalRate.compareTo(new BigDecimal("15")) > 0) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            execute(c, "UPDATE loan_application SET status = ?, proposed_rate = ?, decided_by = ?, decided_at = ?, "
                    + "decision_note = ? WHERE application_id = ?", approve ? "APPROVED" : "REJECTED", finalRate,
                    decidedBy, LocalDateTime.now(clock), clip(note, 400), applicationId);
            return applicationView(c, applicationId);
        });
    }

    /**
     * 担保・保証を登録する（承認前後とも追加でき、追加後に再審査できる）。
     *
     * @param applicationId 申込番号
     * @param type          担保の種類
     * @param description   担保の内容
     * @param appraisal     評価額（null は 0）
     * @param lienRank      抵当権の順位（不動産のみ）
     * @param guarantorName 保証人名（保証のみ）
     * @param by            登録した行員
     * @return 申込の内容
     */
    public Map<String, Object> addCollateral(long applicationId, String type, String description, BigDecimal appraisal,
                                             Integer lienRank, String guarantorName, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> app = lockApplication(c, applicationId);
            requireStatus(app, "APPLIED", "SCREENED", "APPROVED");
            insertReturningId(c, "INSERT INTO loan_collateral (application_id, collateral_type, description, appraisal_value, "
                    + "lien_rank, guarantor_name, registered_by, registered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "collateral_id", applicationId, type, description, appraisal == null ? BigDecimal.ZERO : appraisal,
                    lienRank, guarantorName, by, LocalDateTime.now(clock));
            return applicationView(c, applicationId);
        });
    }

    /**
     * シンジケートローンの参加行を登録する。
     *
     * @param applicationId 申込番号
     * @param lenderName    参加行の名称
     * @param share         分担額
     * @param role          役割（ARRANGER / PARTICIPANT）
     * @return 申込の内容
     */
    public Map<String, Object> addParticipant(long applicationId, String lenderName, BigDecimal share, String role) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> app = lockApplication(c, applicationId);
            if (!"SYNDICATED".equals(app.get("product"))) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "シンジケートローンのみ");
            requireStatus(app, "APPLIED", "SCREENED", "APPROVED");
            execute(c, "INSERT INTO syndicate_participant (application_id, lender_name, share_amount, role) VALUES (?, ?, ?, ?)",
                    applicationId, lenderName, share, role);
            return applicationView(c, applicationId);
        });
    }

    // ===================== contract / execution =====================

    /** 契約締結（承認済みの申込から貸付を作る）。 */
    public Map<String, Object> contract(long applicationId, String repaymentAccountNo, int paymentDay, String by) {
        if (paymentDay < 1 || paymentDay > 28) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> app = lockApplication(c, applicationId);
            requireStatus(app, "APPROVED");
            String product = (String) app.get("product");
            String customerId = (String) app.get("customerId");
            // ロック順は「申込・融資の行 → 口座の行」で統一している。振込は口座の行だけをロックするので、この順序ならデッドロックしない
            Account account = BankService.lockActive(c, accounts, repaymentAccountNo);
            if (!customerId.equals(account.getCustomerId())) throw new BankException(ErrorCode.NOT_OWN_ACCOUNT, repaymentAccountNo);
            BankService.requireYenTransactional(account);
            if ("NOTICE".equals(account.getAccountType())) throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, repaymentAccountNo);
            if ("OVERDRAFT".equals(product) && !"CHECKING".equals(account.getAccountType())) {
                throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "当座貸越は当座預金口座");
            }
            if ("HOUSING".equals(product) && query(c, "SELECT 1 FROM loan_collateral WHERE application_id = ? "
                    + "AND collateral_type = 'REAL_ESTATE'", applicationId).isEmpty()) {
                throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "住宅ローンは不動産担保の設定");
            }
            if ("SYNDICATED".equals(product)) {
                BigDecimal sum = num(query(c, "SELECT COALESCE(SUM(share_amount), 0) AS v FROM syndicate_participant "
                        + "WHERE application_id = ?", applicationId).get(0).get("v"));
                BigDecimal ours = num(query(c, "SELECT COALESCE(SUM(share_amount), 0) AS v FROM syndicate_participant "
                        + "WHERE application_id = ? AND lender_name = ?", applicationId, OUR_BANK).get(0).get("v"));
                // 参加行の分担額の合計が組成総額と一致し、当行分が申込額と一致していなければ契約できない
                if (sum.compareTo((BigDecimal) app.get("syndicateTotal")) != 0
                        || ours.compareTo((BigDecimal) app.get("requestedAmount")) != 0) {
                    throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "参加行の合計 = 組成総額、当行分 = 申込額");
                }
            }
            boolean revolving = "REVOLVING".equals(app.get("repaymentMethod"));
            BigDecimal amount = (BigDecimal) app.get("requestedAmount");
            // リボルビング（カードローン・当座貸越）は元本 0・極度額あり、証書貸付は元本 = 申込額・極度額なしで作る
            long loanId = insertReturningId(c, "INSERT INTO loan (application_id, customer_id, product, principal, outstanding, "
                    + "credit_limit, annual_rate, term_months, repayment_method, repayment_account_no, payment_day, grade, "
                    + "contract_date, status, contracted_by) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 'CONTRACTED', ?)",
                    "loan_id", applicationId, customerId, product, revolving ? BigDecimal.ZERO : amount,
                    revolving ? amount : null, app.get("proposedRate"), app.get("termMonths"), app.get("repaymentMethod"),
                    repaymentAccountNo, paymentDay, app.get("grade"), LocalDate.now(clock), by);
            execute(c, "UPDATE loan_application SET status = 'CONTRACTED' WHERE application_id = ?", applicationId);
            return loanView(c, loanId);
        });
    }

    /** 融資実行: 証書貸付は返済用口座へ入金して返済予定表を作る。カードローンは枠の開始、当座貸越は極度額の設定。 */
    public Map<String, Object> disburse(long loanId, String by, String idempotencyKey) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            // 融資実行は資金が動くため冪等キー必須。二重送信で貸付金が 2 回入金されるのを防ぐ
            BankService.registerRequest(c, idempotencyKey, "LOAN_EXEC", now, requests);
            Map<String, Object> loan = lockLoan(c, loanId);
            requireStatus(loan, "CONTRACTED");
            String product = (String) loan.get("product");
            String accountNo = (String) loan.get("repaymentAccountNo");
            Account account = BankService.lockActive(c, accounts, accountNo);

            if ("CARD".equals(product)) {
                execute(c, "UPDATE loan SET status = 'ACTIVE', start_date = ?, maturity_date = ?, last_accrual_date = ?, "
                        + "executed_by = ?, executed_at = ? WHERE loan_id = ?", today, today.plusMonths(12), today, by, now, loanId);
            } else if ("OVERDRAFT".equals(product)) {
                // 当座貸越は資金を動かさず、口座にマイナス残高を許す極度額を設定するだけ
                accounts.setOverdraftLimit(c, accountNo, (BigDecimal) loan.get("creditLimit"), now);
                execute(c, "UPDATE loan SET status = 'ACTIVE', start_date = ?, maturity_date = ?, executed_by = ?, "
                        + "executed_at = ? WHERE loan_id = ?", today, today.plusMonths(12), by, now, loanId);
            } else {
                BigDecimal principal = (BigDecimal) loan.get("principal");
                BigDecimal rate = (BigDecimal) loan.get("annualRate");
                int term = num(loan.get("termMonths")).intValue();
                int day = num(loan.get("paymentDay")).intValue();
                LocalDate maturity = null;
                // 返済予定表を全回分作ってから入金する（予定表と貸付残高が同じトランザクションで確定する）
                for (LoanMath.Installment i : LoanMath.schedule(principal, rate, term, (String) loan.get("repaymentMethod"))) {
                    LocalDate due = dueDate(c, today, i.no, day);
                    maturity = due;
                    execute(c, "INSERT INTO loan_schedule (loan_id, installment_no, due_date, principal_due, interest_due) "
                            + "VALUES (?, ?, ?, ?, ?)", loanId, i.no, due, i.principal, i.interest);
                }
                BigDecimal balance = account.getBalance().add(principal);
                accounts.updateBalance(c, accountNo, balance, now, true);
                transactions.insert(c, accountNo, TxType.LOAN_DISB, principal, balance, null,
                        productLabel(product) + " 融資実行 No." + loanId, idempotencyKey, now);
                execute(c, "UPDATE loan SET status = 'ACTIVE', outstanding = ?, start_date = ?, maturity_date = ?, "
                        + "executed_by = ?, executed_at = ? WHERE loan_id = ?", principal, today, maturity, by, now, loanId);
                // 融資側の取引履歴（loan_transaction）にも記録し、口座の明細と貸付残高の両方から追えるようにする
                loanTx(c, loanId, "DISBURSE", principal, BigDecimal.ZERO, BigDecimal.ZERO, principal, accountNo, "融資実行", by, now);
            }
            execute(c, "UPDATE loan_application SET status = 'EXECUTED' WHERE application_id = ?", loan.get("applicationId"));
            return loanView(c, loanId);
        });
    }

    /** k 回目の約定返済日（毎月の返済日、休業日は翌営業日）。 */
    static LocalDate dueDate(Connection c, LocalDate start, int k, int paymentDay) throws SQLException {
        LocalDate month = start.plusMonths(k);
        // 月の日数より大きい返済日は月末に寄せる（現状は契約時に 28 日までに制限している）
        LocalDate d = month.withDayOfMonth(Math.min(paymentDay, month.lengthOfMonth()));
        return BusinessCalendar.onOrAfter(c, d);
    }

    // ===================== repayment =====================

    /** カードローンの借入（極度額の範囲で返済用口座へ入金）。 */
    public Map<String, Object> draw(long loanId, BigDecimal amount, String by, String idempotencyKey) {
        BankService.checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "LOAN_DRAW", now, requests);
            Map<String, Object> loan = lockLoan(c, loanId);
            if (!"CARD".equals(loan.get("product"))) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "カードローンのみ");
            requireStatus(loan, "ACTIVE");
            // 残高が変わる前に、それまでの残高で日割利息を計上しておく（変更後の残高で過去分を計算しない）
            accrue(c, loan, now.toLocalDate());
            BigDecimal outstanding = (BigDecimal) loan.get("outstanding");
            if (outstanding.add(amount).compareTo((BigDecimal) loan.get("creditLimit")) > 0) {
                throw new BankException(ErrorCode.CREDIT_LIMIT_EXCEEDED);
            }
            String accountNo = (String) loan.get("repaymentAccountNo");
            Account account = BankService.lockActive(c, accounts, accountNo);
            BigDecimal balance = account.getBalance().add(amount);
            accounts.updateBalance(c, accountNo, balance, now, true);
            transactions.insert(c, accountNo, TxType.LOAN_DISB, amount, balance, null, "カードローン借入 No." + loanId,
                    idempotencyKey, now);
            BigDecimal after = outstanding.add(amount);
            execute(c, "UPDATE loan SET outstanding = ? WHERE loan_id = ?", after, loanId);
            loanTx(c, loanId, "DRAW", amount, BigDecimal.ZERO, BigDecimal.ZERO, after, accountNo, "借入", by, now);
            return loanView(c, loanId);
        });
    }

    /**
     * 返済（返済用口座から引落し）。証書貸付は期日到来分（延滞分を含む）に、遅延損害金 → 利息 → 元金の順に充当する。
     * カードローンは利息 → 元金。
     */
    public Map<String, Object> repay(long loanId, BigDecimal amount, String by, String idempotencyKey) {
        BankService.checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "LOAN_REPAY", now, requests);
            Map<String, Object> loan = lockLoan(c, loanId);
            requireStatus(loan, "ACTIVE", "DELINQUENT");
            String accountNo = (String) loan.get("repaymentAccountNo");
            Account account = BankService.lockActive(c, accounts, accountNo);
            // 返済は口座残高の範囲内で行う（当座貸越の枠を使って返済させない）
            BigDecimal available = account.getBalance().max(BigDecimal.ZERO).min(amount);
            if (available.compareTo(amount) < 0) throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, accountNo);
            BigDecimal[] applied = "CARD".equals(loan.get("product"))
                    ? applyRevolving(c, loan, amount, now)
                    : applyToInstallments(c, loan, amount, now.toLocalDate(), now);
            BigDecimal total = applied[0].add(applied[1]).add(applied[2]);
            if (total.signum() == 0) throw new BankException(ErrorCode.LOAN_STATE_INVALID, "期日の到来した返済はありません（繰上返済をご利用ください）");
            debitForLoan(c, account, total, loanId, "返済", idempotencyKey, now);
            finishPayment(c, loanId, applied, accountNo, "返済", by, now);
            return loanView(c, loanId);
        });
    }

    /** 繰上返済（一部）: 元金を減らし、残りの回数はそのままで返済額を再計算する（返済額軽減型）。延滞中は不可。 */
    public Map<String, Object> prepay(long loanId, BigDecimal amount, String by, String idempotencyKey) {
        BankService.checkAmount(amount);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "LOAN_PREPAY", now, requests);
            Map<String, Object> loan = lockLoan(c, loanId);
            requireStatus(loan, "ACTIVE");
            // リボルビングは随時返済で元金が減るため、繰上返済の手続きは証書貸付だけ
            if ("REVOLVING".equals(loan.get("repaymentMethod"))) throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "証書貸付のみ");
            BigDecimal outstanding = (BigDecimal) loan.get("outstanding");
            if (amount.compareTo(outstanding) > 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
            if (!query(c, "SELECT 1 FROM loan_schedule WHERE loan_id = ? AND status IN ('OVERDUE', 'PARTIAL') "
                    + "OR (loan_id = ? AND status = 'SCHEDULED' AND due_date <= ?)", loanId, loanId, now.toLocalDate()).isEmpty()) {
                throw new BankException(ErrorCode.LOAN_STATE_INVALID, "期日到来分を先に返済してください");
            }
            String accountNo = (String) loan.get("repaymentAccountNo");
            Account account = BankService.lockActive(c, accounts, accountNo);
            debitForLoan(c, account, amount, loanId, "繰上返済", idempotencyKey, now);
            BigDecimal after = outstanding.subtract(amount);
            List<Map<String, Object>> remaining = query(c, "SELECT installment_no FROM loan_schedule WHERE loan_id = ? "
                    + "AND status = 'SCHEDULED' ORDER BY installment_no", loanId);
            if (after.signum() == 0) {
                execute(c, "UPDATE loan_schedule SET status = 'CANCELLED' WHERE loan_id = ? AND status = 'SCHEDULED'", loanId);
            } else {
                // 残りの回数はそのままで、減った元金から返済予定を作り直す（返済額軽減型）
                List<LoanMath.Installment> plan = LoanMath.schedule(after, (BigDecimal) loan.get("annualRate"),
                        remaining.size(), (String) loan.get("repaymentMethod"));
                for (int i = 0; i < remaining.size(); i++) {
                    execute(c, "UPDATE loan_schedule SET principal_due = ?, interest_due = ? WHERE loan_id = ? "
                            + "AND installment_no = ?", plan.get(i).principal, plan.get(i).interest, loanId,
                            remaining.get(i).get("installmentNo"));
                }
            }
            execute(c, "UPDATE loan SET outstanding = ? WHERE loan_id = ?", after, loanId);
            loanTx(c, loanId, "PREPAY", amount, BigDecimal.ZERO, BigDecimal.ZERO, after, accountNo, "繰上返済（返済額軽減）", by, now);
            if (after.signum() == 0) closeLoan(c, loanId, now);
            return loanView(c, loanId);
        });
    }

    /** 期日到来分への充当。戻り値 [元金, 利息, 遅延損害金]。 */
    private BigDecimal[] applyToInstallments(Connection c, Map<String, Object> loan, BigDecimal funds, LocalDate today,
                                             LocalDateTime now) throws SQLException {
        long loanId = num(loan.get("loanId")).longValue();
        BigDecimal remaining = funds;
        BigDecimal pSum = BigDecimal.ZERO;
        BigDecimal iSum = BigDecimal.ZERO;
        BigDecimal fSum = BigDecimal.ZERO;
        for (Map<String, Object> s : query(c, "SELECT * FROM loan_schedule WHERE loan_id = ? AND status IN "
                + "('SCHEDULED', 'PARTIAL', 'OVERDUE') AND due_date <= ? ORDER BY installment_no", loanId, today)) {
            if (remaining.signum() <= 0) break;
            BigDecimal pDue = num(s.get("principalDue")).subtract(num(s.get("principalPaid")));
            BigDecimal iDue = num(s.get("interestDue")).subtract(num(s.get("interestPaid")));
            long daysLate = ChronoUnit.DAYS.between(LocalDate.parse((String) s.get("dueDate")), today);
            // 充当順は 遅延損害金 → 利息 → 元金（古い回から）。民法・約款上の一般的な順序に合わせる
            BigDecimal fee = LoanMath.lateFee(pDue.add(iDue), daysLate);
            BigDecimal payFee = fee.min(remaining);
            remaining = remaining.subtract(payFee);
            BigDecimal payI = iDue.min(remaining);
            remaining = remaining.subtract(payI);
            BigDecimal payP = pDue.min(remaining);
            remaining = remaining.subtract(payP);
            boolean done = payP.compareTo(pDue) == 0 && payI.compareTo(iDue) == 0;
            execute(c, "UPDATE loan_schedule SET principal_paid = principal_paid + ?, interest_paid = interest_paid + ?, "
                    + "late_fee_paid = late_fee_paid + ?, status = ?, paid_at = ? WHERE loan_id = ? AND installment_no = ?",
                    payP, payI, payFee, done ? "PAID" : "PARTIAL", now, loanId, s.get("installmentNo"));
            pSum = pSum.add(payP);
            iSum = iSum.add(payI);
            fSum = fSum.add(payFee);
        }
        return new BigDecimal[]{pSum, iSum, fSum};
    }

    /** カードローン: 日割利息を計上し、利息 → 元金の順に充当する。 */
    private BigDecimal[] applyRevolving(Connection c, Map<String, Object> loan, BigDecimal funds, LocalDateTime now)
            throws SQLException {
        accrue(c, loan, now.toLocalDate());
        long loanId = num(loan.get("loanId")).longValue();
        // 計上済み利息は小数で持っているので、請求は円未満を切り捨てた額にする（端数は次回へ持ち越し）
        BigDecimal accrued = num(loan.get("accruedInterest")).setScale(0, RoundingMode.DOWN);
        BigDecimal payI = accrued.min(funds);
        BigDecimal payP = ((BigDecimal) loan.get("outstanding")).min(funds.subtract(payI));
        execute(c, "UPDATE loan SET accrued_interest = accrued_interest - ? WHERE loan_id = ?", payI, loanId);
        return new BigDecimal[]{payP, payI, BigDecimal.ZERO};
    }

    // 返済用口座から引き落とし、明細に記録する（冪等キーは利用者操作のときだけ。夜間バッチは null）
    private void debitForLoan(Connection c, Account account, BigDecimal amount, long loanId, String label,
                              String key, LocalDateTime now) throws SQLException {
        BigDecimal balance = account.getBalance().subtract(amount);
        if (balance.signum() < 0) throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, account.getAccountNo());
        accounts.updateBalance(c, account.getAccountNo(), balance, now, true);
        transactions.insert(c, account.getAccountNo(), TxType.LOAN_REPAY, amount, balance, null,
                "融資" + label + " No." + loanId, key, now);
    }

    // 充当結果を貸付残高・融資取引に反映し、延滞状況を更新する。完済していれば融資を終了する
    private void finishPayment(Connection c, long loanId, BigDecimal[] applied, String accountNo, String memo, String by,
                               LocalDateTime now) throws SQLException {
        // 充当中に更新した行を読み直す（ロックは同じトランザクション内で取得済み）
        Map<String, Object> loan = lockLoan(c, loanId);
        BigDecimal after = ((BigDecimal) loan.get("outstanding")).subtract(applied[0]);
        execute(c, "UPDATE loan SET outstanding = ? WHERE loan_id = ?", after, loanId);
        loanTx(c, loanId, "REPAY", applied[0], applied[1], applied[2], after, accountNo, memo, by, now);
        refreshDelinquency(c, loanId, now.toLocalDate());
        if (!"REVOLVING".equals(loan.get("repaymentMethod")) && after.signum() == 0
                && query(c, "SELECT 1 FROM loan_schedule WHERE loan_id = ? AND status IN ('SCHEDULED', 'PARTIAL', 'OVERDUE')",
                loanId).isEmpty()) {
            closeLoan(c, loanId, now);
        }
    }

    // 完済: 延滞情報を消し、債務者区分を正常先に戻す
    private static void closeLoan(Connection c, long loanId, LocalDateTime now) throws SQLException {
        execute(c, "UPDATE loan SET status = 'PAID_OFF', borrower_class = 'NORMAL', days_past_due = 0, closed_at = ? "
                + "WHERE loan_id = ?", now, loanId);
        // 完済したので引当は全額戻し入れる
        LoanManagementService.provisionLoan(c, loanId, now.toLocalDate(), "完済", "SYSTEM", now);
    }

    /** カードローンの日割利息を基準日まで計上する。 */
    private void accrue(Connection c, Map<String, Object> loan, LocalDate asOf) throws SQLException {
        if (!"CARD".equals(loan.get("product")) || loan.get("lastAccrualDate") == null) return;
        LocalDate last = LocalDate.parse((String) loan.get("lastAccrualDate"));
        // 前回の計上日から基準日までの日数分だけ計上する（同じ日に何度呼ばれても二重に計上しない）
        long days = ChronoUnit.DAYS.between(last, asOf);
        if (days <= 0) return;
        BigDecimal add = LoanMath.dailyInterest((BigDecimal) loan.get("outstanding"), (BigDecimal) loan.get("annualRate"), days);
        execute(c, "UPDATE loan SET accrued_interest = accrued_interest + ?, last_accrual_date = ? WHERE loan_id = ?",
                add, asOf, loan.get("loanId"));
        // 呼び出し元が同じ Map を使い続けるため、DB と同じ値に更新しておく
        loan.put("accruedInterest", num(loan.get("accruedInterest")).add(add));
        loan.put("lastAccrualDate", asOf.toString());
    }

    // ===================== nightly batch / delinquency =====================

    /**
     * 夜間バッチ: 約定返済の口座引落し（残高の範囲で充当）、延滞日数・債務者区分の更新、カードローン利息の計上。
     *
     * @return 処理件数のまとめ
     */
    public Map<String, Object> processDue(LocalDate asOf) {
        // 対象の一覧だけ先に取り、処理は融資ごとの短いトランザクションで行う（1 件の失敗で全体を止めない）
        List<Map<String, Object>> loans = Transactions.readOnly(dataSource.get(), c -> query(c,
                "SELECT loan_id, product FROM loan WHERE status IN ('ACTIVE', 'DELINQUENT') ORDER BY loan_id"));
        int debited = 0;
        int delinquent = 0;
        for (Map<String, Object> l : loans) {
            long loanId = num(l.get("loanId")).longValue();
            int[] r = Transactions.inTransaction(dataSource.get(), c -> {
                LocalDateTime now = LocalDateTime.now(clock);
                Map<String, Object> loan = lockLoan(c, loanId);
                if ("CARD".equals(loan.get("product"))) {
                    accrue(c, loan, asOf);
                    return new int[]{0, 0};
                }
                if ("OVERDRAFT".equals(loan.get("product"))) return new int[]{0, 0};
                int paid = 0;
                String accountNo = (String) loan.get("repaymentAccountNo");
                // 休眠・凍結の口座からは引き落とさない（残高 0 とみなして延滞判定だけ行う）
                Account account = BankService.lockExisting(c, accounts, accountNo);
                BigDecimal funds = account.isActive() ? account.getBalance().max(BigDecimal.ZERO) : BigDecimal.ZERO;
                if (funds.signum() > 0) {
                    BigDecimal[] applied = applyToInstallments(c, loan, funds, asOf, now);
                    BigDecimal total = applied[0].add(applied[1]).add(applied[2]);
                    if (total.signum() > 0) {
                        debitForLoan(c, account, total, loanId, "約定返済（口座引落し）", null, now);
                        finishPayment(c, loanId, applied, accountNo, "約定返済（口座引落し）", "SYSTEM", now);
                        paid = 1;
                    }
                }
                int dpd = refreshDelinquency(c, loanId, asOf);
                return new int[]{paid, dpd > 0 ? 1 : 0};
            });
            debited += r[0];
            delinquent += r[1];
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", asOf.toString());
        m.put("loans", loans.size());
        m.put("autoDebited", debited);
        m.put("delinquent", delinquent);
        return m;
    }

    /** 延滞日数・債務者区分・状態を更新し、延滞日数を返す。 */
    private static int refreshDelinquency(Connection c, long loanId, LocalDate asOf) throws SQLException {
        Map<String, Object> loan = query(c, "SELECT status, borrower_class, product, restructured FROM loan WHERE loan_id = ?",
                loanId).get(0);
        if (!"ACTIVE".equals(loan.get("status")) && !"DELINQUENT".equals(loan.get("status"))) return 0;
        // 期日を過ぎても未払いの回を延滞にし、最も古い延滞の期日から延滞日数を数える
        execute(c, "UPDATE loan_schedule SET status = 'OVERDUE' WHERE loan_id = ? AND status IN ('SCHEDULED', 'PARTIAL') "
                + "AND due_date < ?", loanId, asOf);
        List<Map<String, Object>> oldest = query(c, "SELECT MIN(due_date) AS d FROM loan_schedule WHERE loan_id = ? "
                + "AND status = 'OVERDUE'", loanId);
        Object d = oldest.get(0).get("d");
        int dpd = d == null ? 0 : (int) ChronoUnit.DAYS.between(LocalDate.parse((String) d), asOf);
        // 破綻先は役席が認定した区分なので、延滞日数による自動判定で上書きしない
        String cls = "BANKRUPT".equals(loan.get("borrowerClass")) ? "BANKRUPT" : borrowerClass(dpd);
        // 条件を緩和した債権（貸出条件緩和債権）は延滞がなくても要管理先より良い区分にしない
        if (num(loan.get("restructured")).intValue() == 1) cls = LoanManagementService.worse(cls, "SPECIAL");
        execute(c, "UPDATE loan SET days_past_due = ?, borrower_class = ?, status = ? WHERE loan_id = ?",
                dpd, cls, dpd > 0 ? "DELINQUENT" : "ACTIVE", loanId);
        return dpd;
    }

    /** 延滞日数から債務者区分（簡略化した自己査定）。 */
    public static String borrowerClass(int daysPastDue) {
        if (daysPastDue <= 0) return "NORMAL";
        if (daysPastDue < 90) return "WATCH";
        if (daysPastDue < 180) return "SPECIAL";
        if (daysPastDue < 365) return "DOUBTFUL";
        return "DEFACTO";
    }

    /**
     * 督促・回収の記録を追加する。
     *
     * @param loanId       融資番号
     * @param type         督促の種類
     * @param note         内容
     * @param promisedDate 返済の約束日（任意）
     * @param by           記録した行員
     * @return 融資の内容
     */
    public Map<String, Object> addCollectionAction(long loanId, String type, String note, LocalDate promisedDate, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            // 存在確認を兼ねて融資行をロックする
            lockLoan(c, loanId);
            insertReturningId(c, "INSERT INTO collection_action (loan_id, action_type, note, promised_date, created_by, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", "action_id", loanId, type, note, promisedDate, by, LocalDateTime.now(clock));
            return loanView(c, loanId);
        });
    }

    /** 債権の償却（破綻懸念先以下・役席）。 */
    public Map<String, Object> writeOff(long loanId, String note, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Map<String, Object> loan = lockLoan(c, loanId);
            requireStatus(loan, "ACTIVE", "DELINQUENT");
            String cls = (String) loan.get("borrowerClass");
            if (!"DOUBTFUL".equals(cls) && !"DEFACTO".equals(cls) && !"BANKRUPT".equals(cls)) {
                throw new BankException(ErrorCode.LOAN_RULE_VIOLATION, "破綻懸念先以下の債権のみ");
            }
            BigDecimal outstanding = (BigDecimal) loan.get("outstanding");
            BigDecimal provision = num(loan.get("provisionAmount"));
            // 償却後は回収予定がなくなるため、未払いの返済予定を取り消す（償却額は融資取引に残る）
            execute(c, "UPDATE loan_schedule SET status = 'CANCELLED' WHERE loan_id = ? AND status <> 'PAID'", loanId);
            execute(c, "UPDATE loan SET outstanding = 0, status = 'WRITTEN_OFF', closed_at = ? WHERE loan_id = ?", now, loanId);
            // 引当済みの額は貸倒引当金の取崩し、足りない分は貸倒損失（摘要に内訳を残す）
            BigDecimal loss = outstanding.subtract(provision.min(outstanding));
            loanTx(c, loanId, "WRITE_OFF", outstanding, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                    clip("償却（引当取崩 " + provision.min(outstanding).toPlainString() + "・損失 " + loss.toPlainString() + "）: "
                            + (note == null ? "" : note), 100), by, now);
            LoanManagementService.provisionLoan(c, loanId, now.toLocalDate(), "償却", by, now);
            return loanView(c, loanId);
        });
    }

    /** 債務者区分の変更（破綻先の認定など、役席）。 */
    public Map<String, Object> setBorrowerClass(long loanId, String cls) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            lockLoan(c, loanId);
            execute(c, "UPDATE loan SET borrower_class = ? WHERE loan_id = ?", cls, loanId);
            return loanView(c, loanId);
        });
    }

    // ===================== queries =====================

    /**
     * 融資申込の一覧（新しい順に最大 200 件）。
     *
     * @param customerScope 顧客ログインなら本人の顧客番号、行員なら null
     * @param status        状態での絞り込み（null は全件）
     * @return 申込一覧
     */
    public List<Map<String, Object>> listApplications(String customerScope, String status) {
        List<Object> params = new ArrayList<>();
        String where = where("a", customerScope, status, params);
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT a.application_id, a.customer_id, c.name AS customer_name, "
                + "a.product, a.requested_amount, a.term_months, a.status, a.grade, a.score, a.recommendation, a.applied_at "
                + "FROM loan_application a JOIN customer c ON c.customer_id = a.customer_id" + where
                + " ORDER BY a.application_id DESC FETCH FIRST 200 ROWS ONLY", params.toArray()));
    }

    // alias と条件はコード内の固定値・バインド変数だけで組み立てる（利用者の入力を SQL に連結しない）
    private static String where(String alias, String customerScope, String status, List<Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (customerScope != null) {
            sb.append(" AND ").append(alias).append(".customer_id = ?");
            params.add(customerScope);
        }
        if (status != null) {
            sb.append(" AND ").append(alias).append(".status = ?");
            params.add(status);
        }
        return sb.toString();
    }

    /**
     * 融資の一覧（新しい順に最大 200 件）。
     *
     * @param customerScope 顧客ログインなら本人の顧客番号、行員なら null
     * @param status        状態での絞り込み（null は全件）
     * @return 融資一覧
     */
    public List<Map<String, Object>> listLoans(String customerScope, String status) {
        List<Object> params = new ArrayList<>();
        String where = where("l", customerScope, status, params);
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT l.loan_id, l.customer_id, c.name AS customer_name, "
                + "l.product, l.principal, l.outstanding, l.credit_limit, l.annual_rate, l.status, l.borrower_class, "
                + "l.days_past_due, l.grade, l.maturity_date, l.repayment_account_no FROM loan l "
                + "JOIN customer c ON c.customer_id = l.customer_id" + where
                + " ORDER BY l.loan_id DESC FETCH FIRST 200 ROWS ONLY", params.toArray()));
    }

    /**
     * 申込の詳細。
     *
     * @param applicationId 申込番号
     * @return 申込の内容（担保・参加行を含む）
     * @throws BankException 見つからない場合（LOAN_NOT_FOUND）
     */
    public Map<String, Object> getApplication(long applicationId) {
        return Transactions.readOnly(dataSource.get(), c -> applicationView(c, applicationId));
    }

    /**
     * 融資の詳細。
     *
     * @param loanId 融資番号
     * @return 融資の内容（返済予定表・延滞額・次回返済を含む）
     * @throws BankException 見つからない場合（LOAN_NOT_FOUND）
     */
    public Map<String, Object> getLoan(long loanId) {
        return Transactions.readOnly(dataSource.get(), c -> loanView(c, loanId));
    }

    /** 申込・融資の名義人（アクセス権の確認用）。 */
    public String applicationOwner(long applicationId) {
        return (String) getApplication(applicationId).get("customerId");
    }

    /**
     * @param loanId 融資番号
     * @return 借主の顧客番号（アクセス権の確認用）
     */
    public String loanOwner(long loanId) {
        return (String) getLoan(loanId).get("customerId");
    }

    // 申込の内容に担保・参加行・実行済みの融資番号を加えて返す
    private Map<String, Object> applicationView(Connection c, long id) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT a.*, c.name AS customer_name, c.customer_type FROM loan_application a "
                + "JOIN customer c ON c.customer_id = a.customer_id WHERE a.application_id = ?", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(id));
        Map<String, Object> m = rows.get(0);
        m.put("collaterals", query(c, "SELECT * FROM loan_collateral WHERE application_id = ? ORDER BY collateral_id", id));
        m.put("participants", query(c, "SELECT * FROM syndicate_participant WHERE application_id = ? ORDER BY share_amount DESC", id));
        List<Map<String, Object>> loans = query(c, "SELECT loan_id FROM loan WHERE application_id = ?", id);
        m.put("loanId", loans.isEmpty() ? null : loans.get(0).get("loanId"));
        return m;
    }

    // 融資の内容に返済予定表・延滞額・次回返済などの表示用の項目を加えて返す
    private Map<String, Object> loanView(Connection c, long loanId) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT l.*, c.name AS customer_name FROM loan l "
                + "JOIN customer c ON c.customer_id = l.customer_id WHERE l.loan_id = ?", loanId);
        if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(loanId));
        Map<String, Object> m = rows.get(0);
        Object appId = m.get("applicationId");
        List<Map<String, Object>> schedule = query(c, "SELECT * FROM loan_schedule WHERE loan_id = ? ORDER BY installment_no", loanId);
        m.put("schedule", schedule);
        m.put("transactions", query(c, "SELECT * FROM loan_transaction WHERE loan_id = ? ORDER BY loan_tx_id DESC", loanId));
        m.put("collaterals", query(c, "SELECT * FROM loan_collateral WHERE application_id = ?", appId));
        m.put("participants", query(c, "SELECT * FROM syndicate_participant WHERE application_id = ? ORDER BY share_amount DESC", appId));
        m.put("collectionActions", query(c, "SELECT * FROM collection_action WHERE loan_id = ? ORDER BY action_id DESC", loanId));
        BigDecimal overdue = BigDecimal.ZERO;
        LocalDate today = LocalDate.now(clock);
        Map<String, Object> next = null;
        for (Map<String, Object> s : schedule) {
            String st = (String) s.get("status");
            BigDecimal unpaid = num(s.get("principalDue")).add(num(s.get("interestDue")))
                    .subtract(num(s.get("principalPaid"))).subtract(num(s.get("interestPaid")));
            if ("OVERDUE".equals(st) || (!"PAID".equals(st) && !"CANCELLED".equals(st)
                    && !LocalDate.parse((String) s.get("dueDate")).isAfter(today))) {
                overdue = overdue.add(unpaid);
            } else if (next == null && ("SCHEDULED".equals(st) || "PARTIAL".equals(st))) {
                next = s;
            }
        }
        m.put("dueNow", overdue);
        m.put("nextInstallment", next);
        if ("OVERDRAFT".equals(m.get("product"))) {
            List<Map<String, Object>> acc = query(c, "SELECT balance FROM account WHERE account_no = ?", m.get("repaymentAccountNo"));
            BigDecimal bal = acc.isEmpty() ? BigDecimal.ZERO : num(acc.get(0).get("balance"));
            m.put("outstanding", bal.negate().max(BigDecimal.ZERO));
        }
        return m;
    }

    // 申込行を FOR UPDATE で取得する（状態遷移を直列化する）
    private static Map<String, Object> lockApplication(Connection c, long id) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM loan_application WHERE application_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(id));
        return rows.get(0);
    }

    // 融資行を FOR UPDATE で取得する（返済・借入・夜間バッチが同じ融資を同時に更新しないように）
    private static Map<String, Object> lockLoan(Connection c, long id) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM loan WHERE loan_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.LOAN_NOT_FOUND, String.valueOf(id));
        return rows.get(0);
    }

    // 許可された状態でなければ LOAN_STATE_INVALID（状態遷移の誤りを防ぐ）
    private static void requireStatus(Map<String, Object> row, String... allowed) {
        Object st = row.get("status");
        for (String a : allowed) if (a.equals(st)) return;
        throw new BankException(ErrorCode.LOAN_STATE_INVALID, String.valueOf(st));
    }

    // 融資側の取引履歴を 1 件追加する
    private static void loanTx(Connection c, long loanId, String type, BigDecimal principal, BigDecimal interest,
                               BigDecimal lateFee, BigDecimal outstandingAfter, String accountNo, String memo, String by,
                               LocalDateTime now) throws SQLException {
        insertReturningId(c, "INSERT INTO loan_transaction (loan_id, tx_type, principal, interest, late_fee, outstanding_after, "
                + "account_no, memo, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "loan_tx_id",
                loanId, type, principal, interest, lateFee, outstandingAfter, accountNo, memo, by, now);
    }

    // 商品コードの表示名（明細の摘要に使う）
    static String productLabel(String product) {
        switch (product) {
            case "HOUSING": return "住宅ローン";
            case "BUSINESS": return "事業融資";
            case "CARD": return "カードローン";
            case "OVERDRAFT": return "当座貸越";
            case "SYNDICATED": return "シンジケートローン";
            default: return product;
        }
    }

    // Map から取り出した数値を BigDecimal にそろえる（DB によって型が違っても扱えるように。null は 0）
    static BigDecimal num(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        return new BigDecimal(v.toString());
    }

    // 列長を超える値を切り詰める
    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // 現在は使っていない（金利の表示形式を変える場合のために残している）
    @SuppressWarnings("unused")
    private static BigDecimal percent(BigDecimal v) { return v.multiply(HUNDRED); }
}
