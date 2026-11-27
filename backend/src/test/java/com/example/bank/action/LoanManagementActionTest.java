package com.example.bank.action;

import com.example.bank.service.LoanManagementService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 融資管理の API：返済のシミュレーションの入力、引当の集計、担保の再評価・条件変更の入力。 */
class LoanManagementActionTest extends ActionTestBase {
    private LoanManagementAction mgmt() {
        return as(SUPERVISOR, new LoanManagementAction(new LoanManagementService(() -> db, clock)));
    }

    @Test
    void simulate() {
        LoanManagementAction a = mgmt();
        a.setAmount("1000000");
        a.setRate("1.5");
        a.setTermMonths("12");
        a.setRepaymentMethod("EQUAL_PAYMENT");
        assertEquals("success", a.simulate());
        assertNotNull(data(a));
    }

    @Test
    void simulateValidation() {
        LoanManagementAction noRate = mgmt();
        noRate.setAmount("1000000");
        noRate.setTermMonths("12");
        noRate.setRepaymentMethod("EQUAL_PAYMENT");
        assertEquals("badRequest", noRate.simulate());
        LoanManagementAction badMethod = mgmt();
        badMethod.setAmount("1000000");
        badMethod.setRate("1.5");
        badMethod.setTermMonths("12");
        badMethod.setRepaymentMethod("BULLET");
        assertEquals("badRequest", badMethod.simulate());
        LoanManagementAction badDay = mgmt();
        badDay.setAmount("1000000");
        badDay.setRate("1.5");
        badDay.setTermMonths("12");
        badDay.setRepaymentMethod("EQUAL_PAYMENT");
        badDay.setPaymentDay("31");
        assertEquals("badRequest", badDay.simulate());
    }

    @Test
    void provisionSummaryIsAvailable() {
        LoanManagementAction a = mgmt();
        assertEquals("success", a.provisionSummary());
    }

    @Test
    void revalueAndRescheduleNeedValidInput() {
        LoanManagementAction revalue = mgmt();
        revalue.setCollateralId("1");
        revalue.setNote("再評価");
        assertEquals("badRequest", revalue.revalue());
        assertEquals("collateral 1 null", revalue.getAuditTarget());
        LoanManagementAction reschedule = mgmt();
        reschedule.setLoanId("1");
        reschedule.setTermMonths("12");
        assertEquals("badRequest", reschedule.reschedule());
        LoanManagementAction unknown = mgmt();
        unknown.setLoanId("999999");
        assertEquals("notFound", unknown.management());
    }
}
