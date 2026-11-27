package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 利息決算の集計：決算済みの行だけを合計し、見送った件数も数える。 */
class SettlementSummaryTest {
    private static InterestResult r(String gross, String national, String local, boolean settled) {
        return new InterestResult("1", "ORDINARY", "a", "b", 1, BigDecimal.ZERO, new BigDecimal(gross),
                new BigDecimal(national), new BigDecimal(local), settled, null);
    }

    @Test
    void totalsOnlySettledRows() {
        SettlementSummary s = new SettlementSummary("2026-11-20", Arrays.asList(
                r("1000", "153", "50", true), r("500", "76", "25", true), r("9999", "1", "1", false)));
        assertEquals("2026-11-20", s.getAsOf());
        assertEquals(3, s.getResults().size());
        assertEquals(2, s.getSettledCount());
        assertEquals(1, s.getSkippedCount());
        assertEquals(new BigDecimal("1500"), s.getTotalGross());
        assertEquals(new BigDecimal("304"), s.getTotalTax());
        assertEquals(new BigDecimal("1196"), s.getTotalNet());
    }

    @Test
    void emptyIsZero() {
        SettlementSummary s = new SettlementSummary("2026-11-20", Collections.<InterestResult>emptyList());
        assertEquals(0, s.getSettledCount());
        assertEquals(BigDecimal.ZERO, s.getTotalGross());
        assertEquals(BigDecimal.ZERO, s.getTotalNet());
    }
}
