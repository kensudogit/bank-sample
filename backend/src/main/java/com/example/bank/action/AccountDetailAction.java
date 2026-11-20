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

    /** Struts が使う既定コンストラクタ。 */
    public AccountDetailAction() { this(BankService.getDefault()); }

    /** @param service 業務サービス（テスト用） */
    public AccountDetailAction(BankService service) { super(service); }

    /** 口座番号の形式を検証する。 */
    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
    }

    /**
     * 口座詳細を返す。
     *
     * @return success、または口座が見つからない場合 notFound
     */
    @Override
    public String execute() {
        try {
            // 顧客は本人名義の口座だけ。権限確認を照会より先に行う
            requireAccountAccess(accountNo);
            AccountDetail detail = service.getAccountDetail(accountNo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("account", detail.getAccount());
            data.put("transactions", detail.getTransactions());
            return ok(data);
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 口座番号 */
    public String getAccountNo() { return accountNo; }

    /** @param accountNo 口座番号（クエリパラメータ） */
    @StrutsParameter
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
