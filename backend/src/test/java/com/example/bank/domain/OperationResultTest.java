package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 操作の結果：コンストラクタの値をそのまま返す。 */
class OperationResultTest {
    @Test
    void exposesAllFields() {
        OperationResult r = new OperationResult("TRANSFER", "1000001", "1000002", new BigDecimal("100"),
                new BigDecimal("900"), "2026-11-20 10:00");
        assertEquals("TRANSFER", r.getOperation());
        assertEquals("1000001", r.getAccountNo());
        assertEquals("1000002", r.getCounterpartyAccountNo());
        assertEquals(new BigDecimal("100"), r.getAmount());
        assertEquals(new BigDecimal("900"), r.getBalanceAfter());
        assertEquals("2026-11-20 10:00", r.getExecutedAt());
    }
}
