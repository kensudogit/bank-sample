package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** 取引明細のページ：次のページの有無と、集計値の保持。 */
class TransactionPageTest {
    private static TransactionPage page(int page, int size, long total) {
        return new TransactionPage(Collections.<AccountTransaction>emptyList(), page, size, total, BigDecimal.ONE,
                BigDecimal.TEN, new BigDecimal("20"), new BigDecimal("11"));
    }

    @Test
    void hasNextWhenMoreRowsRemain() {
        assertTrue(page(1, 50, 51).isHasNext());
        assertFalse(page(1, 50, 50).isHasNext());
        assertFalse(page(2, 50, 100).isHasNext());
        assertTrue(page(2, 50, 101).isHasNext());
        assertFalse(page(1, 50, 0).isHasNext());
    }

    @Test
    void hasNextDoesNotOverflowForLargePages() {
        // page * size を int で掛けると桁あふれして負になり、次のページがあると誤判定する
        assertFalse(page(Integer.MAX_VALUE, 1000, 10).isHasNext());
    }

    @Test
    void exposesTotals() {
        TransactionPage p = page(3, 20, 99);
        assertTrue(p.getItems().isEmpty());
        assertEquals(3, p.getPage());
        assertEquals(20, p.getSize());
        assertEquals(99, p.getTotal());
        assertEquals(BigDecimal.ONE, p.getOpeningBalance());
        assertEquals(BigDecimal.TEN, p.getClosingBalance());
        assertEquals(new BigDecimal("20"), p.getTotalIn());
        assertEquals(new BigDecimal("11"), p.getTotalOut());
    }
}
