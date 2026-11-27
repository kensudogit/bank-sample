package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 振替：口座番号の検証、顧客は本人の口座からだけ、同一口座は不可。 */
class TransferActionTest extends ActionTestBase {
    private TransferAction transfer(LoginUser user, String from, String to, String amount) {
        TransferAction a = as(user, new TransferAction(new BankService(() -> db, clock)));
        a.setFromAccountNo(from);
        a.setToAccountNo(to);
        a.setAmount(amount);
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void customerTransfersFromOwnAccount() {
        TransferAction a = transfer(YAMADA, "1000001", "1000002", "1000");
        assertValid(a);
        assertEquals("success", a.execute());
        assertEquals("1000001->1000002 1000", a.getAuditTarget());
        BankService bank = new BankService(() -> db, clock);
        assertEquals(0, new BigDecimal("1249000").compareTo(bank.getAccount("1000001").getBalance()));
    }

    @Test
    void customerCannotSendFromSomeoneElsesAccount() {
        TransferAction a = transfer(YAMADA, "1000003", "1000001", "1000");
        assertValid(a);
        assertEquals("notFound", a.execute());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(a));
    }

    @Test
    void validationAndSameAccount() {
        assertInvalid(transfer(TELLER, "1", "1000002", "1000"), "fromAccountNo");
        assertInvalid(transfer(TELLER, "1000001", "x", "1000"), "toAccountNo");
        TransferAction same = transfer(TELLER, "1000001", "1000001", "1000");
        assertValid(same);
        same.execute();
        assertEquals("SAME_ACCOUNT", errorCode(same));
    }
}
