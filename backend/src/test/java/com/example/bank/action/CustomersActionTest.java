package com.example.bank.action;

import com.example.bank.domain.Customer;
import com.example.bank.service.CustomerService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 顧客の一覧と詳細：顧客番号の検証、顧客は本人の情報だけ、存在しない顧客。 */
class CustomersActionTest extends ActionTestBase {
    private CustomersAction action(com.example.bank.domain.LoginUser user, String customerId) {
        CustomersAction a = as(user, new CustomersAction(new CustomerService(() -> db, clock)));
        a.setCustomerId(customerId);
        return a;
    }

    @Test
    void listForStaff() {
        CustomersAction a = action(TELLER, null);
        assertEquals("success", a.execute());
        assertTrue(((List<?>) data(a)).size() >= 4);
    }

    @Test
    void detailIsScopedForCustomers() {
        CustomersAction own = action(YAMADA, "CU0001");
        assertEquals("success", own.detail());
        assertEquals("山田 太郎", ((Customer) data(own)).getName());
        assertEquals("CU0001", own.getAuditTarget());

        CustomersAction other = action(YAMADA, "CU0002");
        assertEquals("notFound", other.detail());
        assertEquals("CUSTOMER_NOT_FOUND", errorCode(other));
    }

    @Test
    void detailValidation() {
        CustomersAction bad = action(TELLER, "X");
        assertEquals("input", bad.detail());
        assertTrue(bad.getFieldErrors().containsKey("customerId"));
        CustomersAction unknown = action(TELLER, "CU9999");
        assertEquals("notFound", unknown.detail());
    }
}
