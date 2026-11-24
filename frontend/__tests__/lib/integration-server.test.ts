// lib/integration-server.ts のテスト：役席の確認（勘定系の /me）、CSRF 対策、Kong への中継（API キー・行員名）、
// Content-Type の許可、環境変数からの設定。
import { describe, expect, it, vi } from "vitest";
import {
  acceptedContentType, checkCsrf, configFromEnv, forward, problem, requireSupervisor, type IntegrationConfig,
} from "@/lib/integration-server";

const cfg: IntegrationConfig = { coreApi: "http://kong:8000/bank/api", gateway: "http://kong:8000/integration/v1", opsKey: "ops-key-0123456789" };
const jsonRes = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

describe("configFromEnv", () => {
  it("3 つの環境変数がそろったときだけ設定を返す", () => {
    expect(configFromEnv({ BANK_API_INTERNAL: "a", INTEGRATION_GATEWAY_URL: "b", INTEGRATION_OPS_KEY: "c" }))
      .toEqual({ coreApi: "a", gateway: "b", opsKey: "c" });
    expect(configFromEnv({ BANK_API_INTERNAL: "a", INTEGRATION_GATEWAY_URL: "b" })).toBeNull();
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
    const r = await requireSupervisor(req(), cfg, f);
    expect((r as Response).status).toBe(401);
    expect(f).not.toHaveBeenCalled();
  });

  it("ブラウザの Cookie で /me を呼び、役席なら利用者 ID を返す", async () => {
    const f = vi.fn(async () => jsonRes(200, { data: { userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: false } }));
    expect(await requireSupervisor(req("BANKSESSION=abc"), cfg, f as unknown as typeof fetch)).toBe("supervisor1");
    expect(f).toHaveBeenCalledWith("http://kong:8000/bank/api/me", expect.objectContaining({ headers: expect.objectContaining({ Cookie: "BANKSESSION=abc" }) }));
  });

  it.each([
    [{ userId: "teller1", role: "TELLER", mustChangePassword: false }, 403],
    [{ userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: true }, 403],
    [null, 401],
  ])("役席以外・初期パスワードのまま・未ログインは拒否（%j）", async (user, status) => {
    const f = vi.fn(async () => jsonRes(200, { data: user }));
    expect(((await requireSupervisor(req("c=1"), cfg, f as unknown as typeof fetch)) as Response).status).toBe(status);
  });

  it("勘定系に接続できなければ 502", async () => {
    const f = vi.fn(async () => { throw new TypeError("fail"); });
    expect(((await requireSupervisor(req("c=1"), cfg, f as unknown as typeof fetch)) as Response).status).toBe(502);
  });
});

describe("checkCsrf", () => {
  const req = (headers: Record<string, string>) => new Request("http://x/api/integration/zengin", { method: "POST", headers });

  it("X-Requested-With が無ければ 403", () => {
    expect(checkCsrf(req({}))?.status).toBe(403);
  });

  it("別のオリジンからの要求は 403、同じオリジン・Origin なしは通す", () => {
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest", origin: "http://evil.example", host: "localhost:3000" }))?.status).toBe(403);
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest", origin: "http://localhost:3000", host: "localhost:3000" }))).toBeNull();
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest" }))).toBeNull();
  });

  it("前段の nginx が Host をポートなしで渡しても、同じホスト名なら通す", () => {
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest", origin: "http://localhost:3000", host: "localhost" }))).toBeNull();
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest", origin: "http://evil.example:3000", host: "localhost" }))?.status).toBe(403);
    expect(checkCsrf(req({ "x-requested-with": "XMLHttpRequest", origin: "null", host: "localhost" }))?.status).toBe(403);
  });
});

describe("forward", () => {
  it("bank-ops の API キーと行員名を付けて Kong へ中継し、応答と要求 ID をそのまま返す", async () => {
    const f = vi.fn(async () => jsonRes(422, { title: "NG" }, { "Content-Type": "application/problem+json", "X-Request-ID": "rid-1" }));
    const r = await forward(cfg, "/zengin/incoming", { method: "POST", body: "data", contentType: "text/plain; charset=utf-8", operator: "supervisor1" },
      f as unknown as typeof fetch);
    expect(r.status).toBe(422);
    expect(r.headers.get("content-type")).toBe("application/problem+json");
    expect(r.headers.get("x-request-id")).toBe("rid-1");
    expect(await r.json()).toEqual({ title: "NG" });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("http://kong:8000/integration/v1/zengin/incoming");
    expect(init.headers).toEqual({ apikey: "ops-key-0123456789", "X-Bank-Operator": "supervisor1", Accept: "application/json", "Content-Type": "text/plain; charset=utf-8" });
  });

  it("Kong に接続できなければ 502", async () => {
    const f = vi.fn(async () => { throw new TypeError("fail"); });
    expect((await forward(cfg, "/runs", { method: "GET", operator: "s" }, f as unknown as typeof fetch)).status).toBe(502);
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
