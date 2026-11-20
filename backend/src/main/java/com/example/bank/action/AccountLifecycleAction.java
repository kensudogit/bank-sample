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

    public AccountLifecycleAction() { this(AccountLifecycleService.getDefault()); }

    public AccountLifecycleAction(AccountLifecycleService lifecycle) { this.lifecycle = lifecycle; }

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

    public String close() {
        try {
            return ok(lifecycle.close(accountNo, payoutAccountNo, getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String reactivate() {
        try {
            return ok(lifecycle.reactivate(accountNo, getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    @StrutsParameter public void setPayoutAccountNo(String payoutAccountNo) { this.payoutAccountNo = payoutAccountNo; }
}
