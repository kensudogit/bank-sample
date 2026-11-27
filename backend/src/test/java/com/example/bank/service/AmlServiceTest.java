package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** AML：制裁リスト・高リスク国の照合、名前の類似度、取引モニタリング、アラートの対応状況。 */
class AmlServiceTest {
    private TestDatabase db;
    private BankService bank;
    private AmlService aml;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        TestClock clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        bank = new BankService(() -> db, clock);
        aml = new AmlService(() -> db, clock);
    }

    @Test
    void nameSimilarityIgnoresOrderCaseSymbolsAndTitles() {
        assertEquals(1.0, AmlService.nameSimilarity("Sampleov Ivan Petrov", "petrov, IVAN sampleov"), 1e-9);
        assertEquals(1.0, AmlService.nameSimilarity("Mr. John Smith", "John Smith"), 1e-9);
        assertEquals(1.0, AmlService.nameSimilarity("Demo Trading LLC", "Demo Trading"), 1e-9);
        assertTrue(AmlService.nameSimilarity("John Smith", "Jon Smith") > 0.8);
        assertTrue(AmlService.nameSimilarity("John Smith", "Alice Brown") < 0.5);
        assertEquals(0.0, AmlService.nameSimilarity("", "John"), 1e-9);
        assertEquals(0.0, AmlService.nameSimilarity(null, "John"), 1e-9);
    }

    @Test
    void screeningFindsSanctionsThenHighRiskCountries() throws Exception {
        try (Connection c = db.getConnection()) {
            AmlService.Screening sanctioned = aml.screen(c, "Sampleov Ivan Petrov", "RU");
            assertTrue(sanctioned.hit);
            assertEquals("SANCTIONS", sanctioned.rule);
            assertTrue(sanctioned.score >= 80);

            AmlService.Screening country = aml.screen(c, "Demo Trading", "KP");
            assertTrue(country.hit);
            assertEquals("HIGH_RISK_COUNTRY", country.rule);
            assertEquals(70, country.score);

            AmlService.Screening clean = aml.screen(c, "John Smith", "US");
            assertFalse(clean.hit);
            assertEquals("該当なし", clean.detail);
        }
    }

    @Test
    void scanRaisesLargeCashAlertOnceAndAlertsCanBeWorked() {
        bank.deposit("1000001", new BigDecimal("2500000"), "現金", UUID.randomUUID().toString());
        assertTrue(((Number) aml.scan().get("alerts")).intValue() >= 1);
        assertEquals(0, ((Number) aml.scan().get("alerts")).intValue());
        List<Map<String, Object>> open = aml.listAlerts("OPEN");
        Map<String, Object> alert = open.stream().filter(a -> "LARGE_CASH".equals(a.get("ruleCode"))).findFirst()
                .orElseThrow(AssertionError::new);
        long id = new BigDecimal(alert.get("alertId").toString()).longValue();

        aml.updateAlert(id, "INVESTIGATING", null, "supervisor1");
        assertTrue(aml.listAlerts("INVESTIGATING").stream().anyMatch(a -> id == new BigDecimal(a.get("alertId").toString()).longValue()));
        // 届出・解消には対応内容の記録が必要
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(BankException.class, () -> aml.updateAlert(id, "REPORTED", " ", "supervisor1")).getCode());
        assertEquals("REPORTED", aml.updateAlert(id, "REPORTED", "届出済み", "supervisor1").get("status"));
    }

    @Test
    void unknownAlertIsNotFound() {
        assertEquals(ErrorCode.ALERT_NOT_FOUND,
                assertThrows(BankException.class, () -> aml.updateAlert(999_999, "INVESTIGATING", null, "supervisor1")).getCode());
    }
}
