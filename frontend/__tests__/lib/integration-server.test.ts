// lib/integration-server.ts のテスト：役席の確認（勘定系の /me）、CSRF 対策（Origin の完全一致・セッションに結び付いたトークン）、
// OAuth 2.0 のトークン取得とキャッシュ、Kong への中継、行員 ID の署名、承認済み依頼の送信と結果の記録、Content-Type の許可。
import { createHash, createHmac } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  accessToken, acceptedContentType, checkCsrf, clearTokenCache, configFromEnv, csrfTokenFor, dispatchApproved, forward, problem,
  requireSupervisor, sessionId, signOperatorToken, type IntegrationConfig,
} from "@/lib/integration-server";

const cfg: IntegrationConfig = {
  coreApi: "http://kong:8000/bank/api", gateway: "http://kong:8000/integration/v1", tokenUrl: "http://kong:8000/oauth2/token",
  clientId: "bank-ops", clientSecret: "ops-secret", operatorSecret: "o".repeat(40), csrfSecret: "c".repeat(40),
  publicOrigins: ["http://localhost:3000"],
};
const jsonRes = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
const tokenRes = () => jsonRes(200, { access_token: "tok-1", token_type: "Bearer", expires_in: 300 });

afterEach(() => clearTokenCache());

describe("configFromEnv", () => {
  const env = {
    BANK_API_INTERNAL: "a", INTEGRATION_GATEWAY_URL: "b", INTEGRATION_TOKEN_URL: "t", INTEGRATION_CLIENT_ID: "bank-ops",
    INTEGRATION_CLIENT_SECRET: "s", OPERATOR_TOKEN_SECRET: "o".repeat(32), CONSOLE_CSRF_SECRET: "c".repeat(32),
    PUBLIC_ORIGIN: "http://localhost:3000/, https://bank.example",
  };
  it("必要な値がそろったときだけ設定を返す（Origin は末尾の / を除いて完全一致用に持つ）", () => {
    expect(configFromEnv(env)).toMatchObject({ coreApi: "a", gateway: "b", tokenUrl: "t", publicOrigins: ["http://localhost:3000", "https://bank.example"] });
    expect(configFromEnv({ ...env, INTEGRATION_CLIENT_SECRET: undefined })).toBeNull();
    expect(configFromEnv({ ...env, OPERATOR_TOKEN_SECRET: "short" })).toBeNull();
    expect(configFromEnv({ ...env, CONSOLE_CSRF_SECRET: "short" })).toBeNull();
  });
});

describe("problem", () => {
  it("RFC 9457 形式で返す", async () => {
    const r = problem(403, "権限がありません", "役席のみ");
    expect(r.status).toBe(403);
    expect(r.headers.get("content-type")).toBe("application/problem+json");
    expect(await r.json()).toEqual({ type: "about:blank", title: "権限がありません", status: 403, detail: "役席のみ" });
  });
});

describe("requireSupervisor", () => {
  const req = (cookie?: string) => new Request("http://x/api/integration/runs", { headers: cookie ? { cookie } : {} });

  it("Cookie が無ければ 401（勘定系を呼ばない）", async () => {
    const f = vi.fn();
    expect(((await requireSupervisor(req(), cfg, f)) as Response).status).toBe(401);
    expect(f).not.toHaveBeenCalled();
  });

  it("ブラウザの Cookie で /me を呼び、役席なら利用者 ID を返す。それ以外・初期パスワードのままは 403", async () => {
    const f = vi.fn(async () => jsonRes(200, { data: { userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: false } }));
    expect(await requireSupervisor(req("BANKSESSION=abc"), cfg, f)).toBe("supervisor1");
    expect(f).toHaveBeenCalledWith("http://kong:8000/bank/api/me", expect.objectContaining({ headers: expect.objectContaining({ Cookie: "BANKSESSION=abc" }) }));
    for (const [user, status] of [
      [{ userId: "teller1", role: "TELLER", mustChangePassword: false }, 403],
      [{ userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: true }, 403],
      [null, 401],
    ] as const) {
      const g = vi.fn(async () => jsonRes(200, { data: user }));
      expect(((await requireSupervisor(req("BANKSESSION=abc"), cfg, g)) as Response).status).toBe(status);
    }
  });

  it("勘定系に接続できなければ 502", async () => {
    const f = vi.fn(async () => { throw new TypeError("down"); });
    expect(((await requireSupervisor(req("BANKSESSION=abc"), cfg, f)) as Response).status).toBe(502);
  });
});

