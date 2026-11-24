// lib/api.ts のテスト：共通 request（GET/POST・エラー変換・401 通知）、クエリ文字列、
// 各 API の送信先、キャッシュキー、更新系の mutate（キャッシュ無効化）と冪等キー。
import { describe, expect, it, vi } from "vitest";
import {
  ApiError, authApi, bankApi, mutate, newIdempotencyKey, qs, queryKeys, request, UNAUTHENTICATED_EVENT,
} from "@/lib/api";
import * as query from "@/lib/query";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";

describe("request", () => {
  it("GET は /api/bank を前置し、data を返す", async () => {
    mockApi({ "/jobs": { enabled: true } });
    await expect(request("/jobs")).resolves.toEqual({ enabled: true });
    const [c] = callsTo("/jobs");
    expect(c.method).toBe("GET");
    expect(c.headers["X-Requested-With"]).toBeUndefined();
  });

  it("POST は JSON 本文と CSRF 対策ヘッダーを付ける", async () => {
    mockApi({ "/deposit": { ok: 1 } });
    await request("/deposit", { amount: "100" });
    const [c] = callsTo("/deposit");
    expect(c.method).toBe("POST");
    expect(c.body).toEqual({ amount: "100" });
    expect(c.headers["Content-Type"]).toBe("application/json");
    expect(c.headers["X-Requested-With"]).toBe("XMLHttpRequest");
  });

  it("エラー応答を ApiError（コード・項目エラー付き）に変換する", async () => {
    mockApi({ "/deposit": new ApiFailure(400, "VALIDATION", "入力に誤りがあります。", { amount: ["金額を入力してください。"] }) });
    const err = (await request("/deposit", {}).catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 400, code: "VALIDATION", message: "入力に誤りがあります。" });
    expect(err.fieldErrors.amount).toEqual(["金額を入力してください。"]);
  });

  it("通信エラーは NETWORK_ERROR、JSON でない応答は SYSTEM_ERROR になる", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError("failed"));
    await expect(request("/x")).rejects.toMatchObject({ status: 0, code: "NETWORK_ERROR" });
    vi.mocked(fetch).mockResolvedValueOnce(new Response("<html>bad gateway</html>", { status: 502 }));
    await expect(request("/x")).rejects.toMatchObject({ status: 502, code: "SYSTEM_ERROR" });
  });

  it("401 ならセッション切れイベントを発火する（ログイン API は除く）", async () => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHENTICATED_EVENT, listener);
    mockApi({
      "/accounts": new ApiFailure(401, "UNAUTHENTICATED", "ログインしてください。"),
      "/login": new ApiFailure(401, "BAD_CREDENTIALS", "違います。"),
    });
    await request("/accounts").catch(() => undefined);
    await request("/login", {}).catch(() => undefined);
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(UNAUTHENTICATED_EVENT, listener);
  });
});

describe("qs", () => {
  it("空・null・undefined の値を除いてクエリ文字列を作る", () => {
    expect(qs({ a: "1", b: "", c: null, d: undefined, e: 0 })).toBe("?a=1&e=0");
    expect(qs({})).toBe("");
  });
});

describe("authApi", () => {
  it("login・logout でキャッシュを全消去する", async () => {
    const clear = vi.spyOn(query, "clearCache");
    mockApi({ "/login": { userId: "u" }, "/logout": new ApiFailure(500, "X", "x") });
    await authApi.login("u", "p");
    expect(lastBody("/login")).toEqual({ userId: "u", password: "p" });
    await authApi.logout().catch(() => undefined);
    expect(clear).toHaveBeenCalledTimes(2);
  });

  it("changePassword は現在・新しいパスワードを送る", async () => {
    mockApi({ "/password-change": {} });
    await authApi.changePassword("old", "new");
    expect(lastBody("/password-change")).toEqual({ currentPassword: "old", newPassword: "new" });
  });
});

describe("bankApi", () => {
  it("明細検索は条件をクエリ文字列にして送る", async () => {
    mockApi({ "/transactions": { items: [] } });
    await bankApi.searchTransactions({ accountNo: "1000001", from: "2026-11-01", types: ["DEPOSIT", "FEE"], page: 2 });
    const q = callsTo("/transactions")[0].query;
    expect(q.get("accountNo")).toBe("1000001");
    expect(q.get("from")).toBe("2026-11-01");
    expect(q.get("types")).toBe("DEPOSIT,FEE");
    expect(q.get("page")).toBe("2");
    expect(q.has("to")).toBe(false);
  });

  it("更新系は成功・失敗にかかわらずキャッシュを無効化する", async () => {
    const inv = vi.spyOn(query, "invalidate");
    mockApi({ "/transfer": { amount: 1 }, "/withdraw": new ApiFailure(409, "INSUFFICIENT_FUNDS", "残高不足") });
    await bankApi.transfer({ fromAccountNo: "1", toAccountNo: "2", amount: "1", idempotencyKey: "k" });
    await bankApi.withdraw({ accountNo: "1", amount: "9", idempotencyKey: "k2" }).catch(() => undefined);
    expect(inv).toHaveBeenCalledTimes(2);
    expect(inv).toHaveBeenCalledWith("*");
  });

  it("主要 API が正しいパスへ送られる", async () => {
    mockApi({ "/accounts": {}, "/account": {}, "/customers": [], "/customer": {}, "/audit-log": {}, "/ledger-verify": {}, "/jobs-run": [] });
    await bankApi.searchAccounts({ q: "山田" });
    await bankApi.getAccount("1000001");
    await bankApi.listCustomers();
    await bankApi.getCustomer("CU0001");
    await bankApi.auditLog({ outcome: "DENIED" });
    await bankApi.ledgerVerify();
    await bankApi.runJobs();
    expect(callsTo("/accounts")[0].query.get("q")).toBe("山田");
    expect(callsTo("/account")[0].query.get("accountNo")).toBe("1000001");
    expect(callsTo("/customer")[0].query.get("customerId")).toBe("CU0001");
    expect(callsTo("/audit-log")[0].query.get("outcome")).toBe("DENIED");
    expect(callsTo("/jobs-run")[0].method).toBe("POST");
  });
});

describe("queryKeys", () => {
  it("検索条件ごとに別のキーになり、既定値を補う", () => {
    expect(queryKeys.accounts({})).toBe("accounts::1:50");
    expect(queryKeys.accounts({ q: "x", page: 2, size: 10 })).toBe("accounts:x:2:10");
    expect(queryKeys.transactions({ accountNo: "1", types: ["FEE"] })).toBe("tx:1:::FEE:1:50");
    expect(queryKeys.interest("1", "2026-11-23")).toBe("interest:1:2026-11-23");
  });
});

describe("mutate / newIdempotencyKey", () => {
  it("mutate は結果を返し、例外も再送出する", async () => {
    await expect(mutate(Promise.resolve(5))).resolves.toBe(5);
    await expect(mutate(Promise.reject(new Error("x")))).rejects.toThrow("x");
  });

  it("冪等キーは毎回異なる UUID", () => {
    const a = newIdempotencyKey();
    expect(a).toMatch(/^[0-9a-f-]{36}$/);
    expect(newIdempotencyKey()).not.toBe(a);
  });
});
