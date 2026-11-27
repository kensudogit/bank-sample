package com.example.bank.action;

import com.example.bank.service.AuditService;
import com.example.bank.service.AuthService;
import com.example.bank.service.JobRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 管理機能：監査ログの検索と検証、バッチの実行履歴、元帳の検証と追跡、顧客ログインの発行。 */
class AdminActionTest extends ActionTestBase {
    private AdminAction admin() {
        return as(SUPERVISOR, new AdminAction(new AuditService(() -> db, clock), new JobRunner(() -> db, clock),
                new AuthService(() -> db, clock)));
    }

    @Test
    void auditLogSearchAndValidation() {
        new AuditService(() -> db, clock).record(TELLER, null, "deposit", "1000001", AuditService.SUCCESS, null, "ip");
        AdminAction ok = admin();
        ok.setOutcome("SUCCESS");
        assertEquals("success", ok.auditLog());
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) data(ok);
        assertEquals(1L, page.get("total"));

        AdminAction badOutcome = admin();
        badOutcome.setOutcome("MAYBE");
        assertEquals("input", badOutcome.auditLog());
        AdminAction badSize = admin();
        badSize.setSize("201");
        assertEquals("input", badSize.auditLog());
        AdminAction badDate = admin();
        badDate.setFrom("2026-02-30");
        assertEquals("input", badDate.auditLog());
    }

    @Test
    void auditVerifyAndJobs() {
        AdminAction verify = admin();
        assertEquals("success", verify.auditVerify());
        assertEquals(true, ((Map<?, ?>) data(verify)).get("valid"));
        AdminAction jobs = admin();
        assertEquals("success", jobs.jobs());
        Map<?, ?> m = (Map<?, ?>) data(jobs);
        assertEquals(JobRunner.START_TIME.toString(), m.get("startTime"));
        assertTrue(m.get("runs") instanceof List);
    }

    @Test
    void ledgerVerifyAndTrace() {
        AdminAction all = admin();
        assertEquals("success", all.ledgerVerify());
        AdminAction bad = admin();
        bad.setAccountNo("12");
        assertEquals("badRequest", bad.ledgerVerify());
        AdminAction trace = admin();
        trace.setAccountNo("1000004");
        assertEquals("success", trace.ledgerTrace());
        AdminAction noAccount = admin();
        assertEquals("badRequest", noAccount.ledgerTrace());
    }

    @Test
    void issueLogin() {
        AdminAction bad = admin();
        bad.setCustomerId("X");
        assertEquals("input", bad.issueLogin());
        AdminAction already = admin();
        already.setCustomerId("CU0001");
        assertEquals("conflict", already.issueLogin());
        assertEquals("LOGIN_ALREADY_ISSUED", errorCode(already));
        assertEquals("CU0001", already.getAuditTarget());
    }
}