describe("checkCsrf", () => {
  const token = csrfTokenFor("sess-1", cfg.csrfSecret);
  const req = (headers: Record<string, string>) => new Request("http://localhost:3000/api/integration/requests", { method: "POST", headers });
  const good = { "x-requested-with": "XMLHttpRequest", origin: "http://localhost:3000", cookie: "a=b; BANKSESSION=sess-1", "x-csrf-token": token };

  it("セッション ID を Cookie から取り出し、トークンはセッションごとに違う", () => {
    expect(sessionId(req({ cookie: "x=1; BANKSESSION=abc=def" }))).toBe("abc=def");
    expect(sessionId(req({ cookie: "x=1" }))).toBeNull();
    expect(csrfTokenFor("sess-2", cfg.csrfSecret)).not.toBe(token);
  });

  it("XHR ヘッダー・Origin の完全一致・トークンがそろえば通す", () => {
    expect(checkCsrf(req(good), cfg)).toBeNull();
  });

  it("XHR ヘッダーなし、Origin なし・ポート違い・別サイト、トークンなし・別セッションのトークンは 403", () => {
    const cases: Record<string, string>[] = [
      { ...good, "x-requested-with": "" },
      { ...good, origin: "" },
      { ...good, origin: "http://localhost:3001" },
      { ...good, origin: "https://localhost:3000" },
      { ...good, origin: "http://evil.example" },
      { ...good, "x-csrf-token": "" },
      { ...good, "x-csrf-token": csrfTokenFor("other", cfg.csrfSecret) },
      { ...good, cookie: "a=b" },
    ];
    for (const h of cases) {
      const headers = Object.fromEntries(Object.entries(h).filter(([, v]) => v !== ""));
      expect(checkCsrf(req(headers), cfg)?.status, JSON.stringify(h)).toBe(403);
    }
  });

  it("PUBLIC_ORIGIN が未設定なら、要求の Host・X-Forwarded-Proto から作った Origin と完全一致させる", () => {
    const c = { ...cfg, publicOrigins: [] };
    expect(checkCsrf(req({ ...good, host: "localhost:3000" }), c)).toBeNull();
    expect(checkCsrf(req({ ...good, host: "localhost" }), c)?.status).toBe(403);
    expect(checkCsrf(req({ ...good, origin: "https://bank.example", "x-forwarded-host": "bank.example", "x-forwarded-proto": "https" }), c)).toBeNull();
  });
});

describe("accessToken / forward", () => {
  it("クライアントクレデンシャル（Basic 認証）でトークンを取り、期限の 30 秒前まで再利用する", async () => {
    const f = vi.fn(async () => tokenRes());
    expect(await accessToken(cfg, f, 0)).toBe("tok-1");
    expect(await accessToken(cfg, f, 200_000)).toBe("tok-1");
    expect(f).toHaveBeenCalledTimes(1);
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://kong:8000/oauth2/token");
    expect(init.body).toBe("grant_type=client_credentials");
    expect((init.headers as Record<string, string>).Authorization).toBe(`Basic ${Buffer.from("bank-ops:ops-secret").toString("base64")}`);
    await accessToken(cfg, f, 271_000);
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("トークン付きで Kong へ中継し、401 ならトークンを取り直して 1 回だけ再送する。応答と要求 ID はそのまま返す", async () => {
    let first = true;
    const f = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url.endsWith("/oauth2/token")) return tokenRes();
      if (first) { first = false; return jsonRes(401, { message: "Unauthorized" }); }
      return jsonRes(200, { runs: [] }, { "X-Request-ID": "rid-1" });
    });
    const r = await forward(cfg, "/runs", { method: "GET" }, f as unknown as typeof fetch);
    expect(r.status).toBe(200);
    expect(r.headers.get("x-request-id")).toBe("rid-1");
    const gatewayCalls = f.mock.calls.filter(([u]) => String(u).includes("/integration/v1/runs"));
    expect(gatewayCalls).toHaveLength(2);
    expect((gatewayCalls[1][1] as unknown as RequestInit).headers).toMatchObject({ Authorization: "Bearer tok-1" });
    expect(f.mock.calls.filter(([u]) => String(u).endsWith("/oauth2/token"))).toHaveLength(2);
  });

  it("Kong に接続できなければ 502", async () => {
    const f = vi.fn(async () => { throw new TypeError("down"); });
    expect((await forward(cfg, "/runs", { method: "GET" }, f)).status).toBe(502);
  });
});

