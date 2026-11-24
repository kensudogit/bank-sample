// app/batch/page.tsx のテスト：業務一覧（直近結果・失敗の再実行・今すぐ実行）、日次の結果、月末締め、帳票出力。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import BatchPage from "@/app/batch/page";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { main, renderPage, tellerUser, supervisorUser } from "../../test/render";

const catalog = {
  items: [
    {
      no: 1, business: "日次取引集計", description: "取引種別・通貨ごとの件数と金額",
      jobs: [{ name: "DAILY_SUMMARY", label: "日次取引集計", timing: "毎日", last: { runDate: "2026-11-22", finishedAt: "2026-11-23T01:00:03", outcome: "SUCCESS", detail: "12 種別" } }],
    },
    {
      no: 8, business: "会計仕訳集計", description: "総勘定元帳の日計",
      jobs: [{ name: "GL_POSTING", label: "会計仕訳集計", timing: "毎日", last: { runDate: "2026-11-22", finishedAt: "2026-11-23T01:00:09", outcome: "FAILURE", detail: "仮受金が 0 になりません" } }],
    },
  ],
  otherJobs: [{ name: "REQUEST_CLEANUP", label: "冪等キー整理", timing: "毎日", last: null }],
};

const daily = {
  checks: [
    { checkGroup: "FEE", checkItem: "他行振込手数料", result: "OK", detail: "一致" },
    { checkGroup: "REMITTANCE", checkItem: "被仕向の保留", result: "WARN", detail: "保留 1 件" },
    { checkGroup: "LEDGER", checkItem: "仮受金", result: "NG", detail: "残 100 円" },
  ],
  summary: [{ txType: "DEPOSIT", currency: "JPY", txCount: 1200, amount: 5_000_000 }],
  ledger: [{ glCode: "1000", name: "現金", debit: 1000, credit: 0 }, { glCode: "2000", name: "普通預金", debit: 0, credit: 1000 }],
  interest: [{ accountType: "ORDINARY", accounts: 15, daily: 25.6, cumulative: 1234.9 }],
  fees: [{ feeType: "DOMESTIC_TRANSFER", feeCount: 3, chargedAmount: 1100, expectedAmount: 1100, mismatchCount: 0 }, { feeType: "FOREIGN", feeCount: 1, chargedAmount: 3000, expectedAmount: 4000, mismatchCount: 1 }],
};

