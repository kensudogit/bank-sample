package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * GET /api/accounts?q=&page=1&size=50 — 口座一覧（検索・ページ）。
 * 顧客ログインでは本人名義の口座のみ。q は口座番号の前方一致・顧客番号・氏名／カナの部分一致。
 */
public class AccountsAction extends BaseApiAction {
    private String q;
    private String page;
    private String size;

    public AccountsAction() { this(BankService.getDefault()); }

    public AccountsAction(BankService service) { super(service); }

    @Override
    public void validate() {
        q = InputRules.emptyToNull(q);
        if (q != null && !InputRules.isText(q, 50)) addFieldError("q", getText("validation.query"));
        page = InputRules.emptyToNull(page) == null ? "1" : page.trim();
        size = InputRules.emptyToNull(size) == null ? "50" : size.trim();
        if (!InputRules.isPositiveInt(page)) addFieldError("page", getText("validation.page"));
        if (!InputRules.isPositiveInt(size) || Integer.parseInt(size) > BankService.MAX_PAGE_SIZE) {
            addFieldError("size", getText("validation.size"));
        }
    }

    @Override
    public String execute() {
        try {
            return ok(service.searchAccounts(customerScope(), q, Integer.parseInt(page), Integer.parseInt(size)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setQ(String q) { this.q = q; }
    @StrutsParameter public void setPage(String page) { this.page = page; }
    @StrutsParameter public void setSize(String size) { this.size = size; }
}
