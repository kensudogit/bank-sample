package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** リスク管理と顧客 360°：ダッシュボードの集計、顧客の全体像、応対記録。 */
class RiskServiceTest {
    private RiskService risk;

    @BeforeEach
    void setUp() {
        TestDatabase db = TestDatabase.create();
        risk = new RiskService(() -> db, new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0)));
    }

    @Test
    void dashboardHasCreditAndLiquiditySections() {
        Map<String, Object> d = risk.dashboard();
        assertNotNull(d.get("credit"));
        assertNotNull(d.get("liquidity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> credit = (Map<String, Object>) d.get("credit");
        assertEquals(0, ((Number) credit.get("loanCount")).intValue());
    }

    @Test
    void notesAppearInCustomer360() {
        List<Map<String, Object>> notes = risk.addNote("CU0001", "VISIT", "ご挨拶", "teller1");
        assertEquals(1, notes.size());
        Map<String, Object> c360 = risk.customer360("CU0001");
        assertEquals(1, ((List<?>) c360.get("notes")).size());
        assertNotNull(c360.get("segment"));
        assertTrue(((List<?>) c360.get("loans")).isEmpty());
    }

    @Test
    void unknownCustomerIsNotFound() {
        assertEquals(ErrorCode.CUSTOMER_NOT_FOUND, assertThrows(BankException.class, () -> risk.customer360("CU9999")).getCode());
        assertEquals(ErrorCode.CUSTOMER_NOT_FOUND,
                assertThrows(BankException.class, () -> risk.addNote("CU9999", "VISIT", "x", "teller1")).getCode());
    }
}
