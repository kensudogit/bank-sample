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

    /** Struts が使う既定コンストラクタ。 */
    public CashAction() { this(BankService.getDefault()); }

    /** @param service 業務サービス（テスト用） */
    public CashAction(BankService service) { super(service); }

    /** 口座番号を検証し、金額・摘要・冪等キーは親クラスで検証する。 */
    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
        super.validate();
    }

    /**
     * 入金する。
     *
     * @return success、または業務エラーの結果名
     */
    public String deposit() {
        try {
            return ok(service.deposit(accountNo, amountValue(), getMemo(), getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /**
     * 出金する（残高不足・限度額超過は 422）。
     *
     * @return success、または業務エラーの結果名
     */
    public String withdraw() {
        try {
            return ok(service.withdraw(accountNo, amountValue(), getMemo(), getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 口座番号 */
    public String getAccountNo() { return accountNo; }

    /** @return 監査ログの対象（口座番号と金額） */
    @Override
    public String getAuditTarget() { return accountNo + " " + getAmount(); }

    /** @param accountNo 口座番号 */
    @StrutsParameter
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
}
