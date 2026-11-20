package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AccountLifecycleService;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;

/**
 * GET  /api/dormancy?asOf=2026-11-20   (method="report") — 休眠管理の対象一覧（未来日も指定可）
 * POST /api/dormancy-run {"asOf":...}  (method="run")    — 休眠前通知の記録・休眠化（本日以前）
 */
public class DormancyAction extends BaseApiAction {
    private final AccountLifecycleService lifecycle;
    private String asOf;
    private LocalDate asOfDate;

    public DormancyAction() { this(AccountLifecycleService.getDefault()); }

    public DormancyAction(AccountLifecycleService lifecycle) {
        super(BankService.getDefault());
        this.lifecycle = lifecycle;
    }

    @Override
    public void validate() {
        asOfDate = InputRules.toDate(InputRules.trim(asOf));
        if (InputRules.emptyToNull(asOf) != null && !InputRules.isReasonableDate(asOfDate)) {
            addFieldError("asOf", getText("validation.date"));
        }
    }

    public String report() {
        return ok(lifecycle.dormancyReport(asOfDate));
    }

    public String run() {
        try {
            return ok(lifecycle.runDormancy(asOfDate));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() { return asOf == null ? "today" : asOf; }

    @StrutsParameter public void setAsOf(String asOf) { this.asOf = asOf; }
}
