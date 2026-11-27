package com.example.bank.action;

import com.example.bank.domain.Account;
import com.example.bank.service.BankService;
import com.example.bank.service.CustomerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 口座開設：顧客番号・種別・初回入金の検証、通貨の既定（円）、業務エラーの応答。 */
class AccountOpenActionTest extends ActionTestBase {
    private AccountOpenAction open(String customerId, String type, String deposit) {
        AccountOpenAction a = as(TELLER, new AccountOpenAction(new CustomerService(() -> db, clock)));
        a.setCustomerId(customerId);
        a.setAccountType(type);
        a.setInitialDeposit(deposit);
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void opensAYenAccountWithZeroDepositByDefault() {
        AccountOpenAction a = open("CU0002", "FOREIGN", null);
        a.setCurrency("USD");
        assertValid(a);
        assertEquals("success", a.execute());
        Account opened = (Account) data(a);
        assertEquals("USD", opened.getCurrency());
        assertEquals(0, opened.getBalance().signum());
        assertEquals("CU0002 FOREIGN", a.getAuditTarget());
    }

    @Test
    void validation() {
        assertInvalid(open("X1", "ORDINARY", "0"), "customerId");
        assertInvalid(open("CU0001", "GOLD", "0"), "accountType");
        assertInvalid(open("CU0001", "ORDINARY", "-5"), "initialDeposit");
        assertInvalid(open("CU0001", "ORDINARY", BankService.MAX_AMOUNT_PER_OPERATION.add(BigDecimal.ONE).toPlainString()),
                "initialDeposit");
    }

    @Test
    void sameTypeTwiceIsRejected() {
        AccountOpenAction a = open("CU0001", "ORDINARY", "1000");
        assertValid(a);
        assertEquals("unprocessable", a.execute());
        assertEquals("ACCOUNT_TYPE_LIMIT", errorCode(a));
    }
}
