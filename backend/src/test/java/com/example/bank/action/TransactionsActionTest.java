package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.domain.TransactionPage;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 取引履歴：期間・種別・ページの検証、顧客は本人の口座だけ。 */
class TransactionsActionTest extends ActionTestBase {
    private TransactionsAction action(LoginUser user, String accountNo) {
        TransactionsAction a = as(user, new TransactionsAction(new BankService(() -> db, clock)));
        a.setAccountNo(accountNo);
        return a;
    }

    @Test
    void searchWithDefaults() {
        TransactionsAction a = action(YAMADA, "1000001");
        assertValid(a);
        assertEquals("success", a.execute());
        TransactionPage page = (TransactionPage) data(a);
        assertEquals(1, page.getPage());
        assertEquals(50, page.getSize());
    }

    @Test
    void customerCannotReadOthersHistory() {
        TransactionsAction a = action(YAMADA, "1000003");
        assertValid(a);
        assertEquals("notFound", a.execute());
    }

    @Test
    void validation() {
        assertInvalid(action(TELLER, "1"), "accountNo");
        TransactionsAction range = action(TELLER, "1000001");
        range.setFrom("2026-11-20");
        range.setTo("2026-11-01");
        assertInvalid(range, "to");
        TransactionsAction types = action(TELLER, "1000001");
        types.setTypes("DEPOSIT,BOGUS");
        assertInvalid(types, "types");
        TransactionsAction page = action(TELLER, "1000001");
        page.setPage("abc");
        assertInvalid(page, "page");
    }

    @Test
    void typeFilter() {
        TransactionsAction a = action(TELLER, "1000001");
        a.setTypes("DEPOSIT");
        a.setFrom("2026-01-01");
        a.setTo("2026-11-20");
        assertValid(a);
        assertEquals("success", a.execute());
        assertTrue(((TransactionPage) data(a)).getItems().stream().allMatch(t -> "DEPOSIT".equals(t.getTxType())));
    }
}
