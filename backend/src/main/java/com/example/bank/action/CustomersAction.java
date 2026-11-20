package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.service.BankService;
import com.example.bank.service.CustomerService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * GET /api/customers                    — 顧客一覧（KYC 状態つき）
 * GET /api/customer?customerId=CU0001   — 顧客詳細と保有口座（method="detail"）
 */
public class CustomersAction extends BaseApiAction {
    private final CustomerService customers;
    private String customerId;

    /** Struts が使う既定コンストラクタ。 */
    public CustomersAction() { this(CustomerService.getDefault()); }

    /** @param customers 顧客サービス（テスト用） */
    public CustomersAction(CustomerService customers) {
        super(BankService.getDefault());
        this.customers = customers;
    }

    /**
     * 顧客一覧を返す（行員向け。顧客ロールからの呼び出しは struts.xml で拒否する）。
     *
     * @return success
     */
    @Override
    public String execute() {
        return ok(customers.listCustomers());
    }

    /**
     * 顧客詳細と保有口座を返す。
     *
     * @return success、入力不正なら input、見つからなければ notFound
     */
    public String detail() {
        customerId = InputRules.trim(customerId);
        if (!InputRules.isCustomerId(customerId)) {
            addFieldError("customerId", getText("validation.customerId"));
            return INPUT;
        }
        try {
            // 顧客ログインでは本人以外の顧客情報を返さない
            requireCustomerAccess(customerId);
            return ok(customers.getCustomer(customerId));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return 顧客番号 */
    public String getCustomerId() { return customerId; }

    /** @return 監査ログの対象（顧客番号） */
    @Override
    public String getAuditTarget() { return customerId; }
    /** @param customerId 顧客番号 */
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
}
