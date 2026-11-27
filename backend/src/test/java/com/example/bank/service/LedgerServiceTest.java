package com.example.bank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 取引明細のハッシュチェーン：正規形とハッシュ、口座ごとの先頭、整合性の検証と改ざん検知、追跡。 */
class LedgerServiceTest {
    private TestDatabase db;
    private BankService bank;
    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        ledger = new LedgerService(() -> db, clock);
    }

    @Test
    void canonicalAndHashAreDeterministic() {
        String a = LedgerService.canonical("1000001", "DEPOSIT", new BigDecimal("100"), new BigDecimal("1100"), null,
                "入金", "k", "2026-11-20T10:00:00", "0");
        String b = LedgerService.canonical("1000001", "DEPOSIT", new BigDecimal("100"), new BigDecimal("1100"), null,
                "入金", "k", "2026-11-20T10:00:00", "0");
        String other = LedgerService.canonical("1000001", "DEPOSIT", new BigDecimal("101"), new BigDecimal("1100"), null,
                "入金", "k", "2026-11-20T10:00:00", "0");
        assertEquals(a, b);
        assertEquals(LedgerService.hash(a), LedgerService.hash(b));
        assertNotEquals(LedgerService.hash(a), LedgerService.hash(other));
        assertEquals(64, LedgerService.hash(a).length());
    }

    @Test
    void lastHashMovesWithNewTransactions() throws Exception {
        // 初期データの明細はハッシュが未設定（null）。backfill でチェーンを付けてから比べる
        ledger.backfill();
        String before;
        try (Connection c = db.getConnection()) {
            before = LedgerService.lastHash(c, "1000004");
        }
        assertNotNull(before);
        bank.deposit("1000004", BigDecimal.TEN, null, UUID.randomUUID().toString());
        try (Connection c = db.getConnection()) {
            String after = LedgerService.lastHash(c, "1000004");
            assertNotNull(after);
            assertNotEquals(before, after);
        }
    }

    @Test
    void verifyAndDetectTampering() throws Exception {
        bank.deposit("1000004", BigDecimal.TEN, null, UUID.randomUUID().toString());
        assertEquals(true, ledger.verify(null).get("valid"));
        assertEquals(true, ledger.verify("1000004").get("valid"));
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE account_transaction SET amount = amount + 1 WHERE account_no = '1000004' "
                    + "AND tx_id = (SELECT MAX(tx_id) FROM account_transaction WHERE account_no = '1000004')");
        }
        assertEquals(false, ledger.verify("1000004").get("valid"));
    }

    @Test
    void backfillIsIdempotent() {
        ledger.backfill();
        assertEquals(0, ledger.backfill());
        assertEquals(true, ledger.verify(null).get("valid"));
    }

    @Test
    void traceListsRecentEntries() {
        String k = UUID.randomUUID().toString();
        bank.deposit("1000004", BigDecimal.TEN, "追跡", k);
        List<Map<String, Object>> trace = ledger.trace("1000004", 5);
        assertFalse(trace.isEmpty());
        assertTrue(trace.size() <= 5);
    }
}
