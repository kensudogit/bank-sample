package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.AccountLifecycleService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * POST /api/account-close      (method="close")      — 口座解約（利息決算 → 全額払戻し → CLOSED）
 *   Body: {"accountNo":"1000004","payoutAccountNo":"1000005"(任意: 省略時は現金払戻し),"idempotencyKey":"..."}
 * POST /api/account-reactivate (method="reactivate") — 休眠解除
 *   Body: {"accountNo":"1000006","idempotencyKey":"..."}
 */
public class AccountLifecycleAction extends KeyedWriteAction {
    private final AccountLifecycleService lifecycle;
    private String accountNo;
    private String payoutAccountNo;

    /** Struts が使う既定コンストラクタ。 */
    public AccountLifecycleAction() { this(AccountLifecycleService.getDefault()); }

    /** @param lifecycle 口座ライフサイクルサービス（テスト用） */
    public AccountLifecycleAction(AccountLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    /** 口座番号・払戻し先口座番号の形式を検証し、最後に冪等キーを検証する。 */
    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        payoutAccountNo = InputRules.emptyToNull(payoutAccountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
        if (payoutAccountNo != null && !InputRules.isAccountNo(payoutAccountNo)) {
            addFieldError("payoutAccountNo", getText("validation.accountNo"));
        }
        super.validate();
    }

    /**
     * 口座を解約する（利息決算 → 全額払戻し → CLOSED を 1 トランザクションで行う）。
     *
     * @return success、または業務エラーの結果名
     */
    public String close() {
        try {
            return ok(lifecycle.close(accountNo, payoutAccountNo, getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 休眠口座を ACTIVE に戻す。
     *
     * @return success、または業務エラーの結果名
     */
    public String reactivate() {
        try {
            return ok(lifecycle.reactivate(accountNo, getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    // 監査ログには払戻し先も残し、資金がどこへ移ったかを追えるようにする
    @Override
    public String getAuditTarget() { return payoutAccountNo == null ? accountNo : accountNo + "->" + payoutAccountNo; }

    /** @param accountNo 対象口座番号 */
    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    /** @param payoutAccountNo 払戻し先口座番号（省略時は現金払戻し） */
    @StrutsParameter public void setPayoutAccountNo(String payoutAccountNo) { this.payoutAccountNo = payoutAccountNo; }
}
