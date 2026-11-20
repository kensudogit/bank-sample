package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.LoanService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;

/**
 * 融資 API（役割は struts.xml。お客さまは本人の申込・融資のみ）。
 * GET  /api/loan-applications?status=          GET /api/loan-application?applicationId=
 * POST /api/loan-apply {customerId, product, amount, termMonths, repaymentMethod, purpose, annualIncome,
 *                       existingDebtAnnual, collateralValue, syndicateTotal}
 * POST /api/loan-screen {applicationId}                      （行員）
 * POST /api/loan-decide {applicationId, decision, note, rate}（役席）
 * POST /api/loan-collateral {applicationId, collateralType, description, appraisalValue, lienRank, guarantorName}
 * POST /api/loan-participant {applicationId, lenderName, shareAmount, role}
 * POST /api/loan-contract {applicationId, repaymentAccountNo, paymentDay}
 * POST /api/loan-disburse {loanId, idempotencyKey}           （行員）融資実行
 * GET  /api/loans?status=                     GET /api/loan?loanId=
 * POST /api/loan-draw / loan-repay / loan-prepay {loanId, amount, idempotencyKey}
 * POST /api/loan-collection {loanId, actionType, note, promisedDate}
 * POST /api/loan-writeoff {loanId, note} / loan-class {loanId, borrowerClass}   （役席）
 * POST /api/loan-process-due {asOf}                          （役席）約定返済・延滞判定の手動実行
 */
public class LoanAction extends BaseApiAction {
    private final LoanService loans;
    private String applicationId;
    private String loanId;
    private String customerId;
    private String product;
    private String amount;
    private String termMonths;
    private String repaymentMethod;
    private String purpose;
    private String annualIncome;
    private String existingDebtAnnual;
    private String collateralValue;
    private String syndicateTotal;
    private String decision;
    private String note;
    private String rate;
    private String collateralType;
    private String description;
    private String appraisalValue;
    private String lienRank;
    private String guarantorName;
    private String lenderName;
    private String shareAmount;
    private String role;
    private String repaymentAccountNo;
    private String paymentDay;
    private String actionType;
    private String promisedDate;
    private String borrowerClass;
    private String status;
    private String asOf;
    // 1 つの Action で多数の API を扱うため、入力は文字列で受け取り、各メソッドで必要な項目だけを検証・変換する
    private String idempotencyKey;

    /** Struts が使う既定コンストラクタ。 */
    public LoanAction() { this(LoanService.getDefault()); }

    /** @param loans 融資サービス（テスト用） */
    public LoanAction(LoanService loans) {
        super(BankService.getDefault());
        this.loans = loans;
    }

    // 状態での絞り込み（任意）。許可された状態名以外は 400 にする
    private String status() {
        return status == null || status.trim().isEmpty() ? null
                : oneOf(status.trim(), "APPLIED", "SCREENED", "APPROVED", "REJECTED", "CONTRACTED", "EXECUTED", "WITHDRAWN",
                        "ACTIVE", "DELINQUENT", "PAID_OFF", "WRITTEN_OFF");
    }

