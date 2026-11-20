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

    public InterestAction() { this(InterestService.getDefault()); }

    public InterestAction(InterestService interest) {
        super(BankService.getDefault());
        this.interest = interest;
    }

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

    public String settle() {
        try {
            return ok(accountNo == null ? interest.settleAll(asOfDate) : interest.settle(accountNo, asOfDate));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() { return (accountNo == null ? "ALL" : accountNo) + (asOf == null ? "" : " asOf=" + asOf); }

    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    @StrutsParameter public void setAsOf(String asOf) { this.asOf = asOf; }
}
