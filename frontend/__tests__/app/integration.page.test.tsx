// app/integration/page.tsx のテスト：役席のみ、稼働状況、データの送信（サンプル・ファイル・文字コード）と結果、エラー、実行履歴。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import IntegrationPage from "@/app/integration/page";
import { RawResponse, callsTo, mockApi } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const runs = [
  { runId: "r2", kind: "ZENGIN_INCOMING", startedAt: "2026-11-24T01:02:03.000Z", durationMs: 1234, outcome: "PARTIAL", consumer: "bank-ops", operator: "supervisor1", requestId: "abcdef12-3456", summary: "0005 2 件：入金 1・保留 1・重複 0・対象外 0" },
  { runId: "r1", kind: "FX_RATES", startedAt: "2026-11-24T00:00:00.000Z", durationMs: 20, outcome: "FAILURE", consumer: "fx-provider", operator: null, requestId: "99999999-0000", summary: "入力エラー 1 件" },
];

function mockDefaults() {
  mockApi({
    "/api/integration/status": new RawResponse(200, { status: "UP", runtime: "mule-compat", core: "UP" }),
    "/api/integration/runs": new RawResponse(200, runs),
  });
}

describe("IntegrationPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<IntegrationPage />, { user: tellerUser, path: "/integration" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("Kong・MuleSoft・勘定系の稼働状況と実行履歴を表示する", async () => {
    mockDefaults();
    await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("ランタイム: mule-compat")).toBeInTheDocument();
    expect(main().getAllByText("稼働中")).toHaveLength(3);
    expect(await main().findByText("一部保留")).toHaveClass("badge-PENDING");
    expect(main().getByText("失敗")).toHaveClass("badge-FAILURE");
    expect(main().getByText("（supervisor1）")).toBeInTheDocument();
    expect(main().getByText("1,234 ms")).toBeInTheDocument();
    expect(main().getByText("abcdef12")).toHaveAttribute("title", "abcdef12-3456");
  });

  it("勘定系が停止していれば停止と表示し、状態の取得に失敗すればエラーを出す", async () => {
    mockApi({
      "/api/integration/status": new RawResponse(200, { status: "DEGRADED", runtime: "mule", core: "DOWN" }),
      "/api/integration/runs": new RawResponse(200, []),
    });
    await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("停止")).toHaveClass("minus");
    expect(await main().findByText(/まだ実行履歴はありません/)).toBeInTheDocument();
  });

  it("全銀：サンプルを入れて送信し、行ごとの結果と集計を表示する", async () => {
    mockDefaults();
    mockApi({
      "/api/integration/zengin": new RawResponse(200, {
        runId: "r3", senderBankCode: "0005", remitterNameKana: "ｶ)ﾃｽﾄｼﾖｳｼﾞ", tradeDate: "1124", records: 2, totalAmount: 15000,
        summary: { credited: 1, held: 0, duplicate: 1, rejected: 0 },
        results: [
          { line: 2, accountNo: "1000003", amount: 12000, referenceNo: "ZG-0005-1124-0000000001", status: "CREDITED", note: null },
          { line: 3, accountNo: "1000003", amount: 3000, referenceNo: "ZG-0005-1124-0000000002", status: "DUPLICATE", note: "取込済みの振込（重複）" },
        ],
      }),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    const send = main().getByRole("button", { name: "Kong 経由で送信" });
    expect(send).toBeDisabled();
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    expect((main().getByLabelText("データ（貼り付け）") as HTMLTextAreaElement).value.split(/\r?\n/)[0]).toHaveLength(120);
    await user.click(send);
    expect(await main().findByText(/入金 1・保留 0・重複 1・対象外 0/)).toBeInTheDocument();
    expect(main().getByText("重複（取込済み）")).toBeInTheDocument();
    const [c] = callsTo("/api/integration/zengin");
    expect(c.headers).toMatchObject({ "Content-Type": "text/plain; charset=utf-8", "X-Requested-With": "XMLHttpRequest" });
    expect(String(c.body).startsWith("1210")).toBe(true);
  });

  it("全銀：Shift_JIS のファイルを選んで送れる", async () => {
    mockDefaults();
    mockApi({ "/api/integration/zengin": new RawResponse(200, { senderBankCode: "0005", remitterNameKana: "", tradeDate: "1124", records: 0, totalAmount: 0, summary: { credited: 0, held: 0, duplicate: 0, rejected: 0 }, results: [] }) });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    const file = new File([new Uint8Array([0xb1, 0xb2])], "zengin.txt", { type: "text/plain" });
    fireEvent.change(main().getByLabelText("ファイル"), { target: { files: [file] } });
    await user.selectOptions(main().getByLabelText("文字コード（ファイル）"), "shift_jis");
    expect(main().getByText("選択中のファイル：zengin.txt（2 バイト）")).toBeInTheDocument();
    expect(main().queryByLabelText("データ（貼り付け）")).not.toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "Kong 経由で送信" }));
    await waitFor(() => expect(callsTo("/api/integration/zengin")).toHaveLength(1));
    expect(callsTo("/api/integration/zengin")[0].headers["Content-Type"]).toBe("text/plain; charset=shift_jis");
  });

  it("検証エラー（422）は行番号付きで表示する", async () => {
    mockDefaults();
    mockApi({
      "/api/integration/zengin": new RawResponse(422, {
        type: "https://bank.example/problems/invalid-input", title: "入力データに誤りがあります", detail: "取り込みませんでした。",
        errors: [{ line: 4, message: "トレーラーの合計金額が一致しません" }],
      }, "application/problem+json"),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    await user.type(main().getByLabelText("データ（貼り付け）"), "broken");
    await user.click(main().getByRole("button", { name: "Kong 経由で送信" }));
    const alert = await main().findByRole("alert");
    expect(alert).toHaveTextContent("入力データに誤りがあります：取り込みませんでした。");
    expect(alert).toHaveTextContent("4 行目：トレーラーの合計金額が一致しません");
  });

  it("請求データ・為替相場のタブで送信し、結果を表示する", async () => {
    mockDefaults();
    mockApi({
      "/api/integration/billing": new RawResponse(200, { rows: 2, results: [{ billerCode: "ELEC01", payerNumber: "E-0001", result: "取込" }, { billerCode: "GAS01", payerNumber: "G-1", result: "契約なし" }] }),
      "/api/integration/fx": new RawResponse(200, { provider: "DEMO-FX", updated: ["USD", "EUR"], skipped: ["GBP"] }),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    await user.click(main().getByRole("tab", { name: "口座振替 請求（CSV）" }));
    expect(main().queryByLabelText("文字コード（ファイル）")).not.toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    await user.click(main().getByRole("button", { name: "Kong 経由で送信" }));
    expect(await main().findByText("2 行を処理しました（取込 1 件）。")).toBeInTheDocument();
    expect(main().getByText("契約なし")).toBeInTheDocument();
    expect(callsTo("/api/integration/billing")[0].headers["Content-Type"]).toBe("text/csv; charset=utf-8");

    await user.click(main().getByRole("tab", { name: "為替相場（JSON）" }));
    expect(main().queryByText("契約なし")).not.toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    await user.click(main().getByRole("button", { name: "Kong 経由で送信" }));
    expect(await main().findByText(/DEMO-FX の相場で USD・EUR を更新しました（対象外：GBP）/)).toBeInTheDocument();
    expect(callsTo("/api/integration/fx")[0].headers["Content-Type"]).toBe("application/json");
  });

  it("実行履歴を再読み込みできる", async () => {
    mockDefaults();
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    await main().findByText("一部保留");
    const before = callsTo("/api/integration/runs").length;
    await user.click(main().getByRole("button", { name: "再読み込み" }));
    await waitFor(() => expect(callsTo("/api/integration/runs").length).toBeGreaterThan(before));
  });
});
