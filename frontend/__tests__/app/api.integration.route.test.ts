// app/api/integration/[action]/route.ts のテスト：設定なし・役席の確認・CSRF・形式とサイズの検査・Kong への中継先。
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "@/app/api/integration/[action]/route";
import { RawResponse, callsTo, mockApi } from "../../test/api";

const ctx = (action: string) => ({ params: Promise.resolve({ action }) });
const supervisor = { userId: "supervisor1", role: "SUPERVISOR", mustChangePassword: false };

beforeEach(() => {
  vi.stubEnv("BANK_API_INTERNAL", "http://kong:8000/bank/api");
  vi.stubEnv("INTEGRATION_GATEWAY_URL", "http://kong:8000/integration/v1");
  vi.stubEnv("INTEGRATION_OPS_KEY", "ops-key-0123456789");
});
afterEach(() => vi.unstubAllEnvs());

const get = (action: string, cookie = "BANKSESSION=1") => GET(new Request(`http://localhost/api/integration/${action}`, { headers: { cookie } }), ctx(action));
const post = (action: string, body: BodyInit, headers: Record<string, string> = {}) =>
  POST(new Request(`http://localhost/api/integration/${action}`, {
    method: "POST", body, headers: { cookie: "BANKSESSION=1", "x-requested-with": "XMLHttpRequest", ...headers },
  }), ctx(action));

describe("GET /api/integration/[action]", () => {
  it("外部連携の環境変数が無ければ 503", async () => {
    vi.unstubAllEnvs();
    vi.stubEnv("INTEGRATION_OPS_KEY", "");
    expect((await get("status")).status).toBe(503);
  });

  it("役席なら status・runs を Kong の health・runs へ中継する", async () => {
    mockApi({ "/bank/api/me": supervisor, "/integration/v1/health": new RawResponse(200, { status: "UP" }), "/integration/v1/runs": new RawResponse(200, []) });
    const s = await get("status");
    expect(s.status).toBe(200);
    expect(await s.json()).toEqual({ status: "UP" });
    expect((await get("runs")).status).toBe(200);
    expect(callsTo("/integration/v1/runs")[0].headers).toMatchObject({ apikey: "ops-key-0123456789", "X-Bank-Operator": "supervisor1" });
    expect((await get("other")).status).toBe(404);
  });

  it("役席でなければ Kong を呼ばずに 403", async () => {
    mockApi({ "/bank/api/me": { ...supervisor, role: "TELLER" } });
    expect((await get("runs")).status).toBe(403);
    expect(callsTo("/integration/v1/runs")).toHaveLength(0);
  });
});

describe("POST /api/integration/[action]", () => {
  it("全銀データを charset 付きで Kong の /zengin/incoming へ中継する", async () => {
    mockApi({ "/bank/api/me": supervisor, "/integration/v1/zengin/incoming": new RawResponse(200, { records: 2 }) });
    const r = await post("zengin", "1210...", { "content-type": "text/plain; charset=Shift_JIS" });
    expect(r.status).toBe(200);
    const [c] = callsTo("/integration/v1/zengin/incoming");
    expect(c.method).toBe("POST");
    expect(c.headers["Content-Type"]).toBe("text/plain; charset=shift_jis");
  });

  it("CSRF ヘッダーなし・未定義の種類・許可外の形式・空データは拒否する", async () => {
    mockApi({ "/bank/api/me": supervisor });
    const noXhr = await POST(new Request("http://localhost/api/integration/fx", { method: "POST", body: "{}", headers: { cookie: "c=1", "content-type": "application/json" } }), ctx("fx"));
    expect(noXhr.status).toBe(403);
    expect((await post("unknown", "x", { "content-type": "text/plain" })).status).toBe(404);
    expect((await post("fx", "x", { "content-type": "text/plain" })).status).toBe(415);
    expect((await post("billing", "", { "content-type": "text/csv" })).status).toBe(422);
  });

  it("5 MB を超えるデータは 413", async () => {
    mockApi({ "/bank/api/me": supervisor });
    const big = new Uint8Array(5 * 1024 * 1024 + 1);
    expect((await post("billing", big, { "content-type": "text/csv" })).status).toBe(413);
  });
});
