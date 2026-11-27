package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 口座詳細：口座と取引明細をそのまま保持する。 */
class AccountDetailTest {
    @Test
    void holdsAccountAndTransactions() {
        Account a = new Account("1000001", "CU0001", "山田 太郎", "ORDINARY", BigDecimal.TEN, "ACTIVE",
                null, null, null, null, null, null);
        List<AccountTransaction> txs = Collections.singletonList(
                new AccountTransaction(1, "1000001", "DEPOSIT", BigDecimal.TEN, BigDecimal.TEN, null, "入金", "2026-01-01"));
        AccountDetail d = new AccountDetail(a, txs);
        assertSame(a, d.getAccount());
        assertSame(txs, d.getTransactions());
    }
}
