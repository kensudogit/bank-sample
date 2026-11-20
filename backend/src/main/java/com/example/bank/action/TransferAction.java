package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * POST /api/transfer
 * Body: {"fromAccountNo":"1000001","toAccountNo":"1000003","amount":"5000","memo":"...","idempotencyKey":"..."}
 */
public class TransferAction extends MoneyOperationAction {
    private String fromAccountNo;
    private String toAccountNo;
    private boolean ownAccountsOnly;

    public TransferAction() { this(BankService.getDefault()); }

    public TransferAction(BankService service) { super(service); }

    @Override
    public void validate() {
        fromAccountNo = InputRules.trim(fromAccountNo);
        toAccountNo = InputRules.trim(toAccountNo);
        if (!InputRules.isAccountNo(fromAccountNo)) addFieldError("fromAccountNo", getText("validation.accountNo"));
        if (!InputRules.isAccountNo(toAccountNo)) addFieldError("toAccountNo", getText("validation.accountNo"));
        super.validate();
    }

    @Override
    public String execute() {
        try {
            requireAccountAccess(fromAccountNo);   // customers can only send from their own accounts
            return ok(service.transfer(fromAccountNo, toAccountNo, amountValue(), getMemo(), getIdempotencyKey(),
                    ownAccountsOnly));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() { return fromAccountNo + "->" + toAccountNo + " " + getAmount(); }

    /** true: 振替（同じ顧客の口座間に限る） */
    public boolean isOwnAccountsOnly() { return ownAccountsOnly; }
    @StrutsParameter public void setOwnAccountsOnly(boolean ownAccountsOnly) { this.ownAccountsOnly = ownAccountsOnly; }

    public String getFromAccountNo() { return fromAccountNo; }
    @StrutsParameter public void setFromAccountNo(String fromAccountNo) { this.fromAccountNo = fromAccountNo; }

    public String getToAccountNo() { return toAccountNo; }
    @StrutsParameter public void setToAccountNo(String toAccountNo) { this.toAccountNo = toAccountNo; }
}
