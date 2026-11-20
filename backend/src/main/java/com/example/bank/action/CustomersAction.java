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

    public CustomersAction() { this(CustomerService.getDefault()); }

    public CustomersAction(CustomerService customers) {
        super(BankService.getDefault());
        this.customers = customers;
    }

    @Override
    public String execute() {
        return ok(customers.listCustomers());
    }

    public String detail() {
        customerId = InputRules.trim(customerId);
        if (!InputRules.isCustomerId(customerId)) {
            addFieldError("customerId", getText("validation.customerId"));
            return INPUT;
        }
        try {
            requireCustomerAccess(customerId);
            return ok(customers.getCustomer(customerId));
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String getCustomerId() { return customerId; }

    @Override
    public String getAuditTarget() { return customerId; }
    @StrutsParameter public void setCustomerId(String customerId) { this.customerId = customerId; }
}
