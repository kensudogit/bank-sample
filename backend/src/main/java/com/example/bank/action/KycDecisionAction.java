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

    public KycDecisionAction() { this(CustomerService.getDefault()); }

    public KycDecisionAction(CustomerService customers) {
        super(BankService.getDefault());
        this.customers = customers;
    }

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

    @Override
    public String execute() {
        try {
            return ok(customers.decideKyc(customerId, decision, note));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
    @StrutsParameter public void setDecision(String decision) { this.decision = decision; }
    @StrutsParameter public void setNote(String note) { this.note = note; }
}
