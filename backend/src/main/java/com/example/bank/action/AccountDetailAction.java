package com.example.bank.action;

import com.example.bank.domain.AccountDetail;
import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/account?accountNo=1000001 — 口座詳細と直近の明細。 */
public class AccountDetailAction extends BaseApiAction {
    private String accountNo;

    public AccountDetailAction() { this(BankService.getDefault()); }

    public AccountDetailAction(BankService service) { super(service); }

    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
    }

    @Override
    public String execute() {
        try {
            AccountDetail detail = service.getAccountDetail(accountNo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("account", detail.getAccount());
            data.put("transactions", detail.getTransactions());
            return ok(data);
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String getAccountNo() { return accountNo; }

    @StrutsParameter
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
