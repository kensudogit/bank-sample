package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 夜間バッチ：開始時刻より前は何もしない、1 日 1 回、業務一覧、再実行は失敗したジョブだけ。 */
class JobRunnerTest {
    private TestDatabase db;
    private TestClock clock;
    private JobRunner jobs;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 3, 2, 0, 30));
        jobs = new JobRunner(() -> db, clock);
    }

    @Test
    void nothingRunsBeforeTheStartTime() {
        assertTrue(jobs.runDue().isEmpty());
    }

    @Test
    void jobsRunOncePerDay() {
        clock.set(LocalDateTime.of(2026, 3, 2, 1, 5));
        List<String> ran = jobs.runDue();
        assertTrue(ran.contains(JobRunner.DORMANCY));
        assertTrue(ran.contains(JobRunner.LEDGER_CHECK));
        assertFalse(ran.contains(JobRunner.INTEREST));   // 利息決算は 3/1・9/1 だけ
        assertTrue(jobs.runDue().isEmpty());
        assertTrue(jobs.recentRuns(100).stream().anyMatch(r -> JobRunner.DORMANCY.equals(r.get("jobName"))));
    }

    @Test
    void catalogListsBusinessesAndOtherJobs() {
        Map<String, Object> catalog = jobs.catalog();
        assertEquals(JobRunner.START_TIME.toString(), catalog.get("startTime"));
        assertFalse(((List<?>) catalog.get("items")).isEmpty());
        assertNotNull(catalog.get("otherJobs"));
    }

    @Test
    void rerunOnlyForKnownJobsThatFailed() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BankException.class, () -> jobs.rerun("NO_SUCH_JOB", LocalDate.of(2026, 3, 2), "supervisor1")).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BankException.class, () -> jobs.rerun(JobRunner.DORMANCY, null, "supervisor1")).getCode());
        clock.set(LocalDateTime.of(2026, 3, 2, 1, 5));
        jobs.runDue();
        // 成功したジョブは再実行できない
        assertEquals(ErrorCode.INVALID_BASE_DATE,
                assertThrows(BankException.class, () -> jobs.rerun(JobRunner.DORMANCY, LocalDate.of(2026, 3, 2), "supervisor1")).getCode());
    }

    @Test
    void amlScanCanRunAnyTime() {
        assertNotNull(jobs.scanAml());
    }
}
