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

    /** @param service 業務サービス */
    protected MoneyOperationAction(BankService service) { super(service); }

    /** 共通項目の検証。サブクラスは口座番号の検証を追加する。 */
    @Override
    public void validate() {
        amount = InputRules.trim(amount);
        memo = InputRules.emptyToNull(memo);
        idempotencyKey = InputRules.trim(idempotencyKey);
        if (!InputRules.isAmount(amount)) {
            addFieldError("amount", getText("validation.amount"));
        // 形式が正しい場合だけ数値化して上限を確認する（形式不正の文字列を BigDecimal にすると例外になるため）
        } else if (amountValue().compareTo(BankService.MAX_AMOUNT_PER_OPERATION) > 0) {
            addFieldError("amount", getText("validation.amount.limit"));
        }
        if (!InputRules.isMemo(memo)) addFieldError("memo", getText("validation.memo"));
        if (!InputRules.isIdempotencyKey(idempotencyKey)) {
            addFieldError("idempotencyKey", getText("validation.idempotencyKey"));
        }
    }

    /** @return 検証済みの金額（validate() で形式を確認した後に呼ぶ） */
    protected BigDecimal amountValue() { return InputRules.toAmount(amount); }

    /** @return 金額（入力された文字列） */
    public String getAmount() { return amount; }
    /** @param amount 金額（JSON の数値誤差を避けるため文字列で受け取る） */
    @StrutsParameter public void setAmount(String amount) { this.amount = amount; }

    /** @return 摘要 */
    public String getMemo() { return memo; }
    /** @param memo 摘要（任意） */
    @StrutsParameter public void setMemo(String memo) { this.memo = memo; }

    /** @return 冪等キー */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** @param idempotencyKey 冪等キー（二重送信防止） */
    @StrutsParameter public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    // 冪等性インターセプターと監査ログが参照するキー。空のときは「キーなし」として null を返す
    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
