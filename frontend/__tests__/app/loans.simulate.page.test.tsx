// app/loans/simulate/page.tsx のテスト：返済シミュレーションの計算条件、比較表、予定表の表示切替と CSV 出力。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import SimulatePage from "@/app/loans/simulate/page";
import * as csv from "@/lib/csv";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";
import { customerUser, main, renderPage } from "../../test/render";

function simulate(r: MockRequest) {
  const method = r.query.get("repaymentMethod")!;
  const months = Number(r.query.get("termMonths"));
  return {
    principal: Number(r.query.get("amount")), annualRate: Number(r.query.get("rate")), months, method, paymentDay: Number(r.query.get("paymentDay") ?? 27),
    comparison: [
      { method: "EQUAL_PAYMENT", firstPayment: 91855, lastPayment: 91850, maxPayment: 91855, totalInterest: 8579000, totalPayment: 38579000 },
      { method: "EQUAL_PRINCIPAL", firstPayment: 108928, lastPayment: 71517, maxPayment: 108928, totalInterest: 7893000, totalPayment: 37893000 },
    ],
    schedule: Array.from({ length: months }, (_, i) => ({ no: i + 1, dueDate: "2026-12-28", principal: 54000, interest: 37500, total: 91500, balanceAfter: 30000000 - 54000 * (i + 1) })),
  };
}

describe("SimulatePage", () => {
  it("初期値（3,000 万円・1.5%・35 年）で計算し、比較表と最初の 12 回を表示する", async () => {
    mockApi({ "/loan-simulate": simulate });
    const { user } = await renderPage(<SimulatePage />, { user: customerUser, path: "/loans/simulate" });
    await user.click(main().getByRole("button", { name: "計算する" }));
    expect(await main().findByText(/比較（.*30,000,000・年 1.5%・420 回）/)).toBeInTheDocument();
    const q = callsTo("/loan-simulate")[0].query;
    expect(q.get("termMonths")).toBe("420");
    expect(q.get("paymentDay")).toBe("27");
    expect(main().getByText("最初の 12 回（全 420 回）")).toBeInTheDocument();
    expect(main().getAllByRole("row").filter((r) => /^\d+2026-12-28/.test(r.textContent ?? ""))).toHaveLength(12);
    await user.click(main().getByRole("button", { name: "すべて表示" }));
    expect(main().getByText("全 420 回")).toBeInTheDocument();
  });

  it("全角の借入額・半年単位の期間を変換して送る", async () => {
    mockApi({ "/loan-simulate": simulate });
    const { user } = await renderPage(<SimulatePage />, { user: customerUser, path: "/loans/simulate" });
    await user.clear(main().getByLabelText("借入額（円）"));
    await user.type(main().getByLabelText("借入額（円）"), "１，０００，０００");
    await user.clear(main().getByLabelText("返済期間（年）"));
    await user.type(main().getByLabelText("返済期間（年）"), "1.5");
    await user.clear(main().getByLabelText("毎月の返済日"));
    await user.click(main().getByRole("button", { name: "計算する" }));
    await main().findByText(/・18 回）/);
    const q = callsTo("/loan-simulate")[0].query;
    expect(q.get("amount")).toBe("1000000");
    expect(q.get("termMonths")).toBe("18");
    expect(q.has("paymentDay")).toBe(false);
    expect(main().getByText("最初の 12 回（全 18 回）")).toBeInTheDocument();
  });

  it("比較表から別の返済方法に切り替えて再計算する", async () => {
    mockApi({ "/loan-simulate": simulate });
    const { user } = await renderPage(<SimulatePage />, { user: customerUser, path: "/loans/simulate" });
    await user.click(main().getByRole("button", { name: "計算する" }));
    await user.click(await main().findByRole("button", { name: "この方法で表示" }));
    await waitFor(() => expect(callsTo("/loan-simulate").at(-1)!.query.get("repaymentMethod")).toBe("EQUAL_PRINCIPAL"));
    expect(await main().findByText(/返済予定表（元金均等/)).toBeInTheDocument();
    expect(main().getByLabelText("返済方法")).toHaveValue("EQUAL_PRINCIPAL");
  });

  it("CSV は表示件数に関係なく全回分を出力する", async () => {
    mockApi({ "/loan-simulate": simulate });
    const spy = vi.spyOn(csv, "downloadCsv").mockImplementation(() => undefined);
    const { user } = await renderPage(<SimulatePage />, { user: customerUser, path: "/loans/simulate" });
    await user.click(main().getByRole("button", { name: "計算する" }));
    await user.click(await main().findByRole("button", { name: "CSV出力" }));
    const [filename, header, rows] = spy.mock.calls[0];
    expect(filename).toBe("loan_simulation_30000000_420.csv");
    expect(header).toEqual(["回", "返済日", "元金", "利息", "返済額", "返済後残高"]);
    expect(rows).toHaveLength(420);
  });

  it("エラーを表示する", async () => {
    mockApi({ "/loan-simulate": new ApiFailure(400, "VALIDATION_ERROR", "年利は 0〜15% で入力してください。") });
    const { user } = await renderPage(<SimulatePage />, { user: customerUser, path: "/loans/simulate" });
    await user.click(main().getByRole("button", { name: "計算する" }));
    expect(await main().findByRole("alert")).toHaveTextContent("年利は 0〜15% で入力してください。");
  });
});
