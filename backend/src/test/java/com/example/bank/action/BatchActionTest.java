package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.BankBatchService;
import com.example.bank.service.JobRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 銀行バッチ：業務一覧、再実行の入力、日次の集計、帳票、残高証明（顧客は本人の口座だけ）。 */
class BatchActionTest extends ActionTestBase {
    private BatchAction batch(LoginUser user) {
        return as(user, new BatchAction(new JobRunner(() -> db, clock), new BankBatchService(() -> db, clock)));
    }

    @Test
    void catalogAndLists() {
        BatchAction catalog = batch(SUPERVISOR);
        assertEquals("success", catalog.catalog());
        assertFalse(((List<?>) ((Map<?, ?>) data(catalog)).get("items")).isEmpty());
        BatchAction reports = batch(SUPERVISOR);
        assertEquals("success", reports.reports());
        BatchAction closes = batch(SUPERVISOR);
        assertEquals("success", closes.monthCloses());
    }

    @Test
    void rerunInputIsValidated() {
        BatchAction noName = batch(SUPERVISOR);
        noName.setRunDate("2026-11-19");
        assertEquals("badRequest", noName.rerun());
        BatchAction noDate = batch(SUPERVISOR);
        noDate.setJobName(JobRunner.DORMANCY);
        assertEquals("badRequest", noDate.rerun());
        BatchAction notFailed = batch(SUPERVISOR);
        notFailed.setJobName(JobRunner.DORMANCY);
        notFailed.setRunDate("2026-11-19");
        assertEquals("unprocessable", notFailed.rerun());
        assertEquals(JobRunner.DORMANCY + " 2026-11-19", notFailed.getAuditTarget());
    }

    @Test
    void dailySummaryForADate() {
        BatchAction daily = batch(SUPERVISOR);
        daily.setDate("2026-11-19");
        assertEquals("success", daily.daily());
        BatchAction bad = batch(SUPERVISOR);
        bad.setDate("2026-11-31");
        assertEquals("badRequest", bad.daily());
    }

    @Test
    void reportNeedsAValidId() {
        BatchAction bad = batch(SUPERVISOR);
        bad.setReportId("abc");
        assertEquals("badRequest", bad.report());
        assertEquals("report abc", bad.getAuditTarget());
    }

    @Test
    void balanceCertificateIsScopedForCustomers() {
        BatchAction own = batch(YAMADA);
        own.setAccountNo("1000001");
        own.setDate("2026-11-20");
        assertEquals("success", own.certificate());
        BatchAction other = batch(YAMADA);
        other.setAccountNo("1000003");
        other.setDate("2026-11-20");
        assertEquals("notFound", other.certificate());
        BatchAction future = batch(TELLER);
        future.setAccountNo("1000003");
        future.setDate("2026-11-21");
        assertEquals("unprocessable", future.certificate());
        assertEquals("INVALID_BASE_DATE", errorCode(future));
    }
}
