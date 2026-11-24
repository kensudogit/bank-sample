// app/customers/[customerId]/page.tsx のテスト：顧客情報、本人確認の判定、口座開設、ログイン発行、顧客 360°。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import CustomerDetailPage from "@/app/customers/[customerId]/page";
import type { LoginUser } from "@/lib/types";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { account, customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function customer(over: Record<string, unknown> = {}) {
  return {
    customerId: "CU0001", name: "山田 太郎", nameKana: "ヤマダ タロウ", birthDate: "1980-04-01", postalCode: "100-0001",
    address: "東京都千代田区1-1", phone: "0312345678", idDocType: "DRIVER_LICENSE", idDocLast4: "1234", kycStatus: "VERIFIED",
    kycDecidedAt: "2024-04-01T10:00:00", kycNote: "原本確認", createdAt: "2024-04-01T09:00:00", registeredBy: "teller01",
    customerType: "INDIVIDUAL", kycDecidedBy: "super01", accounts: [account()], ...over,
  };
}

const c360 = {
  segment: "LOYAL", totalAssets: 5_000_000, totalLoans: 300_000,
  amlAlerts: [{ alertId: 9, ruleCode: "LARGE_CASH", status: "OPEN" }],
  loans: [{ loanId: 5, product: "HOUSING", outstanding: 300000, status: "ACTIVE" }],
  applications: [{ applicationId: 7, product: "CARD", status: "SCREENED" }, { applicationId: 6, product: "CARD", status: "REJECTED" }],
  timeDeposits: [{ accountNo: "2000001", principal: 1000000, maturityDate: "2027-04-01", status: "ACTIVE" }],
  mandates: [{ mandateId: 1, billerName: "東京電力", status: "ACTIVE" }],
  notes: [{ noteId: 1, createdAt: "2026-11-01T10:00:00", noteType: "VISIT", content: "住宅ローンの相談", createdBy: "teller01" }],
};

async function open(user: LoginUser, cust = customer()) {
  mockApi({ "/customer": cust, "/customer-360": c360 });
  return renderPage(<CustomerDetailPage />, { user, path: `/customers/${cust.customerId}`, params: { customerId: String(cust.customerId) } });
}

describe("CustomerDetailPage", () => {
  it("お客さまには表示しない", async () => {
    await renderPage(<CustomerDetailPage />, { user: customerUser, path: "/customers/CU0001", params: { customerId: "CU0001" } });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("顧客情報と保有口座を表示する", async () => {
    await open(tellerUser);
    expect(await main().findByRole("heading", { name: /山田 太郎/ })).toBeInTheDocument();
    expect(main().getByText("〒100-0001 東京都千代田区1-1")).toBeInTheDocument();
    expect(main().getByText("運転免許証（下4桁 1234）")).toBeInTheDocument();
    expect(main().getByText("teller01 ／ super01")).toBeInTheDocument();
    expect(main().getByText("備考: 原本確認")).toBeInTheDocument();
    expect(main().getByRole("link", { name: "1000001" })).toHaveAttribute("href", "/accounts/1000001");
  });

  it("顧客 360°：資産・融資・定期・口座振替・AML・接触履歴を表示する", async () => {
    await open(tellerUser);
    expect(await main().findByText("LOYAL")).toBeInTheDocument();
    expect(main().getByText("1 件")).toHaveClass("minus");
    expect(main().getByRole("link", { name: "住宅ローン No.5" })).toHaveAttribute("href", "/loans/5");
    expect(main().getByRole("link", { name: "申込 No.7" })).toHaveAttribute("href", "/loans/applications/7");
    expect(main().queryByRole("link", { name: "申込 No.6" })).not.toBeInTheDocument();
    expect(main().getByText("東京電力（有効）")).toBeInTheDocument();
    expect(main().getByText("住宅ローンの相談")).toBeInTheDocument();
  });

  it("接触履歴を記録する", async () => {
    const { user } = await open(tellerUser);
    mockApi({ "/customer-note": [] });
    await user.selectOptions(await main().findByLabelText("種類"), "CALL");
    await user.type(main().getByLabelText("内容"), "電話で案内");
    await user.click(main().getByRole("button", { name: "記録" }));
    await waitFor(() => expect(lastBody("/customer-note")).toEqual({ customerId: "CU0001", noteType: "CALL", content: "電話で案内" }));
    expect(main().getByLabelText("内容")).toHaveValue("");
  });

  it("本人確認待ち：役席は確認済・謝絶を判定できる", async () => {
    const { user } = await open(supervisorUser, customer({ kycStatus: "PENDING", accounts: [] }));
    mockApi({ "/customer-kyc": {} });
    await user.type(await main().findByLabelText(/^備考/), " 免許証確認 ");
    await user.click(main().getByRole("button", { name: "確認済にする" }));
    await waitFor(() => expect(lastBody("/customer-kyc")).toEqual({ customerId: "CU0001", decision: "VERIFIED", note: "免許証確認" }));
    mockApi({ "/customer-kyc": new ApiFailure(400, "NOTE_REQUIRED", "謝絶の理由を入力してください。") });
    await user.clear(main().getByLabelText(/^備考/));
    await user.click(main().getByRole("button", { name: "謝絶する" }));
    expect(await main().findByText("謝絶の理由を入力してください。")).toBeInTheDocument();
    expect(main().getByText("口座はありません。")).toBeInTheDocument();
  });

  it("本人確認待ち：窓口担当には役席の判定待ちを案内する", async () => {
    await open(tellerUser, customer({ kycStatus: "PENDING" }));
    expect(await main().findByText(/役席者が書類を確認して判定します/)).toBeInTheDocument();
    expect(main().queryByRole("button", { name: "確認済にする" })).not.toBeInTheDocument();
  });

  it("謝絶済みなら口座を開設できない", async () => {
    await open(tellerUser, customer({ kycStatus: "REJECTED" }));
    expect(await main().findByText("本人確認が謝絶されているため、口座を開設できません。")).toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "口座開設" })).not.toBeInTheDocument();
  });

  it("口座開設：保有済みの種別と（個人の）当座は選べず、開設結果を表示する", async () => {
    const { user } = await open(tellerUser);
    mockApi({ "/account-open": account({ accountNo: "1000099", accountType: "SAVINGS", balance: 5000 }) });
    const select = await main().findByLabelText("口座種別");
    const options = Array.from((select as HTMLSelectElement).options).map((o) => o.value);
    expect(options).toEqual(["SAVINGS", "NOTICE", "FOREIGN"]);
    await user.type(main().getByLabelText(/^開設時入金額/), "５，０００");
    await user.click(main().getByRole("button", { name: "口座を開設する" }));
    expect(await main().findByRole("link", { name: "1000099" })).toHaveAttribute("href", "/accounts/1000099");
    expect(lastBody("/account-open")).toMatchObject({ customerId: "CU0001", accountType: "SAVINGS", initialDeposit: "5000" });
    expect(lastBody("/account-open").currency).toBeUndefined();
  });

  it("口座開設：外貨は通貨を選び、残高 0 で開設する（法人は当座も選べる）", async () => {
    const { user } = await open(tellerUser, customer({ customerType: "CORPORATE" }));
    mockApi({ "/account-open": account({ accountNo: "3000009", accountType: "FOREIGN", currency: "EUR", balance: 0 }) });
    const select = await main().findByLabelText("口座種別");
    expect(Array.from((select as HTMLSelectElement).options).map((o) => o.value)).toContain("CHECKING");
    await user.selectOptions(select, "FOREIGN");
    await user.selectOptions(main().getByLabelText(/^通貨/), "EUR");
    await user.type(main().getByLabelText(/^開設時入金額/), "1000");
    await user.click(main().getByRole("button", { name: "口座を開設する" }));
    await main().findByRole("link", { name: "3000009" });
    expect(lastBody("/account-open")).toMatchObject({ accountType: "FOREIGN", currency: "EUR", initialDeposit: "0" });
  });

  it("開設できる種別が無ければその旨を表示する", async () => {
    const accounts = ["ORDINARY", "SAVINGS", "NOTICE"].map((t, i) => account({ accountNo: `100000${i}`, accountType: t as never }))
      .concat([account({ accountNo: "3000001", accountType: "FOREIGN", currency: "USD" }), account({ accountNo: "3000002", accountType: "FOREIGN", currency: "EUR" })]);
    await open(tellerUser, customer({ accounts }));
    expect(await main().findByText(/すでにお持ちです/)).toBeInTheDocument();
  });

  it("ログイン発行：初期パスワードを一度だけ表示する", async () => {
    const { user } = await open(tellerUser);
    mockApi({ "/customer-login-issue": { userId: "c0001", initialPassword: "Init-Pass-123" } });
    await user.click(await main().findByRole("button", { name: "ログインを発行する" }));
    expect(await main().findByText("Init-Pass-123")).toHaveClass("secret");
    expect(main().queryByRole("button", { name: "ログインを発行する" })).not.toBeInTheDocument();
  });

  it("顧客が見つからなければエラーと戻るリンクを出す", async () => {
    mockApi({ "/customer": new ApiFailure(404, "CUSTOMER_NOT_FOUND", "顧客が見つかりません。") });
    await renderPage(<CustomerDetailPage />, { user: tellerUser, path: "/customers/CU9999", params: { customerId: "CU9999" } });
    expect(await main().findByRole("alert")).toHaveTextContent("顧客が見つかりません。");
    expect(main().getByRole("link", { name: "顧客一覧へ戻る" })).toHaveAttribute("href", "/customers");
  });
});
