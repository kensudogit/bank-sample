package com.example.bank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 冪等キーの結果：成功した操作の応答を保存し、同じキーの再送で返す（最初の結果を優先）。 */
class IdempotencyServiceTest {
    private TestDatabase db;
    private BankService bank;
    private IdempotencyService idem;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        idem = new IdempotencyService(() -> db, clock);
    }

    @Test
    void storedResultAfterTheOperationCommits() {
        String k = UUID.randomUUID().toString();
        bank.deposit("1000001", BigDecimal.TEN, null, k);
        // 業務はコミット済みだが、結果はまだ保存していない（処理中に見える）
        assertNull(idem.storedResult(k));
        idem.saveResult(k, "deposit|1000001 10", "{\"amount\":10}");
        assertArrayEquals(new String[]{"deposit|1000001 10", "{\"amount\":10}"}, idem.storedResult(k));
    }

    @Test
    void firstResultWins() {
        String k = UUID.randomUUID().toString();
        bank.deposit("1000001", BigDecimal.TEN, null, k);
        idem.saveResult(k, "first", "{}");
        idem.saveResult(k, "second", "{\"x\":1}");
        assertEquals("first", idem.storedResult(k)[0]);
    }

    @Test
    void unknownKeyHasNoResult() {
        assertNull(idem.storedResult("no-such-key-000000"));
    }
}
