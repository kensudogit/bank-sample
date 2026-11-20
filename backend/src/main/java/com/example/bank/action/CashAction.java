package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * POST /api/deposit  (method="deposit")
 * POST /api/withdraw (method="withdraw")
 * Body: {"accountNo":"1000001","amount":"10000","memo":"...","idempotencyKey":"..."}
 */
public class CashAction extends MoneyOperationAction {
    private String accountNo;

    public CashAction() { this(BankService.getDefault()); }

    public CashAction(BankService service) { super(service); }

    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
        super.validate();
    }

    public String deposit() {
        try {
            return ok(service.deposit(accountNo, amountValue(), getMemo(), getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String withdraw() {
        try {
            return ok(service.withdraw(accountNo, amountValue(), getMemo(), getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String getAccountNo() { return accountNo; }

    @StrutsParameter
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
