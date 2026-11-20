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

    /** Struts が使う既定コンストラクタ。 */
    public RiskAction() { this(RiskService.getDefault(), AmlService.getDefault()); }

    /**
     * @param risk リスク管理サービス
     * @param aml  AML（マネー・ローンダリング対策）サービス
     */
    public RiskAction(RiskService risk, AmlService aml) {
        super(BankService.getDefault());
        this.risk = risk;
        this.aml = aml;
    }

    /** @return success（与信・延滞・AML などの集計） */
    public String dashboard() { return ok(risk.dashboard()); }

    /**
     * AML アラートの一覧。
     *
     * @return success、または業務エラーの結果名
     */
    public String alerts() {
        try {
            String s = status == null || status.trim().isEmpty() ? null
                    : oneOf(status.trim(), "OPEN", "INVESTIGATING", "CLOSED", "REPORTED");
            return ok(aml.listAlerts(s));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * AML アラートの調査状況を更新する（REPORTED は疑わしい取引の届出済み）。
     *
     * @return success、または業務エラーの結果名
     */
    public String alertUpdate() {
        try {
            return ok(aml.updateAlert(id(alertId), oneOf(status, "INVESTIGATING", "CLOSED", "REPORTED"), text(note, 400), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（取引モニタリングを今すぐ実行した結果） */
    public String scan() { return ok(aml.scan()); }

    /**
     * 顧客の全体像（口座・融資・送金・メモ）を返す。閲覧自体が監査対象。
     *
     * @return success、または業務エラーの結果名
     */
    public String customer360() {
        try {
            String cid = required(customerId, 10);
            if (!InputRules.isCustomerId(cid)) throw new BankException(com.example.bank.domain.ErrorCode.VALIDATION_ERROR);
            return ok(risk.customer360(cid));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 顧客の応対メモ（CRM）を追加する。
     *
     * @return success、または業務エラーの結果名
     */
    public String addNote() {
        try {
            return ok(risk.addNote(required(customerId, 10), oneOf(noteType, "VISIT", "CALL", "PROPOSAL", "COMPLAINT", "MEMO"),
                    required(content, 1000), who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // アラート操作ならアラート番号と状態、顧客操作なら顧客番号を監査ログに残す
    @Override
    public String getAuditTarget() {
        return alertId != null ? "alert " + alertId + " " + status : customerId;
    }

    /** @param v 状態 */
    @StrutsParameter public void setStatus(String v) { status = v; }
    /** @param v アラート番号 */
    @StrutsParameter public void setAlertId(String v) { alertId = v; }
    /** @param v メモ */
    @StrutsParameter public void setNote(String v) { note = v; }
    /** @param v 顧客番号 */
    @StrutsParameter public void setCustomerId(String v) { customerId = v; }
    /** @param v メモの種類 */
    @StrutsParameter public void setNoteType(String v) { noteType = v; }
    /** @param v メモの内容 */
    @StrutsParameter public void setContent(String v) { content = v; }
}
