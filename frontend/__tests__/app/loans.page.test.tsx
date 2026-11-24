// app/loans/page.tsx のテスト：融資一覧・申込一覧・新規申込（商品ごとの入力項目、顧客と行員の違い）。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import LoansPage from "@/app/loans/page";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { nav } from "../../test/nav";
import { customerUser, main, renderPage, tellerUser } from "../../test/render";

const loans = [
  { loanId: 5, customerName: "山田 太郎", customerId: "CU0001", product: "HOUSING", outstanding: 30000000, creditLimit: null, annualRate: 1.2, status: "DELINQUENT", daysPastDue: 40, borrowerClass: "WATCH", maturityDate: "2060-11-27" },
  { loanId: 6, customerName: "株式会社テスト", customerId: "CU0100", product: "OVERDRAFT", outstanding: 0, creditLimit: 5000000, annualRate: 3, status: "ACTIVE", daysPastDue: 0, borrowerClass: "NORMAL", maturityDate: "2027-03-31" },
];
const apps = [
  { applicationId: 11, appliedAt: "2026-11-20T10:00:00", customerName: "山田 太郎", customerId: "CU0001", product: "CARD", requestedAmount: 500000, termMonths: 0, status: "SCREENED", grade: "B", score: 72, recommendation: "REVIEW" },
];

describe("LoansPage", () => {
  it("融資一覧：延滞日数・極度額・債務者区分を表示する", async () => {
    mockApi({ "/loans": loans });
    await renderPage(<LoansPage />, { user: tellerUser, path: "/loans" });
    expect(await main().findByRole("link", { name: "5" })).toHaveAttribute("href", "/loans/5");
    expect(main().getByText("40日延滞", { exact: false })).toHaveClass("minus");
    expect(main().getByText("要注意先")).toBeInTheDocument();
    expect(main().getByText("延滞")).toHaveClass("badge-FAILURE");
    expect(main().getByRole("link", { name: "返済シミュレーション" })).toHaveAttribute("href", "/loans/simulate");
  });

  it("融資一覧が空・エラーのとき", async () => {
    mockApi({ "/loans": [] });
    await renderPage(<LoansPage />, { user: tellerUser, path: "/loans" });
    expect(await main().findByText("融資はありません。")).toBeInTheDocument();
  });

  it("申込一覧：格付・スコア・推奨判定を表示する", async () => {
    mockApi({ "/loans": [], "/loan-applications": apps });
    const { user } = await renderPage(<LoansPage />, { user: tellerUser, path: "/loans" });
    await user.click(main().getByRole("tab", { name: "申込一覧" }));
    expect(await main().findByRole("link", { name: "11" })).toHaveAttribute("href", "/loans/applications/11");
    expect(main().getByText("(72)")).toBeInTheDocument();
    expect(main().getByText("要検討")).toBeInTheDocument();
  });

  it("お客さま：法人向け商品は選べず、カードローンは期間・返済方法を出さない。申込後は申込一覧へ", async () => {
    mockApi({ "/loans": [], "/loan-applications": [], "/loan-apply": { applicationId: 12 } });
    const { user } = await renderPage(<LoansPage />, { user: customerUser, path: "/loans" });
    await user.click(main().getByRole("tab", { name: "新規申込" }));
    const product = main().getByLabelText(/^商品/) as HTMLSelectElement;
    expect(Array.from(product.options).map((o) => o.value)).toEqual(["HOUSING", "CARD"]);
    expect(product.value).toBe("CARD");
    expect(main().queryByLabelText("期間（月）")).not.toBeInTheDocument();
    expect(main().queryByLabelText("顧客番号")).not.toBeInTheDocument();
    await user.type(main().getByLabelText("ご利用限度額（円）"), "５００，０００");
    await user.type(main().getByLabelText("年収（税込・円）"), "6,000,000");
    await user.click(main().getByRole("button", { name: "申し込む" }));
    await waitFor(() => expect(main().getByRole("tab", { name: "申込一覧" })).toHaveAttribute("aria-selected", "true"));
    expect(lastBody("/loan-apply")).toMatchObject({ customerId: "CU0001", product: "CARD", amount: "500000", annualIncome: "6000000" });
    expect(nav.router.push).not.toHaveBeenCalled();
  });

  it("行員：顧客番号を指定し、シンジケートローンは担保・組成総額を入力して申込詳細へ移動する", async () => {
    mockApi({ "/loans": [], "/loan-apply": { applicationId: 13 } });
    const { user } = await renderPage(<LoansPage />, { user: tellerUser, path: "/loans" });
    await user.click(main().getByRole("tab", { name: "新規申込" }));
    await user.type(main().getByLabelText("顧客番号"), "CU0100");
    await user.selectOptions(main().getByLabelText(/^商品/), "SYNDICATED");
    expect(main().getByLabelText("年間営業キャッシュフロー（円）")).toBeInTheDocument();
    await user.type(main().getByLabelText("借入額（円）"), "100000000");
    await user.clear(main().getByLabelText("期間（月）"));
    await user.type(main().getByLabelText("期間（月）"), "60");
    await user.selectOptions(main().getByLabelText("返済方法"), "EQUAL_PRINCIPAL");
    await user.type(main().getByLabelText("年間営業キャッシュフロー（円）"), "50000000");
    await user.type(main().getByLabelText("担保評価額（円）"), "80000000");
    await user.type(main().getByLabelText(/^組成総額/), "500000000");
    await user.click(main().getByRole("button", { name: "申し込む" }));
    await waitFor(() => expect(nav.router.push).toHaveBeenCalledWith("/loans/applications/13"));
    expect(lastBody("/loan-apply")).toMatchObject({ customerId: "CU0100", product: "SYNDICATED", termMonths: "60", repaymentMethod: "EQUAL_PRINCIPAL", syndicateTotal: "500000000" });
  });

  it("申込エラーを表示する", async () => {
    mockApi({ "/loans": [], "/loan-apply": new ApiFailure(422, "TOTAL_LIMIT", "総量規制を超えています。") });
    const { user } = await renderPage(<LoansPage />, { user: customerUser, path: "/loans" });
    await user.click(main().getByRole("tab", { name: "新規申込" }));
    await user.type(main().getByLabelText("ご利用限度額（円）"), "5000000");
    await user.type(main().getByLabelText("年収（税込・円）"), "3000000");
    await user.click(main().getByRole("button", { name: "申し込む" }));
    expect(await main().findByRole("alert")).toHaveTextContent("総量規制を超えています。");
  });
});
