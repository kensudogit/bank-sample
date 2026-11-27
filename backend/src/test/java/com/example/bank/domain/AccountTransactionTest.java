package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 取引明細：コンストラクタの値をそのまま返す。 */
class AccountTransactionTest {
    @Test
    void exposesAllFields() {
        AccountTransaction t = new AccountTransaction(42, "1000001", "TRANSFER_OUT", new BigDecimal("3000"),
                new BigDecimal("7000"), "1000002", "振替", "2026-11-20 10:00");
        assertEquals(42, t.getTxId());
        assertEquals("1000001", t.getAccountNo());
        assertEquals("TRANSFER_OUT", t.getTxType());
        assertEquals(new BigDecimal("3000"), t.getAmount());
        assertEquals(new BigDecimal("7000"), t.getBalanceAfter());
        assertEquals("1000002", t.getCounterpartyAccountNo());
        assertEquals("振替", t.getMemo());
        assertEquals("2026-11-20 10:00", t.getCreatedAt());
    }
}
