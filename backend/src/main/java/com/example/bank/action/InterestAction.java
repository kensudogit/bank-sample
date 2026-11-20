package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import com.example.bank.service.InterestService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * GET  /api/interest-preview?accountNo=1000001&asOf=2026-12-01  (method="preview") — 利息・税の見込み計算
 * POST /api/interest-settle {"asOf":"2026-11-20","accountNo"(任意)}   (method="settle")  — 利息決算
 *   accountNo 省略時は解約済み以外の全口座。決算日の前日までを計算し、同じ期間は二重に決算しない。
 */
public class InterestAction extends BaseApiAction {
    private final InterestService interest;
    private String accountNo;
    private String asOf;
    private LocalDate asOfDate;

    /** Struts が使う既定コンストラクタ。 */
    public InterestAction() { this(InterestService.getDefault()); }

    /** @param interest 利息サービス（テスト用） */
    public InterestAction(InterestService interest) {
        super(BankService.getDefault());
        this.interest = interest;
    }

    /** 口座番号（任意）と基準日（任意）の形式を検証する。 */
    @Override
    public void validate() {
        accountNo = InputRules.emptyToNull(accountNo);
        if (accountNo != null && !InputRules.isAccountNo(accountNo)) {
            addFieldError("accountNo", getText("validation.accountNo"));
        }
        asOfDate = InputRules.toDate(InputRules.trim(asOf));
        if (InputRules.emptyToNull(asOf) != null && !InputRules.isReasonableDate(asOfDate)) {
            addFieldError("asOf", getText("validation.date"));
        }
    }

    /**
     * 利息・税の見込みを計算する（口座には入金しない）。
     *
     * @return success、口座番号がなければ input
     */
    public String preview() {
        if (accountNo == null) {
            addFieldError("accountNo", getText("validation.accountNo"));
            return INPUT;
        }
        try {
            requireAccountAccess(accountNo);
            return ok(interest.preview(accountNo, asOfDate));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 利息を決算して口座に入金する。口座番号を省略すると全口座が対象。
     *
     * @return success、または業務エラーの結果名
     */
    public String settle() {
        try {
            return ok(accountNo == null ? interest.settleAll(asOfDate) : interest.settle(accountNo, asOfDate));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（口座番号または ALL と基準日） */
    @Override
    public String getAuditTarget() { return (accountNo == null ? "ALL" : accountNo) + (asOf == null ? "" : " asOf=" + asOf); }

    /** @param accountNo 口座番号（任意） */
    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    /** @param asOf 基準日（yyyy-MM-dd、任意） */
    @StrutsParameter public void setAsOf(String asOf) { this.asOf = asOf; }
}
