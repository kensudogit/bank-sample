package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** JSON API の基底：応答の形、入力の解析（不正は 400）、本人の口座・顧客だけに限る確認、再送の再現。 */
class BaseApiActionTest extends ActionTestBase {
    /** 基底クラスの protected なメソッドをテストから呼ぶための最小の Action。 */
    static final class Probe extends BaseApiAction {
        Probe(BankService service) { super(service); }

        @Override public String execute() { return ok("x"); }

        void account(String no) { requireAccountAccess(no); }
        void customer(String id) { requireCustomerAccess(id); }
        void owner(String ownerId) { requireOwner(ownerId, ErrorCode.LOAN_NOT_FOUND, "1"); }
        String scope() { return customerScope(); }
        String own(String id) { return ownCustomerOr(id); }
        String whoAmI() { return who(); }
        boolean supervisor() { return isSupervisor(); }
        String failWith(BankException e) { return fail(e); }
    }

    private Probe probe(LoginUser user) {
        return as(user, new Probe(new BankService(() -> db, clock)));
    }

    private static void assertBadRequest(Runnable r) {
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BankException.class, r::run).getCode());
    }

    @Test
    void okAndFailShapeTheResponse() {
        Probe p = probe(TELLER);
        assertEquals("success", p.execute());
        assertEquals("x", data(p));
        assertEquals("notFound", p.failWith(new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "1")));
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(p));
        assertEquals("ACCOUNT_NOT_FOUND", p.getAuditDetail());
    }

    @Test
    void systemErrorResponseHidesDetails() {
        Map<String, Object> r = probe(TELLER).getSystemErrorResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) r.get("error");
        assertEquals("SYSTEM_ERROR", error.get("code"));
        assertEquals(2, error.size());   // code と message だけ
    }

    @Test
    void replayMarksTheResponse() {
        Probe p = probe(TELLER);
        p.replay(java.util.Collections.singletonMap("amount", 10));
        assertEquals(true, p.getResponse().get("replayed"));
        assertNull(p.getAuditDetail());
    }

    @Test
    void customersSeeOnlyTheirOwnAccountsAndRecords() {
        Probe customer = probe(YAMADA);
        customer.account("1000001");
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, assertThrows(BankException.class, () -> customer.account("1000003")).getCode());
        customer.customer("CU0001");
        assertEquals(ErrorCode.CUSTOMER_NOT_FOUND, assertThrows(BankException.class, () -> customer.customer("CU0002")).getCode());
        assertEquals(ErrorCode.LOAN_NOT_FOUND, assertThrows(BankException.class, () -> customer.owner("CU0002")).getCode());
        assertEquals("CU0001", customer.scope());
        assertEquals("CU0001", customer.own("CU0002"));   // 他人の名義では申し込めない

        Probe teller = probe(TELLER);
        teller.account("1000003");
        teller.customer("CU0002");
        teller.owner("CU0002");
        assertNull(teller.scope());
        assertEquals("CU0002", teller.own("CU0002"));
        assertEquals("teller1", teller.whoAmI());
        assertFalse(teller.supervisor());
        assertTrue(probe(SUPERVISOR).supervisor());
        assertNull(probe(null).whoAmI());
    }

    @Test
    void yenParsing() {
        assertEquals(new BigDecimal("1234567"), BaseApiAction.yen(" 1,234,567 "));
        assertNull(BaseApiAction.yen(" "));
        assertBadRequest(() -> BaseApiAction.yen("-1"));
        assertBadRequest(() -> BaseApiAction.yen("1.5"));
        assertBadRequest(() -> BaseApiAction.yen("0123"));
        assertBadRequest(() -> BaseApiAction.yen("1234567890123"));   // 13 桁
    }

    @Test
    void decimalIdIntegerAndDateParsing() {
        assertEquals(new BigDecimal("1.25"), BaseApiAction.decimal("1.25"));
        assertBadRequest(() -> BaseApiAction.decimal("1.1234567"));
        assertEquals(42L, BaseApiAction.id(" 42 "));
        assertBadRequest(() -> BaseApiAction.id("0"));
        assertBadRequest(() -> BaseApiAction.id(null));
        assertEquals(5, BaseApiAction.integer("5", 1, 10));
        assertBadRequest(() -> BaseApiAction.integer("11", 1, 10));
        assertBadRequest(() -> BaseApiAction.integer("x", 1, 10));
        assertEquals(LocalDate.of(2026, 11, 20), BaseApiAction.date("2026-11-20"));
        assertNull(BaseApiAction.date(""));
        assertBadRequest(() -> BaseApiAction.date("2026-02-30"));
    }

    @Test
    void textRequiredOneOfAccountNoAndKey() {
        assertEquals("abc", BaseApiAction.text(" abc ", 5));
        assertNull(BaseApiAction.text("", 5));
        assertBadRequest(() -> BaseApiAction.text("abcdef", 5));
        assertBadRequest(() -> BaseApiAction.required(" ", 5));
        assertEquals("A", BaseApiAction.oneOf("A", "A", "B"));
        assertBadRequest(() -> BaseApiAction.oneOf("C", "A", "B"));
        assertBadRequest(() -> BaseApiAction.oneOf(null, "A"));
        assertEquals("1000001", BaseApiAction.accountNo(" 1000001 "));
        assertBadRequest(() -> BaseApiAction.accountNo("100000"));
        String k = key();
        assertEquals(k, BaseApiAction.key(k));
        assertBadRequest(() -> BaseApiAction.key("short"));
    }

    @Test
    void defaultsForAuditAndKey() {
        Probe p = probe(TELLER);
        assertNull(p.getRequestKey());
        assertNull(p.getAuditTarget());
        assertNull(p.getAuditUserHint());
    }
}
