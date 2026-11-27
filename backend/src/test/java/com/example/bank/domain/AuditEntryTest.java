package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 監査ログの行：ハッシュの元になる正規形（区切り・エスケープ・冪等キー）と値の保持。 */
class AuditEntryTest {
    @Test
    void canonicalJoinsFieldsWithNewlinesAndTreatsNullAsEmpty() {
        String s = AuditEntry.canonical(1, "2026-11-20 10:00:00", "teller1", "TELLER", "TRANSFER", "1000001",
                "SUCCESS", null, "127.0.0.1", "0000");
        assertEquals("1\n2026-11-20 10:00:00\nteller1\nTELLER\nTRANSFER\n1000001\nSUCCESS\n\n127.0.0.1\n0000\n", s);
    }

    @Test
    void canonicalEscapesBackslashAndNewlineSoFieldsCannotBeForged() {
        // 改行をそのまま入れると、項目の境界をずらした別の行と同じ正規形にできてしまう
        String forged = AuditEntry.canonical(1, "t", "u", "r", "a", "x\ny", "o", "d\\", "ip", "p");
        assertTrue(forged.contains("x\\ny\n"));
        assertTrue(forged.contains("d\\\\\n"));
        assertNotEquals(forged, AuditEntry.canonical(1, "t", "u", "r", "a", "x", "y", "o", "d\\", "ip"));
    }

    @Test
    void requestKeyIsAppendedOnlyWhenPresent() {
        String without = AuditEntry.canonical(1, "t", "u", "r", "a", "x", "o", "d", "ip", "p");
        assertEquals(without, AuditEntry.canonical(1, "t", "u", "r", "a", "x", "o", "d", "ip", "p", null));
        assertEquals(without + "key:k-123\n",
                AuditEntry.canonical(1, "t", "u", "r", "a", "x", "o", "d", "ip", "p", "k-123"));
    }

    @Test
    void withRequestKeySetsKeyAndReturnsSameEntry() {
        AuditEntry e = new AuditEntry(7, "t", "u", "TELLER", "LOGIN", "x", "SUCCESS", "d", "ip", "prev", "row");
        assertNull(e.getRequestKey());
        assertSame(e, e.withRequestKey("k"));
        assertEquals("k", e.getRequestKey());
        assertEquals(7, e.getAuditId());
        assertEquals("t", e.getOccurredAt());
        assertEquals("u", e.getUserId());
        assertEquals("TELLER", e.getUserRole());
        assertEquals("LOGIN", e.getAction());
        assertEquals("x", e.getTarget());
        assertEquals("SUCCESS", e.getOutcome());
        assertEquals("d", e.getDetail());
        assertEquals("ip", e.getClientIp());
        assertEquals("prev", e.getPrevHash());
        assertEquals("row", e.getRowHash());
    }
}
