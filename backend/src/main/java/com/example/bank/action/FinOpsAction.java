package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.ErrorCode;
import com.example.bank.service.BankService;
import com.example.bank.service.FinOpsService;
import org.apache.struts2.ServletActionContext;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 課金の可視化・コスト予測と、AI Agent のメーター。
 * <ul>
 *   <li>usage-ingest：Kong の http-log（ゲートウェイの共有シークレットで送信元を確認。Kong の公開経路からは呼べない）</li>
 *   <li>agent-usage / agent-budget：AI Agent（アプリのみのトークン・ロール AGENT）。Agent の ID はトークンから決まる</li>
 *   <li>finops-*：役席の画面（集計・予測・Agent の実行・単価表・予算・Railway の取込み）</li>
 * </ul>
 */
public class FinOpsAction extends BaseApiAction {
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private final FinOpsService finops;

    private String month;
    private String subjectType;
    private String subjectId;
    private String monthlyBudgetJpy;
    private String from;
    private String to;
    // agent-usage
    private String runId;
    private String principal;
    private String principalOid;
    private String status;
    private String model;
    private String steps;
    private String toolCalls;
    private String deniedCalls;
    private String inputTokens;
    private String outputTokens;
    private String cacheReadTokens;
    private String cacheWriteTokens;
    private String promptSha256;
    private String traceId;
    private String summary;
    private String startedAt;

    public FinOpsAction() { this(FinOpsService.getDefault()); }

    public FinOpsAction(FinOpsService finops) {
        super(BankService.getDefault());
        this.finops = finops;
    }

    /** Kong の http-log（JSON の配列または 1 件）を記録する。 */
    @SuppressWarnings("unchecked")
    public String ingest() {
        try {
            String body = readBody();
            Object json = new org.apache.struts2.json.JSONReader().read(body);
            List<?> entries = json instanceof List ? (List<?>) json : json instanceof Map ? Collections.singletonList(json) : null;
            if (entries == null) throw new BankException(ErrorCode.VALIDATION_ERROR);
            return ok(Collections.singletonMap("recorded", finops.ingestGateway(entries)));
        } catch (org.apache.struts2.json.JSONException | IOException e) {
            return fail(ErrorCode.VALIDATION_ERROR);
        } catch (BankException e) {
            return fail(e);
        }
    }

    private static String readBody() throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = ServletActionContext.getRequest().getReader();
        char[] buf = new char[8192];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
            if (sb.length() > MAX_BODY) throw new IOException("too large");
        }
        return sb.toString();
    }

    /** Agent の実行と LLM のトークン数を記録する。 */
    public String agentUsage() {
        try {
            FinOpsService.AgentRun r = new FinOpsService.AgentRun();
            r.runId = runId;
            r.principal = principal;
            r.principalOid = principalOid;
            r.status = status;
            r.model = model;
            r.steps = integer(steps, 0, 100);
            r.toolCalls = text(toolCalls, 1000);
            r.deniedCalls = deniedCalls == null ? 0 : integer(deniedCalls, 0, 100);
            r.inputTokens = count(inputTokens);
            r.outputTokens = count(outputTokens);
            r.cacheReadTokens = count(cacheReadTokens);
            r.cacheWriteTokens = count(cacheWriteTokens);
            r.promptSha256 = promptSha256;
            r.traceId = traceId;
            r.summary = text(summary, 400);
            r.startedAtEpochMs = startedAt == null || !startedAt.matches("^[0-9]{10,14}$") ? null : Long.parseLong(startedAt);
            return ok(finops.recordAgentRun(agentId(), r));
        } catch (BankException e) {
            return fail(e);
        }
    }

    /** Agent の当月の予算の状況（新しい実行を許すか）。 */
    public String agentBudget() { return ok(finops.agentBudget(agentId())); }

    // Agent の ID は、Kong が検証したトークンの azp（ApiGuardInterceptor が利用者 ID「agent:<azp>」にする）
    private String agentId() {
        String id = currentUser() == null ? null : currentUser().getUserId();
        if (id == null || !id.startsWith("agent:")) throw new BankException(ErrorCode.FORBIDDEN);
        return id.substring("agent:".length());
    }

    public String summaryView() {
        try {
            YearMonth ym = month == null || month.trim().isEmpty() ? null : YearMonth.parse(month.trim());
            return ok(finops.summary(ym));
        } catch (DateTimeParseException e) {
            return fail(ErrorCode.VALIDATION_ERROR);
        } catch (BankException e) {
            return fail(e);
        }
    }

    public String agentRuns() { return ok(finops.agentRuns()); }

    public String rateCard() { return ok(finops.rateCard()); }

    public String budgetUpdate() {
        try {
            return ok(finops.updateBudget(oneOf(subjectType, "CONSUMER", "AGENT", "CHANNEL", "INFRA"), required(subjectId, 64),
                    yen(monthlyBudgetJpy).longValueExact(), who()));
        } catch (BankException e) {
            return fail(e);
        } catch (ArithmeticException | NullPointerException e) {
            return fail(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Railway の使用量を取り込む（既定は当月 1 日〜今日）。 */
    public String infraSync() {
        try {
            LocalDate t = to == null ? LocalDate.now() : date(to);
            LocalDate f = from == null ? t.withDayOfMonth(1) : date(from);
            return ok(finops.syncInfra(f, t));
        } catch (BankException e) {
            return fail(e);
        }
    }

    private static long count(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        if (!s.trim().matches("^[0-9]{1,10}$")) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Long.parseLong(s.trim());
    }

    @Override
    public String getAuditTarget() {
        if (runId != null) return "run:" + runId;
        if (subjectId != null) return subjectType + ":" + subjectId;
        return month;
    }

    @StrutsParameter public void setMonth(String v) { month = v; }
    @StrutsParameter public void setSubjectType(String v) { subjectType = v; }
    @StrutsParameter public void setSubjectId(String v) { subjectId = v; }
    @StrutsParameter public void setMonthlyBudgetJpy(String v) { monthlyBudgetJpy = v; }
    @StrutsParameter public void setFrom(String v) { from = v; }
    @StrutsParameter public void setTo(String v) { to = v; }
    @StrutsParameter public void setRunId(String v) { runId = v; }
    @StrutsParameter public void setPrincipal(String v) { principal = v; }
    @StrutsParameter public void setPrincipalOid(String v) { principalOid = v; }
    @StrutsParameter public void setStatus(String v) { status = v; }
    @StrutsParameter public void setModel(String v) { model = v; }
    @StrutsParameter public void setSteps(String v) { steps = v; }
    @StrutsParameter public void setToolCalls(String v) { toolCalls = v; }
    @StrutsParameter public void setDeniedCalls(String v) { deniedCalls = v; }
    @StrutsParameter public void setInputTokens(String v) { inputTokens = v; }
    @StrutsParameter public void setOutputTokens(String v) { outputTokens = v; }
    @StrutsParameter public void setCacheReadTokens(String v) { cacheReadTokens = v; }
    @StrutsParameter public void setCacheWriteTokens(String v) { cacheWriteTokens = v; }
    @StrutsParameter public void setPromptSha256(String v) { promptSha256 = v; }
    @StrutsParameter public void setTraceId(String v) { traceId = v; }
    @StrutsParameter public void setSummary(String v) { summary = v; }
    @StrutsParameter public void setStartedAt(String v) { startedAt = v; }
}
