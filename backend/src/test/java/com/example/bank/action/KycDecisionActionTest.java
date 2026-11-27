package com.example.bank.action;

import com.example.bank.domain.Customer;
import com.example.bank.domain.NewCustomer;
import com.example.bank.service.CustomerService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 本人確認の判定：判定の値、却下には理由が必須、受付した本人は判定できない。 */
class KycDecisionActionTest extends ActionTestBase {
    private String pendingCustomer(String registeredBy) {
        return new CustomerService(() -> db, clock).register(new NewCustomer("伊藤 健", "イトウ ケン", LocalDate.of(1990, 4, 1),
                "060-0001", "北海道札幌市中央区北1条西1-1", "0111234567", "MY_NUMBER_CARD", "AB12345678"), key(), registeredBy)
                .getCustomerId();
    }

    private KycDecisionAction action(String customerId, String decision, String note) {
        KycDecisionAction a = as(SUPERVISOR, new KycDecisionAction(new CustomerService(() -> db, clock)));
        a.setCustomerId(customerId);
        a.setDecision(decision);
        a.setNote(note);
        return a;
    }

    @Test
    void verifyByAnotherPerson() {
        String id = pendingCustomer("teller1");
        KycDecisionAction a = action(id, "VERIFIED", null);
        assertValid(a);
        assertEquals("success", a.execute());
        assertTrue(((Customer) data(a)).isVerified());
        assertEquals(id + " VERIFIED", a.getAuditTarget());
    }

    @Test
    void registrantCannotDecide() {
        String id = pendingCustomer("supervisor1");
        KycDecisionAction a = action(id, "VERIFIED", null);
        assertValid(a);
        assertEquals("unprocessable", a.execute());
        assertEquals("SEGREGATION_OF_DUTIES", errorCode(a));
    }

    @Test
    void validation() {
        assertInvalid(action("X", "VERIFIED", null), "customerId");
        assertInvalid(action("CU0001", "MAYBE", null), "decision");
        assertInvalid(action("CU0001", "REJECTED", " "), "note");
    }
}
