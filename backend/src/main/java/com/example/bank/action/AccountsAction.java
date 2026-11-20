package com.example.bank.action;

import com.example.bank.service.BankService;

/** GET /api/accounts — 口座一覧。 */
public class AccountsAction extends BaseApiAction {

    public AccountsAction() { this(BankService.getDefault()); }

    public AccountsAction(BankService service) { super(service); }

    @Override
    public String execute() {
        return ok(service.listAccounts());
    }
}
