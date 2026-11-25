// AI アシスタント（/assistant）のサーバー側処理。ブラウザ → Next.js（このファイル）→ Kong → Agent（Claude）→ Kong → 勘定系。
// - 行員の確認：ブラウザの Cookie で勘定系の /me を呼び、窓口・役席（初期パスワード変更済み）だけに使わせる
// - 行員の委任トークン：画面のクライアント（bank-console）が、確認した行員について auth のセッションブリッジで取得する
//   （aud=api://bank-agent・scp=Agent.Invoke。Entra ID ではサインイン＝認可コード＋PKCE で得たトークンに置き換わる）。
//   トークンもシークレットもブラウザには渡さない
// - Agent は受け取ったトークンを On-Behalf-Of で勘定系向けに交換し、勘定系は「行員の権限 ∩ Agent のスコープ」で判定する
// - CSRF 対策は連携コンソールと同じ（XHR・Origin の完全一致・セッションに結び付いたトークン）

import { randomBytes } from "node:crypto";
import { problem } from "./integration-server";

export interface AgentConfig {
  coreApi: string;
  agentRunsUrl: string;
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  csrfSecret: string;
  publicOrigins: string[];
  agentAudience: string;
}

export function agentConfigFromEnv(env: Record<string, string | undefined> = process.env): AgentConfig | null {
  const coreApi = env.BANK_API_INTERNAL;
  const agentRunsUrl = env.AGENT_RUNS_URL;
  const tokenUrl = env.INTEGRATION_TOKEN_URL;
  const clientId = env.AGENT_CONSOLE_CLIENT_ID;
  const clientSecret = env.AGENT_CONSOLE_CLIENT_SECRET;
  const csrfSecret = env.CONSOLE_CSRF_SECRET ?? "";
  if (!coreApi || !agentRunsUrl || !tokenUrl || !clientId || !clientSecret || csrfSecret.length < 32) return null;
  const publicOrigins = (env.PUBLIC_ORIGIN ?? "").split(",").map((s) => s.trim().replace(/\/$/, "")).filter(Boolean);
  return { coreApi, agentRunsUrl, tokenUrl, clientId, clientSecret, csrfSecret, publicOrigins, agentAudience: env.AGENT_AUDIENCE ?? "api://bank-agent" };
}

export interface StaffUser { userId: string; role: "TELLER" | "SUPERVISOR" }

/** ブラウザの Cookie で勘定系の /me を呼び、窓口・役席なら利用者を返す（それ以外は拒否の Response） */
export async function requireStaff(req: Request, cfg: AgentConfig, fetchImpl: typeof fetch = fetch): Promise<StaffUser | Response> {
  const cookie = req.headers.get("cookie");
  if (!cookie) return problem(401, "ログインしてください");
  let res: Response;
  try {
    res = await fetchImpl(`${cfg.coreApi}/me`, { headers: { Cookie: cookie, Accept: "application/json" }, cache: "no-store" });
  } catch {
    return problem(502, "勘定系に接続できません");
  }
  const json = (await res.json().catch(() => null)) as { data?: { userId: string; role: string; mustChangePassword: boolean } | null } | null;
  const user = json?.data;
  if (!res.ok || !user) return problem(401, "ログインしてください");
  if ((user.role !== "TELLER" && user.role !== "SUPERVISOR") || user.mustChangePassword) return problem(403, "この操作を行う権限がありません");
  return { userId: user.userId, role: user.role };
}

const userTokens = new Map<string, { token: string; expiresAt: number }>();

/** テスト用：行員トークンのキャッシュを消す */
export function clearUserTokens(): void {
  userTokens.clear();
}

/** 行員の委任トークン（Agent 呼び出し用）。期限の 60 秒前まで再利用する */
export async function userToken(user: StaffUser, cfg: AgentConfig, fetchImpl: typeof fetch = fetch, now = Date.now()): Promise<string> {
  const key = `${user.userId}|${user.role}`;
  const cached = userTokens.get(key);
  if (cached && cached.expiresAt > now) return cached.token;
  const res = await fetchImpl(cfg.tokenUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Authorization: `Basic ${Buffer.from(`${encodeURIComponent(cfg.clientId)}:${encodeURIComponent(cfg.clientSecret)}`).toString("base64")}`,
    },
    body: new URLSearchParams({
      grant_type: "urn:bank:params:oauth:grant-type:session-bridge", subject: user.userId, subject_role: user.role,
      scope: `${cfg.agentAudience}/Agent.Invoke`,
    }).toString(),
    cache: "no-store",
  });
  const json = (await res.json().catch(() => null)) as { access_token?: string; expires_in?: number } | null;
  if (!res.ok || !json?.access_token) throw new Error(`token endpoint returned HTTP ${res.status}`);
  userTokens.set(key, { token: json.access_token, expiresAt: now + Math.max(0, (json.expires_in ?? 120) - 60) * 1000 });
  return json.access_token;
}

/** Agent を呼ぶ（Kong 経由・行員の委任トークン・分散トレース）。応答はそのまま返す */
export async function runAgent(user: StaffUser, prompt: string, cfg: AgentConfig, fetchImpl: typeof fetch = fetch): Promise<Response> {
  let token: string;
  try {
    token = await userToken(user, cfg, fetchImpl);
  } catch {
    return problem(502, "認証サービスに接続できません");
  }
  const traceparent = `00-${randomBytes(16).toString("hex")}-${randomBytes(8).toString("hex")}-01`;
  let res: Response;
  try {
    res = await fetchImpl(cfg.agentRunsUrl, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", Accept: "application/json", traceparent },
      body: JSON.stringify({ prompt }),
      cache: "no-store",
      signal: AbortSignal.timeout(180_000),
    });
  } catch {
    return problem(502, "AI アシスタントに接続できません");
  }
  const text = await res.text();
  return new Response(text, {
    status: res.status,
    headers: { "Content-Type": res.headers.get("content-type") ?? "application/json", "Cache-Control": "no-store" },
  });
}
