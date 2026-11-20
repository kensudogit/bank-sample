package com.example.bank.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputRulesTest {

    @Test
    void accountNo() {
        assertTrue(InputRules.isAccountNo("1000001"));
        assertFalse(InputRules.isAccountNo("100000"));
        assertFalse(InputRules.isAccountNo("10000011"));
        assertFalse(InputRules.isAccountNo("10000a1"));
        assertFalse(InputRules.isAccountNo(null));
    }

    @Test
    void amountIsPositiveIntegerString() {
        assertTrue(InputRules.isAmount("1"));
        assertTrue(InputRules.isAmount("999999999"));
        assertFalse(InputRules.isAmount("0"));
        assertFalse(InputRules.isAmount("-1"));
        assertFalse(InputRules.isAmount("1.0"));
        assertFalse(InputRules.isAmount("1e5"));
        assertFalse(InputRules.isAmount("０１"));          // full-width digits
        assertFalse(InputRules.isAmount("1000000000"));  // 10 digits
        assertFalse(InputRules.isAmount(null));
    }

    @Test
    void idempotencyKey() {
        assertTrue(InputRules.isIdempotencyKey("3f2b8c1e-8d6a-4c1e-9b7a-1a2b3c4d5e6f"));
        assertFalse(InputRules.isIdempotencyKey("short"));
        assertFalse(InputRules.isIdempotencyKey("bad key with spaces!!"));
    }

    @Test
    void customerRegistrationFields() {
        assertTrue(InputRules.isKana("ヤマダ タロウ"));
        assertTrue(InputRules.isKana("ヴァン　ゴッホ"));
        assertFalse(InputRules.isKana("やまだ"));
        assertFalse(InputRules.isKana("ﾔﾏﾀﾞ"));   // half-width
        assertFalse(InputRules.isKana(""));
        assertTrue(InputRules.isPostalCode("100-0001"));
        assertTrue(InputRules.isPostalCode("1000001"));
        assertEquals("100-0001", InputRules.normalizePostalCode("1000001"));
        assertFalse(InputRules.isPostalCode("100-001"));
        assertTrue(InputRules.isPhone(InputRules.digitsOnly("090-1234-5678")));
        assertTrue(InputRules.isPhone("0312345678"));
        assertFalse(InputRules.isPhone("9012345678"));
        assertTrue(InputRules.isIdDocType("MY_NUMBER_CARD"));
        assertFalse(InputRules.isIdDocType("HEALTH_INSURANCE"));
        assertTrue(InputRules.isIdDocNumber("AB12345678"));
        assertFalse(InputRules.isIdDocNumber("AB-1234"));
        assertTrue(InputRules.isCustomerId("CU0001"));
        assertFalse(InputRules.isCustomerId("cu0001"));
    }

    @Test
    void datesAndFilters() {
        assertEquals(java.time.LocalDate.of(2026, 2, 28), InputRules.toDate("2026-02-28"));
        assertNull(InputRules.toDate("2026-02-30"));
        assertNull(InputRules.toDate("2026/02/01"));
        assertFalse(InputRules.isReasonableDate(InputRules.toDate("1800-01-01")));
        assertEquals(2, InputRules.toTxTypes("DEPOSIT, INTEREST").size());
        assertTrue(InputRules.toTxTypes("").isEmpty());
        assertNull(InputRules.toTxTypes("DEPOSIT,DROP_TABLE"));
        assertTrue(InputRules.isAmountOrZero("0"));
        assertFalse(InputRules.isAmountOrZero("00"));
    }

    @Test
    void memo() {
        assertTrue(InputRules.isMemo(null));
        assertTrue(InputRules.isMemo("家賃 3月分"));
        assertFalse(InputRules.isMemo(new String(new char[51]).replace('\0', 'あ')));
        assertFalse(InputRules.isMemo("line\nbreak"));
    }
}
