// AI アシスタント（/assistant）と課金の可視化（/finops）のクライアント。
// アシスタントは Next.js のサーバー側 API（/api/agent/*）、課金は勘定系の API（/api/bank/finops-*）を呼ぶ。

import { ApiError, request, qs } from "./api";
import { toError } from "./integration";
import { invalidate, useQuery } from "./query";

/** Agent が呼んだツール 1 件 */
export interface AgentToolCall {
  name: string;
  status: "OK" | "DENIED" | "ERROR" | "INVALID" | "PROPOSED";
  httpStatus?: number;
  ms: number;
}

/** Agent の実行結果 */
export interface AgentRunResult {
  runId: string;
  traceId: string;
  status: "COMPLETED" | "FAILED" | "BLOCKED" | "LIMITED";
  answer: string;
  toolCalls: AgentToolCall[];
  proposals: { type: "transfer"; fromAccountNo: string; toAccountNo: string; amount: number; memo: string; requiresHumanApproval: true }[];
  model: string;
  usage: { inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number };
  costJpy: number | null;
  steps: number;
}

let csrf: string | null = null;
async function csrfToken(refresh = false): Promise<string> {
  if (!csrf || refresh) {
    const res = await fetch("/api/agent/csrf", { cache: "no-store" }).catch(() => null);
    if (!res) throw new ApiError(0, "NETWORK_ERROR", "サーバーに接続できません。");
    if (!res.ok) throw await toError(res);
    csrf = ((await res.json()) as { token: string }).token;
  }
  return csrf;
}
/** テスト用：CSRF トークンのキャッシュを消す */
export function resetAgentCsrf(): void {
  csrf = null;
}

export const agentApi = {
  /** 質問する。CSRF トークンが古ければ 1 回だけ取り直す。実行後は課金の画面のキャッシュを古い扱いにする */
  run: async (prompt: string): Promise<AgentRunResult> => {
    const send = async (refresh: boolean) => {
      let res: Response;
      try {
        res = await fetch("/api/agent/runs", {
          method: "POST", cache: "no-store",
          headers: { "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", "X-CSRF-Token": await csrfToken(refresh) },
          body: JSON.stringify({ prompt }),
        });
      } catch {
        throw new ApiError(0, "NETWORK_ERROR", "サーバーに接続できません。");
      }
      if (!res.ok) throw await toError(res);
      return (await res.json()) as AgentRunResult;
    };
    try {
      return await send(false);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403 && /再読み込み/.test(e.message)) return send(true);
      throw e;
    } finally {
      invalidate("finops-summary*", "finops-agent-runs");
    }
  },
};

// ---------------- 課金の可視化・コスト予測 ----------------

export type BudgetStatus = "OK" | "WARN" | "OVER" | "NO_BUDGET";

export interface FinOpsSubject {
  subjectType: "CONSUMER" | "AGENT" | "CHANNEL" | "INFRA";
  subjectId: string;
  department: string;
  owner: string | null;
  requests: number;
  tokens: number;
  monthToDateJpy: number;
  forecastJpy: number;
  forecastLowJpy: number;
  forecastHighJpy: number;
  confidence: string;
  anomaly: boolean;
  budgetJpy: number;
  hardStop: boolean;
  budgetStatus: BudgetStatus;
}

export interface FinOpsSummary {
  month: string;
  asOf: string;
  current: boolean;
  total: { monthToDateJpy: number; forecastJpy: number; forecastLowJpy: number; forecastHighJpy: number; fixedJpy: number; budgetJpy: number; budgetStatus: BudgetStatus; lowConfidence: boolean };
  bySource: { source: string; requests?: number; inputTokens?: number; outputTokens?: number; cacheReadTokens?: number; costJpy: number }[];
  byDepartment: { department: string; monthToDateJpy: number; forecastJpy: number; budgetJpy: number; budgetStatus: BudgetStatus }[];
  bySubject: FinOpsSubject[];
  byApi: { apiId: string; source: string; requests: number; avgLatencyMs: number | null; errors: number; costJpy: number }[];
  daily: { date: string; gatewayJpy: number; llmJpy: number; infraJpy: number }[];
  infra: { serviceName: string; cpuVcpuMin: number; memoryGbMin: number; egressGb: number; costUsd: number; costJpy: number; fetchedAt: string }[];
  assumptions: { fxUsdJpy: number; method: string; note: string };
}

export interface AgentRunRow {
  runId: string;
  agentId: string;
  principal: string;
  startedAt: string;
  status: string;
  model: string;
  steps: number;
  toolCalls: string | null;
  deniedCalls: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  costJpy: number;
  traceId: string | null;
  summary: string | null;
}

export interface RateRow { rateKey: string; unit: string; unitPrice: number; currency: string; description: string; updatedAt: string }

export const finopsApi = {
  summary: (month?: string) => request<FinOpsSummary>(`/finops-summary${qs({ month })}`),
  agentRuns: () => request<AgentRunRow[]>("/finops-agent-runs"),
  rateCard: () => request<RateRow[]>("/finops-rate-card"),
  updateBudget: async (subjectType: string, subjectId: string, monthlyBudgetJpy: number) => {
    try {
      return await request("/finops-budget-update", { subjectType, subjectId, monthlyBudgetJpy: String(monthlyBudgetJpy) });
    } finally {
      invalidate("finops-summary*");
    }
  },
  syncInfra: async () => {
    try {
      return await request<{ status: string; message?: string; days?: number; rows?: number }>("/finops-infra-sync", {});
    } finally {
      invalidate("finops-summary*");
    }
  },
};

export const useFinOpsSummary = (month?: string) => useQuery(`finops-summary${month ? `:${month}` : ""}`, () => finopsApi.summary(month));
export const useAgentRuns = () => useQuery("finops-agent-runs", finopsApi.agentRuns);
export const useRateCard = () => useQuery("finops-rate-card", finopsApi.rateCard);
