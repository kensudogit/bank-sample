// 連携コンソールのサーバー側 API のテスト：
//  /api/integration/[action]（status・runs・csrf。直接の送信は廃止）
//  /api/integration/requests（承認依頼の一覧・作成）
//  /api/integration/requests/[id]/[op]（承認して送信・却下・再送）
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "@/app/api/integration/[action]/route";
import { GET as LIST, POST as CREATE } from "@/app/api/integration/requests/route";
import { POST as OP } from "@/app/api/integration/requests/[id]/[op]/route";
import { clearTokenCache, csrfTokenFor } from "@/lib/integration-server";
import { RawResponse, callsTo, lastBody, mockApi } from "../../test/api";

const CSRF_SECRET = "c".repeat(40);
const ctx = (action: string) => ({ params: Promise.resolve({ action }) });
const opCtx = (id: string, op: string) => ({ params: Promise.resolve({ id, op }) });
const supervisor = { userId: "supervisor2", role: "SUPERVISOR", mustChangePassword: false };
const token = new RawResponse(200, { access_token: "tok-1", token_type: "Bearer", expires_in: 300 });

beforeEach(() => {
  vi.stubEnv("BANK_API_INTERNAL", "http://kong:8000/bank/api");
  vi.stubEnv("INTEGRATION_GATEWAY_URL", "http://kong:8000/integration/v1");
  vi.stubEnv("INTEGRATION_TOKEN_URL", "http://kong:8000/oauth2/token");
  vi.stubEnv("INTEGRATION_CLIENT_ID", "bank-ops");
  vi.stubEnv("INTEGRATION_CLIENT_SECRET", "ops-secret");
  vi.stubEnv("OPERATOR_TOKEN_SECRET", "o".repeat(40));
  vi.stubEnv("CONSOLE_CSRF_SECRET", CSRF_SECRET);
  vi.stubEnv("PUBLIC_ORIGIN", "http://localhost:3000");
  clearTokenCache();
});
afterEach(() => vi.unstubAllEnvs());

const cookie = "BANKSESSION=sess-1";
const writeHeaders = {
  cookie, "x-requested-with": "XMLHttpRequest", origin: "http://localhost:3000", "x-csrf-token": csrfTokenFor("sess-1", CSRF_SECRET),
};
const get = (action: string) => GET(new Request(`http://localhost/api/integration/${action}`, { headers: { cookie } }), ctx(action));

describe("GET /api/integration/[action]", () => {
  it("外部連携の環境変数が無ければ 503", async () => {
    vi.stubEnv("INTEGRATION_CLIENT_SECRET", "");
    expect((await get("status")).status).toBe(503);
  });

  it("役席なら status・runs を Kong の health・runs へ、bank-ops のアクセストークンで中継する", async () => {
    mockApi({
      "/bank/api/me": supervisor, "/oauth2/token": token,
      "/integration/v1/health": new RawResponse(200, { status: "UP" }), "/integration/v1/runs": new RawResponse(200, []),
    });
    const s = await get("status");
    expect(s.status).toBe(200);
    expect(await s.json()).toEqual({ status: "UP" });
    expect((await get("runs")).status).toBe(200);
    expect(callsTo("/integration/v1/runs")[0].headers).toMatchObject({ Authorization: "Bearer tok-1" });
    expect(callsTo("/integration/v1/runs")[0].headers.apikey).toBeUndefined();
    expect(callsTo("/oauth2/token")).toHaveLength(1);
    expect((await get("other")).status).toBe(404);
  });

  it("csrf はセッションに結び付いたトークンを返す", async () => {
    mockApi({ "/bank/api/me": supervisor });
    expect(await (await get("csrf")).json()).toEqual({ token: csrfTokenFor("sess-1", CSRF_SECRET) });
  });

  it("役席でなければ Kong を呼ばずに 403", async () => {
    mockApi({ "/bank/api/me": { ...supervisor, role: "TELLER" } });
    expect((await get("runs")).status).toBe(403);
    expect(callsTo("/integration/v1/runs")).toHaveLength(0);
  });

  it("直接の送信（POST /api/integration/zengin など）はできない", async () => {
    expect((await POST()).status).toBe(405);
  });
});

