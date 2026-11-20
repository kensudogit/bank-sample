package com.example.bank.action;

import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/** 冪等キー（二重送信防止）を受け取る更新系 Action の基底クラス。 */
public abstract class KeyedWriteAction extends BaseApiAction {
    private String idempotencyKey;

    protected KeyedWriteAction() { super(BankService.getDefault()); }

    @Override
    public void validate() {
        idempotencyKey = InputRules.trim(idempotencyKey);
        if (!InputRules.isIdempotencyKey(idempotencyKey)) {
            addFieldError("idempotencyKey", getText("validation.idempotencyKey"));
        }
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    @StrutsParameter public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
