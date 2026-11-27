package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import com.example.bank.service.FinOpsService;
import com.example.bank.testsupport.FakeHttp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 課金・コストの API：単価表、月の指定の検証、Agent 専用の API、予算の入力、Kong からの取込の本文。 */
class FinOpsActionTest extends ActionTestBase {
    private FinOpsAction finops(LoginUser user) {
        return as(user, new FinOpsAction(new FinOpsService(() -> db, clock, null, null)));
    }

    @Test
    void rateCardAndSummary() {
        FinOpsAction rates = finops(SUPERVISOR);
        assertEquals("success", rates.rateCard());
        assertNotNull(data(rates));
        FinOpsAction summary = finops(SUPERVISOR);
        summary.setMonth("2026-11");
        assertEquals("success", summary.summaryView());
        assertEquals("2026-11", summary.getAuditTarget());
        FinOpsAction badMonth = finops(SUPERVISOR);
        badMonth.setMonth("2026-13");
        assertEquals("badRequest", badMonth.summaryView());
    }

    @Test
    void agentApisNeedAnAgentIdentity() {
        // 経路（struts.xml の agent-budget）は AGENT ロールだけなので、通常は関所で 403 になりここまで来ない。
        // Action 側の確認は二重の防御で、例外のまま投げる（関所を通らずに呼ばれた場合は 500 になる）
        BankException e = assertThrows(BankException.class, () -> finops(TELLER).agentBudget());
        assertEquals(ErrorCode.FORBIDDEN, e.getCode());
        FinOpsAction agent = finops(new LoginUser("agent:agent-app", "AI Agent", LoginUser.AGENT, null, false));
        assertEquals("success", agent.agentBudget());
    }

    @Test
    void budgetUpdateValidation() {
        FinOpsAction missing = finops(SUPERVISOR);
        missing.setSubjectType("AGENT");
        missing.setSubjectId("agent-app");
        assertEquals("badRequest", missing.budgetUpdate());
        FinOpsAction badType = finops(SUPERVISOR);
        badType.setSubjectType("PERSON");
        badType.setSubjectId("x");
        badType.setMonthlyBudgetJpy("1000");
        assertEquals("badRequest", badType.budgetUpdate());
        assertEquals("PERSON:x", badType.getAuditTarget());
    }

    @Test
    void ingestRejectsBodiesThatAreNotJsonObjectsOrArrays() {
        struts.with(FakeHttp.request().jsonPost().body("not json").build());
        assertEquals("badRequest", finops(SUPERVISOR).ingest());
        struts.with(FakeHttp.request().jsonPost().body("123").build());
        assertEquals("badRequest", finops(SUPERVISOR).ingest());
    }
}
