package com.example.bank.service;

import com.example.bank.domain.AuditEntry;
import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 監査ログ：記録（長い値は切り詰め）、検索、ハッシュチェーンの検証、日次アンカー、保存期間の削除。 */
class AuditServiceTest {
    private static final LoginUser TELLER = new LoginUser("teller1", "窓口", LoginUser.TELLER, null, false);
    private TestDatabase db;
    private TestClock clock;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        audit = new AuditService(() -> db, clock);
    }

    @Test
    void recordAndSearch() {
        audit.record(TELLER, null, "deposit", "1000001 100", AuditService.SUCCESS, null, "10.0.0.1");
        audit.record(null, "unknown", "login", null, AuditService.FAILURE, "AUTH_FAILED", "10.0.0.2", "key-1");
        Map<String, Object> all = audit.search(null, null, null, null, null, 1, 50);
        assertEquals(2L, all.get("total"));
        assertEquals(false, all.get("hasNext"));
        @SuppressWarnings("unchecked")
        List<AuditEntry> failures = (List<AuditEntry>) audit.search(null, null, null, null, AuditService.FAILURE, 1, 50).get("items");
        assertEquals(1, failures.size());
        assertEquals("unknown", failures.get(0).getUserId());
        assertNull(failures.get(0).getUserRole());
        assertEquals(1L, audit.search(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20), "teller1", null, null, 1, 50).get("total"));
        assertEquals(0L, audit.search(LocalDate.of(2026, 11, 21), null, null, null, null, 1, 50).get("total"));
    }

    @Test
    void longValuesAreClippedInsteadOfFailing() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1000; i++) longText.append('x');
        audit.record(TELLER, null, longText.toString(), longText.toString(), AuditService.SUCCESS, longText.toString(), "ip");
        @SuppressWarnings("unchecked")
        List<AuditEntry> items = (List<AuditEntry>) audit.search(null, null, null, null, null, 1, 10).get("items");
        assertEquals(40, items.get(0).getAction().length());
        assertEquals(100, items.get(0).getTarget().length());
        assertEquals(400, items.get(0).getDetail().length());
    }

    @Test
    void searchValidatesPaging() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BankException.class, () -> audit.search(null, null, null, null, null, 0, 50)).getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BankException.class, () -> audit.search(null, null, null, null, null, 1, 201)).getCode());
    }

    @Test
    void verifyDetectsTampering() throws Exception {
        audit.record(TELLER, null, "deposit", "1000001 100", AuditService.SUCCESS, null, "ip");
        audit.record(TELLER, null, "withdraw", "1000001 50", AuditService.SUCCESS, null, "ip");
        assertEquals(true, audit.verify().get("valid"));
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE audit_log SET target = '1000001 999' WHERE action = 'deposit'");
        }
        assertEquals(false, audit.verify().get("valid"));
    }

    @Test
    void anchorOncePerDay() {
        audit.record(TELLER, null, "deposit", null, AuditService.SUCCESS, null, "ip");
        Map<String, Object> first = audit.anchor(LocalDate.of(2026, 11, 20));
        Map<String, Object> second = audit.anchor(LocalDate.of(2026, 11, 20));
        assertNotNull(first);
        assertNotEquals(first, second);
        assertEquals(true, audit.verify().get("valid"));
    }

    @Test
    void purgeKeepsRowsWithinTheRetentionPeriod() {
        audit.record(TELLER, null, "deposit", null, AuditService.SUCCESS, null, "ip");
        assertEquals(0, audit.purgeExpired());
        assertEquals(1L, audit.search(null, null, null, null, null, 1, 10).get("total"));
    }
}
