package com.example.bank.action;

import com.example.bank.domain.Account;
import com.example.bank.domain.CloseResult;
import com.example.bank.service.AccountLifecycleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 解約・休眠解除：入力の検証、払出先の指定、業務エラーの応答。 */
class AccountLifecycleActionTest extends ActionTestBase {
    private AccountLifecycleAction action(String accountNo, String payout) {
        AccountLifecycleAction a = as(SUPERVISOR, new AccountLifecycleAction(new AccountLifecycleService(() -> db, clock)));
        a.setAccountNo(accountNo);
        a.setPayoutAccountNo(payout);
        a.setIdempotencyKey(key());
        return a;
    }

    @Test
    void closeIntoAnotherOwnAccount() {
        AccountLifecycleAction a = action("1000001", "1000002");
        assertValid(a);
        assertEquals("success", a.close());
        assertEquals("1000002", ((CloseResult) data(a)).getPayoutAccountNo());
        assertEquals("1000001->1000002", a.getAuditTarget());
    }

    @Test
    void blankPayoutMeansCash() {
        AccountLifecycleAction a = action("1000004", " ");
        assertValid(a);
        assertEquals("success", a.close());
        assertNull(((CloseResult) data(a)).getPayoutAccountNo());
        assertEquals("1000004", a.getAuditTarget());
    }

    @Test
    void validation() {
        assertInvalid(action("1", null), "accountNo");
        assertInvalid(action("1000001", "2"), "payoutAccountNo");
    }

    @Test
    void reactivateNeedsADormantAccount() {
        AccountLifecycleAction a = action("1000001", null);
        assertValid(a);
        assertEquals("unprocessable", a.reactivate());
        assertEquals("ACCOUNT_NOT_DORMANT", errorCode(a));

        new AccountLifecycleService(() -> db, clock).runDormancy(java.time.LocalDate.of(2026, 11, 20));
        AccountLifecycleAction dormant = action("1000006", null);
        assertValid(dormant);
        assertEquals("success", dormant.reactivate());
        assertEquals("ACTIVE", ((Account) data(dormant)).getStatus());
    }
}
