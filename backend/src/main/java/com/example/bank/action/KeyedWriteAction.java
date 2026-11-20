package com.example.bank.action;

import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/** 冪等キー（二重送信防止）を受け取る更新系 Action の基底クラス。 */
public abstract class KeyedWriteAction extends BaseApiAction {
    private String idempotencyKey;

    /** 既定の BankService で生成する（サブクラスは自分が使うサービスを別に持つ）。 */
    protected KeyedWriteAction() { super(BankService.getDefault()); }

    /** 冪等キーの形式を検証する。サブクラスは自分の項目を検証してから super.validate() を呼ぶ。 */
    @Override
    public void validate() {
        idempotencyKey = InputRules.trim(idempotencyKey);
        if (!InputRules.isIdempotencyKey(idempotencyKey)) {
            addFieldError("idempotencyKey", getText("validation.idempotencyKey"));
        }
    }

    /** @return 冪等キー */
    public String getIdempotencyKey() { return idempotencyKey; }
    // Struts 6 では @StrutsParameter を付けたセッターだけがリクエストから値を受け取れる（不正なパラメータ注入の防止）
    /** @param idempotencyKey 冪等キー（JSON ボディから設定） */
    @StrutsParameter public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    // 冪等性インターセプターと監査ログが参照するキー。空のときは「キーなし」として null を返す
    @Override
    public String getRequestKey() {
        return idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null : idempotencyKey.trim();
    }
}
