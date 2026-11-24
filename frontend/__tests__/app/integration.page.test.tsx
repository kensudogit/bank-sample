// app/integration/page.tsx のテスト：役席のみ、稼働状況、承認依頼の作成（サンプル・ファイル・文字コード）、
// 二者承認（自分の依頼は承認できない・別の役席が承認して送信・却下・再送）と結果、エラー、実行履歴。
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import IntegrationPage from "@/app/integration/page";
import { resetCsrf } from "@/lib/integration";
import { RawResponse, callsTo, mockApi } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const runs = [
  { runId: "r2", kind: "ZENGIN_INCOMING", startedAt: "2026-11-24T01:02:03.000Z", durationMs: 1234, outcome: "PARTIAL", consumer: "bank-ops", operator: "supervisor2", approver: "supervisor1", requestId: "abcdef12-3456", summary: "0005 2 件：入金 1・保留 1・重複 0・対象外 0", attempts: 2 },
  { runId: "r1", kind: "FX_RATES", startedAt: "2026-11-24T00:00:00.000Z", durationMs: 20, outcome: "FAILURE", consumer: "fx-provider", operator: null, requestId: "99999999-0000", summary: "入力エラー 1 件" },
];
const req = (over: Record<string, unknown>) => ({
  requestId: "q1", kind: "FX_RATES", contentType: "application/json", payloadSha256: "a".repeat(64), payloadBytes: 120, fileName: "fx.json",
  commentText: null, status: "PENDING", maker: "supervisor2", createdAt: "2026-11-24T01:00:00", approver: null, decidedAt: null,
  rejectReason: null, attempts: 0, runId: null, resultStatus: null, ...over,
});

function mockDefaults(requests: unknown[] = []) {
  mockApi({
    "/api/integration/status": new RawResponse(200, { status: "UP", runtime: "mule-compat", core: "UP" }),
    "/api/integration/runs": new RawResponse(200, runs),
    "/api/integration/requests": (r: { method: string }) => r.method === "GET"
      ? new RawResponse(200, requests)
      : new RawResponse(201, req({ requestId: "new", maker: "supervisor1", payloadBytes: 300, payloadSha256: "b".repeat(64) })),
    "/api/integration/csrf": new RawResponse(200, { token: "csrf-1" }),
  });
}

beforeEach(() => resetCsrf());

