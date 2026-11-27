package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 外部連携：実行の登録と冪等性（NEW・REPLAY・IN_PROGRESS・RETRY）、手動送信の二者承認、SHA-256。 */
class IntegrationServiceTest {
    private static final String SHA_A = repeat('a');
    private static final String SHA_B = repeat('b');
    private TestDatabase db;
    private TestClock clock;
    private IntegrationService integration;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        integration = new IntegrationService(() -> db, clock, new AuditService(() -> db, clock));
    }

    private static String repeat(char ch) {
        char[] c = new char[64];
        Arrays.fill(c, ch);
        return new String(c);
    }

    private static IntegrationService.RunStart start(String key, String sha) {
        IntegrationService.RunStart s = new IntegrationService.RunStart();
        s.consumer = "zengin-network";
        s.kind = "ZENGIN_INCOMING";
        s.idempotencyKey = key;
        s.payloadSha256 = sha;
        s.requestId = "req-1";
        return s;
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(BankException.class, call).getCode());
    }

    private Map<String, Object> createRequest(String maker) {
        return integration.createRequest(maker, "FX_RATES", "application/json",
                "{\"provider\":\"x\",\"rates\":[]}".getBytes(StandardCharsets.UTF_8), "fx.json", "定例");
    }

    @Test
    void sha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IntegrationService.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void runStatesForTheSameKey() {
        Map<String, Object> first = integration.startRun(start("file-0001", SHA_A));
        assertEquals("NEW", first.get("state"));
        String runId = (String) first.get("runId");
        assertEquals("IN_PROGRESS", integration.startRun(start("file-0001", SHA_A)).get("state"));
        assertError(ErrorCode.IDEMPOTENCY_KEY_REUSED, () -> integration.startRun(start("file-0001", SHA_B)));
        integration.finishRun(runId, IntegrationService.COMPLETED, "SUCCESS", "2 件", 200, "{\"ok\":true}");
        Map<String, Object> replay = integration.startRun(start("file-0001", SHA_A));
        assertEquals("REPLAY", replay.get("state"));
        assertEquals("{\"ok\":true}", replay.get("response"));
        assertFalse(integration.listRuns(10).isEmpty());
    }

    @Test
    void failedRunIsRetried() {
        String runId = (String) integration.startRun(start("file-0002", SHA_A)).get("runId");
        integration.finishRun(runId, IntegrationService.FAILED, "FAILURE", "勘定系の障害", 502, null);
        Map<String, Object> retry = integration.startRun(start("file-0002", SHA_A));
        assertEquals("RETRY", retry.get("state"));
        assertEquals(runId, retry.get("runId"));
        assertEquals(2, ((Number) retry.get("attempts")).intValue());
    }

    @Test
    void finishRunValidatesStatusAndOutcome() {
        String runId = (String) integration.startRun(start("file-0003", SHA_A)).get("runId");
        assertError(ErrorCode.VALIDATION_ERROR, () -> integration.finishRun(runId, "DONE", "SUCCESS", "x", 200, null));
        assertError(ErrorCode.VALIDATION_ERROR, () -> integration.finishRun(runId, IntegrationService.COMPLETED, "OK", "x", 200, null));
    }

    @Test
    void manualSendNeedsASecondSupervisor() {
        String id = (String) createRequest("supervisor1").get("requestId");
        assertEquals("PENDING", integration.listRequests().get(0).get("status"));
        assertError(ErrorCode.INTEGRATION_SAME_PERSON, () -> integration.approve(id, "supervisor1"));
        assertError(ErrorCode.INTEGRATION_SAME_PERSON, () -> integration.reject(id, "supervisor1", "x"));
        assertEquals("APPROVED", integration.approve(id, "supervisor2").get("status"));
        assertError(ErrorCode.INTEGRATION_STATE_INVALID, () -> integration.approve(id, "supervisor2"));
    }

    @Test
    void rejectedRequestCannotBeApproved() {
        String id = (String) createRequest("supervisor1").get("requestId");
        assertEquals("REJECTED", integration.reject(id, "supervisor2", "金額の誤り").get("status"));
        assertError(ErrorCode.INTEGRATION_STATE_INVALID, () -> integration.approve(id, "supervisor2"));
        assertError(ErrorCode.INTEGRATION_REQUEST_NOT_FOUND, () -> integration.approve("no-such-request", "supervisor2"));
    }

    @Test
    void createRequestValidatesContent() {
        assertThrows(BankException.class, () -> integration.createRequest("supervisor1", "FX_RATES", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8), null, null));
        assertThrows(BankException.class, () -> integration.createRequest("supervisor1", "ZENGIN_INCOMING", "text/plain",
                new byte[0], null, null));
    }
}
