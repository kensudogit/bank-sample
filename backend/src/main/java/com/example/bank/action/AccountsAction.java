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

    /** Struts が使う既定コンストラクタ。 */
    public AccountsAction() { this(BankService.getDefault()); }

    /** @param service 業務サービス（テスト用） */
    public AccountsAction(BankService service) { super(service); }

    /** 検索語・ページ番号・件数を検証する（未指定はページ 1・50 件）。 */
    @Override
    public void validate() {
        q = InputRules.emptyToNull(q);
        if (q != null && !InputRules.isText(q, 50)) addFieldError("q", getText("validation.query"));
        page = InputRules.emptyToNull(page) == null ? "1" : page.trim();
        size = InputRules.emptyToNull(size) == null ? "50" : size.trim();
        if (!InputRules.isPositiveInt(page)) addFieldError("page", getText("validation.page"));
        // 1 回で大量の行を返さないよう、件数に上限を設ける
        if (!InputRules.isPositiveInt(size) || Integer.parseInt(size) > BankService.MAX_PAGE_SIZE) {
            addFieldError("size", getText("validation.size"));
        }
    }

    /**
     * 口座を検索する。
     *
     * @return success
     */
    @Override
    public String execute() {
        try {
            // customerScope() が顧客なら本人の顧客番号で絞り込む（他人の口座が一覧に出ない）
            return ok(service.searchAccounts(customerScope(), q, Integer.parseInt(page), Integer.parseInt(size)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @param q 検索語 */
    @StrutsParameter public void setQ(String q) { this.q = q; }
    /** @param page ページ番号 */
    @StrutsParameter public void setPage(String page) { this.page = page; }
    /** @param size 1 ページの件数 */
    @StrutsParameter public void setSize(String size) { this.size = size; }
}
