package com.example.bank.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 休眠の報告：対象一覧と件数をそのまま保持する。 */
class DormancyReportTest {
    @Test
    void exposesAllFields() {
        List<Account> notice = new ArrayList<>();
        List<Account> dormancy = new ArrayList<>();
        List<Account> dormant = new ArrayList<>();
        DormancyReport r = new DormancyReport("2026-11-20", notice, dormancy, dormant, 3, 2);
        assertEquals("2026-11-20", r.getAsOf());
        assertSame(notice, r.getNoticeTargets());
        assertSame(dormancy, r.getDormancyTargets());
        assertSame(dormant, r.getDormantAccounts());
        assertEquals(3, r.getNoticed());
        assertEquals(2, r.getMadeDormant());
    }
}
