package com.example.bank.repository;

import com.example.bank.domain.AuditEntry;
import com.example.bank.service.TestDatabase;
import com.example.bank.support.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 監査ログの DB アクセス：ハッシュチェーンの追記、検索、アンカー、保存期間を過ぎた行の削除。 */
class AuditRepositoryTest {
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 20, 10, 0);
    private final AuditRepository repo = new AuditRepository();
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
    void appendChainsHashesAndMovesHead() throws SQLException {
        String[] head0 = repo.head(c);
        AuditEntry a = repo.append(c, T, "teller1", "TELLER", "DEPOSIT", "1000001", "SUCCESS", null, "127.0.0.1");
        AuditEntry b = repo.append(c, T.plusMinutes(1), "teller1", "TELLER", "WITHDRAW", "1000001", "FAILURE",
                "INSUFFICIENT_FUNDS", "127.0.0.1", "key-1");
        assertEquals(head0[1], a.getPrevHash());
        assertEquals(a.getRowHash(), b.getPrevHash());
        assertEquals(PasswordHasher.sha256Hex(AuditEntry.canonical(a.getAuditId(), a.getOccurredAt(), a.getUserId(),
                a.getUserRole(), a.getAction(), a.getTarget(), a.getOutcome(), a.getDetail(), a.getClientIp(),
                a.getPrevHash())), a.getRowHash());
        String[] head = repo.head(c);
        assertEquals(String.valueOf(b.getAuditId()), head[0]);
        assertEquals(b.getRowHash(), head[1]);
        assertEquals("key-1", b.getRequestKey());
    }

    @Test
    void searchAndCountWithFilters() throws SQLException {
        repo.append(c, T, "teller1", "TELLER", "DEPOSIT", "1000001", "SUCCESS", null, "ip");
        repo.append(c, T.plusHours(1), "teller1", "TELLER", "DEPOSIT", "1000001", "FAILURE", null, "ip");
        repo.append(c, T.plusHours(2), "supervisor1", "SUPERVISOR", "CLOSE", "1000004", "SUCCESS", null, "ip");
        AuditRepository.Filter byUser = new AuditRepository.Filter(null, null, "teller1", null, null);
        assertEquals(2, repo.count(c, byUser));
        AuditRepository.Filter failures = new AuditRepository.Filter(null, null, null, null, "FAILURE");
        assertEquals(1, repo.count(c, failures));
        AuditRepository.Filter window = new AuditRepository.Filter(T, T.plusHours(2), null, "DEPOSIT", null);
        List<AuditEntry> found = repo.search(c, window, 0, 10);
        assertEquals(2, found.size());
        // 新しい順
        assertTrue(found.get(0).getAuditId() > found.get(1).getAuditId());
        assertEquals(1, repo.search(c, window, 1, 10).size());
    }

    @Test
    void chunkAfterReturnsIdOrder() throws SQLException {
        AuditEntry a = repo.append(c, T, "u", "TELLER", "A", null, "SUCCESS", null, null);
        AuditEntry b = repo.append(c, T, "u", "TELLER", "B", null, "SUCCESS", null, null);
        List<AuditEntry> chunk = repo.chunkAfter(c, a.getAuditId() - 1, 10);
        assertEquals(a.getAuditId(), chunk.get(0).getAuditId());
        assertEquals(b.getAuditId(), chunk.get(1).getAuditId());
        assertEquals(1, repo.chunkAfter(c, a.getAuditId(), 1).size());
    }

    @Test
    void anchorIsRecordedOncePerDay() throws SQLException {
        AuditEntry a = repo.append(c, T, "u", "TELLER", "A", null, "SUCCESS", null, null);
        String[] anchored = repo.anchor(c, LocalDate.of(2026, 11, 20), T);
        assertNotNull(anchored);
        assertEquals(String.valueOf(a.getAuditId()), anchored[0]);
        assertNull(repo.anchor(c, LocalDate.of(2026, 11, 20), T.plusHours(1)));
        List<String[]> after = repo.anchorsAfter(c, 0);
        assertEquals(1, after.size());
        assertEquals(a.getRowHash(), after.get(0)[2]);
        assertTrue(repo.anchorsAfter(c, a.getAuditId()).isEmpty());
    }

    @Test
    void purgeMovesTheBaseToTheLastDeletedRow() throws SQLException {
        String[] base0 = repo.base(c);
        AuditEntry old1 = repo.append(c, T.minusYears(11), "u", "TELLER", "OLD1", null, "SUCCESS", null, null);
        AuditEntry old2 = repo.append(c, T.minusYears(11).plusDays(1), "u", "TELLER", "OLD2", null, "SUCCESS", null, null);
        AuditEntry recent = repo.append(c, T, "u", "TELLER", "NEW", null, "SUCCESS", null, null);
        assertNotNull(base0);
        assertEquals(2, repo.purgeBefore(c, T.minusYears(10), 100));
        String[] base = repo.base(c);
        assertEquals(String.valueOf(old2.getAuditId()), base[0]);
        assertEquals(old2.getRowHash(), base[1]);
        // 残った行はチェーンが続いている（起点のハッシュが次の行の prev）
        assertEquals(base[1], repo.chunkAfter(c, Long.parseLong(base[0]), 1).get(0).getPrevHash());
        assertEquals(recent.getAuditId(), repo.chunkAfter(c, Long.parseLong(base[0]), 1).get(0).getAuditId());
        assertTrue(old1.getAuditId() < old2.getAuditId());
    }

    @Test
    void purgeRespectsTheBatchLimit() throws SQLException {
        for (int i = 0; i < 3; i++) repo.append(c, T.minusYears(11).plusDays(i), "u", "TELLER", "OLD", null, "SUCCESS", null, null);
        assertEquals(2, repo.purgeBefore(c, T.minusYears(10), 2));
        assertEquals(1, repo.purgeBefore(c, T.minusYears(10), 2));
        assertEquals(0, repo.purgeBefore(c, T.minusYears(10), 2));
    }
}
