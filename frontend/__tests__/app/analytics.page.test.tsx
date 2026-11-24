// app/analytics/page.tsx のテスト：RFM セグメント集計・スコア分布、クロスセル候補・離反リスク・顧客一覧、各 CSV 出力。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import AnalyticsPage from "@/app/analytics/page";
import * as csv from "@/lib/csv";
import { ApiFailure, callsTo, mockApi } from "../../test/api";
import { customerUser, main, renderPage, tellerUser } from "../../test/render";

const customers = [
  { customerId: "CU0001", name: "山田 太郎", customerType: "INDIVIDUAL", segment: "LOYAL", totalAssets: 5_000_000, totalLoans: 0, productCount: 3, lastActivity: "2026-11-20", recencyDays: 1, frequency90d: 30, rScore: 5, fScore: 4, mScore: 5 },
  { customerId: "CU0002", name: "佐藤 花子", customerType: "CORPORATE", segment: "AT_RISK", totalAssets: 2_000_000, totalLoans: 1_000_000, productCount: 1, lastActivity: null, recencyDays: null, frequency90d: 0, rScore: 1, fScore: 1, mScore: 3 },
];
const analytics = {
  date: "2026-11-21",
  segments: [{ segment: "LOYAL", customers: 3, totalAssets: 9_000_000, totalLoans: 0 }, { segment: "AT_RISK", customers: 1, totalAssets: 2_000_000, totalLoans: 1_000_000 }],
  rDistribution: [{ score: 5, customers: 3 }, { score: 1, customers: 1 }],
  mDistribution: [{ score: 5, customers: 2 }],
  crossSell: [{ customerId: "CU0001", name: "山田 太郎", segment: "LOYAL", totalAssets: 5_000_000, product: "TIME", productLabel: "定期預金", reason: "普通預金の残高が大きい" }],
  churnRisk: [{ customerId: "CU0002", name: "佐藤 花子", segment: "AT_RISK", totalAssets: 2_000_000, assetsBefore: null, lastActivity: null, reason: "取引が 180 日ない" }],
  customers,
};

describe("AnalyticsPage", () => {
  it("お客さまには表示しない", async () => {
    await renderPage(<AnalyticsPage />, { user: customerUser, path: "/analytics" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("セグメント別の構成比・平均とスコア分布を表示する", async () => {
    mockApi({ "/customer-analytics": analytics });
    await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    expect(await main().findByText("基準日 2026-11-21（その日までの取引で集計）")).toBeInTheDocument();
    expect(main().getByText("75.0%")).toBeInTheDocument();
    expect(main().getAllByText("優良", { selector: "span.seg" })[0]).toHaveClass("seg-LOYAL");
    expect(main().getAllByTitle("スコア 5: 3 先")[0].querySelector(".bar-value")).toHaveTextContent("3 先（75%）");
    expect(main().getAllByTitle("スコア 2: 0 先")[0].querySelector(".bar-fill")).toHaveStyle({ width: "0%" });
  });

  it("タブでクロスセル候補・離反リスク・顧客一覧を切り替え、顧客詳細へリンクする", async () => {
    mockApi({ "/customer-analytics": analytics });
    const { user } = await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    expect(await main().findByText("定期預金")).toBeInTheDocument();
    expect(main().getByRole("link", { name: /山田 太郎/ })).toHaveAttribute("href", "/customers/CU0001");
    await user.click(main().getByRole("tab", { name: "離反リスク 1" }));
    expect(main().getByText("取引が 180 日ない")).toBeInTheDocument();
    expect(main().getAllByText("—").length).toBeGreaterThanOrEqual(2);
    await user.click(main().getByRole("tab", { name: "顧客一覧 2" }));
    expect(main().getByText("5 / 4 / 5")).toBeInTheDocument();
    expect(main().getByText("（1日前）")).toBeInTheDocument();
    expect(main().getByText("法人")).toBeInTheDocument();
  });

  it("セグメントの「一覧」で顧客一覧を絞り込み、すべてに戻せる", async () => {
    mockApi({ "/customer-analytics": analytics });
    const { user } = await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    const rows = await main().findAllByRole("button", { name: "一覧" });
    await user.click(rows[1]);
    expect(main().getByRole("tab", { name: "顧客一覧 2" })).toHaveAttribute("aria-selected", "true");
    expect(main().getByLabelText("セグメント")).toHaveValue("AT_RISK");
    expect(main().queryByText("5 / 4 / 5")).not.toBeInTheDocument();
    await user.selectOptions(main().getByLabelText("セグメント"), "");
    expect(main().getByText("5 / 4 / 5")).toBeInTheDocument();
  });

  it("各タブの CSV を出力する（区分・セグメントは日本語）", async () => {
    mockApi({ "/customer-analytics": analytics });
    const spy = vi.spyOn(csv, "downloadCsv").mockImplementation(() => undefined);
    const { user } = await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    await user.click(await main().findByRole("button", { name: "CSV出力" }));
    expect(spy.mock.calls[0][0]).toBe("cross_sell_2026-11-21.csv");
    expect(spy.mock.calls[0][2]).toEqual([["CU0001", "山田 太郎", "優良", 5000000, "定期預金", "普通預金の残高が大きい"]]);
    await user.click(main().getByRole("tab", { name: "離反リスク 1" }));
    await user.click(main().getByRole("button", { name: "CSV出力" }));
    expect(spy.mock.calls[1][2]).toEqual([["CU0002", "佐藤 花子", "休眠リスク", 2000000, "", "", "取引が 180 日ない"]]);
    await user.click(main().getByRole("tab", { name: "顧客一覧 2" }));
    await user.selectOptions(main().getByLabelText("セグメント"), "LOYAL");
    await user.click(main().getByRole("button", { name: "CSV出力" }));
    expect(spy.mock.calls[2][0]).toBe("customers_rfm_2026-11-21_LOYAL.csv");
    expect(spy.mock.calls[2][2][0].slice(0, 4)).toEqual(["CU0001", "山田 太郎", "個人", "優良"]);
  });

  it("候補が無いタブは CSV 出力を無効にする", async () => {
    mockApi({ "/customer-analytics": { ...analytics, crossSell: [] } });
    await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    expect(await main().findByText("候補はありません。")).toBeInTheDocument();
    expect(main().getByRole("button", { name: "CSV出力" })).toBeDisabled();
  });

  it("基準日を変えると取得し直す。未取込なら案内を出す", async () => {
    mockApi({ "/customer-analytics": (r: { query: URLSearchParams }) => (r.query.get("date") === "2020-01-01" ? { date: null } : analytics) });
    await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    await main().findByText(/基準日 2026-11-21/);
    fireEvent.change(main().getByLabelText(/^基準日/), { target: { value: "2020-01-01" } });
    expect(await main().findByText(/情報系のデータがまだ取り込まれていません/)).toBeInTheDocument();
    expect(main().getByText("データなし")).toBeInTheDocument();
    await waitFor(() => expect(callsTo("/customer-analytics").some((c) => c.query.get("date") === "2020-01-01")).toBe(true));
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/customer-analytics": new ApiFailure(500, "SYSTEM_ERROR", "集計できません。") });
    await renderPage(<AnalyticsPage />, { user: tellerUser, path: "/analytics" });
    expect(await main().findByRole("alert")).toHaveTextContent("集計できません。");
  });
});
