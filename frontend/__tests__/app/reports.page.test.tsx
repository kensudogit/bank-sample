// app/reports/page.tsx のテスト：帳票の種類と条件（基準日／期間）、列の種類ごとの表示、合計行、CSV 出力。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ReportsPage from "@/app/reports/page";
import * as csv from "@/lib/csv";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function report(r: MockRequest) {
  const type = r.query.get("type");
  if (type === "TX_SUMMARY") {
    return {
      title: "取引集計表", condition: `${r.query.get("from") ?? "2026-10-23"}〜${r.query.get("to") ?? "2026-11-21"}`, generatedAt: "2026-11-23T10:00:00",
      columns: [{ key: "txType", label: "取引種別", kind: "txType" }, { key: "count", label: "件数", kind: "number" }, { key: "amount", label: "金額", kind: "yen" }],
      rows: [{ txType: "DEPOSIT", count: 1200, amount: 5_000_000 }, { txType: "FEE", count: 3, amount: null }],
      totals: { count: 1203, amount: 5_000_000 },
    };
  }
  return {
    title: "預金残高表", condition: `基準日 ${r.query.get("date") ?? "2026-11-21"}`, generatedAt: "2026-11-23T10:00:00",
    columns: [{ key: "accountType", label: "預金種類", kind: "accountType" }, { key: "currency", label: "通貨", kind: "text" }, { key: "balance", label: "残高", kind: "yen" }],
    rows: [{ accountType: "ORDINARY", currency: "JPY", balance: 400_000_000 }, { accountType: "FOREIGN", currency: "USD", balance: 100_000_000 }],
    totals: {},
  };
}

describe("ReportsPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<ReportsPage />, { user: tellerUser, path: "/reports" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("既定は預金残高表。区分値は日本語で表示し、基準日を変えると取得し直す", async () => {
    mockApi({ "/info-report": report });
    await renderPage(<ReportsPage />, { user: supervisorUser, path: "/reports" });
    expect(await main().findByRole("heading", { name: "預金残高表" })).toBeInTheDocument();
    expect(main().getByText("普通")).toBeInTheDocument();
    expect(main().getByText(/400,000,000/)).toBeInTheDocument();
    expect(main().getByText(/基準日 2026-11-21 ・ 2 行 ・ 作成 2026-11-23 10:00:00/)).toBeInTheDocument();
    expect(document.querySelector("tfoot")).toBeNull();
    fireEvent.change(main().getByLabelText("基準日"), { target: { value: "2026-10-31" } });
    expect(await main().findByText(/基準日 2026-10-31/)).toBeInTheDocument();
    expect(callsTo("/info-report").at(-1)!.query.get("date")).toBe("2026-10-31");
  });

  it("期間指定の帳票は開始日・終了日を送り、合計行と空欄（—）を表示する", async () => {
    mockApi({ "/info-report": report });
    const { user } = await renderPage(<ReportsPage />, { user: supervisorUser, path: "/reports" });
    await user.click(main().getByRole("tab", { name: "取引集計表" }));
    expect(await main().findByRole("heading", { name: "取引集計表" })).toBeInTheDocument();
    expect(main().getByText("入金")).toBeInTheDocument();
    expect(main().getByText("—")).toBeInTheDocument();
    const foot = document.querySelector("tfoot")!;
    expect(foot).toHaveTextContent("合計");
    expect(foot).toHaveTextContent("1,203");
    fireEvent.change(main().getByLabelText("開始日"), { target: { value: "2026-11-01" } });
    fireEvent.change(main().getByLabelText("終了日"), { target: { value: "2026-11-15" } });
    await waitFor(() => expect(callsTo("/info-report").some((c) => c.query.get("from") === "2026-11-01" && c.query.get("to") === "2026-11-15")).toBe(true));
  });

  it("CSV は区分値を日本語、金額は数値のまま出力し、合計行と条件付きのファイル名にする", async () => {
    mockApi({ "/info-report": report });
    const spy = vi.spyOn(csv, "downloadCsv").mockImplementation(() => undefined);
    const { user } = await renderPage(<ReportsPage />, { user: supervisorUser, path: "/reports" });
    await user.click(main().getByRole("tab", { name: "取引集計表" }));
    await main().findByRole("heading", { name: "取引集計表" });
    await user.click(main().getByRole("button", { name: "CSV出力" }));
    const [filename, header, rows] = spy.mock.calls[0];
    expect(filename).toBe("tx_summary_2026-10-23_2026-11-21.csv");
    expect(header).toEqual(["取引種別", "件数", "金額"]);
    expect(rows).toEqual([["入金", 1200, 5000000], ["手数料", 3, ""], ["合計", 1203, 5000000]]);
  });

  it("基準日の帳票の CSV ファイル名には日付だけを付ける", async () => {
    mockApi({ "/info-report": report });
    const spy = vi.spyOn(csv, "downloadCsv").mockImplementation(() => undefined);
    const { user } = await renderPage(<ReportsPage />, { user: supervisorUser, path: "/reports" });
    await main().findByRole("heading", { name: "預金残高表" });
    await user.click(main().getByRole("button", { name: "CSV出力" }));
    expect(spy.mock.calls[0][0]).toBe("deposit_balance_2026-11-21.csv");
    expect(spy.mock.calls[0][2]).toHaveLength(2);
  });

  it("データが無ければ CSV 出力を無効にし、エラーを表示する", async () => {
    mockApi({ "/info-report": { title: "貸出金残高表", condition: "基準日 2026-11-21", generatedAt: "2026-11-23T10:00:00", columns: [], rows: [], totals: {} } });
    const { user } = await renderPage(<ReportsPage />, { user: supervisorUser, path: "/reports" });
    await user.click(main().getByRole("tab", { name: "貸出金残高表" }));
    expect(await main().findByText("該当するデータがありません。")).toBeInTheDocument();
    expect(main().getByRole("button", { name: "CSV出力" })).toBeDisabled();
    mockApi({ "/info-report": new ApiFailure(400, "INVALID_PERIOD", "期間の指定が正しくありません。") });
    await user.click(main().getByRole("tab", { name: "経営指標 日次推移表" }));
    expect(await main().findByRole("alert")).toHaveTextContent("期間の指定が正しくありません。");
  });
});
