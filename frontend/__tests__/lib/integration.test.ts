// lib/integration.ts のテスト：problem+json を ApiError に変換すること、送信のヘッダー、キャッシュの無効化、サンプルの全銀データ。
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { SAMPLE_BILLING, SAMPLE_FX, integrationApi, sampleZengin } from "@/lib/integration";
import * as query from "@/lib/query";
import { RawResponse, callsTo, mockApi } from "../../test/api";

describe("integrationApi", () => {
  it("status・runs は /api/integration の JSON をそのまま返す", async () => {
    mockApi({ "/api/integration/status": new RawResponse(200, { status: "UP", runtime: "mule-compat", core: "UP" }) });
    await expect(integrationApi.status()).resolves.toEqual({ status: "UP", runtime: "mule-compat", core: "UP" });
  });

  it("problem+json を行番号付きの項目エラーを持つ ApiError にする", async () => {
    mockApi({
      "/api/integration/zengin": new RawResponse(422, {
        type: "https://bank.example/problems/invalid-input", title: "入力データに誤りがあります", detail: "取り込みませんでした。",
        errors: [{ line: 3, message: "金額が正しくありません" }, { line: 0, message: "合計が一致しません" }],
      }, "application/problem+json"),
    });
    const err = (await integrationApi.submit("zengin", "x", "text/plain").catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(422);
    expect(err.code).toBe("INVALID_INPUT");
    expect(err.message).toBe("入力データに誤りがあります：取り込みませんでした。");
    expect(err.fieldErrors.errors).toEqual(["3 行目：金額が正しくありません", "合計が一致しません"]);
  });

  it("送信は XHR ヘッダーと Content-Type を付け、成功・失敗にかかわらずキャッシュを無効化する", async () => {
    const inv = vi.spyOn(query, "invalidate");
    mockApi({ "/api/integration/fx": new RawResponse(200, { updated: ["USD"] }) });
    await integrationApi.submit("fx", SAMPLE_FX, "application/json");
    const [c] = callsTo("/api/integration/fx");
    expect(c.headers).toMatchObject({ "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest" });
    expect(c.body).toMatchObject({ provider: "DEMO-FX" });
    mockApi({ "/api/integration/billing": new RawResponse(502, { title: "勘定系システムに接続できません" }) });
    await expect(integrationApi.submit("billing", SAMPLE_BILLING, "text/csv")).rejects.toMatchObject({ status: 502 });
    expect(inv).toHaveBeenCalledTimes(2);
  });

  it("通信エラーは NETWORK_ERROR", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError("down"));
    await expect(integrationApi.runs()).rejects.toMatchObject({ code: "NETWORK_ERROR" });
  });
});

describe("sampleZengin", () => {
  it("120 桁固定長のヘッダー・データ・トレーラー・エンドを作り、件数と合計がそろう", () => {
    const text = sampleZengin(new Date(2026, 10, 24, 9, 5, 7));
    const lines = text.split("\r\n").filter(Boolean);
    expect(lines.map((l) => l.length)).toEqual([120, 120, 120, 120, 120]);
    expect(lines.map((l) => l[0])).toEqual(["1", "2", "2", "8", "9"]);
    expect(lines[0].slice(54, 58)).toBe("1124");
    expect(lines[0].slice(58, 62)).toBe("0005");
    expect(lines[1].slice(1, 5)).toBe("9999");
    // 顧客コード1（92〜101 桁目）は作成時刻＋連番
    expect(lines[1].slice(91, 101)).toBe("0905070001");
    expect(lines[2].slice(91, 101)).toBe("0905070002");
    expect(lines[3].slice(1, 7)).toBe("000002");
    expect(Number(lines[3].slice(7, 19))).toBe(15000);
  });
});
