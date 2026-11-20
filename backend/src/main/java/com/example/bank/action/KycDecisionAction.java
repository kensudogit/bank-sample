package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import com.example.bank.service.CustomerService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * POST /api/customer-kyc — 本人確認の結果を記録
 * Body: {"customerId":"CU0004","decision":"VERIFIED"|"REJECTED","note":"運転免許証 原本確認"}
 * （本サンプルには行員認証がないため、本番では行員権限のチェックが必須）
 */
public class KycDecisionAction extends BaseApiAction {
    private final CustomerService customers;
    private String customerId;
    private String decision;
    private String note;

    /** Struts が使う既定コンストラクタ。 */
    public KycDecisionAction() { this(CustomerService.getDefault()); }

    /** @param customers 顧客サービス（テスト用） */
    public KycDecisionAction(CustomerService customers) {
        super(BankService.getDefault());
        this.customers = customers;
    }

    /** 顧客番号・判定（VERIFIED / REJECTED）・メモを検証する。 */
    @Override
    public void validate() {
        customerId = InputRules.trim(customerId);
        decision = InputRules.trim(decision);
        note = InputRules.emptyToNull(note);
        if (!InputRules.isCustomerId(customerId)) addFieldError("customerId", getText("validation.customerId"));
        if (!"VERIFIED".equals(decision) && !"REJECTED".equals(decision)) {
            addFieldError("decision", getText("validation.decision"));
        }
        // 謝絶時は理由を必須にする
        if ("REJECTED".equals(decision) && note == null) addFieldError("note", getText("validation.note.required"));
        if (note != null && !InputRules.isText(note, InputRules.NOTE_MAX)) addFieldError("note", getText("validation.note"));
    }

    /**
     * 本人確認の判定を記録する（申込を受け付けた行員自身は判定できない: 職務分離）。
     *
     * @return success、または業務エラーの結果名
     */
    @Override
    public String execute() {
        try {
            return ok(customers.decideKyc(customerId, decision, note, currentUser().getUserId()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（顧客番号と判定） */
    @Override
    public String getAuditTarget() { return customerId + " " + decision; }

    /** @param customerId 顧客番号 */
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
    /** @param decision 判定（VERIFIED / REJECTED） */
    @StrutsParameter public void setDecision(String decision) { this.decision = decision; }
    /** @param note 判定メモ（謝絶時は必須） */
    @StrutsParameter public void setNote(String note) { this.note = note; }
}
