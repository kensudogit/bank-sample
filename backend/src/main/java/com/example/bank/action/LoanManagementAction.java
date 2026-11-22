package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.LoanManagementService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;

/**
 * 融資管理の API（役割は struts.xml）。
 * GET  /api/loan-simulate?amount=&amp;rate=&amp;termMonths=&amp;repaymentMethod=&amp;paymentDay=  （ログイン中の全員）返済シミュレーション
 * GET  /api/loan-management?loanId=                       （行員）引当・条件変更・担保の再評価の履歴
 * POST /api/loan-provision-run {asOf?}                    （役席）貸倒引当金の見直し
 * GET  /api/loan-provision-summary                        （役席）引当の総額と区分別
 * POST /api/collateral-revalue {collateralId, appraisalValue, note}（役席）担保の再評価
 * POST /api/loan-reschedule {loanId, termMonths, rate?, note}       （役席）条件変更
 */
public class LoanManagementAction extends BaseApiAction {
    private final LoanManagementService management;
    private String amount;
    private String rate;
    private String termMonths;
    private String repaymentMethod;
    private String paymentDay;
    private String loanId;
    private String collateralId;
    private String appraisalValue;
    private String note;
    private String asOf;

    /** Struts が使う既定コンストラクタ。 */
    public LoanManagementAction() { this(LoanManagementService.getDefault()); }

    /** @param management 融資管理サービス */
    public LoanManagementAction(LoanManagementService management) {
        super(BankService.getDefault());
        this.management = management;
    }

    /** @return success、または業務エラーの結果名 */
    public String simulate() {
        try {
            BigDecimal principal = yen(amount);
            BigDecimal r = decimal(rate);
            if (principal == null || r == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            Integer day = paymentDay == null || paymentDay.trim().isEmpty() ? null : integer(paymentDay, 1, 28);
            return ok(management.simulate(principal, r, integer(termMonths, 1, LoanManagementService.MAX_MONTHS),
                    oneOf(repaymentMethod, "EQUAL_PAYMENT", "EQUAL_PRINCIPAL"), day));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String management() {
        try {
            return ok(management.management(id(loanId)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String provisionRun() {
        try {
            return ok(management.provisionAll(date(asOf), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（引当の総額と区分別の内訳） */
    public String provisionSummary() { return ok(management.provisionSummary()); }

    /** @return success、または業務エラーの結果名 */
    public String revalue() {
        try {
            BigDecimal value = yen(appraisalValue);
            if (value == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(management.revalueCollateral(id(collateralId), value, required(note, 200), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success、または業務エラーの結果名 */
    public String reschedule() {
        try {
            return ok(management.reschedule(id(loanId), integer(termMonths, 1, LoanManagementService.MAX_MONTHS), decimal(rate),
                    required(note, 200), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 融資・担保の番号と、変更内容（回数・金利・評価額）を監査ログに残す
    @Override
    public String getAuditTarget() {
        if (collateralId != null) return "collateral " + collateralId + " " + appraisalValue;
        if (loanId != null) return "loan " + loanId + (termMonths == null ? "" : " " + termMonths + "回 " + (rate == null ? "" : rate + "%"));
        return asOf;
    }

    /** @param v 借入額 */
    @StrutsParameter public void setAmount(String v) { amount = v; }
    /** @param v 年利（%） */
    @StrutsParameter public void setRate(String v) { rate = v; }
    /** @param v 返済回数 */
    @StrutsParameter public void setTermMonths(String v) { termMonths = v; }
    /** @param v 返済方法 */
    @StrutsParameter public void setRepaymentMethod(String v) { repaymentMethod = v; }
    /** @param v 毎月の返済日 */
    @StrutsParameter public void setPaymentDay(String v) { paymentDay = v; }
    /** @param v 融資番号 */
    @StrutsParameter public void setLoanId(String v) { loanId = v; }
    /** @param v 担保番号 */
    @StrutsParameter public void setCollateralId(String v) { collateralId = v; }
    /** @param v 新しい評価額 */
    @StrutsParameter public void setAppraisalValue(String v) { appraisalValue = v; }
    /** @param v 理由 */
    @StrutsParameter public void setNote(String v) { note = v; }
    /** @param v 基準日 */
    @StrutsParameter public void setAsOf(String v) { asOf = v; }
}
