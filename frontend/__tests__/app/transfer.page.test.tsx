// app/transfer/page.tsx のテスト：入力 → 確認 → 完了の流れ、入力チェック、振替の口座候補、冪等キー。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import TransferPage from "@/app/transfer/page";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { account, accountPage, customerUser, main, renderPage, tellerUser } from "../../test/render";

const accounts = accountPage([
  account({ accountNo: "1000001", balance: 500000 }),
  account({ accountNo: "1000002", accountType: "SAVINGS", balance: 100000 }),
  account({ accountNo: "1000003", customerId: "CU0002", customerName: "佐藤 花子" }),
  account({ accountNo: "1000004", status: "FROZEN" }),
]);
const done = { operation: "TRANSFER", accountNo: "1000001", counterpartyAccountNo: "1000003", amount: 5000, balanceAfter: 495000, executedAt: "2026-11-23T10:00:00" };

describe("TransferPage", () => {
  it("お客さま：振込元は利用可能な本人口座、確認画面を経て振り込む", async () => {
    mockApi({ "/accounts": accounts, "/transfer": done });
    const { user } = await renderPage(<TransferPage />, { user: customerUser, path: "/transfer" });
    const from = await main().findByRole("combobox", { name: "振込元口座" });
    await waitFor(() => expect(from).toHaveValue("1000001"));
    expect(Array.from((from as HTMLSelectElement).options).map((o) => o.value)).toEqual(["1000001", "1000002", "1000003"]);
    await user.type(main().getByLabelText(/^振込先口座番号/), "１０００００３");
    await user.type(main().getByLabelText("金額（円）"), "5,000");
    await user.type(main().getByLabelText("メモ（任意）"), "会費");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    expect(main().getByRole("heading", { name: "振込内容の確認" })).toBeInTheDocument();
    expect(main().getByText("1000003 佐藤 花子")).toBeInTheDocument();
    expect(callsTo("/transfer")).toHaveLength(0);
    await user.click(main().getByRole("button", { name: "振込を実行する" }));
    expect(await main().findByText("振込が完了しました。")).toBeInTheDocument();
    expect(lastBody("/transfer")).toMatchObject({ fromAccountNo: "1000001", toAccountNo: "1000003", amount: "5000", memo: "会費", ownAccountsOnly: false });
    expect(main().getByRole("link", { name: "振込元の明細を見る" })).toHaveAttribute("href", "/accounts/1000001");
  });

  it("続けて振込むと新しい冪等キーで入力画面に戻る", async () => {
    mockApi({ "/accounts": accounts, "/transfer": done });
    const { user } = await renderPage(<TransferPage />, { user: customerUser, path: "/transfer" });
    await user.type(await main().findByLabelText(/^振込先口座番号/), "1000003");
    await user.type(main().getByLabelText("金額（円）"), "5000");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    await user.click(main().getByRole("button", { name: "振込を実行する" }));
    const firstKey = lastBody("/transfer").idempotencyKey;
    await user.click(await main().findByRole("button", { name: "続けて振込" }));
    expect(main().getByLabelText(/^振込先口座番号/)).toHaveValue("");
    await user.type(main().getByLabelText(/^振込先口座番号/), "1000003");
    await user.type(main().getByLabelText("金額（円）"), "1");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    await user.click(main().getByRole("button", { name: "振込を実行する" }));
    await main().findByText("振込が完了しました。");
    expect(lastBody("/transfer").idempotencyKey).not.toBe(firstKey);
  });

  it("お客さま：振替は同じ名義の口座だけを候補にする", async () => {
    mockApi({ "/accounts": accounts, "/transfer": { ...done, counterpartyAccountNo: "1000002" } });
    const { user } = await renderPage(<TransferPage />, { user: customerUser, path: "/transfer" });
    await user.click(await main().findByRole("tab", { name: "振替（ご本人の口座間）" }));
    const to = main().getByRole("combobox", { name: "振替先口座" });
    expect(Array.from((to as HTMLSelectElement).options).map((o) => o.value)).toEqual(["", "1000002"]);
    await user.selectOptions(to, "1000002");
    await user.type(main().getByLabelText("金額（円）"), "1000");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    await user.click(main().getByRole("button", { name: "振替を実行する" }));
    await main().findByText("振替が完了しました。");
    expect(lastBody("/transfer").ownAccountsOnly).toBe(true);
  });

  it.each([
    ["123", "1000003", "100", "振込元口座番号は7桁の数字で入力してください。"],
    ["1000001", "12", "100", "振込先口座番号は7桁の数字で入力してください。"],
    ["1000001", "1000001", "100", "振込元と振込先に同じ口座は指定できません。"],
    ["1000001", "1000003", "0", "金額は1円以上の整数で入力してください。"],
  ])("行員：入力チェック（元 %s・先 %s・金額 %s）", async (from, to, amount, message) => {
    mockApi({ "/accounts": accounts });
    const { user } = await renderPage(<TransferPage />, { user: tellerUser, path: "/transfer" });
    await user.type(await main().findByLabelText("振込元口座"), from);
    await user.type(main().getByLabelText(/^振込先口座番号/), to);
    await user.type(main().getByLabelText("金額（円）"), amount);
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    expect(main().getByRole("alert")).toHaveTextContent(message);
    expect(main().queryByRole("heading", { name: "振込内容の確認" })).not.toBeInTheDocument();
  });

  it("確認画面から修正に戻れ、失敗したらエラーを出して入力画面に戻る", async () => {
    mockApi({ "/accounts": accounts, "/transfer": new ApiFailure(409, "INSUFFICIENT_FUNDS", "残高が不足しています。") });
    const { user } = await renderPage(<TransferPage />, { user: tellerUser, path: "/transfer" });
    await user.type(await main().findByLabelText("振込元口座"), "1000001");
    await user.type(main().getByLabelText(/^振込先口座番号/), "1000003");
    await user.type(main().getByLabelText("金額（円）"), "99999999");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    await user.click(main().getByRole("button", { name: "修正する" }));
    expect(main().getByLabelText("金額（円）")).toHaveValue("99999999");
    await user.click(main().getByRole("button", { name: "確認へ進む" }));
    await user.click(main().getByRole("button", { name: "振込を実行する" }));
    expect(await main().findByRole("alert")).toHaveTextContent("残高が不足しています。");
    expect(main().getByRole("button", { name: "確認へ進む" })).toBeInTheDocument();
  });

  it("口座一覧の取得エラーを表示する", async () => {
    mockApi({ "/accounts": new ApiFailure(500, "SYSTEM_ERROR", "障害が発生しました。") });
    await renderPage(<TransferPage />, { user: customerUser, path: "/transfer" });
    expect(await main().findByRole("alert")).toHaveTextContent("障害が発生しました。");
  });
});
