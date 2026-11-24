package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.SecurityAndOperationsTest.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** 外部連携の強化：実行履歴の永続化と冪等性・再処理、二重入金の防止、二者承認、監査ログの保存期間とアンカー。 */
class IntegrationHardeningTest {
    private TestDatabase db;
    private MutableClock clock;
    private IntegrationService integration;
    private PaymentService payments;
    private BankService bank;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        db = TestDatabase.create();
        clock = new MutableClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        audit = new AuditService(() -> db, clock);
        integration = new IntegrationService(() -> db, clock, audit);
        payments = new PaymentService(() -> db, clock);
        bank = new BankService(() -> db, clock);
    }

    private static final String SHA_A = repeat('a');
    private static final String SHA_B = repeat('b');

    private static String repeat(char ch) {
        char[] c = new char[64];
        Arrays.fill(c, ch);
        return new String(c);
    }

    private IntegrationService.RunStart start(String key, String sha) {
        IntegrationService.RunStart s = new IntegrationService.RunStart();
        s.consumer = "zengin-network";
        s.kind = "ZENGIN_INCOMING";
        s.idempotencyKey = key;
        s.payloadSha256 = sha;
        s.requestId = "req-1";
        return s;
    }

    private void sql(String statement) throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(statement);
        }
    }

    private BigDecimal balance(String no) { return bank.getAccount(no).getBalance(); }

    // ===================== 実行履歴と冪等性 =====================

    /** 完了した実行への再送は、業務処理を繰り返さずに保存した応答を返す。 */
    @Test
    void completedRunIsReplayed() {
        Map<String, Object> first = integration.startRun(start("file-0001", SHA_A));
        assertEquals("NEW", first.get("state"));
        String runId = (String) first.get("runId");
        integration.finishRun(runId, IntegrationService.COMPLETED, "SUCCESS", "2 件：入金 2", 200, "{\"ok\":true}");

        Map<String, Object> again = integration.startRun(start("file-0001", SHA_A));
        assertEquals("REPLAY", again.get("state"));
        assertEquals(runId, again.get("runId"));
        assertEquals("{\"ok\":true}", again.get("response"));
        assertEquals(0, new BigDecimal("200").compareTo(new BigDecimal(String.valueOf(again.get("httpStatus")))));
        // 実行履歴は DB に残る（再起動しても消えない）
        assertEquals(1, integration.listRuns(50).size());
    }

    /** 処理中の実行への再送は IN_PROGRESS。期限（lease）を過ぎた実行と、失敗した実行は再処理できる。 */
    @Test
    void inProgressFailedAndExpiredRuns() {
        String runId = (String) integration.startRun(start("file-0002", SHA_A)).get("runId");
        assertEquals("IN_PROGRESS", integration.startRun(start("file-0002", SHA_A)).get("state"));

        // 異常終了して完了が記録されないまま期限を過ぎた
        clock.set(LocalDateTime.of(2026, 11, 20, 10, 6));
        Map<String, Object> retry = integration.startRun(start("file-0002", SHA_A));
        assertEquals("RETRY", retry.get("state"));
        assertEquals(runId, retry.get("runId"));
        assertEquals(2, retry.get("attempts"));

        integration.finishRun(runId, IntegrationService.FAILED, "FAILURE", "勘定系に接続できません", 502, null);
        Map<String, Object> again = integration.startRun(start("file-0002", SHA_A));
        assertEquals("RETRY", again.get("state"));
        assertEquals(3, again.get("attempts"));
    }

    /** 同じ冪等キーで内容の違う送信は拒否する。 */
    @Test
    void sameKeyDifferentPayloadIsRejected() {
        integration.startRun(start("file-0003", SHA_A));
        BankException e = assertThrows(BankException.class, () -> integration.startRun(start("file-0003", SHA_B)));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getCode());
    }

    /** 同じ送信が同時に届いても、実行として登録されるのは 1 件だけ。 */
    @Test
    void concurrentStartsRegisterOneRun() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return integration.startRun(start("file-0004", SHA_A)).get("state");
            }));
        }
        go.countDown();
        int fresh = 0;
        for (Future<Object> f : fs) if ("NEW".equals(f.get())) fresh++;
        pool.shutdown();
        assertEquals(1, fresh);
        assertEquals(1, integration.listRuns(50).size());
    }

    /** 完了した実行は上書きできない（保存した応答と送信側が受け取った応答を一致させる）。 */
    @Test
    void completedRunCannotBeFinishedAgain() {
        String runId = (String) integration.startRun(start("file-0005", SHA_A)).get("runId");
        integration.finishRun(runId, IntegrationService.COMPLETED, "SUCCESS", "ok", 200, "{}");
        BankException e = assertThrows(BankException.class,
                () -> integration.finishRun(runId, IntegrationService.FAILED, "FAILURE", "x", 502, null));
        assertEquals(ErrorCode.INTEGRATION_STATE_INVALID, e.getCode());
    }

    // ===================== 二重入金の防止（業務側の一意制約） =====================

    /** 同じ被仕向振込を 10 本の要求が同時に取り込んでも、入金は 1 回だけ（残りは重複）。 */
    @Test
    void concurrentIncomingCreditsOnce() throws Exception {
        BigDecimal before = balance("1000003");
        PaymentService.Incoming in = new PaymentService.Incoming();
        in.bankCode = "0005";
        in.referenceNo = "ZG-0005-1120-0042";
        in.remitterNameKana = "ﾃｽﾄ";
        in.accountNo = "1000003";
        in.nameKana = "サトウ ハナコ";
        in.amount = BigDecimal.valueOf(10_000);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return payments.receiveIncoming(Arrays.asList(in), "svc-mule").get(0).get("status");
            }));
        }
        go.countDown();
        int credited = 0;
        int duplicate = 0;
        for (Future<Object> f : fs) {
            Object s = f.get();
            if ("CREDITED".equals(s)) credited++;
            if ("DUPLICATE".equals(s)) duplicate++;
        }
        pool.shutdown();
        assertEquals(1, credited);
        assertEquals(9, duplicate);
        assertEquals(0, before.add(BigDecimal.valueOf(10_000)).compareTo(balance("1000003")));
    }

    /** 同じ請求データを同時に取り込んでも、引落し依頼は 1 件だけ（もう一方は重複として結果を返す）。 */
    @Test
    void concurrentBillingImportIsIdempotent() throws Exception {
        payments.registerMandate("1000004", "ELEC01", "E-8888", "teller1");
        PaymentService.Billing b = new PaymentService.Billing();
        b.billerCode = "ELEC01";
        b.payerNumber = "E-8888";
        b.amount = BigDecimal.valueOf(5000);
        b.dueDate = LocalDate.of(2026, 11, 25);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return payments.importBilling(Arrays.asList(b)).get(0).get("result");
            }));
        }
        go.countDown();
        int taken = 0;
        for (Future<Object> f : fs) if ("取込".equals(f.get())) taken++;
        pool.shutdown();
        assertEquals(1, taken);
        int rows;
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM direct_debit_request r JOIN direct_debit_mandate m "
                     + "ON m.mandate_id = r.mandate_id WHERE m.payer_number = 'E-8888'")) {
            rs.next();
            rows = rs.getInt(1);
        }
        assertEquals(1, rows);
    }

    // ===================== 二者承認（職務分離） =====================

    private Map<String, Object> create(String maker) {
        return integration.createRequest(maker, "FX_RATES", "application/json",
                "{\"provider\":\"x\",\"rates\":[]}".getBytes(StandardCharsets.UTF_8), "fx.json", "定例");
    }

    /** 作成者本人は承認・却下できない。別の役席の承認で送信データ（作成時と同じ内容）が渡される。 */
    @Test
    void makerCannotApproveOwnRequest() {
        String id = (String) create("supervisor1").get("requestId");
        BankException e = assertThrows(BankException.class, () -> integration.approve(id, "supervisor1"));
        assertEquals(ErrorCode.INTEGRATION_SAME_PERSON, e.getCode());
        assertThrows(BankException.class, () -> integration.reject(id, "supervisor1", "x"));

        Map<String, Object> approved = integration.approve(id, "supervisor2");
        assertEquals("APPROVED", approved.get("status"));
        assertEquals("supervisor2", approved.get("approver"));
        assertEquals("{\"provider\":\"x\",\"rates\":[]}",
                new String(Base64.getDecoder().decode((String) approved.get("payloadBase64")), StandardCharsets.UTF_8));
        // 承認済みの依頼をもう一度承認することはできない
        assertEquals(ErrorCode.INTEGRATION_STATE_INVALID,
                assertThrows(BankException.class, () -> integration.approve(id, "supervisor2")).getCode());
    }

    /** アプリを通さずに作成者本人を承認者にしようとしても、DB の CHECK 制約が拒否する。 */
    @Test
    void databaseEnforcesSegregationOfDuties() {
        String id = (String) create("supervisor1").get("requestId");
        assertThrows(SQLException.class, () -> sql("UPDATE integration_request SET approver = 'supervisor1', status = 'APPROVED' "
                + "WHERE request_id = '" + id + "'"));
    }

    /** 送信に失敗（5xx・接続失敗）した依頼は再送でき、成功・入力エラーは送信済み（SENT）になる。 */
    @Test
    void failedDispatchCanBeRetried() {
        String id = (String) create("supervisor1").get("requestId");
        integration.approve(id, "supervisor2");
        assertEquals("FAILED", integration.completeRequest(id, null, 502, "{\"title\":\"core down\"}").get("status"));
        Map<String, Object> again = integration.redispatch(id, "supervisor1");
        assertEquals("APPROVED", again.get("status"));
        assertEquals(2, ((Number) again.get("attempts")).intValue());
        assertNotNull(again.get("payloadBase64"));
        Map<String, Object> done = integration.completeRequest(id, "run-1", 200, "{\"updated\":[]}");
        assertEquals("SENT", done.get("status"));
        assertEquals("{\"updated\":[]}", done.get("result"));
        // 送信済みは再送できない
        assertThrows(BankException.class, () -> integration.redispatch(id, "supervisor1"));
    }

    /** 却下した依頼は承認できない。種類と形式の組み合わせ・空データは登録できない。 */
    @Test
    void rejectAndValidation() {
        String id = (String) create("supervisor1").get("requestId");
        assertEquals("REJECTED", integration.reject(id, "supervisor2", "金額の誤り").get("status"));
        assertThrows(BankException.class, () -> integration.approve(id, "supervisor2"));
        assertThrows(BankException.class, () -> integration.createRequest("supervisor1", "FX_RATES", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8), null, null));
        assertThrows(BankException.class, () -> integration.createRequest("supervisor1", "ZENGIN_INCOMING", "text/plain",
                new byte[0], null, null));
    }

    // ===================== 監査ログの保存期間とアンカー =====================

    /** 保存期間を過ぎた行だけを古い順に削除し、残りの行は起点から検証できる。 */
    @Test
    void expiredAuditRowsArePurgedAndChainStillVerifies() throws Exception {
        clock.set(LocalDateTime.of(2015, 1, 1, 9, 0));
        audit.record(null, "a", "LOGIN", null, AuditService.SUCCESS, null, "127.0.0.1");
        audit.record(null, "b", "LOGIN", null, AuditService.SUCCESS, null, "127.0.0.1");
        clock.set(LocalDateTime.of(2026, 11, 20, 10, 0));
        audit.record(null, "c", "LOGIN", null, AuditService.SUCCESS, null, "127.0.0.1");

        assertEquals(2, audit.purgeExpired());
        assertEquals(0, audit.purgeExpired());
        Map<String, Object> v = audit.verify();
        assertEquals(Boolean.TRUE, v.get("valid"), String.valueOf(v));
        assertEquals(1L, v.get("checked"));
    }

    /** 日次のアンカーと食い違う書き換え（チェーンを作り直す改ざん）を検出する。 */
    @Test
    void anchorDetectsRewrittenChain() throws Exception {
        audit.record(null, "a", "LOGIN", null, AuditService.SUCCESS, null, "127.0.0.1");
        assertNotNull(audit.anchor(LocalDate.of(2026, 11, 20)));
        assertNull(audit.anchor(LocalDate.of(2026, 11, 20)));
        assertEquals(Boolean.TRUE, audit.verify().get("valid"));
        // 行とチェーン先頭のハッシュを両方書き換えても、アンカーと一致しない
        sql("UPDATE audit_log SET row_hash = 'ffff', detail = 'x' WHERE audit_id = 1");
        sql("UPDATE audit_chain SET last_hash = 'ffff'");
        Map<String, Object> v = audit.verify();
        assertEquals(Boolean.FALSE, v.get("valid"));
    }
}
