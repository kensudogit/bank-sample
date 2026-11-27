package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 口座：既定値（円・当座貸越なし）、利用可能残高、状態と通貨の判定。 */
class AccountTest {
    private static Account account(String status, String currency, String balance, String overdraft) {
        return new Account("1000001", "CU0001", "山田 太郎", "ORDINARY", new BigDecimal(balance), status,
                "2026-01-01 10:00", "2020-01-01 09:00", null, null, null, null, currency, new BigDecimal(overdraft));
    }

    @Test
    void shortConstructorDefaultsToYenWithoutOverdraft() {
        Account a = new Account("1000001", "CU0001", "山田 太郎", "ORDINARY", new BigDecimal("1000"), "ACTIVE",
                "u", "o", null, null, null, null);
        assertEquals("JPY", a.getCurrency());
        assertEquals(0, a.getOverdraftLimit().signum());
        assertTrue(a.isJpy());
        assertEquals("1000001", a.getAccountNo());
        assertEquals("CU0001", a.getCustomerId());
        assertEquals("山田 太郎", a.getCustomerName());
        assertEquals("ORDINARY", a.getAccountType());
        assertEquals("u", a.getUpdatedAt());
        assertEquals("o", a.getOpenedAt());
        assertNull(a.getClosedAt());
        assertNull(a.getLastActivityAt());
        assertNull(a.getDormancyNotifiedAt());
        assertNull(a.getDormantAt());
    }

    @Test
    void availableBalanceIncludesOverdraftLimit() {
        assertEquals(new BigDecimal("1500"), account("ACTIVE", "JPY", "1000", "500").getAvailableBalance());
        assertEquals(new BigDecimal("-200"), account("ACTIVE", "JPY", "-700", "500").getAvailableBalance());
    }

    @Test
    void activeOnlyWhenStatusIsActive() {
        assertTrue(account("ACTIVE", "JPY", "0", "0").isActive());
        assertFalse(account("FROZEN", "JPY", "0", "0").isActive());
        assertFalse(account("DORMANT", "JPY", "0", "0").isActive());
        assertFalse(account("CLOSED", "JPY", "0", "0").isActive());
    }

    @Test
    void foreignCurrencyIsNotYen() {
        Account usd = account("ACTIVE", "USD", "100", "0");
        assertFalse(usd.isJpy());
        assertEquals("USD", usd.getCurrency());
    }
}
