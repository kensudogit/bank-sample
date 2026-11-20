package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AmlService;
import com.example.bank.service.BankService;
import com.example.bank.service.RiskService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * リスク管理・AML・CRM の API（役割は struts.xml）。
 * GET  /api/risk-dashboard                         （役席）
 * GET  /api/aml-alerts?status=                     （役席）
 * POST /api/aml-alert-update {alertId, status, note}（役席）
 * POST /api/aml-scan {}                            （役席）取引モニタリングの即時実行
 * GET  /api/customer-360?customerId=               （行員・閲覧は監査ログに記録）
 * POST /api/customer-note {customerId, noteType, content}（行員）
 */
public class RiskAction extends BaseApiAction {
    private final RiskService risk;
    private final AmlService aml;
    private String status;
    private String alertId;
    private String note;
    private String customerId;
    private String noteType;
    private String content;

    public RiskAction() { this(RiskService.getDefault(), AmlService.getDefault()); }

    public RiskAction(RiskService risk, AmlService aml) {
        super(BankService.getDefault());
        this.risk = risk;
        this.aml = aml;
    }

    public String dashboard() { return ok(risk.dashboard()); }

    public String alerts() {
        try {
            String s = status == null || status.trim().isEmpty() ? null
                    : oneOf(status.trim(), "OPEN", "INVESTIGATING", "CLOSED", "REPORTED");
            return ok(aml.listAlerts(s));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String alertUpdate() {
        try {
            return ok(aml.updateAlert(id(alertId), oneOf(status, "INVESTIGATING", "CLOSED", "REPORTED"), text(note, 400), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String scan() { return ok(aml.scan()); }

    public String customer360() {
        try {
            String cid = required(customerId, 10);
            if (!InputRules.isCustomerId(cid)) throw new BankException(com.example.bank.domain.ErrorCode.VALIDATION_ERROR);
            return ok(risk.customer360(cid));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String addNote() {
        try {
            return ok(risk.addNote(required(customerId, 10), oneOf(noteType, "VISIT", "CALL", "PROPOSAL", "COMPLAINT", "MEMO"),
                    required(content, 1000), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() {
        return alertId != null ? "alert " + alertId + " " + status : customerId;
    }

    @StrutsParameter public void setStatus(String v) { status = v; }
    @StrutsParameter public void setAlertId(String v) { alertId = v; }
    @StrutsParameter public void setNote(String v) { note = v; }
    @StrutsParameter public void setCustomerId(String v) { customerId = v; }
    @StrutsParameter public void setNoteType(String v) { noteType = v; }
    @StrutsParameter public void setContent(String v) { content = v; }
}