describe("signOperatorToken", () => {
  it("HS256 で作成者・承認者・依頼 ID・本文のハッシュを署名する（2 分有効）", () => {
    const t = signOperatorToken({ maker: "supervisor1", approver: "supervisor2", approvalId: "r-1", bodySha256: "ab" }, cfg.operatorSecret, 1000);
    const [h, p, s] = t.split(".");
    expect(JSON.parse(Buffer.from(h, "base64url").toString())).toEqual({ alg: "HS256", typ: "JWT" });
    expect(JSON.parse(Buffer.from(p, "base64url").toString())).toEqual({
      iss: "bank-console", aud: "bank-integration", iat: 1000, exp: 1120, maker: "supervisor1", approver: "supervisor2", approvalId: "r-1", bodySha256: "ab",
    });
    expect(s).toBe(createHmac("sha256", cfg.operatorSecret).update(`${h}.${p}`).digest("base64url"));
  });
});

describe("dispatchApproved", () => {
  const payload = Buffer.from("{\"provider\":\"x\",\"rates\":[]}");
  const view = {
    requestId: "req-1", kind: "FX_RATES", contentType: "application/json", payloadBase64: payload.toString("base64"),
    payloadSha256: createHash("sha256").update(payload).digest("hex"), maker: "supervisor1", approver: "supervisor2",
  };
  const req = new Request("http://localhost:3000/api/integration/requests/req-1/approve", { method: "POST", headers: { cookie: "BANKSESSION=s" } });

  it("承認された内容を署名付きで Kong に送り、結果を勘定系に記録する", async () => {
    const f = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url.endsWith("/oauth2/token")) return tokenRes();
      if (url.endsWith("/fx/rates")) return jsonRes(200, { runId: "run-9", updated: [] });
      return jsonRes(200, { data: { requestId: "req-1", status: "SENT" } });
    });
    const r = await dispatchApproved(req, cfg, view, f as unknown as typeof fetch);
    expect(await r.json()).toEqual({ request: { requestId: "req-1", status: "SENT" }, httpStatus: 200, result: { runId: "run-9", updated: [] }, kind: "fx" });
    const sent = f.mock.calls.find(([u]) => String(u).endsWith("/fx/rates"))![1] as RequestInit;
    const h = sent.headers as Record<string, string>;
    expect(h["Content-Type"]).toBe("application/json");
    expect(h["Idempotency-Key"]).toBe("approval-req-1");
    const claims = JSON.parse(Buffer.from(h["X-Bank-Operator-Token"].split(".")[1], "base64url").toString());
    expect(claims).toMatchObject({ maker: "supervisor1", approver: "supervisor2", approvalId: "req-1", bodySha256: view.payloadSha256 });
    const done = f.mock.calls.find(([u]) => String(u).endsWith("/integration-request-complete"))![1] as RequestInit;
    expect(JSON.parse(String(done.body))).toMatchObject({ requestId: "req-1", runId: "run-9", httpStatus: "200" });
    expect((done.headers as Record<string, string>).Cookie).toBe("BANKSESSION=s");
  });

  it("連携が 502 なら、その状態を記録する（勘定系が FAILED にして再送できるようにする）", async () => {
    const f = vi.fn(async (url: string) => {
      if (url.endsWith("/oauth2/token")) return tokenRes();
      if (url.endsWith("/fx/rates")) return jsonRes(502, { title: "勘定系システムに接続できません" });
      return jsonRes(200, { data: { requestId: "req-1", status: "FAILED" } });
    });
    const body = await (await dispatchApproved(req, cfg, view, f as unknown as typeof fetch)).json();
    expect(body.httpStatus).toBe(502);
    expect(body.request.status).toBe("FAILED");
  });

  it("本文が承認時のハッシュと違えば送らずに 409", async () => {
    const f = vi.fn();
    const r = await dispatchApproved(req, cfg, { ...view, payloadSha256: "0".repeat(64) }, f);
    expect(r.status).toBe(409);
    expect(f).not.toHaveBeenCalled();
  });
});

describe("acceptedContentType", () => {
  it("種類ごとに許可した形式だけを通し、charset を残す", () => {
    expect(acceptedContentType("zengin", "text/plain; charset=Shift_JIS")).toBe("text/plain; charset=shift_jis");
    expect(acceptedContentType("billing", "text/csv")).toBe("text/csv");
    expect(acceptedContentType("fx", "application/json; charset=utf-8")).toBe("application/json; charset=utf-8");
    expect(acceptedContentType("fx", "text/plain")).toBeNull();
    expect(acceptedContentType("zengin", null)).toBeNull();
  });
});
