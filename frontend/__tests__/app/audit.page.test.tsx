// app/audit/page.tsx のテスト：ハッシュチェーン検証、元帳の整合性検証、監査ログの検索とページ送り。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AuditPage from "@/app/audit/page";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";
import { main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function auditPage(r: MockRequest) {
  const p = Number(r.query.get("page"));
  return {
    items: [{ auditId: p * 100, occurredAt: "2026-11-23T10:00:00.123", userId: "teller01", userRole: "TELLER", action: "transfer", target: "1000001", outcome: "SUCCESS", detail: null, clientIp: "10.0.0.1", prevHash: "", rowHash: "" }],
    page: p, size: 50, total: 80, hasNext: p === 1,
  };
}

describe("AuditPage", () => {
  it("役席以外には表示しない", async () => {
    await renderPage(<AuditPage />, { user: tellerUser, path: "/audit" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("監査ログを表示し、条件を確定して検索する", async () => {
    mockApi({ "/audit-log": auditPage });
    const { user } = await renderPage(<AuditPage />, { user: supervisorUser, path: "/audit" });
    expect(await main().findByText("（窓口担当）")).toBeInTheDocument();
    expect(main().getByText("2026-11-23 10:00:00")).toBeInTheDocument();
    await user.type(main().getByLabelText("利用者ID"), "teller01");
    await user.selectOptions(main().getByLabelText("結果"), "DENIED");
    await user.click(main().getByRole("button", { name: "検索" }));
    await waitFor(() => expect(callsTo("/audit-log").some((c) => c.query.get("outcome") === "DENIED")).toBe(true));
    const q = callsTo("/audit-log").find((c) => c.query.get("outcome") === "DENIED")!.query;
    expect(q.get("userId")).toBe("teller01");
    expect(q.get("page")).toBe("1");
  });

  it("次ページを先読みし、ページ送りできる", async () => {
    mockApi({ "/audit-log": auditPage });
    const { user } = await renderPage(<AuditPage />, { user: supervisorUser, path: "/audit" });
    await main().findByText("全 80 件 ／ 1 ページ");
    await waitFor(() => expect(callsTo("/audit-log").some((c) => c.query.get("page") === "2")).toBe(true));
    await user.click(main().getByRole("button", { name: "次へ" }));
    expect(await main().findByText("200")).toBeInTheDocument();
    expect(main().getByRole("button", { name: "次へ" })).toBeDisabled();
  });

  it("ハッシュチェーン検証：正常と改ざん検知を表示する", async () => {
    mockApi({ "/audit-log": auditPage, "/audit-verify": { valid: true, checked: 1234, brokenAt: null, reason: null } });
    const { user } = await renderPage(<AuditPage />, { user: supervisorUser, path: "/audit" });
    await user.click(main().getByRole("button", { name: "ハッシュチェーンを検証" }));
    expect(await main().findByText("正常です（1,234 行を検証）。")).toBeInTheDocument();
    mockApi({ "/audit-verify": { valid: false, checked: 10, brokenAt: 7, reason: "hash mismatch" } });
    await user.click(main().getByRole("button", { name: "ハッシュチェーンを検証" }));
    expect(await main().findByText("改ざんの可能性があります：監査ID 7（hash mismatch）")).toBeInTheDocument();
  });

  it("元帳検証：口座番号を指定して実行し、不整合を一覧する", async () => {
    mockApi({
      "/audit-log": auditPage,
      "/ledger-verify": { checkedAt: "", accounts: 1, transactions: 5, valid: false, problems: [{ accountNo: "1000001", txId: 3, check: "BALANCE_CHAIN", detail: "残高が連続していません" }] },
    });
    const { user } = await renderPage(<AuditPage />, { user: supervisorUser, path: "/audit" });
    await user.type(main().getByLabelText("口座番号（空欄で全口座）"), "1000001");
    await user.click(main().getByRole("button", { name: "元帳を検証" }));
    expect(await main().findByText(/不整合を 1 件検出しました/)).toBeInTheDocument();
    expect(main().getByText("残高が連続していません")).toBeInTheDocument();
    expect(callsTo("/ledger-verify")[0].query.get("accountNo")).toBe("1000001");
  });

  it("元帳検証：全口座正常・エラー表示", async () => {
    mockApi({ "/audit-log": auditPage, "/ledger-verify": { checkedAt: "", accounts: 15, transactions: 300, valid: true, problems: [] } });
    const { user } = await renderPage(<AuditPage />, { user: supervisorUser, path: "/audit" });
    await user.click(main().getByRole("button", { name: "元帳を検証" }));
    expect(await main().findByText("正常です（15 口座・300 明細を検証）。")).toBeInTheDocument();
    expect(callsTo("/ledger-verify")[0].query.has("accountNo")).toBe(false);
    mockApi({ "/ledger-verify": new ApiFailure(500, "SYSTEM_ERROR", "検証に失敗しました。") });
    await user.click(main().getByRole("button", { name: "元帳を検証" }));
    expect(await main().findByText("検証に失敗しました。")).toBeInTheDocument();
  });
});
