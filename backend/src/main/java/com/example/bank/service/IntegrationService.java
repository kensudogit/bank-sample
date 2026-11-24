package com.example.bank.service;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 外部連携（Kong・MuleSoft）の実行履歴と、連携コンソールからの手動送信の二者承認。
 *
 * <h3>実行履歴と冪等性（MuleSoft が呼ぶ。ロール INTEGRATION）</h3>
 * <ul>
 *   <li>{@link #startRun}：利用者・種類・冪等キーの一意制約で、同じ送信を 1 回だけ処理させる。
 *       完了済みなら保存した応答を返し（REPLAY）、処理中なら IN_PROGRESS、失敗・期限切れなら再処理（RETRY）を許す</li>
 *   <li>同じ冪等キーで内容（本文のハッシュ）が違う送信は拒否する（IDEMPOTENCY_KEY_REUSED）</li>
 *   <li>{@link #finishRun}：結果を保存し、監査ログに残す</li>
 * </ul>
 *
 * <h3>二者承認（役席。職務分離）</h3>
 * 作成者が送信データを登録し（PENDING）、別の役席が承認したときだけ送信用にデータを渡す（APPROVED）。
 * 作成者本人は承認できない（アプリと DB の CHECK 制約の両方で保証）。送信結果は {@link #completeRequest} で記録し、
 * 勘定系の障害などで失敗した場合は {@link #redispatch} で同じ冪等キーのまま再送できる（二重に処理されない）。
 */
public class IntegrationService {
    /** 実行の状態：処理中 */
    public static final String PROCESSING = "PROCESSING";
    /** 実行の状態：完了（応答を保存済み。再送には同じ応答を返す） */
    public static final String COMPLETED = "COMPLETED";
    /** 実行の状態：失敗（再処理できる） */
    public static final String FAILED = "FAILED";
    /** 処理中の実行の期限（これを過ぎても完了しない実行は、異常終了とみなして再処理を許す） */
    public static final Duration LEASE = Duration.ofMinutes(5);
    /** 手動送信の上限（Kong の上限と同じ 5 MB） */
    public static final int MAX_PAYLOAD = 5 * 1024 * 1024;
    /** 種類 → 許可する Content-Type */
    private static final Map<String, List<String>> CONTENT_TYPES = new LinkedHashMap<>();

    static {
        CONTENT_TYPES.put("ZENGIN_INCOMING", Arrays.asList("text/plain"));
        CONTENT_TYPES.put("BILLING_IMPORT", Arrays.asList("text/csv", "text/plain"));
        CONTENT_TYPES.put("FX_RATES", Arrays.asList("application/json"));
    }

    private static final IntegrationService DEFAULT =
            new IntegrationService(DataSources::bank, Clock.systemDefaultZone(), AuditService.getDefault());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AuditService audit;

    /**
     * @param dataSource 接続の取得元
     * @param clock      日時の取得元（テストで固定できるように）
     * @param audit      監査ログ
     */
    public IntegrationService(Supplier<DataSource> dataSource, Clock clock, AuditService audit) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.audit = audit;
    }

    /** @return アプリケーション既定のインスタンス */
    public static IntegrationService getDefault() { return DEFAULT; }

    // ===================== 実行履歴（MuleSoft） =====================

    /** 実行の開始要求。 */
    public static final class RunStart {
        public String consumer;
        public String kind;
        public String idempotencyKey;
        public String payloadSha256;
        public String requestId;
        public String operator;
        public String approver;
    }

    /**
     * 実行を開始する（冪等キーで重複を判定する）。
     *
     * @return state（NEW / RETRY / REPLAY / IN_PROGRESS）と runId。REPLAY は保存した httpStatus・response を含む
     * @throws BankException 入力不正（VALIDATION_ERROR）、同じキーで内容が違う（IDEMPOTENCY_KEY_REUSED）場合
     */
    public Map<String, Object> startRun(RunStart s) {
        validateRunStart(s);
        try {
            return Transactions.inTransaction(dataSource.get(), c -> startRunTx(c, s));
        } catch (RuntimeException e) {
            // 同じキーの実行が同時に登録された：もう一方が先に挿入したので、既存の行として判定し直す
            if (!isUniqueViolation(e)) throw e;
            return Transactions.inTransaction(dataSource.get(), c -> startRunTx(c, s));
        }
    }

    private Map<String, Object> startRunTx(Connection c, RunStart s) throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> rows = query(c, "SELECT run_id, payload_sha256, status, http_status, response_json, "
                + "lease_until, attempts FROM integration_run WHERE consumer = ? AND kind = ? AND idempotency_key = ? FOR UPDATE",
                s.consumer, s.kind, s.idempotencyKey);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            String runId = UUID.randomUUID().toString();
            execute(c, "INSERT INTO integration_run (run_id, kind, consumer, idempotency_key, payload_sha256, status, operator, "
                    + "approver, request_id, attempts, started_at, lease_until) VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, 1, ?, ?)",
                    runId, s.kind, s.consumer, s.idempotencyKey, s.payloadSha256, s.operator, s.approver, s.requestId, now,
                    now.plus(LEASE));
            out.put("state", "NEW");
            out.put("runId", runId);
            out.put("attempts", 1);
            return out;
        }
        Map<String, Object> r = rows.get(0);
        // 同じキーで別の内容：送信側のキーの使い回しの誤り。前回の結果を返すと誤解を招くので拒否する
        if (!s.payloadSha256.equals(r.get("payloadSha256"))) throw new BankException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        String runId = (String) r.get("runId");
        out.put("runId", runId);
        String status = (String) r.get("status");
        if (COMPLETED.equals(status)) {
            out.put("state", "REPLAY");
            out.put("httpStatus", r.get("httpStatus"));
            out.put("response", r.get("responseJson"));
            return out;
        }
        LocalDateTime lease = toLocal(r.get("leaseUntil"));
        if (PROCESSING.equals(status) && lease != null && lease.isAfter(now)) {
            out.put("state", "IN_PROGRESS");
            return out;
        }
        // 失敗した実行、または期限切れの処理中の実行を再処理する（業務側の一意制約で二重入金はしない）
        int attempts = ((Number) r.get("attempts")).intValue() + 1;
        execute(c, "UPDATE integration_run SET status = 'PROCESSING', attempts = ?, lease_until = ?, request_id = ?, "
                + "operator = ?, approver = ?, outcome = NULL, finished_at = NULL WHERE run_id = ?",
                attempts, now.plus(LEASE), s.requestId, s.operator, s.approver, runId);
        out.put("state", "RETRY");
        out.put("attempts", attempts);
        return out;
    }

    /**
     * 実行を終える。COMPLETED は応答を保存して以後の再送に同じ応答を返す。FAILED は再処理を許す。
     *
     * @param runId        実行 ID
     * @param status       COMPLETED / FAILED
     * @param outcome      SUCCESS / PARTIAL / FAILURE
     * @param summary      内容の要約（口座番号・氏名は含めない）
     * @param httpStatus   連携先へ返した HTTP ステータス
     * @param responseJson 連携先へ返した応答（COMPLETED のときだけ保存）
     * @return 更新後の実行
     */
    public Map<String, Object> finishRun(String runId, String status, String outcome, String summary, int httpStatus,
                                         String responseJson) {
        if (!COMPLETED.equals(status) && !FAILED.equals(status)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (!"SUCCESS".equals(outcome) && !"PARTIAL".equals(outcome) && !"FAILURE".equals(outcome)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        if (httpStatus < 100 || httpStatus > 599) throw new BankException(ErrorCode.VALIDATION_ERROR);
        Map<String, Object> run = Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            List<Map<String, Object>> rows = query(c, "SELECT * FROM integration_run WHERE run_id = ? FOR UPDATE", runId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.INTEGRATION_RUN_NOT_FOUND, runId);
            Map<String, Object> r = rows.get(0);
            // 完了した実行は上書きしない（保存した応答と、送信側が受け取った応答を一致させるため）
            if (!PROCESSING.equals(r.get("status"))) throw new BankException(ErrorCode.INTEGRATION_STATE_INVALID, String.valueOf(r.get("status")));
            LocalDateTime started = toLocal(r.get("startedAt"));
            long ms = started == null ? 0 : Math.max(0, Duration.between(started, now).toMillis());
            try (PreparedStatement ps = c.prepareStatement("UPDATE integration_run SET status = ?, outcome = ?, summary = ?, "
                    + "http_status = ?, response_json = ?, finished_at = ?, duration_ms = ?, lease_until = NULL WHERE run_id = ?")) {
                ps.setString(1, status);
                ps.setString(2, outcome);
                ps.setString(3, clip(summary, 400));
                ps.setInt(4, httpStatus);
                setClob(ps, 5, COMPLETED.equals(status) ? responseJson : null);
                ps.setTimestamp(6, Timestamp.valueOf(now));
                ps.setLong(7, ms);
                ps.setString(8, runId);
                ps.executeUpdate();
            }
            return runView(c, runId);
        });
        // 誰の送信で（利用者・行員・承認者）、何件をどう処理したかを監査ログに残す
        String who = run.get("operator") == null ? "" : " operator=" + run.get("operator") + " approver=" + run.get("approver");
        audit.record(null, "consumer:" + run.get("consumer"), "INTEGRATION_RUN", run.get("kind") + ":" + runId,
                "FAILURE".equals(outcome) ? AuditService.FAILURE : AuditService.SUCCESS,
                clip(status + " " + outcome + " " + (summary == null ? "" : summary) + who, 400), null,
                clip((String) run.get("idempotencyKey"), 64));
        return run;
    }

    /** 実行履歴（新しい順・最大 limit 件。応答本文は含めない）。 */
    public List<Map<String, Object>> listRuns(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT run_id, kind, consumer, status, outcome, operator, "
                + "approver, request_id, summary, http_status, attempts, started_at, finished_at, duration_ms "
                + "FROM integration_run ORDER BY started_at DESC FETCH FIRST " + n + " ROWS ONLY"));
    }

    private Map<String, Object> runView(Connection c, String runId) throws SQLException {
        return query(c, "SELECT run_id, kind, consumer, idempotency_key, status, outcome, operator, approver, request_id, "
                + "summary, http_status, attempts, started_at, finished_at, duration_ms FROM integration_run WHERE run_id = ?",
                runId).get(0);
    }

    private static void validateRunStart(RunStart s) {
        if (s == null || s.consumer == null || !s.consumer.matches("^[A-Za-z0-9_.-]{1,40}$")
                || s.kind == null || !CONTENT_TYPES.containsKey(s.kind)
                || s.idempotencyKey == null || !s.idempotencyKey.matches("^[A-Za-z0-9_.:-]{8,80}$")
                || s.payloadSha256 == null || !s.payloadSha256.matches("^[0-9a-f]{64}$")
                || (s.requestId != null && !s.requestId.matches("^[A-Za-z0-9-]{1,64}$"))
                || (s.operator != null && s.operator.length() > 30) || (s.approver != null && s.approver.length() > 30)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
    }

    // ===================== 二者承認（連携コンソール） =====================

    /**
     * 送信データを登録する（承認待ち）。
     *
     * @param maker       作成者（役席）
     * @param kind        種類（ZENGIN_INCOMING / BILLING_IMPORT / FX_RATES）
     * @param contentType Content-Type（charset 付きでもよい）
     * @param payload     送信データ
     * @param fileName    元のファイル名（なければ null）
     * @param comment     依頼のコメント（なければ null）
     * @return 登録した依頼（本文は含めない）
     */
    public Map<String, Object> createRequest(String maker, String kind, String contentType, byte[] payload, String fileName,
                                             String comment) {
        List<String> allowed = CONTENT_TYPES.get(kind);
        String base = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (maker == null || allowed == null || !allowed.contains(base) || contentType.length() > 60
                || !contentType.matches("^[A-Za-z0-9/+.;= _-]+$")) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) throw new BankException(ErrorCode.VALIDATION_ERROR);
        String id = UUID.randomUUID().toString();
        String sha = sha256Hex(payload);
        Map<String, Object> view = Transactions.inTransaction(dataSource.get(), c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO integration_request (request_id, kind, content_type, payload, "
                    + "payload_sha256, payload_bytes, file_name, comment_text, status, maker, created_at, attempts) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0)")) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, contentType);
                ps.setBytes(4, payload);
                ps.setString(5, sha);
                ps.setInt(6, payload.length);
                ps.setString(7, clip(fileName, 200));
                ps.setString(8, clip(comment, 200));
                ps.setString(9, maker);
                ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.executeUpdate();
            }
            return requestView(c, id);
        });
        return view;
    }

    /** 依頼の一覧（新しい順・最大 50 件。本文は含めない）。 */
    public List<Map<String, Object>> listRequests() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT " + REQUEST_COLS
                + " FROM integration_request ORDER BY created_at DESC FETCH FIRST 50 ROWS ONLY"));
    }

    /**
     * 承認する（作成者以外の役席）。承認と同時に送信用のデータ（Base64）を返す。
     *
     * @throws BankException 依頼がない（INTEGRATION_REQUEST_NOT_FOUND）、承認待ちでない（INTEGRATION_STATE_INVALID）、
     *                       作成者本人（INTEGRATION_SAME_PERSON）の場合
     */
    public Map<String, Object> approve(String requestId, String approver) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> r = lockRequest(c, requestId);
            if (!"PENDING".equals(r.get("status"))) throw new BankException(ErrorCode.INTEGRATION_STATE_INVALID, String.valueOf(r.get("status")));
            if (approver == null || approver.equals(r.get("maker"))) throw new BankException(ErrorCode.INTEGRATION_SAME_PERSON);
            LocalDateTime now = LocalDateTime.now(clock);
            execute(c, "UPDATE integration_request SET status = 'APPROVED', approver = ?, decided_at = ?, attempts = 1, "
                    + "dispatched_at = ? WHERE request_id = ? AND status = 'PENDING'", approver, now, now, requestId);
            return dispatchView(c, requestId);
        });
    }

    /** 却下する（作成者以外の役席）。 */
    public Map<String, Object> reject(String requestId, String by, String reason) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> r = lockRequest(c, requestId);
            if (!"PENDING".equals(r.get("status"))) throw new BankException(ErrorCode.INTEGRATION_STATE_INVALID, String.valueOf(r.get("status")));
            if (by == null || by.equals(r.get("maker"))) throw new BankException(ErrorCode.INTEGRATION_SAME_PERSON);
            execute(c, "UPDATE integration_request SET status = 'REJECTED', approver = ?, decided_at = ?, reject_reason = ? "
                    + "WHERE request_id = ?", by, LocalDateTime.now(clock), clip(reason, 200), requestId);
            return requestView(c, requestId);
        });
    }

    /**
     * 承認済みで送信に失敗した（または結果が記録されていない）依頼を再送する。承認者・作成者は変わらない。
     * 送信は依頼 ID を冪等キーにするので、先の送信が実は届いていても二重には処理されない。
     */
    public Map<String, Object> redispatch(String requestId, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> r = lockRequest(c, requestId);
            String status = (String) r.get("status");
            if (!"FAILED".equals(status) && !"APPROVED".equals(status)) throw new BankException(ErrorCode.INTEGRATION_STATE_INVALID, status);
            if (by == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            int attempts = ((Number) r.get("attempts")).intValue() + 1;
            execute(c, "UPDATE integration_request SET status = 'APPROVED', attempts = ?, dispatched_at = ? WHERE request_id = ?",
                    attempts, LocalDateTime.now(clock), requestId);
            return dispatchView(c, requestId);
        });
    }

    /**
     * 送信結果を記録する。HTTP 5xx・接続失敗（0）は FAILED（再送できる）、それ以外は SENT。
     *
     * @param httpStatus 連携の応答ステータス（接続できなかった場合は 0）
     */
    public Map<String, Object> completeRequest(String requestId, String runId, int httpStatus, String resultJson) {
        if (httpStatus != 0 && (httpStatus < 100 || httpStatus > 599)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (runId != null && !runId.matches("^[A-Za-z0-9-]{1,36}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            Map<String, Object> r = lockRequest(c, requestId);
            if (!"APPROVED".equals(r.get("status"))) throw new BankException(ErrorCode.INTEGRATION_STATE_INVALID, String.valueOf(r.get("status")));
            String status = httpStatus == 0 || httpStatus >= 500 ? "FAILED" : "SENT";
            try (PreparedStatement ps = c.prepareStatement("UPDATE integration_request SET status = ?, completed_at = ?, run_id = ?, "
                    + "result_status = ?, result_json = ? WHERE request_id = ?")) {
                ps.setString(1, status);
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                ps.setString(3, runId);
                ps.setInt(4, httpStatus);
                setClob(ps, 5, resultJson);
                ps.setString(6, requestId);
                ps.executeUpdate();
            }
            Map<String, Object> view = requestView(c, requestId);
            view.put("result", query(c, "SELECT result_json FROM integration_request WHERE request_id = ?", requestId).get(0).get("resultJson"));
            return view;
        });
    }

    private static final String REQUEST_COLS = "request_id, kind, content_type, payload_sha256, payload_bytes, file_name, "
            + "comment_text, status, maker, created_at, approver, decided_at, reject_reason, attempts, dispatched_at, "
            + "completed_at, run_id, result_status";

    private static Map<String, Object> requestView(Connection c, String id) throws SQLException {
        return query(c, "SELECT " + REQUEST_COLS + " FROM integration_request WHERE request_id = ?", id).get(0);
    }

    // 送信に必要な情報（本文を Base64 で含める）
    private static Map<String, Object> dispatchView(Connection c, String id) throws SQLException {
        Map<String, Object> view = requestView(c, id);
        try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM integration_request WHERE request_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Blob b = rs.getBlob(1);
                byte[] bytes = b.getBytes(1, (int) b.length());
                // 保存後に本文が書き換えられていないこと（承認した内容と送る内容が同じこと）を確かめる
                if (!sha256Hex(bytes).equals(view.get("payloadSha256"))) throw new IllegalStateException("payload hash mismatch: " + id);
                view.put("payloadBase64", Base64.getEncoder().encodeToString(bytes));
            }
        }
        return view;
    }

    private static Map<String, Object> lockRequest(Connection c, String id) throws SQLException {
        if (id == null || !id.matches("^[A-Za-z0-9-]{1,36}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        List<Map<String, Object>> rows = query(c, "SELECT request_id, status, maker, attempts FROM integration_request "
                + "WHERE request_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.INTEGRATION_REQUEST_NOT_FOUND, id);
        return rows.get(0);
    }

    // ===================== helpers =====================

    /** 本文の SHA-256（16 進小文字）。 */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setClob(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) ps.setNull(index, Types.CLOB);
        else ps.setCharacterStream(index, new StringReader(value), value.length());
    }

    private static LocalDateTime toLocal(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime) return (LocalDateTime) v;
        if (v instanceof Timestamp) return ((Timestamp) v).toLocalDateTime();
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }

    static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                SQLException s = (SQLException) t;
                if (s.getErrorCode() == 1 || (s.getSQLState() != null && s.getSQLState().startsWith("23"))) return true;
            }
        }
        return false;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
