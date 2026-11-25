// lib/agent-server.ts のテスト：窓口・役席の確認、行員の委任トークン（セッションブリッジ）の取得とキャッシュ、Agent の呼び出し。
import { afterEach, describe, expect, it, vi } from "vitest";
import { agentConfigFromEnv, clearUserTokens, requireStaff, runAgent, userToken, type AgentConfig } from "@/lib/agent-server";

const cfg: AgentConfig = {
  coreApi: "http://kong:8000/bank/api", agentRunsUrl: "http://kong:8000/agent/v1/runs", tokenUrl: "http://kong:8000/oauth2/token",
  clientId: "bank-console", clientSecret: "console-secret", csrfSecret: "c".repeat(40), publicOrigins: ["http://localhost:3000"],
  agentAudience: "api://bank-agent",
};
const jsonRes = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => clearUserTokens());

describe("agentConfigFromEnv", () => {
  it("必要な値がそろったときだけ設定を返す", () => {
    const env = { BANK_API_INTERNAL: "a", AGENT_RUNS_URL: "b", INTEGRATION_TOKEN_URL: "t", AGENT_CONSOLE_CLIENT_ID: "bank-console",
      AGENT_CONSOLE_CLIENT_SECRET: "s", CONSOLE_CSRF_SECRET: "c".repeat(32) };
    expect(agentConfigFromEnv(env)).toMatchObject({ agentRunsUrl: "b", clientId: "bank-console", agentAudience: "api://bank-agent" });
    expect(agentConfigFromEnv({ ...env, AGENT_CONSOLE_CLIENT_SECRET: undefined })).toBeNull();
  });
});

describe("requireStaff", () => {
  const req = (cookie?: string) => new Request("http://x/api/agent/runs", { headers: cookie ? { cookie } : {} });
  it("窓口・役席だけを通す（顧客・初期パスワードのまま・未ログインは拒否）", async () => {
    for (const [user, expected] of [
      [{ userId: "teller1", role: "TELLER", mustChangePassword: false }, "teller1"],
      [{ userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: false }, "supervisor1"],
      [{ userId: "yamada", role: "CUSTOMER", mustChangePassword: false }, 403],
      [{ userId: "teller1", role: "TELLER", mustChangePassword: true }, 403],
      [null, 401],
    ] as const) {
      const r = await requireStaff(req("BANKSESSION=s"), cfg, vi.fn(async () => jsonRes(200, { data: user })));
      if (typeof expected === "string") expect((r as { userId: string }).userId).toBe(expected);
      else expect((r as Response).status).toBe(expected);
    }
    expect(((await requireStaff(req(), cfg, vi.fn())) as Response).status).toBe(401);
  });
});

describe("userToken / runAgent", () => {
  it("画面のクライアントで行員の委任トークンを取り（セッションブリッジ）、期限の 60 秒前まで再利用する", async () => {
    const f = vi.fn(async () => jsonRes(200, { access_token: "user-token", expires_in: 600 }));
    const user = { userId: "teller1", role: "TELLER" as const };
    expect(await userToken(user, cfg, f, 0)).toBe("user-token");
    expect(await userToken(user, cfg, f, 500_000)).toBe("user-token");
    expect(f).toHaveBeenCalledTimes(1);
    const [, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    const body = new URLSearchParams(String(init.body));
    expect(body.get("grant_type")).toBe("urn:bank:params:oauth:grant-type:session-bridge");
    expect(body.get("subject")).toBe("teller1");
    expect(body.get("subject_role")).toBe("TELLER");
    expect(body.get("scope")).toBe("api://bank-agent/Agent.Invoke");
    expect((init.headers as Record<string, string>).Authorization).toBe(`Basic ${Buffer.from("bank-console:console-secret").toString("base64")}`);
    await userToken(user, cfg, f, 541_000);
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("Agent を委任トークンとトレース付きで呼び、応答をそのまま返す。接続できなければ 502", async () => {
    const f = vi.fn(async (url: string, _init?: RequestInit) => url.endsWith("/oauth2/token")
      ? jsonRes(200, { access_token: "user-token", expires_in: 600 }) : jsonRes(200, { runId: "r1", answer: "ok" }));
    const r = await runAgent({ userId: "teller1", role: "TELLER" }, "残高は？", cfg, f as unknown as typeof fetch);
    expect(await r.json()).toEqual({ runId: "r1", answer: "ok" });
    const [url, init] = f.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toBe("http://kong:8000/agent/v1/runs");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer user-token");
    expect((init.headers as Record<string, string>).traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    expect(JSON.parse(String(init.body))).toEqual({ prompt: "残高は？" });
    const down = vi.fn(async (url: string) => {
      if (url.endsWith("/oauth2/token")) return jsonRes(200, { access_token: "t", expires_in: 600 });
      throw new TypeError("down");
    });
    expect((await runAgent({ userId: "teller2", role: "TELLER" }, "x", cfg, down as unknown as typeof fetch)).status).toBe(502);
  });
});
