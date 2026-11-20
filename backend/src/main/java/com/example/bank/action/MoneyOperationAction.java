package com.example.bank.action;

import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.math.BigDecimal;

/**
 * 入金・出金・振込の共通入力。JSON ボディ（json interceptor）から値を受け取る。
 * 金額は浮動小数点誤差を避けるため文字列で受け取り BigDecimal に変換する。
 */
public abstract class MoneyOperationAction extends BaseApiAction {
    private String amount;
    private String memo;
    private String idempotencyKey;

    protected MoneyOperationAction(BankService service) { super(service); }

    /** 共通項目の検証。サブクラスは口座番号の検証を追加する。 */
    @Override
    public void validate() {
        amount = InputRules.trim(amount);
        memo = InputRules.emptyToNull(memo);
        idempotencyKey = InputRules.trim(idempotencyKey);
        if (!InputRules.isAmount(amount)) {
            addFieldError("amount", getText("validation.amount"));
        } else if (amountValue().compareTo(BankService.MAX_AMOUNT_PER_OPERATION) > 0) {
            addFieldError("amount", getText("validation.amount.limit"));
        }
        if (!InputRules.isMemo(memo)) addFieldError("memo", getText("validation.memo"));
        if (!InputRules.isIdempotencyKey(idempotencyKey)) {
            addFieldError("idempotencyKey", getText("validation.idempotencyKey"));
        }
    }

    protected BigDecimal amountValue() { return InputRules.toAmount(amount); }

    public String getAmount() { return amount; }
    @StrutsParameter public void setAmount(String amount) { this.amount = amount; }

    public String getMemo() { return memo; }
    @StrutsParameter public void setMemo(String memo) { this.memo = memo; }

    public String getIdempotencyKey() { return idempotencyKey; }
    @StrutsParameter public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
