// app/mis/page.tsx のテスト：最新取込日を基準にした期間の取得、主要指標と前日比、構成バー、月次サマリー、DWH 取込。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import MisPage from "@/app/mis/page";
import { ApiFailure, callsTo, lastBody, mockApi, type MockRequest } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const status = { firstDate: "2026-06-01", lastDate: "2026-11-21", days: 174, lastLoad: { loadedAt: "2026-11-22T01:05:00", snapshotDate: "2026-11-21", loadedBy: "BATCH" } };
const latest = { snapshotDate: "2026-11-21", totalDeposits: 500_000_000, totalLoans: 100_000_000, nplAmount: 2_000_000, customerCount: 1200, accountCount: 1500, activeAccounts: 1400, txCount: 321, newAccounts: 3, closedAccounts: 1 };

function dashboard(r: MockRequest) {
  return {
    status, from: r.query.get("from") ?? "2026-10-23", to: r.query.get("to") ?? "2026-11-21",
    latest, previous: { ...latest, totalDeposits: 501_000_000, totalLoans: 100_000_000 },
    series: [{ snapshotDate: "2026-11-20", totalDeposits: 501_000_000, totalLoans: 100_000_000 }, { snapshotDate: "2026-11-21", totalDeposits: 500_000_000, totalLoans: 100_000_000 }],
    profit: { loanInterestIncome: 120000, feeIncome: 30000, depositInterestPaid: 200000, net: -50000 },
    depositMix: [{ accountType: "ORDINARY", currency: "JPY", accounts: 1000, balanceJpy: 400_000_000 }, { accountType: "FOREIGN", currency: "USD", accounts: 20, balanceJpy: 100_000_000 }],
    loanMix: [], loanClassMix: [{ borrowerClass: "SPECIAL", loans: 1, outstanding: 2_000_000 }],
    segments: [{ segment: "LOYAL", customers: 100, totalAssets: 300_000_000 }],
    monthly: [{ month: "2026-11", days: 21, totalDeposits: 500_000_000, totalLoans: 100_000_000, customerCount: 1200, txCount: 6000, newAccounts: 30, closedAccounts: 5, loanInterestIncome: 120000, feeIncome: 30000, depositInterestPaid: 200000 }],
  };
}

describe("MisPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<MisPage />, { user: tellerUser, path: "/mis" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("最新の取込日から 30 日分を取得し、主要指標・前日比・損益を表示する", async () => {
    mockApi({ "/mis-dashboard": dashboard });
    await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    await waitFor(() => expect(callsTo("/mis-dashboard").some((c) => c.query.get("from") === "2026-10-23" && c.query.get("to") === "2026-11-21")).toBe(true));
    expect(await main().findByText(/基準日 2026-11-21（2026-10-23 〜 2026-11-21）/)).toBeInTheDocument();
    expect(main().getByText(/前日比 −/)).toHaveClass("delta-minus");
    expect(main().getByText(/前日比 ±/)).toHaveClass("muted");
    expect(main().getByText("20.0%")).toBeInTheDocument();
    expect(main().getByText("2.00%")).toBeInTheDocument();
    expect(main().getByText("1,200 先")).toBeInTheDocument();
    expect(main().getByText("差引").nextElementSibling).toHaveClass("minus");
  });

  it("表示期間を切り替えると開始日を変えて取得する", async () => {
    mockApi({ "/mis-dashboard": dashboard });
    const { user } = await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    await main().findByText(/基準日 2026-11-21/);
    await user.click(main().getByRole("tab", { name: "90日" }));
    await waitFor(() => expect(callsTo("/mis-dashboard").some((c) => c.query.get("from") === "2026-08-24")).toBe(true));
  });

  it("構成バー（外貨は通貨付き・該当なし）と月次サマリー、推移グラフを表示する", async () => {
    mockApi({ "/mis-dashboard": dashboard });
    await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    expect(await main().findByTitle(/^外貨 USD:/)).toBeInTheDocument();
    expect(main().getByTitle(/^普通:/).querySelector(".bar-fill")).toHaveStyle({ width: "100%" });
    expect(main().getByText("該当なし")).toBeInTheDocument();
    expect(main().getByTitle(/^優良:/)).toHaveAttribute("title", expect.stringContaining("100 先"));
    expect(main().getByText("（21日）")).toBeInTheDocument();
    expect(main().getByRole("img", { name: /預金・貸出金残高の推移/ })).toBeInTheDocument();
  });

  it("未取込なら案内を出し、取込状況は「未取込」", async () => {
    mockApi({ "/mis-dashboard": { status: { firstDate: null, lastDate: null, days: 0, lastLoad: null }, latest: null } });
    await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    expect(await main().findByText("情報系のデータがまだ取り込まれていません")).toBeInTheDocument();
    expect(main().getByText("未取込")).toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "主要指標" })).not.toBeInTheDocument();
  });

  it("DWH 取込：期間を送り、取り込んだ日数を表示する", async () => {
    mockApi({ "/mis-dashboard": dashboard, "/dw-load": [{}, {}, {}] });
    const { user } = await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    await main().findByText(/^基準日 2026-11-21/);
    expect(main().getByText("2026-11-21 分・BATCH")).toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "取り込む" }));
    expect(await main().findByText(/3 日分を取り込みました/)).toBeInTheDocument();
    const body = lastBody("/dw-load");
    expect(body.from < body.to).toBe(true);
  });

  it("DWH 取込のエラー・ダッシュボードの取得エラーを表示する", async () => {
    mockApi({ "/mis-dashboard": new ApiFailure(500, "SYSTEM_ERROR", "DWH に接続できません。") });
    await renderPage(<MisPage />, { user: supervisorUser, path: "/mis" });
    expect(await main().findByRole("alert")).toHaveTextContent("DWH に接続できません。");
  });
});
