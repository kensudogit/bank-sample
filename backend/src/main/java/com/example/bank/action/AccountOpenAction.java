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
    private String currency;

    /** Struts が使う既定コンストラクタ。 */
    public AccountOpenAction() { this(CustomerService.getDefault()); }

    /** @param customers 顧客サービス（テスト用） */
    public AccountOpenAction(CustomerService customers) { this.customers = customers; }

    /** 顧客番号・口座種別・初回入金額を検証し、最後に冪等キーを検証する。 */
    @Override
    public void validate() {
        customerId = InputRules.trim(customerId);
        accountType = InputRules.trim(accountType);
        initialDeposit = InputRules.emptyToNull(initialDeposit);
        // 初回入金は任意。未入力は 0 円として扱う
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

    /**
     * 口座を開設する（本人確認済みでない顧客は KYC_NOT_VERIFIED）。
     *
     * @return success、または業務エラーの結果名
     */
    @Override
    public String execute() {
        try {
            return ok(customers.openAccount(customerId, accountType, currency == null ? "JPY" : currency.trim(),
                    InputRules.toAmount(initialDeposit), getIdempotencyKey()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 監査ログの対象（顧客番号と口座種別） */
    @Override
    public String getAuditTarget() { return customerId + " " + accountType; }

    /** @param customerId 顧客番号 */
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
    /** @param accountType 口座種別 */
    @StrutsParameter public void setAccountType(String accountType) { this.accountType = accountType; }
    /** @param initialDeposit 初回入金額（任意） */
    @StrutsParameter public void setInitialDeposit(String initialDeposit) { this.initialDeposit = initialDeposit; }
    /** 外貨預金の通貨（USD / EUR） */
    @StrutsParameter public void setCurrency(String currency) { this.currency = currency; }
}
