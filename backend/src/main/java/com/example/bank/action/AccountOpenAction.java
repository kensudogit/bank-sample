package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import com.example.bank.service.CustomerService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * POST /api/account-open — 口座開設（本人確認済みの顧客のみ）
 * Body: {"customerId":"CU0004","accountType":"ORDINARY","initialDeposit":"10000","idempotencyKey":"..."}
 */
public class AccountOpenAction extends KeyedWriteAction {
    private final CustomerService customers;
    private String customerId;
    private String accountType;
    private String initialDeposit;

    public AccountOpenAction() { this(CustomerService.getDefault()); }

    public AccountOpenAction(CustomerService customers) { this.customers = customers; }

    @Override
    public void validate() {
        customerId = InputRules.trim(customerId);
        accountType = InputRules.trim(accountType);
        initialDeposit = InputRules.emptyToNull(initialDeposit);
        if (initialDeposit == null) initialDeposit = "0";
        if (!InputRules.isCustomerId(customerId)) addFieldError("customerId", getText("validation.customerId"));
        if (!InputRules.isAccountType(accountType)) addFieldError("accountType", getText("validation.accountType"));
        if (!InputRules.isAmountOrZero(initialDeposit)) {
            addFieldError("initialDeposit", getText("validation.initialDeposit"));
        } else if (InputRules.toAmount(initialDeposit).compareTo(BankService.MAX_AMOUNT_PER_OPERATION) > 0) {
            addFieldError("initialDeposit", getText("validation.amount.limit"));
        }
        super.validate();
    }

    @Override
    public String execute() {
        try {
            return ok(customers.openAccount(customerId, accountType, InputRules.toAmount(initialDeposit),
                    getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
    @StrutsParameter public void setAccountType(String accountType) { this.accountType = accountType; }
    @StrutsParameter public void setInitialDeposit(String initialDeposit) { this.initialDeposit = initialDeposit; }
}
