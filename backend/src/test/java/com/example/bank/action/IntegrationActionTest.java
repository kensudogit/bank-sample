package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.AuditService;
import com.example.bank.service.IntegrationService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 外部連携の API：実行の登録・終了・一覧、手動送信の作成（Base64）・承認（作成者以外）・完了の入力。 */
class IntegrationActionTest extends ActionTestBase {
    private static final LoginUser MULE = new LoginUser("svc-mule", "連携", LoginUser.INTEGRATION, null, false);

    private IntegrationAction integration(LoginUser user) {
        return as(user, new IntegrationAction(new IntegrationService(() -> db, clock, new AuditService(() -> db, clock))));
    }

    private static String sha(char ch) {
        char[] c = new char[64];
        Arrays.fill(c, ch);
        return new String(c);
    }

    @Test
    void runStartFinishAndList() {
        IntegrationAction start = integration(MULE);
        start.setConsumer("zengin-network");
        start.setKind("ZENGIN_INCOMING");
        start.setIdempotencyKey("file-0001");
        start.setPayloadSha256(sha('a'));
        start.setCorrelationId("req-1");
        assertEquals("success", start.runStart());
        Map<?, ?> run = (Map<?, ?>) data(start);
        assertEquals("NEW", run.get("state"));
        assertEquals("ZENGIN_INCOMING:file-0001", start.getAuditTarget());

        IntegrationAction finish = integration(MULE);
        finish.setRunId((String) run.get("runId"));
        finish.setStatus(IntegrationService.COMPLETED);
        finish.setOutcome("SUCCESS");
        finish.setSummary("1 件");
        finish.setHttpStatus("200");
        assertEquals("success", finish.runFinish());

        IntegrationAction runs = integration(SUPERVISOR);
        runs.setLimit("10");
        assertEquals("success", runs.runs());
        assertEquals(1, ((List<?>) data(runs)).size());
        IntegrationAction badLimit = integration(SUPERVISOR);
        badLimit.setLimit("0");
        assertEquals("badRequest", badLimit.runs());
    }

    private String createRequest(LoginUser maker) {
        IntegrationAction create = integration(maker);
        create.setKind("FX_RATES");
        create.setContentType("application/json");
        create.setPayloadBase64(Base64.getEncoder().encodeToString("{\"provider\":\"x\",\"rates\":[]}".getBytes(StandardCharsets.UTF_8)));
        create.setFileName("fx.json");
        assertEquals("success", create.requestCreate());
        return (String) ((Map<?, ?>) data(create)).get("requestId");
    }

    @Test
    void manualSendIsApprovedByAnotherSupervisor() {
        String id = createRequest(SUPERVISOR);
        IntegrationAction self = integration(SUPERVISOR);
        self.setRequestId(id);
        assertEquals("unprocessable", self.requestApprove());
        assertEquals("INTEGRATION_SAME_PERSON", errorCode(self));
        assertEquals("request:" + id, self.getAuditTarget());

        IntegrationAction other = integration(SUPERVISOR2);
        other.setRequestId(id);
        assertEquals("success", other.requestApprove());
        assertEquals("APPROVED", ((Map<?, ?>) data(other)).get("status"));
        IntegrationAction list = integration(SUPERVISOR);
        assertEquals("success", list.requests());
    }

    @Test
    void inputValidation() {
        IntegrationAction badBase64 = integration(SUPERVISOR);
        badBase64.setKind("FX_RATES");
        badBase64.setContentType("application/json");
        badBase64.setPayloadBase64("***not base64***");
        assertEquals("badRequest", badBase64.requestCreate());

        IntegrationAction rejectWithoutReason = integration(SUPERVISOR2);
        rejectWithoutReason.setRequestId(createRequest(SUPERVISOR));
        assertEquals("badRequest", rejectWithoutReason.requestReject());

        IntegrationAction badStatus = integration(SUPERVISOR);
        badStatus.setRequestId("x");
        badStatus.setHttpStatus("abc");
        assertEquals("badRequest", badStatus.requestComplete());
        IntegrationAction noStatus = integration(SUPERVISOR);
        noStatus.setRequestId("x");
        assertEquals("badRequest", noStatus.requestComplete());
    }
}
