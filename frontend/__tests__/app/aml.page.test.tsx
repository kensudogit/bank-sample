// app/aml/page.tsx のテスト：AML アラートの状態別一覧、調査開始・問題なし・届出、今すぐ検査。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AmlPage from "@/app/aml/page";
import { ApiFailure, callsTo, lastBody, mockApi, type MockRequest } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const alerts = [
  { alertId: 1, ruleCode: "LARGE_CASH", status: "OPEN", score: 80, customerId: "CU0001", customerName: "山田 太郎", accountNo: "1000001", createdAt: "2026-11-22T10:00:00", detail: "現金 300 万円の入金" },
  { alertId: 2, ruleCode: "STRUCTURING", status: "INVESTIGATING", score: 40, customerId: null, accountNo: null, createdAt: "2026-11-21T10:00:00", detail: "分割入金", resolutionNote: "確認中", assignedTo: "super01" },
];

describe("AmlPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<AmlPage />, { user: tellerUser, path: "/aml" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("未対応タブを既定で表示し、ルール名・スコア・リンクを出す", async () => {
    mockApi({ "/aml-alerts": alerts });
    await renderPage(<AmlPage />, { user: supervisorUser, path: "/aml" });
    expect(await main().findByRole("heading", { name: /大口現金取引/ })).toBeInTheDocument();
    expect(callsTo("/aml-alerts")[0].query.get("status")).toBe("OPEN");
    expect(main().getByText("80")).toHaveClass("minus");
    expect(main().getByRole("link", { name: "山田 太郎" })).toHaveAttribute("href", "/customers/CU0001");
    expect(main().getByRole("link", { name: "1000001" })).toHaveAttribute("href", "/accounts/1000001");
    expect(main().getByText("対応: 確認中（super01）")).toBeInTheDocument();
    // 調査中のアラートには「調査開始」を出さない
    expect(main().getAllByRole("button", { name: "調査開始" })).toHaveLength(1);
  });

  it("「すべて」タブでは状態を指定せずに取得する", async () => {
    mockApi({ "/aml-alerts": [] });
    const { user } = await renderPage(<AmlPage />, { user: supervisorUser, path: "/aml" });
    await user.click(main().getByRole("tab", { name: "すべて" }));
    expect(await main().findByText("該当するアラートはありません。")).toBeInTheDocument();
    await waitFor(() => expect(callsTo("/aml-alerts").some((c) => !c.query.has("status"))).toBe(true));
  });

  it("調査結果を入力して届出・問題なし・調査開始を送る", async () => {
    mockApi({ "/aml-alerts": alerts, "/aml-alert-update": {} });
    const { user } = await renderPage(<AmlPage />, { user: supervisorUser, path: "/aml" });
    const inputs = await main().findAllByLabelText("調査結果・届出内容");
    await user.type(inputs[0], "届出済み");
    await user.click(main().getAllByRole("button", { name: "疑わしい取引の届出" })[0]);
    expect(await main().findByText("届出済にしました")).toBeInTheDocument();
    expect(lastBody("/aml-alert-update")).toEqual({ alertId: "1", status: "REPORTED", note: "届出済み" });
    await user.click(main().getAllByRole("button", { name: "問題なし" })[1]);
    expect(await main().findByText("問題なしで完了しました")).toBeInTheDocument();
    expect(lastBody("/aml-alert-update")).toMatchObject({ alertId: "2", status: "CLOSED" });
    await user.click(main().getByRole("button", { name: "調査開始" }));
    expect(await main().findByText("調査を開始しました")).toBeInTheDocument();
  });

  it("今すぐ検査の結果件数を表示し、失敗はエラーにする", async () => {
    mockApi({ "/aml-alerts": [], "/aml-scan": { scanned: 120, alerts: 3 } });
    const { user } = await renderPage(<AmlPage />, { user: supervisorUser, path: "/aml" });
    await user.click(main().getByRole("button", { name: "今すぐ検査" }));
    expect(await main().findByText("検査しました: 120 件の取引、新しいアラート 3 件")).toBeInTheDocument();
    mockApi({ "/aml-scan": (_r: MockRequest) => new ApiFailure(503, "BUSY", "検査中です。") });
    await user.click(main().getByRole("button", { name: "今すぐ検査" }));
    expect(await main().findByRole("alert")).toHaveTextContent("検査中です。");
  });
});