    /**
     * 融資申込の一覧（顧客は本人の申込のみ）。
     *
     * @return success、または業務エラーの結果名
     */
    public String applications() {
        try {
            return ok(loans.listApplications(customerScope(), status()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 融資申込の詳細。
     *
     * @return success、本人以外の申込なら notFound
     */
    public String application() {
        try {
            long appId = id(applicationId);
            java.util.Map<String, Object> app = loans.getApplication(appId);
            // 取得後に所有者を確かめ、他人の申込は「見つかりません」として存在を隠す
            requireOwner((String) app.get("customerId"), ErrorCode.LOAN_NOT_FOUND, applicationId);
            return ok(app);
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 融資を申し込む。
     *
     * @return success、または業務エラーの結果名
     */
    public String apply() {
        try {
            LoanService.Application a = new LoanService.Application();
            // 顧客ログインでは入力に関係なく本人の顧客番号で申し込む（他人名義の申込を防ぐ）
            a.customerId = ownCustomerOr(required(customerId, 10));
            a.product = oneOf(product, "HOUSING", "BUSINESS", "CARD", "OVERDRAFT", "SYNDICATED");
            a.amount = yen(amount);
            // カードローン・当座貸越は極度額の範囲で借りて返す「リボルビング」型。期間・返済方法は固定値にする
            boolean revolving = "CARD".equals(a.product) || "OVERDRAFT".equals(a.product);
            a.termMonths = revolving ? 12 : integer(termMonths, 1, 420);
            a.repaymentMethod = revolving ? "REVOLVING" : oneOf(repaymentMethod, "EQUAL_PAYMENT", "EQUAL_PRINCIPAL");
            a.purpose = text(purpose, 200);
            a.annualIncome = yen(annualIncome);
            BigDecimal debt = yen(existingDebtAnnual);
            a.existingDebtAnnual = debt == null ? BigDecimal.ZERO : debt;
            BigDecimal coll = yen(collateralValue);
            a.collateralValue = coll == null ? BigDecimal.ZERO : coll;
            a.syndicateTotal = yen(syndicateTotal);
            if (a.amount == null || a.annualIncome == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(loans.apply(a, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 申込を審査する（スコアリングと総量規制・返済比率の確認。行員）。
     *
     * @return success、または業務エラーの結果名
     */
    public String screen() {
        try {
            return ok(loans.screen(id(applicationId), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 審査結果を承認・否決する（役席。審査した行員とは別の人が決裁する）。
     *
     * @return success、または業務エラーの結果名
     */
    public String decide() {
        try {
            boolean approve = "APPROVE".equals(oneOf(decision, "APPROVE", "REJECT"));
            return ok(loans.decide(id(applicationId), approve, text(note, 400), decimal(rate), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 担保・保証を登録する。
     *
     * @return success、または業務エラーの結果名
     */
    public String collateral() {
        try {
            BigDecimal appraisal = yen(appraisalValue);
            Integer rank = lienRank == null || lienRank.trim().isEmpty() ? null : integer(lienRank, 1, 9);
            return ok(loans.addCollateral(id(applicationId),
                    oneOf(collateralType, "REAL_ESTATE", "DEPOSIT", "SECURITIES", "GUARANTOR", "GUARANTEE_CO"),
                    required(description, 200), appraisal, rank, text(guarantorName, 100), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * シンジケートローンの参加行を登録する。
     *
     * @return success、または業務エラーの結果名
     */
    public String participant() {
        try {
            BigDecimal share = yen(shareAmount);
            if (share == null || share.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(loans.addParticipant(id(applicationId), required(lenderName, 100), share,
                    oneOf(role, "ARRANGER", "PARTICIPANT")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 承認済みの申込で契約を結ぶ（返済口座と約定返済日を決める）。
     *
     * @return success、または業務エラーの結果名
     */
    public String contract() {
        try {
            // 返済日は 28 日まで: どの月にも存在する日に限り、月末の扱いの違いをなくす
            return ok(loans.contract(id(applicationId), accountNo(repaymentAccountNo), integer(paymentDay, 1, 28), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 融資を実行し、貸付金を返済口座へ入金する（冪等キーで二重実行を防ぐ）。
     *
     * @return success、または業務エラーの結果名
     */
    public String disburse() {
        try {
            return ok(loans.disburse(id(loanId), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 融資（実行済み）の一覧（顧客は本人の融資のみ）。
     *
     * @return success、または業務エラーの結果名
     */
    public String loans() {
        try {
            return ok(loans.listLoans(customerScope(), status()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 融資の詳細（返済予定表を含む）。
     *
     * @return success、本人以外の融資なら notFound
     */
    public String loan() {
        try {
            java.util.Map<String, Object> l = loans.getLoan(id(loanId));
            requireOwner((String) l.get("customerId"), ErrorCode.LOAN_NOT_FOUND, loanId);
            return ok(l);
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 顧客ログインなら本人の融資であることを確かめてから融資番号を返す（借入・返済の前に必ず通す）
    private long ownLoan() {
        long idValue = id(loanId);
        if (isCustomerUser()) requireOwner(loans.loanOwner(idValue), ErrorCode.LOAN_NOT_FOUND, loanId);
        return idValue;
    }

    // 借入・返済の金額は 1 円以上（0 円の取引で明細を増やさない）
    private BigDecimal positiveAmount() {
        BigDecimal a = yen(amount);
        if (a == null || a.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return a;
    }

    /**
     * 極度型（カードローン・当座貸越）で借り入れる。
     *
     * @return success、または業務エラーの結果名
     */
    public String draw() {
        try {
            return ok(loans.draw(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 約定外で返済する（随時返済）。
     *
     * @return success、または業務エラーの結果名
     */
    public String repay() {
        try {
            return ok(loans.repay(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 繰上返済する（元本を減らして返済予定を再計算する）。
     *
     * @return success、または業務エラーの結果名
     */
    public String prepay() {
        try {
            return ok(loans.prepay(ownLoan(), positiveAmount(), who(), key(idempotencyKey)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 延滞債権の督促・回収の記録を追加する。
     *
     * @return success、または業務エラーの結果名
     */
    public String collection() {
        try {
            return ok(loans.addCollectionAction(id(loanId), oneOf(actionType, "CALL", "LETTER", "VISIT", "PROMISE", "LEGAL"),
                    required(note, 400), date(promisedDate), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 回収不能と判断した融資を償却する（役席）。
     *
     * @return success、または業務エラーの結果名
     */
    public String writeOff() {
        try {
            return ok(loans.writeOff(id(loanId), required(note, 90), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 債務者区分（正常先〜破綻先）を変更する（役席）。
     *
     * @return success、または業務エラーの結果名
     */
    public String setClass() {
        try {
            return ok(loans.setBorrowerClass(id(loanId),
                    oneOf(borrowerClass, "NORMAL", "WATCH", "SPECIAL", "DOUBTFUL", "DEFACTO", "BANKRUPT")));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 約定返済の引落しと延滞判定を手動で実行する（役席）。
     *
     * @return success、未来日なら INVALID_BASE_DATE
     */
    public String processDue() {
        try {
            java.time.LocalDate d = date(asOf);
            java.time.LocalDate today = java.time.LocalDate.now();
            // 未来日で実行すると、まだ来ていない返済日の引落しが行われてしまうため拒否する
            if (d != null && d.isAfter(today)) throw new BankException(ErrorCode.INVALID_BASE_DATE);
            return ok(loans.processDue(d == null ? today : d));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 融資番号 → 申込番号 → 商品の順に、設定されている識別子で監査ログの対象を作る
    @Override
    public String getAuditTarget() {
        if (loanId != null) return "loan " + loanId + (amount == null ? "" : " " + amount);
        if (applicationId != null) return "application " + applicationId + (decision == null ? "" : " " + decision);
        return product == null ? null : product + " " + customerId + " " + amount;
    }

    // 以下は JSON ボディ・クエリから受け取る入力（すべて文字列。各 API で使う項目だけが設定される）
    /** @param v 申込番号 */
    @StrutsParameter public void setApplicationId(String v) { applicationId = v; }
    /** @param v 融資番号 */
    @StrutsParameter public void setLoanId(String v) { loanId = v; }
    /** @param v 顧客番号 */
    @StrutsParameter public void setCustomerId(String v) { customerId = v; }
    /** @param v 商品（HOUSING / BUSINESS / CARD / OVERDRAFT / SYNDICATED） */
    @StrutsParameter public void setProduct(String v) { product = v; }
    /** @param v 金額 */
    @StrutsParameter public void setAmount(String v) { amount = v; }
    /** @param v 期間（月数） */
    @StrutsParameter public void setTermMonths(String v) { termMonths = v; }
    /** @param v 返済方法（EQUAL_PAYMENT: 元利均等 / EQUAL_PRINCIPAL: 元金均等） */
    @StrutsParameter public void setRepaymentMethod(String v) { repaymentMethod = v; }
    /** @param v 資金使途 */
    @StrutsParameter public void setPurpose(String v) { purpose = v; }
    /** @param v 年収 */
    @StrutsParameter public void setAnnualIncome(String v) { annualIncome = v; }
    /** @param v 既存借入の年間返済額 */
    @StrutsParameter public void setExistingDebtAnnual(String v) { existingDebtAnnual = v; }
    /** @param v 担保評価額 */
    @StrutsParameter public void setCollateralValue(String v) { collateralValue = v; }
    /** @param v シンジケートローンの総額 */
    @StrutsParameter public void setSyndicateTotal(String v) { syndicateTotal = v; }
    /** @param v 決裁（APPROVE / REJECT） */
    @StrutsParameter public void setDecision(String v) { decision = v; }
    /** @param v メモ・理由 */
    @StrutsParameter public void setNote(String v) { note = v; }
    /** @param v 適用金利 */
    @StrutsParameter public void setRate(String v) { rate = v; }
    /** @param v 担保の種類 */
    @StrutsParameter public void setCollateralType(String v) { collateralType = v; }
    /** @param v 担保の内容 */
    @StrutsParameter public void setDescription(String v) { description = v; }
    /** @param v 担保の評価額 */
    @StrutsParameter public void setAppraisalValue(String v) { appraisalValue = v; }
    /** @param v 抵当権の順位 */
    @StrutsParameter public void setLienRank(String v) { lienRank = v; }
    /** @param v 保証人名 */
    @StrutsParameter public void setGuarantorName(String v) { guarantorName = v; }
    /** @param v 参加行の名称 */
    @StrutsParameter public void setLenderName(String v) { lenderName = v; }
    /** @param v 参加行の分担額 */
    @StrutsParameter public void setShareAmount(String v) { shareAmount = v; }
    /** @param v 参加行の役割（ARRANGER / PARTICIPANT） */
    @StrutsParameter public void setRole(String v) { role = v; }
    /** @param v 返済口座番号 */
    @StrutsParameter public void setRepaymentAccountNo(String v) { repaymentAccountNo = v; }
    /** @param v 約定返済日（1〜28） */
    @StrutsParameter public void setPaymentDay(String v) { paymentDay = v; }
    /** @param v 督促の種類 */
    @StrutsParameter public void setActionType(String v) { actionType = v; }
    /** @param v 返済の約束日 */
    @StrutsParameter public void setPromisedDate(String v) { promisedDate = v; }
    /** @param v 債務者区分 */
    @StrutsParameter public void setBorrowerClass(String v) { borrowerClass = v; }
    /** @param v 状態での絞り込み */
    @StrutsParameter public void setStatus(String v) { status = v; }
    /** @param v 基準日 */
    @StrutsParameter public void setAsOf(String v) { asOf = v; }
    /** @param v 冪等キー */
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }

    // 冪等性インターセプターと監査ログが参照するキー。空のときは「キーなし」として null を返す
    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