describe("IntegrationPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<IntegrationPage />, { user: tellerUser, path: "/integration" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("稼働状況と実行履歴（作成者 → 承認者・再処理の回数）を表示する", async () => {
    mockDefaults();
    await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("ランタイム: mule-compat")).toBeInTheDocument();
    expect(main().getAllByText("稼働中")).toHaveLength(3);
    expect(await main().findByText("一部保留")).toHaveClass("badge-PENDING");
    expect(main().getByText("失敗")).toHaveClass("badge-FAILURE");
    expect(main().getByText("（supervisor2 → 承認 supervisor1）")).toBeInTheDocument();
    expect(main().getByText("・2 回目")).toBeInTheDocument();
    expect(main().getByText("1,234 ms")).toBeInTheDocument();
    expect(main().getByText("abcdef12")).toHaveAttribute("title", "abcdef12-3456");
    expect(await main().findByText("承認依頼はありません。")).toBeInTheDocument();
  });

  it("勘定系が停止していれば停止と表示する", async () => {
    mockDefaults();
    mockApi({
      "/api/integration/status": new RawResponse(200, { status: "DEGRADED", runtime: "mule", core: "DOWN" }),
      "/api/integration/runs": new RawResponse(200, []),
    });
    await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("停止")).toHaveClass("minus");
    expect(await main().findByText(/まだ実行履歴はありません/)).toBeInTheDocument();
  });

  it("全銀：サンプルを入れて承認依頼を作成する（この時点では送信しない）", async () => {
    mockDefaults();
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    const create = main().getByRole("button", { name: "承認依頼を作成" });
    expect(create).toBeDisabled();
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    expect((main().getByLabelText("データ（貼り付け）") as HTMLTextAreaElement).value.split(/\r?\n/)[0]).toHaveLength(120);
    await user.type(main().getByLabelText("依頼のコメント（任意）"), "午前分");
    await user.click(create);
    expect(await main().findByText(/承認依頼を作成しました（300 バイト・SHA-256 bbbbbbbbbbbb…）/)).toBeInTheDocument();
    const c = callsTo("/api/integration/requests").find((x) => x.method === "POST")!;
    expect(c.query.get("kind")).toBe("zengin");
    expect(c.headers).toMatchObject({
      "Content-Type": "text/plain; charset=utf-8", "X-Requested-With": "XMLHttpRequest", "X-CSRF-Token": "csrf-1", "X-Comment": encodeURIComponent("午前分"),
    });
    expect(String(c.body).startsWith("1210")).toBe(true);
  });

  it("全銀：Shift_JIS のファイルを選んで依頼を作れる（ファイル名も送る）", async () => {
    mockDefaults();
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    const file = new File([new Uint8Array([0xb1, 0xb2])], "zengin.txt", { type: "text/plain" });
    fireEvent.change(main().getByLabelText("ファイル"), { target: { files: [file] } });
    await user.selectOptions(main().getByLabelText("文字コード（ファイル）"), "shift_jis");
    expect(main().getByText("選択中のファイル：zengin.txt（2 バイト）")).toBeInTheDocument();
    expect(main().queryByLabelText("データ（貼り付け）")).not.toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "承認依頼を作成" }));
    await waitFor(() => expect(callsTo("/api/integration/requests").filter((x) => x.method === "POST")).toHaveLength(1));
    const c = callsTo("/api/integration/requests").find((x) => x.method === "POST")!;
    expect(c.headers).toMatchObject({ "Content-Type": "text/plain; charset=shift_jis", "X-File-Name": "zengin.txt" });
  });

  it("自分が作成した依頼は承認できず、「別の役席の承認待ち」と表示する", async () => {
    mockDefaults([req({ maker: supervisorUser.userId })]);
    await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("別の役席の承認待ち")).toBeInTheDocument();
    expect(main().queryByRole("button", { name: "承認して送信" })).not.toBeInTheDocument();
  });

  it("別の役席の依頼を承認すると送信され、取込結果を表示する", async () => {
    mockDefaults([req({})]);
    mockApi({
      "/api/integration/requests/q1/approve": new RawResponse(200, {
        httpStatus: 200, kind: "fx", request: req({ status: "SENT" }), result: { provider: "DEMO-FX", updated: ["USD", "EUR"], skipped: ["GBP"] },
      }),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("承認待ち")).toHaveClass("badge-PENDING");
    await user.click(main().getByRole("button", { name: "承認して送信" }));
    expect(await main().findByText(/DEMO-FX の相場で USD・EUR を更新しました（対象外：GBP）/)).toBeInTheDocument();
    expect(callsTo("/api/integration/requests/q1/approve")[0].headers).toMatchObject({ "X-CSRF-Token": "csrf-1", "X-Requested-With": "XMLHttpRequest" });
  });

  it("全銀の承認結果は行ごとに表示し、連携の 422 は行番号付きで表示する", async () => {
    mockDefaults([req({ kind: "ZENGIN_INCOMING" }), req({ requestId: "q2", kind: "ZENGIN_INCOMING" })]);
    mockApi({
      "/api/integration/requests/q1/approve": new RawResponse(200, {
        httpStatus: 200, kind: "zengin", request: null, result: {
          runId: "r3", senderBankCode: "0005", remitterNameKana: "ｶ)ﾃｽﾄｼﾖｳｼﾞ", tradeDate: "1124", records: 2, totalAmount: 15000,
          summary: { credited: 1, held: 0, duplicate: 1, rejected: 0 },
          results: [
            { line: 2, accountNo: "1000003", amount: 12000, referenceNo: "ZG-0005-1124-0000000001", status: "CREDITED", note: null },
            { line: 3, accountNo: "1000003", amount: 3000, referenceNo: "ZG-0005-1124-0000000002", status: "DUPLICATE", note: "取込済みの振込（重複）" },
          ],
        },
      }),
      "/api/integration/requests/q2/approve": new RawResponse(200, {
        httpStatus: 422, kind: "zengin", request: null,
        result: { title: "入力データに誤りがあります", detail: "取り込みませんでした。", errors: [{ line: 4, message: "トレーラーの合計金額が一致しません" }] },
      }),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    const buttons = await main().findAllByRole("button", { name: "承認して送信" });
    await user.click(buttons[0]);
    expect(await main().findByText(/入金 1・保留 0・重複 1・対象外 0/)).toBeInTheDocument();
    expect(main().getByText("重複（取込済み）")).toBeInTheDocument();
    await user.click((await main().findAllByRole("button", { name: "承認して送信" }))[1]);
    const alert = await main().findByText(/連携が受け付けませんでした（HTTP 422）/);
    expect(alert).toHaveTextContent("入力データに誤りがあります：取り込みませんでした。");
    expect(within(alert).getByText("4 行目：トレーラーの合計金額が一致しません")).toBeInTheDocument();
  });

  it("送信失敗は再送でき、却下は理由を付けて行う", async () => {
    mockDefaults([req({ status: "FAILED", approver: "supervisor3", attempts: 1 }), req({ requestId: "q2" })]);
    mockApi({
      "/api/integration/requests/q1/retry": new RawResponse(200, { httpStatus: 502, kind: "fx", request: null, result: { title: "勘定系システムに接続できません" } }),
      "/api/integration/requests/q2/reject": new RawResponse(200, req({ requestId: "q2", status: "REJECTED" })),
    });
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    expect(await main().findByText("送信失敗")).toHaveClass("badge-FAILURE");
    await user.click(main().getByRole("button", { name: "再送" }));
    expect(await main().findByText(/HTTP 502.*再送/)).toBeInTheDocument();
    vi.spyOn(window, "prompt").mockReturnValue("金額の誤り");
    await user.click(main().getByRole("button", { name: "却下" }));
    expect(await main().findByText("依頼を却下しました。")).toBeInTheDocument();
    expect(callsTo("/api/integration/requests/q2/reject")[0].body).toEqual({ reason: "金額の誤り" });
  });

  it("請求データ・為替相場のタブを切り替えて依頼を作れる", async () => {
    mockDefaults();
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    await user.click(main().getByRole("tab", { name: "口座振替 請求（CSV）" }));
    expect(main().queryByLabelText("文字コード（ファイル）")).not.toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    await user.click(main().getByRole("button", { name: "承認依頼を作成" }));
    await waitFor(() => expect(callsTo("/api/integration/requests").filter((x) => x.method === "POST")).toHaveLength(1));
    expect(callsTo("/api/integration/requests").find((x) => x.method === "POST")!.headers["Content-Type"]).toBe("text/csv; charset=utf-8");
    await user.click(main().getByRole("tab", { name: "為替相場（JSON）" }));
    await user.click(main().getByRole("button", { name: "サンプルを入れる" }));
    await user.click(main().getByRole("button", { name: "承認依頼を作成" }));
    await waitFor(() => expect(callsTo("/api/integration/requests").filter((x) => x.method === "POST")).toHaveLength(2));
    expect(callsTo("/api/integration/requests").filter((x) => x.method === "POST")[1].headers["Content-Type"]).toBe("application/json");
  });

  it("実行履歴を再読み込みできる", async () => {
    mockDefaults();
    const { user } = await renderPage(<IntegrationPage />, { user: supervisorUser, path: "/integration" });
    await main().findByText("一部保留");
    const before = callsTo("/api/integration/runs").length;
    const reloads = main().getAllByRole("button", { name: "再読み込み" });
    await user.click(reloads[reloads.length - 1]);
    await waitFor(() => expect(callsTo("/api/integration/runs").length).toBeGreaterThan(before));
  });
});
