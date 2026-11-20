package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AuditService;
import com.example.bank.service.AuthService;
import com.example.bank.service.BankService;
import com.example.bank.service.JobRunner;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理系 API（役割は struts.xml で制限）。
 * GET  /api/audit-log?from=&to=&userId=&action=&outcome=&page=&size=  (method="auditLog")     監査ログ検索
 * GET  /api/audit-verify                                              (method="auditVerify")  ハッシュチェーン検証
 * GET  /api/jobs                                                      (method="jobs")         バッチ実行履歴
 * POST /api/jobs-run {}                                               (method="runJobs")      期限の来たバッチを今すぐ実行
 * POST /api/customer-login-issue {"customerId"}                       (method="issueLogin")   顧客ログインの発行
 */
public class AdminAction extends BaseApiAction {
    private final AuditService audit;
    private final JobRunner jobs;
    private final AuthService auth;
    private String from;
    private String to;
    private String userId;
    private String auditAction;
    private String outcome;
    private String page;
    private String size;
    private String customerId;

    public AdminAction() { this(AuditService.getDefault(), JobRunner.getDefault(), AuthService.getDefault()); }

    public AdminAction(AuditService audit, JobRunner jobs, AuthService auth) {
        super(BankService.getDefault());
        this.audit = audit;
        this.jobs = jobs;
        this.auth = auth;
    }

    public String auditLog() {
        LocalDate fromDate = InputRules.toDate(InputRules.trim(from));
        LocalDate toDate = InputRules.toDate(InputRules.trim(to));
        if (InputRules.emptyToNull(from) != null && !InputRules.isReasonableDate(fromDate)) {
            addFieldError("from", getText("validation.date"));
        }
        if (InputRules.emptyToNull(to) != null && !InputRules.isReasonableDate(toDate)) {
            addFieldError("to", getText("validation.date"));
        }
        String p = InputRules.emptyToNull(page) == null ? "1" : page.trim();
        String s = InputRules.emptyToNull(size) == null ? "50" : size.trim();
        if (!InputRules.isPositiveInt(p) || !InputRules.isPositiveInt(s) || Integer.parseInt(s) > 200) {
            addFieldError("page", getText("validation.page"));
        }
        String o = InputRules.emptyToNull(outcome);
        if (o != null && !o.matches("SUCCESS|FAILURE|DENIED")) addFieldError("outcome", getText("validation.outcome"));
        if (hasFieldErrors()) return INPUT;
        try {
            return ok(audit.search(fromDate, toDate, InputRules.emptyToNull(userId), InputRules.emptyToNull(auditAction),
                    o, Integer.parseInt(p), Integer.parseInt(s)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String auditVerify() {
        return ok(audit.verify());
    }

    public String jobs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", "true".equalsIgnoreCase(System.getenv("BANK_JOBS_ENABLED")));
        m.put("startTime", JobRunner.START_TIME.toString());
        m.put("runs", jobs.recentRuns(30));
        return ok(m);
    }

    public String runJobs() {
        return ok(jobs.runDue());
    }

    public String issueLogin() {
        customerId = InputRules.trim(customerId);
        if (!InputRules.isCustomerId(customerId)) {
            addFieldError("customerId", getText("validation.customerId"));
            return INPUT;
        }
        try {
            return ok(auth.issueCustomerLogin(customerId));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() { return customerId; }

    @StrutsParameter public void setFrom(String from) { this.from = from; }
    @StrutsParameter public void setTo(String to) { this.to = to; }
    @StrutsParameter public void setUserId(String userId) { this.userId = userId; }
    /** 監査ログの操作名で絞り込み（Struts の action パラメーター名と衝突しないよう auditAction） */
    @StrutsParameter public void setAuditAction(String auditAction) { this.auditAction = auditAction; }
    @StrutsParameter public void setOutcome(String outcome) { this.outcome = outcome; }
    @StrutsParameter public void setPage(String page) { this.page = page; }
    @StrutsParameter public void setSize(String size) { this.size = size; }
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
}
