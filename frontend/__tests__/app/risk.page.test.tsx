// app/risk/page.tsx のテスト：信用リスク（EAD・EL・不良債権・引当・内訳バー・与信集中）、流動性リスク、為替リスク。
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import RiskPage from "@/app/risk/page";
import { ApiFailure, mockApi } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const dashboard = {
  asOf: "2026-11-23T10:00:00",
  credit: {
    totalOutstanding: 100_000_000, loanCount: 12, totalEad: 120_000_000, expectedLoss: 600_000, nplAmount: 5_000_000, nplRatio: 0.05,
    totalProvision: 2_000_000, provisionToLoans: 0.02, provisionToNpl: 0.4,
    byGrade: [{ key: "A", ead: 80_000_000, count: 8, expectedLoss: 100_000 }, { key: "C", ead: 0, count: 0, expectedLoss: 0 }],
    byClass: [{ key: "NORMAL", outstanding: 95_000_000, count: 11, expectedLoss: 1 }, { key: "SPECIAL", outstanding: 5_000_000, count: 1, expectedLoss: 1 }],
    byProduct: [{ key: "HOUSING", ead: 90_000_000, count: 9, expectedLoss: 1 }],
    topBorrowers: [{ customerId: "CU0001", customerName: "山田 太郎", ead: 30_000_000, share: 0.25 }],
  },
  liquidity: {
    totalDeposits: 500_000_000, demandDeposits: 300_000_000, totalLoans: 100_000_000, loanToDepositRatio: 0.2,
    timeDepositsMaturing30d: { amount: 10_000_000, n: 3 },
    byType: [{ accountType: "ORDINARY", currency: "JPY", accounts: 1200, yenEquivalent: 300_000_000 }],
  },
  fx: { positions: [{ currency: "USD", position: 12345.6, ttm: 150, yenEquivalent: 1_851_840, pnlIfYenWeakens1pct: 18518 }] },
};

describe("RiskPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<RiskPage />, { user: tellerUser, path: "/risk" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("信用リスクの指標を表示し、不良債権比率が 3% 超なら警告色にする", async () => {
    mockApi({ "/risk-dashboard": dashboard });
    await renderPage(<RiskPage />, { user: supervisorUser, path: "/risk" });
    expect(await main().findByText("基準時点 2026-11-23 10:00:00")).toBeInTheDocument();
    expect(main().getByText("12 件")).toBeInTheDocument();
    expect(main().getByText("EAD 比 0.50%")).toBeInTheDocument();
    expect(main().getByText("不良債権比率 5.00%").previousElementSibling).toHaveClass("minus");
    expect(main().getByText("40.0%")).toBeInTheDocument();
  });

  it("内訳バーは最大値を 100% とし、0 は — で表示する", async () => {
    mockApi({ "/risk-dashboard": dashboard });
    await renderPage(<RiskPage />, { user: supervisorUser, path: "/risk" });
    const rowA = await main().findByTitle(/^格付 A:/);
    expect(rowA.querySelector(".bar-fill")).toHaveStyle({ width: "100%" });
    const rowC = main().getByTitle(/^格付 C:/);
    expect(rowC.querySelector(".bar-value")).toHaveTextContent("—");
    expect(main().getByTitle(/^要管理先:/).querySelector(".bar-fill")).toHaveStyle({ width: `${(5 / 95) * 100}%` });
  });

  it("与信集中・流動性・為替リスクを表示する", async () => {
    mockApi({ "/risk-dashboard": dashboard });
    await renderPage(<RiskPage />, { user: supervisorUser, path: "/risk" });
    expect(await main().findByText("25.0%")).toBeInTheDocument();
    expect(main().getByText("20.0%")).toBeInTheDocument();
    expect(main().getByText("3 件")).toBeInTheDocument();
    expect(main().getByText("1,200")).toBeInTheDocument();
    expect(main().getByText("12,345.60")).toBeInTheDocument();
    expect(main().getByText("150.00")).toBeInTheDocument();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/risk-dashboard": new ApiFailure(500, "SYSTEM_ERROR", "集計に失敗しました。") });
    await renderPage(<RiskPage />, { user: supervisorUser, path: "/risk" });
    expect(await main().findByRole("alert")).toHaveTextContent("集計に失敗しました。");
  });
});
