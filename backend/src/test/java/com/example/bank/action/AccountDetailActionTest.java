package com.example.bank.action;

import com.example.bank.domain.Account;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 口座詳細：口座と最近の取引、顧客は本人の口座だけ（他人の口座は「見つかりません」）。 */
class AccountDetailActionTest extends ActionTestBase {
    private AccountDetailAction detail(LoginUser user, String accountNo) {
        AccountDetailAction a = as(user, new AccountDetailAction(new BankService(() -> db, clock)));
        a.setAccountNo(accountNo);
        return a;
    }

    @Test
    void returnsAccountAndTransactions() {
        AccountDetailAction a = detail(TELLER, " 1000001 ");
        assertValid(a);
        assertEquals("1000001", a.getAccountNo());
        assertEquals("success", a.execute());
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) data(a);
        assertEquals("1000001", ((Account) d.get("account")).getAccountNo());
        assertTrue(d.get("transactions") instanceof List);
    }

    @Test
    void customerSeesOnlyOwnAccount() {
        AccountDetailAction own = detail(YAMADA, "1000002");
        assertValid(own);
        assertEquals("success", own.execute());
        AccountDetailAction other = detail(YAMADA, "1000003");
        assertValid(other);
        assertEquals("notFound", other.execute());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(other));
    }

    @Test
    void invalidAndUnknownAccounts() {
        assertInvalid(detail(TELLER, "abc"), "accountNo");
        AccountDetailAction unknown = detail(TELLER, "9999999");
        assertValid(unknown);
        assertEquals("notFound", unknown.execute());
    }
}
