// lib/banking.ts のテスト：預金・融資・決済・リスク・バッチ・情報系 API の送信先とパラメータ、
// 更新系がキャッシュを無効化すること、読み取りフックのキャッシュキー。
import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  batchApi, depositApi, infoApi, loanApi, paymentApi, riskApi, useBatchDaily, useInfoReport, useLoans, useMisDashboard,
} from "@/lib/banking";
import * as query from "@/lib/query";
import { callsTo, lastBody, mockApi, type MockRequest } from "../../test/api";

describe("banking API", () => {
  it("GET は空のパラメータを送らない", async () => {
    mockApi({ "/loans": [], "/remittances": [] });
    await loanApi.loans();
    await paymentApi.remittances("OUT", "");
    expect(callsTo("/loans")[0].query.toString()).toBe("");
    expect(callsTo("/remittances")[0].query.toString()).toBe("direction=OUT");
  });

  it("POST は本文を送り、キャッシュを無効化する", async () => {
    const inv = vi.spyOn(query, "invalidate");
    mockApi({ "/loan-repay": {}, "/fx-exchange": {}, "/remit-resolve": {}, "/aml-alert-update": {}, "/batch-rerun": {}, "/dw-load": [] });
    await loanApi.repay("L1", "1000", "k1");
    await depositApi.exchange({ fromAccountNo: "1", toAccountNo: "2", amount: "5", idempotencyKey: "k2" });
    await paymentApi.resolve("R1", "CREDIT", "1000001");
    await riskApi.updateAlert("A1", "CLOSED", "確認済");
    await batchApi.rerun("GL_POSTING", "2026-11-21");
    await infoApi.load("2026-11-01", "2026-11-21");
    expect(lastBody("/loan-repay")).toEqual({ loanId: "L1", amount: "1000", idempotencyKey: "k1" });
    expect(lastBody("/remit-resolve")).toEqual({ remitId: "R1", decision: "CREDIT", accountNo: "1000001" });
    expect(lastBody("/batch-rerun")).toEqual({ jobName: "GL_POSTING", runDate: "2026-11-21" });
    expect(lastBody("/dw-load")).toEqual({ from: "2026-11-01", to: "2026-11-21" });
    expect(inv).toHaveBeenCalledTimes(6);
  });

  it("返済シミュレーション・残高証明書・情報系帳票のパラメータ", async () => {
    mockApi({ "/loan-simulate": {}, "/balance-certificate": {}, "/info-report": {} });
    await loanApi.simulate({ amount: "1000000", rate: "1.5", termMonths: "12", repaymentMethod: "EQUAL_PAYMENT" });
    await batchApi.certificate("1000001", "2026-11-01");
    await infoApi.report("TX_SUMMARY", { from: "2026-11-01", to: "2026-11-30" });
    expect(Object.fromEntries(callsTo("/loan-simulate")[0].query)).toEqual({
      amount: "1000000", rate: "1.5", termMonths: "12", repaymentMethod: "EQUAL_PAYMENT",
    });
    expect(Object.fromEntries(callsTo("/balance-certificate")[0].query)).toEqual({ accountNo: "1000001", date: "2026-11-01" });
    expect(Object.fromEntries(callsTo("/info-report")[0].query)).toEqual({ type: "TX_SUMMARY", from: "2026-11-01", to: "2026-11-30" });
  });
});

describe("cached readers", () => {
  it("useLoans は状態ごとに別キャッシュで取得する", async () => {
    mockApi({ "/loans": (r: MockRequest) => [{ status: r.query.get("status") }] });
    const { result } = renderHook(() => useLoans("ACTIVE"));
    await waitFor(() => expect(result.current.data).toEqual([{ status: "ACTIVE" }]));
  });

  it("useMisDashboard・useBatchDaily・useInfoReport は期間・日付をパラメータで送る", async () => {
    mockApi({ "/mis-dashboard": { ok: 1 }, "/batch-daily": { ok: 2 }, "/info-report": { ok: 3 } });
    const mis = renderHook(() => useMisDashboard("2026-10-01", "2026-10-31"));
    const daily = renderHook(() => useBatchDaily("2026-11-21"));
    const rep = renderHook(() => useInfoReport("DEPOSIT_BALANCE", { date: "2026-11-21" }));
    await waitFor(() => expect(mis.result.current.data).toEqual({ ok: 1 }));
    await waitFor(() => expect(daily.result.current.data).toEqual({ ok: 2 }));
    await waitFor(() => expect(rep.result.current.data).toEqual({ ok: 3 }));
    expect(callsTo("/mis-dashboard")[0].query.get("from")).toBe("2026-10-01");
    expect(callsTo("/batch-daily")[0].query.get("date")).toBe("2026-11-21");
    expect(callsTo("/info-report")[0].query.get("type")).toBe("DEPOSIT_BALANCE");
  });
});
