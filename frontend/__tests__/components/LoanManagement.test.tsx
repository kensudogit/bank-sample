// components/LoanManagement.tsx のテスト：貸倒引当金・担保の保全額と再評価・条件変更の履歴とフォーム。
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { LoanManagement } from "@/components/LoanManagement";
import { loanApi } from "@/lib/banking";
import { ApiFailure, lastBody, mockApi } from "../../test/api";

const management = {
  restructured: true,
  restructureCount: 1,
  provisionAmount: 45000,
  provisionUpdatedAt: "2026-11-22T01:00:00",
  requiredProvision: 45000,
  borrowerClass: "SPECIAL",
  unsecuredBasis: false,
  secured: 7_000_000,
  unsecured: 0,
  provisions: [
    { provisionId: 1, asOf: "2026-11-22", borrowerClass: "SPECIAL", outstanding: 300000, secured: 7000000, provisionRate: 0.15, required: 45000, changeAmount: 30000, reason: "条件変更", createdBy: "BATCH" },
    { provisionId: 2, asOf: "2026-11-01", borrowerClass: "WATCH", outstanding: 500000, secured: 7000000, provisionRate: 0.03, required: 15000, changeAmount: -500, reason: "定例", createdBy: "BATCH" },
  ],
  collaterals: [
    {
      collateralId: 7, collateralType: "REAL_ESTATE", description: "東京都港区の土地", guarantorName: null, appraisalValue: 10_000_000,
      haircut: 0.7, recognized: 7_000_000, revaluedAt: "2026-10-01", registeredAt: "2026-01-01",
      revaluations: [{ revaluedAt: "2026-10-01", previousValue: 12000000, newValue: 10000000, reason: "路線価" }],
    },
  ],
  restructures: [
    { restructureId: 1, createdAt: "2026-11-20T10:00:00", outstanding: 300000, arrearsInterest: 1200, oldRate: 2, newRate: 1.5, oldRemaining: 12, newRemaining: 72, oldPayment: 25000, newPayment: 4300, oldMaturity: "2027-11-27", newMaturity: "2032-11-27", reason: "業況悪化", approvedBy: "super01", daysPastDue: 40 },
  ],
};

const loan = {
  loanId: 5, status: "ACTIVE", repaymentMethod: "EQUAL_PAYMENT", outstanding: 300000, annualRate: 1.5,
  schedule: [{ status: "PAID" }, { status: "SCHEDULED" }, { status: "OVERDUE" }],
};

function setup(over: Partial<{ supervisor: boolean; loan: Record<string, unknown>; ok: boolean }> = {}) {
  const run = vi.fn(async (fn: () => Promise<unknown>) => {
    try {
      await fn();
      return over.ok ?? true;
    } catch {
      return false;
    }
  });
  render(<LoanManagement loanId="5" loan={over.loan ?? loan} supervisor={over.supervisor ?? true} run={run} />);
  return run;
}

describe("LoanManagement", () => {
  it("引当の計上状況と履歴（増減の符号）を表示する", async () => {
    mockApi({ "/loan-management": management });
    setup();
    expect(await screen.findByText("条件変更あり（1 回）")).toBeInTheDocument();
    expect(screen.getByText("計上済みと一致")).toBeInTheDocument();
    expect(screen.getAllByText("要管理先").length).toBeGreaterThan(0);
    expect(screen.getByText("残高に引当")).toBeInTheDocument();
    expect(screen.getByText("15.0%")).toBeInTheDocument();
    const cells = screen.getAllByText(/^[+−][￥¥]/);
    expect(cells.map((c) => c.textContent![0])).toEqual(["+", "−"]);
  });

  it("担保の保全額と再評価の履歴（title）を表示し、役席は再評価できる", async () => {
    mockApi({ "/loan-management": management, "/collateral-revalue": {} });
    const run = setup();
    expect(await screen.findByText("不動産")).toBeInTheDocument();
    expect(screen.getByText("70%")).toBeInTheDocument();
    expect(screen.getByText("（再評価 1 回）")).toHaveAttribute("title", expect.stringContaining("12000000→10000000 路線価"));
    await userEvent.type(screen.getByLabelText("新しい評価額"), "９，０００，０００");
    await userEvent.type(screen.getByLabelText("再評価の理由"), "鑑定");
    await userEvent.click(screen.getByRole("button", { name: "再評価" }));
    expect(run).toHaveBeenCalledWith(expect.any(Function), "担保を再評価し、引当を見直しました");
    expect(lastBody("/collateral-revalue")).toEqual({ collateralId: "7", appraisalValue: "9000000", note: "鑑定" });
    expect(screen.getByLabelText("新しい評価額")).toHaveValue("");
  });

  it("条件変更の履歴を変更前 → 変更後で表示する", async () => {
    mockApi({ "/loan-management": management });
    setup();
    expect(await screen.findByText("2.000% → 1.500%")).toBeInTheDocument();
    expect(screen.getByText("12 → 72")).toBeInTheDocument();
    expect(screen.getByText("2027-11-27 → 2032-11-27")).toBeInTheDocument();
  });

  it("条件変更フォーム：初期値は残り回数 + 60、確認のうえ実行する", async () => {
    mockApi({ "/loan-management": management, "/loan-reschedule": {} });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const run = setup();
    const months = await screen.findByLabelText("新しい残り回数");
    expect(months).toHaveValue(62);
    await userEvent.type(screen.getByLabelText("理由"), "返済期間の延長");
    await userEvent.click(screen.getByRole("button", { name: "条件変更する" }));
    expect(confirm).toHaveBeenCalledWith("残り 62 回に条件変更します。よろしいですか？");
    expect(run).toHaveBeenCalled();
    expect(lastBody("/loan-reschedule")).toEqual({ loanId: "5", termMonths: "62", note: "返済期間の延長" });
  });

  it("確認ダイアログでキャンセルすると実行しない", async () => {
    mockApi({ "/loan-management": management });
    vi.spyOn(window, "confirm").mockReturnValue(false);
    const spy = vi.spyOn(loanApi, "reschedule");
    setup();
    await userEvent.type(await screen.findByLabelText("理由"), "x");
    await userEvent.click(screen.getByRole("button", { name: "条件変更する" }));
    expect(spy).not.toHaveBeenCalled();
  });

  it("行員（役席以外）・完済済み・リボルビングには操作を出さない", async () => {
    mockApi({ "/loan-management": management });
    setup({ supervisor: false });
    await screen.findByText("不動産");
    expect(screen.queryByRole("button", { name: "再評価" })).not.toBeInTheDocument();
    expect(screen.queryByText("条件変更（役席）")).not.toBeInTheDocument();
  });

  it("リボルビングは条件変更フォームを出さない", async () => {
    mockApi({ "/loan-management": management });
    setup({ loan: { ...loan, repaymentMethod: "REVOLVING" } });
    await screen.findByText("不動産");
    expect(screen.getByRole("button", { name: "再評価" })).toBeInTheDocument();
    expect(screen.queryByText("条件変更（役席）")).not.toBeInTheDocument();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/loan-management": new ApiFailure(403, "FORBIDDEN", "権限がありません。") });
    setup();
    expect(await screen.findByRole("alert")).toHaveTextContent("権限がありません。");
  });
});
