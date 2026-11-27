package com.example.bank.repository;

import com.example.bank.service.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 夜間バッチの実行記録：1 日 1 回の実行権、終了の記録、失敗の取り消し、古い冪等キーの削除。 */
class JobRunRepositoryTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 2, 0);
    private final JobRunRepository repo = new JobRunRepository();
    private Connection c;

    @BeforeEach
    void setUp() throws SQLException {
        c = TestDatabase.create().getConnection();
    }

    @AfterEach
    void tearDown() throws SQLException {
        c.close();
    }

    @Test
    void claimOnlyOncePerJobAndDay() throws SQLException {
        assertTrue(repo.claim(c, "TEST_JOB", DAY, NOW));
        assertFalse(repo.claim(c, "TEST_JOB", DAY, NOW.plusMinutes(10)));
        assertTrue(repo.claim(c, "TEST_JOB", DAY.plusDays(1), NOW));
        assertTrue(repo.claim(c, "OTHER_JOB", DAY, NOW));
    }

    @Test
    void finishRecordsOutcomeAndTruncatesDetail() throws SQLException {
        repo.claim(c, "TEST_JOB", DAY, NOW);
        StringBuilder longDetail = new StringBuilder();
        for (int i = 0; i < 500; i++) longDetail.append('x');
        repo.finish(c, "TEST_JOB", DAY, "SUCCESS", longDetail.toString(), NOW.plusMinutes(1));
        List<Map<String, Object>> recent = repo.recent(c, 10);
        Map<String, Object> row = recent.stream().filter(r -> "TEST_JOB".equals(r.get("jobName"))).findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("SUCCESS", row.get("outcome"));
        assertEquals(400, String.valueOf(row.get("detail")).length());
    }

    @Test
    void deleteFailedAllowsRerun() throws SQLException {
        repo.claim(c, "TEST_JOB", DAY, NOW);
        repo.finish(c, "TEST_JOB", DAY, "FAILURE", "db down", NOW);
        assertEquals(1, repo.deleteFailed(c, "TEST_JOB", DAY));
        assertTrue(repo.claim(c, "TEST_JOB", DAY, NOW.plusHours(1)));
    }

    @Test
    void deleteFailedKeepsSuccessfulRuns() throws SQLException {
        repo.claim(c, "TEST_JOB", DAY, NOW);
        repo.finish(c, "TEST_JOB", DAY, "SUCCESS", null, NOW);
        assertEquals(0, repo.deleteFailed(c, "TEST_JOB", DAY));
        assertFalse(repo.claim(c, "TEST_JOB", DAY, NOW));
    }

    @Test
    void deleteRequestsBeforeRemovesOldIdempotencyKeys() throws SQLException {
        OperationRequestRepository requests = new OperationRequestRepository();
        requests.register(c, "old-key-0000000000000000", "TRANSFER", NOW.minusDays(40));
        requests.register(c, "new-key-0000000000000000", "TRANSFER", NOW);
        assertTrue(repo.deleteRequestsBefore(c, NOW.minusDays(30)) >= 1);
        // 古いキーは消えたので再登録できる。新しいキーは残っている
        assertTrue(requests.register(c, "old-key-0000000000000000", "TRANSFER", NOW));
        assertFalse(requests.register(c, "new-key-0000000000000000", "TRANSFER", NOW));
    }
}
