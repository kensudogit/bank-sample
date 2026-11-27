package com.example.bank.action;

import com.example.bank.domain.Account;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 口座一覧：既定のページ（1 ページ 50 件）、件数の上限、顧客は本人名義だけ。 */
class AccountsActionTest extends ActionTestBase {
    private AccountsAction list(LoginUser user) {
        return as(user, new AccountsAction(new BankService(() -> db, clock)));
    }

    @SuppressWarnings("unchecked")
    private static List<Account> items(AccountsAction a) {
        return (List<Account>) ((Map<String, Object>) data(a)).get("items");
    }

    @Test
    void defaultsToFirstPageOf50() {
        AccountsAction a = list(TELLER);
        assertValid(a);
        assertEquals("success", a.execute());
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) data(a);
        assertEquals(1, d.get("page"));
        assertEquals(50, d.get("size"));
        assertTrue(items(a).size() >= 8);
    }

    @Test
    void customerSeesOnlyOwnAccounts() {
        AccountsAction a = list(YAMADA);
        assertValid(a);
        a.execute();
        assertFalse(items(a).isEmpty());
        assertTrue(items(a).stream().allMatch(x -> "CU0001".equals(x.getCustomerId())));
    }

    @Test
    void searchAndPagingValidation() {
        AccountsAction search = list(TELLER);
        search.setQ("1000003");
        assertValid(search);
        search.execute();
        assertEquals("1000003", items(search).get(0).getAccountNo());

        AccountsAction badPage = list(TELLER);
        badPage.setPage("0");
        assertInvalid(badPage, "page");
        AccountsAction tooBig = list(TELLER);
        tooBig.setSize(String.valueOf(BankService.MAX_PAGE_SIZE + 1));
        assertInvalid(tooBig, "size");
    }
}
