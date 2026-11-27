package com.example.bank.action;

import com.example.bank.service.AmlService;
import com.example.bank.service.RiskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** リスク管理・AML の API：ダッシュボード、アラートの状態の検証、顧客 360° と応対記録の入力。 */
class RiskActionTest extends ActionTestBase {
    private RiskAction risk() {
        return as(SUPERVISOR, new RiskAction(new RiskService(() -> db, clock), new AmlService(() -> db, clock)));
    }

    @Test
    void dashboardScanAndAlerts() {
        RiskAction dashboard = risk();
        assertEquals("success", dashboard.dashboard());
        RiskAction scan = risk();
        assertEquals("success", scan.scan());
        RiskAction open = risk();
        open.setStatus("OPEN");
        assertEquals("success", open.alerts());
        RiskAction bad = risk();
        bad.setStatus("DONE");
        assertEquals("badRequest", bad.alerts());
    }

    @Test
    void alertUpdateValidation() {
        RiskAction badId = risk();
        badId.setAlertId("x");
        badId.setStatus("CLOSED");
        assertEquals("badRequest", badId.alertUpdate());
        RiskAction unknown = risk();
        unknown.setAlertId("999999");
        unknown.setStatus("INVESTIGATING");
        assertEquals("notFound", unknown.alertUpdate());
        assertEquals("alert 999999 INVESTIGATING", unknown.getAuditTarget());
    }

    @Test
    void customer360AndNotes() {
        RiskAction bad = risk();
        bad.setCustomerId("X1");
        assertEquals("badRequest", bad.customer360());
        RiskAction badType = risk();
        badType.setCustomerId("CU0001");
        badType.setNoteType("GOSSIP");
        badType.setContent("x");
        assertEquals("badRequest", badType.addNote());
        RiskAction note = risk();
        note.setCustomerId("CU0001");
        note.setNoteType("CALL");
        note.setContent("ご連絡");
        assertEquals("success", note.addNote());
        assertEquals(1, ((List<?>) data(note)).size());
        RiskAction c360 = risk();
        c360.setCustomerId("CU0001");
        assertEquals("success", c360.customer360());
        assertEquals("CU0001", c360.getAuditTarget());
    }
}
