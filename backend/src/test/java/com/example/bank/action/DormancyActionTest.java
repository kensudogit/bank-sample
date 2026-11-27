package com.example.bank.action;

import com.example.bank.domain.DormancyReport;
import com.example.bank.service.AccountLifecycleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 休眠管理：基準日の検証、照会と実行、未来日の実行は不可。 */
class DormancyActionTest extends ActionTestBase {
    private DormancyAction action(String asOf) {
        DormancyAction a = as(SUPERVISOR, new DormancyAction(new AccountLifecycleService(() -> db, clock)));
        a.setAsOf(asOf);
        return a;
    }

    @Test
    void reportAndRun() {
        DormancyAction report = action("2026-11-20");
        assertValid(report);
        assertEquals("success", report.report());
        assertEquals(1, ((DormancyReport) data(report)).getDormancyTargets().size());

        DormancyAction run = action("2026-11-20");
        assertValid(run);
        assertEquals("success", run.run());
        assertEquals(1, ((DormancyReport) data(run)).getMadeDormant());
        assertEquals("2026-11-20", run.getAuditTarget());
    }

    @Test
    void invalidAndFutureDates() {
        assertInvalid(action("2026-13-01"), "asOf");
        DormancyAction future = action("2026-11-21");
        assertValid(future);
        assertEquals("unprocessable", future.run());
        assertEquals("INVALID_BASE_DATE", errorCode(future));
        assertEquals("today", action(null).getAuditTarget());
    }
}
