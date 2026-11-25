// app/assistant と app/finops のテスト：AI アシスタントの質問と実行の記録・提案、コスト・課金の表示と予算の変更・Railway の取込み。
import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import AssistantPage from "@/app/assistant/page";
import FinOpsPage from "@/app/finops/page";
import { resetAgentCsrf } from "@/lib/agent";
import { RawResponse, callsTo, lastBody, mockApi } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

beforeEach(() => resetAgentCsrf());

const runResult = {
  runId: "run-1", traceId: "a".repeat(32), status: "COMPLETED", answer: "残高は 500,000 円です。", model: "claude-opus-5-5", steps: 2,
  toolCalls: [{ name: "get_account", status: "OK", httpStatus: 200, ms: 35 }, { name: "list_customers", status: "DENIED", httpStatus: 403, ms: 12 }],
  proposals: [{ type: "transfer", fromAccountNo: "1000001", toAccountNo: "1000002", amount: 5000, memo: "立替", requiresHumanApproval: true }],
  usage: { inputTokens: 2000, outputTokens: 400, cacheReadTokens: 1000, cacheWriteTokens: 0 }, costJpy: 2.43,
};

describe("AssistantPage", () => {
  it("顧客には表示しない", async () => {
    await renderPage(<AssistantPage />, { user: { ...tellerUser, role: "CUSTOMER", customerId: "CU0001" } as never, path: "/assistant" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("質問すると、回答・提案（未実行）・ツールの結果・費用と追跡 ID を表示する", async () => {
    mockApi({ "/api/agent/csrf": new RawResponse(200, { token: "csrf-1" }), "/api/agent/runs": new RawResponse(200, runResult) });
    const { user } = await renderPage(<AssistantPage />, { user: tellerUser, path: "/assistant" });
    await user.type(main().getByLabelText("質問"), "1000001 の残高は？");
    await user.click(main().getByRole("button", { name: "質問する" }));
    expect(await main().findByText("残高は 500,000 円です。")).toBeInTheDocument();
    expect(main().getByText(/振込：1000001 → 1000002/)).toBeInTheDocument();
    expect(main().getByRole("link", { name: "振込画面" })).toHaveAttribute("href", "/transfer");
    expect(main().getByText("権限なし")).toHaveClass("badge-FAILURE");
    expect(main().getByText("2.43 円")).toBeInTheDocument();
    expect(main().getByText(/トレース ID a{32}/)).toBeInTheDocument();
    const c = callsTo("/api/agent/runs")[0];
    expect(c.headers).toMatchObject({ "X-CSRF-Token": "csrf-1", "X-Requested-With": "XMLHttpRequest" });
    expect(c.body).toEqual({ prompt: "1000001 の残高は？" });
  });

  it("予算超過で止まったときは状態を表示し、エラーは問題として表示する", async () => {
    mockApi({ "/api/agent/csrf": new RawResponse(200, { token: "csrf-1" }),
      "/api/agent/runs": new RawResponse(200, { ...runResult, status: "BLOCKED", answer: "実行できません：予算", toolCalls: [], proposals: [] }) });
    const { user } = await renderPage(<AssistantPage />, { user: tellerUser, path: "/assistant" });
    await user.click(main().getByRole("button", { name: /米ドル/ }));
    await user.click(main().getByRole("button", { name: "質問する" }));
    expect(await main().findByText("予算超過で停止")).toHaveClass("badge-FAILURE");
    mockApi({ "/api/agent/runs": new RawResponse(502, { title: "AI アシスタントに接続できません" }, "application/problem+json") });
    await user.click(main().getByRole("button", { name: "質問する" }));
    expect(await main().findByRole("alert")).toHaveTextContent("AI アシスタントに接続できません");
  });
});

const summary = {
  month: "2026-11", asOf: "2026-11-25", current: true,
  total: { monthToDateJpy: 1200, forecastJpy: 1500, forecastLowJpy: 1300, forecastHighJpy: 1900, fixedJpy: 0, budgetJpy: 40000, budgetStatus: "OK", lowConfidence: true },
  bySource: [{ source: "GATEWAY", requests: 900, costJpy: 300 }, { source: "LLM", requests: 3, inputTokens: 6000, outputTokens: 900, cacheReadTokens: 3000, costJpy: 800 }, { source: "INFRA", costJpy: 100 }],
  byDepartment: [{ department: "業務企画部", monthToDateJpy: 800, forecastJpy: 6000, budgetJpy: 5500, budgetStatus: "OVER" }],
  bySubject: [{ subjectType: "AGENT", subjectId: "bank-agent", department: "業務企画部", owner: "AI 推進室", requests: 12, tokens: 9900, monthToDateJpy: 800,
    forecastJpy: 6000, forecastLowJpy: 5000, forecastHighJpy: 7000, confidence: "低", anomaly: true, budgetJpy: 5000, hardStop: true, budgetStatus: "OVER" }],
  byApi: [{ apiId: "agent-core-api", source: "GATEWAY", requests: 12, avgLatencyMs: 42.4, errors: 1, costJpy: 0.84 }],
  daily: [{ date: "2026-11-24", gatewayJpy: 100, llmJpy: 300, infraJpy: 50 }, { date: "2026-11-25", gatewayJpy: 200, llmJpy: 500, infraJpy: 50 }],
  infra: [{ serviceName: "oracle", cpuVcpuMin: 3000, memoryGbMin: 90000, egressGb: 0.01, costUsd: 0.6, costJpy: 90, fetchedAt: "2026-11-25T01:00:00" }],
  assumptions: { fxUsdJpy: 150, method: "月末予測 = 当月実績 ＋ 直近 7 日の日平均 × 残り日数 ＋ 固定費", note: "単価は仮置き" },
};

describe("FinOpsPage", () => {
  const mockFinops = () => mockApi({
    "/finops-summary": summary,
    "/finops-agent-runs": [{ runId: "run-1", agentId: "bank-agent", principal: "teller1", startedAt: "2026-11-25T10:00:00", status: "COMPLETED", model: "claude-opus-5-5",
      steps: 2, toolCalls: "get_account:OK,list_customers:DENIED", deniedCalls: 1, inputTokens: 2000, outputTokens: 400, cacheReadTokens: 1000, costJpy: 2.43, traceId: "b".repeat(32), summary: null }],
    "/finops-rate-card": [{ rateKey: "LLM:claude-opus-5-5:INPUT", unit: "MTOK", unitPrice: 4, currency: "USD", description: "入力", updatedAt: "2026-11-25T00:00:00" }],
  });

  it("窓口には表示しない", async () => {
    await renderPage(<FinOpsPage />, { user: tellerUser, path: "/finops" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("当月実績・見込み・部門と配賦先の予算の状況・急増・Agent の追跡・単価表を表示する", async () => {
    mockFinops();
    await renderPage(<FinOpsPage />, { user: supervisorUser, path: "/finops" });
    expect(await main().findByText("1,200 円")).toBeInTheDocument();
    expect(main().getByText(/幅 1,300 円 〜 1,900 円・一部は信頼度「低」/)).toBeInTheDocument();
    expect(main().getAllByText("超過見込み")[0]).toHaveClass("badge-FAILURE");
    expect(main().getByText("急増")).toBeInTheDocument();
    expect(main().getByText("AI Agent・超過時は停止")).toBeInTheDocument();
    expect(await main().findByText("get_account:OK,list_customers:DENIED")).toBeInTheDocument();
    expect(main().getByText("拒否 1")).toBeInTheDocument();
    expect(await main().findByText("LLM:claude-opus-5-5:INPUT")).toBeInTheDocument();
    expect(main().getByRole("img", { name: /日別の費用/ })).toBeInTheDocument();
  });

  it("予算を変更でき、Railway の使用量を取り込める（未設定ならその旨）", async () => {
    mockFinops();
    mockApi({ "/finops-budget-update": { subjectId: "bank-agent" }, "/finops-infra-sync": { status: "NOT_CONFIGURED", message: "RAILWAY_API_TOKEN と RAILWAY_PROJECT_ID を設定してください" } });
    const { user } = await renderPage(<FinOpsPage />, { user: supervisorUser, path: "/finops" });
    await user.click(await main().findByRole("button", { name: "5,000 円" }));
    const input = main().getByLabelText("bank-agent の予算");
    await user.clear(input);
    await user.type(input, "8000");
    await user.click(main().getByRole("button", { name: "保存" }));
    await waitFor(() => expect(lastBody("/finops-budget-update")).toEqual({ subjectType: "AGENT", subjectId: "bank-agent", monthlyBudgetJpy: "8000" }));
    await user.click(main().getByRole("button", { name: "Railway の使用量を取り込む" }));
    expect(await main().findByText("RAILWAY_API_TOKEN と RAILWAY_PROJECT_ID を設定してください")).toBeInTheDocument();
  });
});
