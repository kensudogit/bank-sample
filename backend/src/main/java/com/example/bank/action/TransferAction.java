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

    /** Struts が使う既定コンストラクタ。 */
    public TransferAction() { this(BankService.getDefault()); }

    /** @param service 業務サービス（テスト用） */
    public TransferAction(BankService service) { super(service); }

    /** 振込元・振込先の口座番号を検証し、金額・摘要・冪等キーは親クラスで検証する。 */
    @Override
    public void validate() {
        fromAccountNo = InputRules.trim(fromAccountNo);
        toAccountNo = InputRules.trim(toAccountNo);
        if (!InputRules.isAccountNo(fromAccountNo)) addFieldError("fromAccountNo", getText("validation.accountNo"));
        if (!InputRules.isAccountNo(toAccountNo)) addFieldError("toAccountNo", getText("validation.accountNo"));
        super.validate();
    }

    /**
     * 行内振込を実行する。
     *
     * @return success、または業務エラーの結果名
     */
    @Override
    public String execute() {
        try {
            // 顧客は本人の口座からのみ振り込める（振込先は他人の口座でもよい）
            requireAccountAccess(fromAccountNo);   // customers can only send from their own accounts
            return ok(service.transfer(fromAccountNo, toAccountNo, amountValue(), getMemo(), getIdempotencyKey(),
                    ownAccountsOnly));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（振込元 -> 振込先 と金額） */
    @Override
    public String getAuditTarget() { return fromAccountNo + "->" + toAccountNo + " " + getAmount(); }

    /** true: 振替（同じ顧客の口座間に限る） */
    public boolean isOwnAccountsOnly() { return ownAccountsOnly; }
    /** @param ownAccountsOnly true なら振替（同じ顧客の口座間に限る） */
    @StrutsParameter public void setOwnAccountsOnly(boolean ownAccountsOnly) { this.ownAccountsOnly = ownAccountsOnly; }

    /** @return 振込元口座番号 */
    public String getFromAccountNo() { return fromAccountNo; }
    /** @param fromAccountNo 振込元口座番号 */
    @StrutsParameter public void setFromAccountNo(String fromAccountNo) { this.fromAccountNo = fromAccountNo; }

    /** @return 振込先口座番号 */
    public String getToAccountNo() { return toAccountNo; }
    /** @param toAccountNo 振込先口座番号 */
    @StrutsParameter public void setToAccountNo(String toAccountNo) { this.toAccountNo = toAccountNo; }
}
