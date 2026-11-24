// app/operations/page.tsx のテスト：夜間バッチの実行履歴と手動実行、貸倒引当金、利息決算（確認付き）、休眠預金管理。
import { fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import OperationsPage from "@/app/operations/page";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { account, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function mockDefaults(over: Record<string, unknown> = {}) {
  mockApi({
    "/jobs": {
      enabled: false, startTime: "01:00",
      runs: [
        { jobName: "GL_POSTING", runDate: "2026-11-21", startedAt: "2026-11-22T01:00:00", finishedAt: "2026-11-22T01:00:05", outcome: "SUCCESS", detail: "仕訳 12 件" },
        { jobName: "MONTH_END", runDate: "2026-11-01", startedAt: "2026-11-02T01:00:00", finishedAt: null, outcome: null, detail: null },
      ],
    },
    "/loan-provision-summary": { byClass: [{ borrowerClass: "NORMAL", loans: 10, provision: 50000 }, { borrowerClass: "SPECIAL", loans: 1, provision: 45000 }], totalProvision: 95000 },
    "/dormancy": {
      asOf: "2026-11-23", noticed: 0, madeDormant: 0,
      noticeTargets: [account({ accountNo: "1000010", lastActivityAt: "2017-01-01T00:00:00" })],
      dormancyTargets: [],
      dormantAccounts: [account({ accountNo: "1000011", status: "DORMANT", dormantAt: "2025-01-01T00:00:00" })],
    },
    ...over,
  });
}

describe("OperationsPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<OperationsPage />, { user: tellerUser, path: "/operations" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("夜間バッチ：ジョブ名を日本語で表示し、自動実行が無効な旨と実行中を示す", async () => {
    mockDefaults();
    await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    expect(await main().findByText("会計仕訳集計")).toBeInTheDocument();
    expect(main().getByText("月末締め").closest("tr")).toHaveTextContent("実行中");
    expect(main().getByText(/自動実行は無効です/)).toBeInTheDocument();
  });

  it("夜間バッチ：手動実行の結果（実行したジョブ・なし）を表示する", async () => {
    mockDefaults({ "/jobs-run": ["DAILY_SUMMARY", "UNKNOWN_JOB"] });
    const { user } = await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    await user.click(main().getByRole("button", { name: "未実行のジョブを今すぐ実行" }));
    expect(await main().findByText("実行しました: 日次取引集計、UNKNOWN_JOB")).toBeInTheDocument();
    mockApi({ "/jobs-run": [] });
    await user.click(main().getByRole("button", { name: "未実行のジョブを今すぐ実行" }));
    expect(await main().findByText(/本日実行すべきジョブはありません/)).toBeInTheDocument();
  });

  it("貸倒引当金：区分別の引当と合計を表示し、今すぐ見直せる", async () => {
    mockDefaults({ "/loan-provision-run": { loans: 11, changed: 2, totalChange: "-500", totalProvision: "94500" } });
    const { user } = await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    expect(await main().findByText("要管理先")).toBeInTheDocument();
    expect(main().getByText("合計").closest("tr")).toHaveTextContent("95,000");
    await user.click(main().getByRole("button", { name: "引当を今すぐ見直す" }));
    expect(await main().findByText(/11 件を見直し、2 件を更新しました/)).toHaveTextContent("94,500");
  });

  it("利息決算：確認してから実行し、口座ごとの結果を表示する", async () => {
    mockDefaults({
      "/interest-settle": {
        asOf: "2026-09-01", settledCount: 1, skippedCount: 1, totalGross: 1000, totalTax: 203, totalNet: 797,
        results: [
          { accountNo: "1000001", periodFrom: "2026-03-01", periodTo: "2026-08-31", days: 184, averageBalance: 1000000, grossInterest: 1000, nationalTax: 153, localTax: 50, netInterest: 797, settled: true, note: "決算済" },
          { accountNo: "1000002", periodFrom: null, periodTo: null, days: 0, averageBalance: 0, grossInterest: 0, nationalTax: 0, localTax: 0, netInterest: 0, settled: false, note: "対象期間なし" },
        ],
      },
    });
    const { user } = await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    fireEvent.change(main().getByLabelText("決算日"), { target: { value: "2026-09-01" } });
    await user.click(main().getByRole("button", { name: "利息決算を実行…" }));
    await user.click(main().getByRole("button", { name: "やめる" }));
    expect(callsTo("/interest-settle")).toHaveLength(0);
    await user.click(main().getByRole("button", { name: "利息決算を実行…" }));
    await user.click(main().getByRole("button", { name: "2026-09-01 で決算する" }));
    expect(await main().findByText("決算日 2026-09-01：決算 1 口座／対象外 1 口座")).toBeInTheDocument();
    expect(lastBody("/interest-settle")).toEqual({ asOf: "2026-09-01" });
    expect(main().getByText("対象期間なし")).toHaveClass("muted");
    expect(main().getByRole("link", { name: "1000002" }).closest("tr")).toHaveTextContent("—");
  });

  it("休眠預金管理：対象口座を区分ごとに表示し、通知・休眠化を実行する", async () => {
    mockDefaults({ "/dormancy-run": { noticed: 1, madeDormant: 0 } });
    const { user } = await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    expect(await main().findByText(/休眠前の通知対象.*：1 件/)).toBeInTheDocument();
    expect(main().getByText(/休眠化の対象.*：0 件/)).toBeInTheDocument();
    expect(main().getByRole("link", { name: "1000011" }).closest("tr")).toHaveTextContent("2025-01-01");
    await user.click(main().getByRole("button", { name: "通知記録・休眠化を実行" }));
    expect(await main().findByText("休眠前通知 1 件を記録し、0 口座を休眠にしました。")).toBeInTheDocument();
  });

  it("休眠預金管理：未来日は確認のみで実行できない。対象が無ければ実行できない", async () => {
    mockDefaults();
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
    vi.setSystemTime(new Date(2026, 10, 23, 12));
    try {
      await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
      const btn = main().getByRole("button", { name: "通知記録・休眠化を実行" });
      await main().findByText(/休眠前の通知対象.*：1 件/);
      expect(btn).toBeEnabled();
      fireEvent.change(main().getByLabelText("基準日"), { target: { value: "2027-01-01" } });
      expect(btn).toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it("各処理のエラーを表示する", async () => {
    mockDefaults({ "/jobs": new ApiFailure(500, "SYSTEM_ERROR", "ジョブ情報を取得できません。") });
    await renderPage(<OperationsPage />, { user: supervisorUser, path: "/operations" });
    expect(await main().findByText("ジョブ情報を取得できません。")).toBeInTheDocument();
  });
});
