// app/accounts/[accountNo]/page.tsx のテスト：口座概要、役割・状態ごとのパネル出し分け、窓口の入出金。
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import AccountDetailPage from "@/app/accounts/[accountNo]/page";
import type { Account } from "@/lib/types";
import type { LoginUser } from "@/lib/types";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { account, customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const emptyTx = { items: [], page: 1, size: 20, total: 0, hasNext: false, openingBalance: 0, closingBalance: 0, totalIn: 0, totalOut: 0 };
const interest = {
  accountNo: "1000001", accountType: "ORDINARY", periodFrom: "2026-09-01", periodTo: "2026-11-22", days: 83, balanceDays: 83,
  averageBalance: 0, grossInterest: 0, nationalTax: 0, localTax: 0, tax: 0, netInterest: 0, settled: false, note: null,
};

async function open(acc: Account, user: LoginUser) {
  mockApi({
    "/account": { account: acc, transactions: [] },
    "/transactions": emptyTx,
    "/interest-preview": interest,
    "/customer": { customerId: acc.customerId, accounts: [acc] },
  });
  return renderPage(<AccountDetailPage />, { user, path: `/accounts/${acc.accountNo}`, params: { accountNo: acc.accountNo } });
}

describe("AccountDetailPage", () => {
  it("お客さま：概要・残高推移・利息・取引履歴を表示し、行員向けパネルは出さない", async () => {
    await open(account(), customerUser);
    expect(await main().findByText(/普通預金 ・ No\. 1000001/)).toBeInTheDocument();
    expect(main().getByRole("link", { name: "ご利用口座" })).toHaveAttribute("href", "/");
    expect(main().getByRole("link", { name: "残高証明書" })).toHaveAttribute("href", "/accounts/1000001/certificate");
    expect(main().getByRole("heading", { name: "残高推移" })).toBeInTheDocument();
    expect(main().getByRole("heading", { name: "利息・税金（見込み）" })).toBeInTheDocument();
    expect(main().getByRole("heading", { name: "取引履歴" })).toBeInTheDocument();
    expect(main().queryByRole("tab", { name: "入金" })).not.toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "取引の追跡" })).not.toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "口座解約" })).not.toBeInTheDocument();
  });

  it("行員：名義を顧客画面へリンクし、窓口入出金と取引の追跡を出す", async () => {
    await open(account(), tellerUser);
    expect(await main().findByRole("link", { name: "山田 太郎" })).toHaveAttribute("href", "/customers/CU0001");
    expect(main().getByRole("tab", { name: "入金" })).toBeInTheDocument();
    expect(main().getByRole("heading", { name: "取引の追跡" })).toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "口座解約" })).not.toBeInTheDocument();
  });

  it("行員：入金は金額を正規化して送り、結果を表示する", async () => {
    const { user } = await open(account(), tellerUser);
    mockApi({ "/deposit": { operation: "DEPOSIT", accountNo: "1000001", amount: 10000, balanceAfter: 1260000, executedAt: "2026-11-23T10:00:00", counterpartyAccountNo: null } });
    await user.type(await main().findByRole("textbox", { name: /金額（円）/ }), "１０，０００");
    await user.type(main().getByLabelText("メモ（任意）"), "窓口");
    await user.click(main().getByRole("button", { name: "入金する" }));
    expect(await main().findByText(/入金しました/)).toHaveTextContent("1,260,000");
    expect(lastBody("/deposit")).toMatchObject({ accountNo: "1000001", amount: "10000", memo: "窓口" });
    expect(main().getByRole("textbox", { name: /金額（円）/ })).toHaveValue("");
  });

  it("行員：出金タブに切り替えて出金し、エラーを表示する", async () => {
    const { user } = await open(account(), tellerUser);
    mockApi({ "/withdraw": new ApiFailure(409, "INSUFFICIENT_FUNDS", "残高が不足しています。") });
    await user.click(await main().findByRole("tab", { name: "出金" }));
    await user.type(main().getByRole("textbox", { name: /金額（円）/ }), "99999999");
    await user.click(main().getByRole("button", { name: "出金する" }));
    expect(await main().findByText("残高が不足しています。")).toBeInTheDocument();
  });

  it("役席：口座解約パネルを出す", async () => {
    await open(account(), supervisorUser);
    expect(await main().findByRole("heading", { name: "口座解約" })).toBeInTheDocument();
  });

  it("凍結口座：お取引できない旨を出し、入出金フォームを出さない", async () => {
    await open(account({ status: "FROZEN" }), tellerUser);
    expect(await main().findByText("この口座は凍結中のため、お取引できません。")).toBeInTheDocument();
    expect(main().queryByRole("tab", { name: "入金" })).not.toBeInTheDocument();
  });

  it("休眠口座：役席は休眠解除、それ以外は窓口案内", async () => {
    await open(account({ status: "DORMANT" }), supervisorUser);
    expect(await main().findByRole("button", { name: "本人確認済み・休眠を解除する" })).toBeInTheDocument();
  });

  it("休眠口座：お客さまには窓口への案内を出す", async () => {
    await open(account({ status: "DORMANT" }), customerUser);
    expect(await main().findByText(/お取引の再開は窓口でお申し出ください/)).toBeInTheDocument();
  });

  it("当座預金は利息見込みを出さず、当座パネルを出す", async () => {
    await open(account({ accountType: "CHECKING", overdraftLimit: 500000 }), customerUser);
    expect(await main().findByRole("heading", { name: "当座預金" })).toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "利息・税金（見込み）" })).not.toBeInTheDocument();
  });

  it("口座が見つからなければエラーと戻るリンクを出す", async () => {
    mockApi({ "/account": new ApiFailure(404, "ACCOUNT_NOT_FOUND", "口座が見つかりません。") });
    await renderPage(<AccountDetailPage />, { user: customerUser, path: "/accounts/9999999", params: { accountNo: "9999999" } });
    expect(await main().findByRole("alert")).toHaveTextContent("口座が見つかりません。");
    expect(main().getByRole("link", { name: "口座一覧へ戻る" })).toHaveAttribute("href", "/");
  });
});
