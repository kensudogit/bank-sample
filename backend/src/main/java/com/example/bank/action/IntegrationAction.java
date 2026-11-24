package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.IntegrationService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.util.Base64;

/**
 * 外部連携の API。
 * <ul>
 *   <li>integration-run-start / integration-run-finish / integration-runs：MuleSoft（ロール INTEGRATION）が実行履歴を記録・参照する</li>
 *   <li>integration-request-*：連携コンソール（役席）の手動送信の二者承認</li>
 * </ul>
 * 権限は struts.xml で設定する。
 */
public class IntegrationAction extends BaseApiAction {
    private final IntegrationService integration;

    // 実行履歴
    private String consumer;
    private String kind;
    private String idempotencyKey;
    private String payloadSha256;
    private String correlationId;
    private String operator;
    private String approver;
    private String runId;
    private String status;
    private String outcome;
    private String summary;
    private String httpStatus;
    private String responseBody;
    private String limit;
    // 手動送信
    private String requestId;
    private String contentType;
    private String payloadBase64;
    private String fileName;
    private String comment;
    private String reason;
    private String resultBody;

    /** 既定のサービスで生成する（Struts から）。 */
    public IntegrationAction() { this(IntegrationService.getDefault()); }

    /** @param integration 連携サービス（テストでは差し替えたもの） */
    public IntegrationAction(IntegrationService integration) {
        super(BankService.getDefault());
        this.integration = integration;
    }

    /** @return success（state・runId。REPLAY なら保存した応答） */
    public String runStart() {
        try {
            IntegrationService.RunStart s = new IntegrationService.RunStart();
            s.consumer = consumer;
            s.kind = kind;
            s.idempotencyKey = idempotencyKey;
            s.payloadSha256 = payloadSha256;
            s.requestId = correlationId;
            s.operator = text(operator, 30);
            s.approver = text(approver, 30);
            return ok(integration.startRun(s));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（更新後の実行） */
    public String runFinish() {
        try {
            return ok(integration.finishRun(runId, status, outcome, summary, integer(httpStatus, 100, 599), responseBody));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（実行履歴。新しい順） */
    public String runs() {
        try {
            return ok(integration.listRuns(limit == null ? 50 : integer(limit, 1, 200)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（手動送信の依頼の一覧） */
    public String requests() { return ok(integration.listRequests()); }

    /** @return success（登録した依頼。承認待ち） */
    public String requestCreate() {
        try {
            byte[] payload;
            try {
                payload = payloadBase64 == null ? null : Base64.getDecoder().decode(payloadBase64);
            } catch (IllegalArgumentException e) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            return ok(integration.createRequest(who(), kind, contentType, payload, text(fileName, 200), text(comment, 200)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（承認した依頼と、送信するデータ） */
    public String requestApprove() {
        try {
            return ok(integration.approve(requestId, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（却下した依頼） */
    public String requestReject() {
        try {
            return ok(integration.reject(requestId, who(), required(reason, 200)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（再送する依頼と、送信するデータ） */
    public String requestRedispatch() {
        try {
            return ok(integration.redispatch(requestId, who()));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** @return success（送信結果を記録した依頼） */
    public String requestComplete() {
        try {
            int st = httpStatus == null ? -1 : "0".equals(httpStatus.trim()) ? 0 : integer(httpStatus, 100, 599);
            if (st < 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(integration.completeRequest(requestId, runId, st, resultBody));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @Override
    public String getAuditTarget() {
        if (requestId != null) return "request:" + requestId;
        if (runId != null) return "run:" + runId;
        return kind == null ? null : kind + (idempotencyKey == null ? "" : ":" + idempotencyKey);
    }

    @StrutsParameter public void setConsumer(String v) { consumer = v; }
    @StrutsParameter public void setKind(String v) { kind = v; }
    @StrutsParameter public void setIdempotencyKey(String v) { idempotencyKey = v; }
    @StrutsParameter public void setPayloadSha256(String v) { payloadSha256 = v; }
    @StrutsParameter public void setCorrelationId(String v) { correlationId = v; }
    @StrutsParameter public void setOperator(String v) { operator = v; }
    @StrutsParameter public void setApprover(String v) { approver = v; }
    @StrutsParameter public void setRunId(String v) { runId = v; }
    @StrutsParameter public void setStatus(String v) { status = v; }
    @StrutsParameter public void setOutcome(String v) { outcome = v; }
    @StrutsParameter public void setSummary(String v) { summary = v; }
    @StrutsParameter public void setHttpStatus(String v) { httpStatus = v; }
    @StrutsParameter public void setResponseBody(String v) { responseBody = v; }
    @StrutsParameter public void setLimit(String v) { limit = v; }
    @StrutsParameter public void setRequestId(String v) { requestId = v; }
    @StrutsParameter public void setContentType(String v) { contentType = v; }
    @StrutsParameter public void setPayloadBase64(String v) { payloadBase64 = v; }
    @StrutsParameter public void setFileName(String v) { fileName = v; }
    @StrutsParameter public void setComment(String v) { comment = v; }
    @StrutsParameter public void setReason(String v) { reason = v; }
    @StrutsParameter public void setResultBody(String v) { resultBody = v; }
}