describe("BatchPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<BatchPage />, { user: tellerUser, path: "/batch" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("業務一覧：直近の結果を表示し、失敗したジョブだけ再実行できる", async () => {
    mockApi({ "/batch-catalog": catalog, "/batch-rerun": { outcome: "SUCCESS", detail: "仕訳 12 件" } });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    expect(await main().findByText("成功")).toHaveClass("badge-SUCCESS");
    expect(main().getByText("失敗")).toHaveClass("badge-FAILURE");
    expect(main().getByText("未実行")).toBeInTheDocument();
    expect(main().getByText("仮受金が 0 になりません")).toHaveClass("minus");
    expect(main().getAllByRole("button", { name: "再実行" })).toHaveLength(1);
    await user.click(main().getByRole("button", { name: "再実行" }));
    expect(await main().findByText("会計仕訳集計 を再実行しました：成功（仕訳 12 件）")).toBeInTheDocument();
    expect(lastBody("/batch-rerun")).toEqual({ jobName: "GL_POSTING", runDate: "2026-11-22" });
  });

  it("業務一覧：未実行のジョブを今すぐ実行する", async () => {
    mockApi({ "/batch-catalog": catalog, "/jobs-run": ["DAILY_SUMMARY"] });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(await main().findByRole("button", { name: "未実行のジョブを今すぐ実行" }));
    expect(await main().findByText("実行しました：DAILY_SUMMARY")).toBeInTheDocument();
    mockApi({ "/jobs-run": new ApiFailure(409, "BUSY", "実行中のジョブがあります。") });
    await user.click(main().getByRole("button", { name: "未実行のジョブを今すぐ実行" }));
    expect(await main().findByText("実行中のジョブがあります。")).toBeInTheDocument();
  });

  it("日次の結果：照合・集計・元帳（貸借合計）・利息・手数料を表示し、業務日を変えられる", async () => {
    mockApi({ "/batch-daily": daily });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "日次の結果" }));
    expect(await main().findByText("被仕向の保留")).toBeInTheDocument();
    expect(main().getByText("WARN")).toHaveClass("badge-PENDING");
    expect(main().getByText("NG")).toHaveClass("badge-FAILURE");
    expect(main().getByText("会計照合")).toBeInTheDocument();
    expect(main().getByText("入金")).toBeInTheDocument();
    const total = main().getByText("合計", { selector: "th" }).closest("tr")!;
    expect(total.textContent!.match(/1,000/g)).toHaveLength(2);
    expect(main().getByText("25.60 円")).toBeInTheDocument();
    expect(main().getByText(/1,234$/)).toBeInTheDocument();
    expect(main().getByText("海外送金").closest("tr")!.lastElementChild).toHaveClass("minus");
    fireEvent.change(main().getByLabelText("業務日"), { target: { value: "2026-11-01" } });
    await waitFor(() => expect(callsTo("/batch-daily").some((c) => c.query.get("date") === "2026-11-01")).toBe(true));
  });

  it("日次の結果：結果が無い日は案内を出す", async () => {
    mockApi({ "/batch-daily": { checks: [], summary: [], ledger: [], interest: [], fees: [] } });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "日次の結果" }));
    expect(await main().findByText(/この日のバッチ結果はまだありません/)).toBeInTheDocument();
    expect(main().queryByText("合計", { selector: "th" })).not.toBeInTheDocument();
  });

  it("月末締め：締め済みと例外（NG 件数）を表示する", async () => {
    mockApi({
      "/month-closes": [
        { closeMonth: "2026-10", status: "CLOSED", ngChecks: 0, totalDeposits: 1, totalLoans: 1, accountCount: 15, txCount: 300, feeIncome: 1, loanInterest: 1, interestPaid: 1, accruedInterest: 1, closedAt: "2026-11-02T01:00:00", closedBy: "BATCH" },
        { closeMonth: "2026-09", status: "EXCEPTION", ngChecks: 2, totalDeposits: 1, totalLoans: 1, accountCount: 15, txCount: 280, feeIncome: 1, loanInterest: 1, interestPaid: 1, accruedInterest: 1, closedAt: "2026-10-02T01:00:00", closedBy: "BATCH" },
      ],
    });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "月末締め" }));
    expect(await main().findByText("締め済み")).toHaveClass("badge-SUCCESS");
    expect(main().getByText("例外（NG 2）")).toHaveClass("badge-FAILURE");
  });

  it("月末締め・帳票出力：まだ無いときの表示", async () => {
    mockApi({ "/month-closes": [], "/report-outputs": [] });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "月末締め" }));
    expect(await main().findByText("まだ締めた月はありません。")).toBeInTheDocument();
    await user.click(main().getByRole("tab", { name: "帳票出力" }));
    expect(await main().findByText("まだ帳票はありません。")).toBeInTheDocument();
  });

  it("帳票出力：一覧を表示し、CSV をサーバーの本文のままダウンロードする", async () => {
    mockApi({
      "/report-outputs": [{ reportId: 7, businessDate: "2026-11-22", reportType: "DAILY_BALANCE", fileName: "daily_balance_20261122.csv", rowCount: 15, createdAt: "2026-11-23T01:00:10", createdBy: "BATCH" }],
      "/report-output": { content: "﻿科目,借方,貸方\r\n1000,1000,0" },
    });
    const create = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:r");
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "帳票出力" }));
    expect(await main().findByText("日計表")).toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "CSV" }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(callsTo("/report-output")[0].query.get("reportId")).toBe("7");
    expect((create.mock.calls[0][0] as Blob).type).toContain("text/csv");
  });

  it("帳票出力：ダウンロードのエラーを表示する", async () => {
    mockApi({
      "/report-outputs": [{ reportId: 7, businessDate: "2026-11-22", reportType: "X_UNKNOWN", fileName: "x.csv", rowCount: 1, createdAt: "2026-11-23T01:00:10", createdBy: "BATCH" }],
      "/report-output": new ApiFailure(404, "NOT_FOUND", "帳票が見つかりません。"),
    });
    const { user } = await renderPage(<BatchPage />, { user: supervisorUser, path: "/batch" });
    await user.click(main().getByRole("tab", { name: "帳票出力" }));
    expect(await main().findByText("X_UNKNOWN")).toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "CSV" }));
    expect(await main().findByRole("alert")).toHaveTextContent("帳票が見つかりません。");
  });
});
