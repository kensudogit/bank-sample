package com.example.bank.action;

import com.example.bank.domain.InterestResult;
import com.example.bank.domain.LoginUser;
import com.example.bank.domain.SettlementSummary;
import com.example.bank.service.InterestService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 利息の試算と決算：口座番号・基準日の検証、試算は口座が必須、顧客は本人の口座だけ、口座なしの決算は一括。 */
class InterestActionTest extends ActionTestBase {
    private InterestAction action(LoginUser user, String accountNo, String asOf) {
        InterestAction a = as(user, new InterestAction(new InterestService(() -> db, clock)));
        a.setAccountNo(accountNo);
        a.setAsOf(asOf);
        return a;
    }

    @Test
    void previewOwnAccount() {
        InterestAction a = action(YAMADA, "1000001", "2026-03-01");
        assertValid(a);
        assertEquals("success", a.preview());
        assertFalse(((InterestResult) data(a)).isSettled());
    }

    @Test
    void previewNeedsAnAccountAndIsScoped() {
        InterestAction none = action(TELLER, null, null);
        assertValid(none);
        assertEquals("input", none.preview());
        InterestAction other = action(YAMADA, "1000003", null);
        assertValid(other);
        assertEquals("notFound", other.preview());
    }

    @Test
    void settleOneOrAll() {
        InterestAction one = action(SUPERVISOR, "1000004", "2026-03-01");
        assertValid(one);
        assertEquals("success", one.settle());
        assertTrue(((InterestResult) data(one)).isSettled());
        assertEquals("1000004 asOf=2026-03-01", one.getAuditTarget());

        InterestAction all = action(SUPERVISOR, null, "2026-03-01");
        assertValid(all);
        assertEquals("success", all.settle());
        assertTrue(data(all) instanceof SettlementSummary);
        assertEquals("ALL asOf=2026-03-01", all.getAuditTarget());
    }

    @Test
    void validation() {
        assertInvalid(action(TELLER, "12", null), "accountNo");
        assertInvalid(action(TELLER, "1000001", "2026-02-30"), "asOf");
    }
}
