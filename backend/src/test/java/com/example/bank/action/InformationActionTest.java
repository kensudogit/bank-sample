package com.example.bank.action;

import com.example.bank.service.InformationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 情報系（DW）の API：状態、取込の期間の検証、帳票の種類の検証。 */
class InformationActionTest extends ActionTestBase {
    private InformationAction info() {
        return as(SUPERVISOR, new InformationAction(new InformationService(() -> db, clock)));
    }

    @Test
    void statusIsAvailable() {
        InformationAction a = info();
        assertEquals("success", a.status());
        assertNotNull(data(a));
    }

    @Test
    void loadNeedsBothDatesAndAValidPeriod() {
        InformationAction missing = info();
        missing.setFrom("2026-11-19");
        assertEquals("badRequest", missing.load());
        InformationAction today = info();
        today.setFrom("2026-11-20");
        today.setTo("2026-11-20");
        assertEquals("unprocessable", today.load());
        assertEquals("INVALID_PERIOD", errorCode(today));
        assertEquals("2026-11-20..2026-11-20", today.getAuditTarget());
    }

    @Test
    void loadAPastDay() {
        InformationAction a = info();
        a.setFrom("2026-11-19");
        a.setTo("2026-11-19");
        assertEquals("success", a.load());
    }

    @Test
    void reportTypeMustBeKnown() {
        InformationAction a = info();
        a.setType("NO_SUCH_REPORT");
        assertEquals("badRequest", a.report());
        InformationAction badDate = info();
        badDate.setDate("2026-02-30");
        assertEquals("badRequest", badDate.customers());
    }
}