describe("/api/integration/requests", () => {
  const create = (kind: string, body: BodyInit, headers: Record<string, string> = {}) =>
    CREATE(new Request(`http://localhost/api/integration/requests?kind=${kind}`, { method: "POST", body, headers: { ...writeHeaders, ...headers } }));

  it("一覧は勘定系の integration-requests を役席の Cookie で呼ぶ", async () => {
    mockApi({ "/bank/api/me": supervisor, "/bank/api/integration-requests": [{ requestId: "r1" }] });
    const r = await LIST(new Request("http://localhost/api/integration/requests", { headers: { cookie } }));
    expect(await r.json()).toEqual([{ requestId: "r1" }]);
    expect(callsTo("/bank/api/integration-requests")[0].headers.Cookie).toBe(cookie);
  });

  it("作成は本文を Base64 にして勘定系に登録する（Kong の連携 API にはまだ送らない）", async () => {
    mockApi({ "/bank/api/me": supervisor, "/bank/api/integration-request-create": { requestId: "r1", status: "PENDING" } });
    const r = await create("zengin", "1210...", { "content-type": "text/plain; charset=Shift_JIS", "x-file-name": encodeURIComponent("全銀.txt"), "x-comment": encodeURIComponent("定例") });
    expect(r.status).toBe(201);
    expect(lastBody("/bank/api/integration-request-create")).toEqual({
      kind: "ZENGIN_INCOMING", contentType: "text/plain; charset=shift_jis", payloadBase64: Buffer.from("1210...").toString("base64"),
      fileName: "全銀.txt", comment: "定例",
    });
    expect(callsTo("/integration/v1/zengin/incoming")).toHaveLength(0);
  });

  it("CSRF（XHR・Origin・トークン）、未定義の種類、許可外の形式、空データ、5 MB 超を拒否する", async () => {
    mockApi({ "/bank/api/me": supervisor });
    expect((await create("fx", "{}", { "content-type": "application/json", "x-csrf-token": "wrong" })).status).toBe(403);
    expect((await create("fx", "{}", { "content-type": "application/json", origin: "http://localhost:3001" })).status).toBe(403);
    expect((await create("unknown", "x", { "content-type": "text/plain" })).status).toBe(404);
    expect((await create("fx", "x", { "content-type": "text/plain" })).status).toBe(415);
    expect((await create("billing", "", { "content-type": "text/csv" })).status).toBe(422);
    expect((await create("billing", new Uint8Array(5 * 1024 * 1024 + 1), { "content-type": "text/csv" })).status).toBe(413);
    expect(callsTo("/bank/api/integration-request-create")).toHaveLength(0);
  });

  it("勘定系のエラー（作成者本人の承認など）は problem+json で返す", async () => {
    mockApi({
      "/bank/api/me": supervisor,
      "/bank/api/integration-request-approve": new RawResponse(422, { error: { code: "INTEGRATION_SAME_PERSON", message: "依頼を作成した役席は承認・却下できません" } }),
    });
    const r = await OP(new Request("http://localhost/api/integration/requests/r1/approve", { method: "POST", headers: writeHeaders }), opCtx("r1", "approve"));
    expect(r.status).toBe(422);
    expect((await r.json()).title).toMatch(/承認・却下できません/);
    expect(callsTo("/integration/v1/fx/rates")).toHaveLength(0);
  });
});

describe("/api/integration/requests/[id]/[op]", () => {
  const payload = Buffer.from("{\"provider\":\"x\",\"rates\":[]}");
  const approved = {
    requestId: "r1", kind: "FX_RATES", contentType: "application/json", payloadBase64: payload.toString("base64"),
    payloadSha256: "3e2f5c9b8bbd6ab0d1bd4cbe4b6c3c2f7e35f8b8c5a35c3e6f0a3b28f56d1a90", maker: "supervisor1", approver: "supervisor2",
  };
  const op = (id: string, o: string, body?: string) =>
    OP(new Request(`http://localhost/api/integration/requests/${id}/${o}`, { method: "POST", body, headers: { ...writeHeaders, "content-type": "application/json" } }), opCtx(id, o));

  it("承認すると、承認された内容を Kong に送って結果を記録する", async () => {
    const { createHash } = await import("node:crypto");
    const view = { ...approved, payloadSha256: createHash("sha256").update(payload).digest("hex") };
    mockApi({
      "/bank/api/me": supervisor, "/oauth2/token": token, "/bank/api/integration-request-approve": view,
      "/integration/v1/fx/rates": new RawResponse(200, { runId: "run-1", provider: "x", updated: [], skipped: [] }),
      "/bank/api/integration-request-complete": { requestId: "r1", status: "SENT" },
    });
    const r = await op("r1", "approve");
    const body = await r.json();
    expect(body).toMatchObject({ httpStatus: 200, kind: "fx", request: { status: "SENT" } });
    expect(lastBody("/bank/api/integration-request-approve")).toEqual({ requestId: "r1" });
    const sent = callsTo("/integration/v1/fx/rates")[0];
    expect(sent.headers).toMatchObject({ Authorization: "Bearer tok-1", "Idempotency-Key": "approval-r1" });
    expect(sent.headers["X-Bank-Operator-Token"]).toMatch(/^[\w-]+\.[\w-]+\.[\w-]+$/);
    expect(lastBody("/bank/api/integration-request-complete")).toMatchObject({ requestId: "r1", runId: "run-1", httpStatus: "200" });
  });

  it("再送は redispatch、却下は理由付きで reject を呼ぶ。未定義の操作は 404", async () => {
    mockApi({ "/bank/api/me": supervisor, "/bank/api/integration-request-reject": { requestId: "r1", status: "REJECTED" },
      "/bank/api/integration-request-redispatch": new RawResponse(409, { error: { code: "INTEGRATION_STATE_INVALID", message: "状態が違います" } }) });
    expect((await op("r1", "reject", JSON.stringify({ reason: "金額誤り" }))).status).toBe(200);
    expect(lastBody("/bank/api/integration-request-reject")).toEqual({ requestId: "r1", reason: "金額誤り" });
    expect((await op("r1", "retry")).status).toBe(409);
    expect((await op("r1", "delete")).status).toBe(404);
    expect((await op("../x", "approve")).status).toBe(404);
  });
});
