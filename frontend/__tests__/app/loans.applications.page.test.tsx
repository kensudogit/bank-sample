// app/loans/applications/[id]/page.tsx のテスト：与信審査の結果、審査・承認/謝絶、担保・参加行の登録、契約と融資実行。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ApplicationPage from "@/app/loans/applications/[id]/page";
import type { LoginUser } from "@/lib/types";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function app(over: Record<string, unknown> = {}) {
  return {
    applicationId: 11, product: "HOUSING", requestedAmount: 30_000_000, customerName: "山田 太郎", customerId: "CU0001", status: "SCREENED",
    termMonths: 420, repaymentMethod: "EQUAL_PAYMENT", syndicateTotal: null, annualIncome: 8_000_000, existingDebtAnnual: 0,
    collateralValue: 40_000_000, score: 78, grade: "A", dti: 0.14, ltv: 0.75, proposedRate: 1.2, recommendation: "APPROVE",
    screeningNote: "問題なし", appliedBy: "cust01", appliedAt: "2026-11-20T10:00:00", screenedBy: "teller01", decidedBy: null, decisionNote: null,
    collaterals: [{ collateralId: 1, collateralType: "REAL_ESTATE", description: "東京都港区", appraisalValue: 40_000_000, lienRank: 1, guarantorName: null }],
    participants: [], loanId: null, ...over,
  };
}

async function open(user: LoginUser, a = app()) {
  mockApi({ "/loan-application": a });
  return renderPage(<ApplicationPage />, { user, path: `/loans/applications/${a.applicationId}`, params: { id: String(a.applicationId) } });
}

describe("ApplicationPage", () => {
  it("審査結果（スコア・格付・DTI・LTV・提示金利・推奨）を表示する", async () => {
    await open(customerUser);
    expect(await main().findByText("融資申込 No.11 ・ 住宅ローン")).toBeInTheDocument();
    expect(main().getByText("78 点 ／ A")).toBeInTheDocument();
    expect(main().getByText("14.0%")).toBeInTheDocument();
    expect(main().getByText("75.0%")).toBeInTheDocument();
    expect(main().getByText("1.200%")).toBeInTheDocument();
    expect(main().getByText("承認")).toHaveClass("plus");
    expect(main().getByText("第1順位")).toBeInTheDocument();
    // お客さまは審査・担保登録などの操作を行えない
    expect(main().queryByRole("button")).not.toBeInTheDocument();
  });

  it("行員：再審査と担保・保証の登録（保証人の入力欄の出し分け）", async () => {
    const { user } = await open(tellerUser);
    mockApi({ "/loan-screen": {}, "/loan-collateral": {} });
    await user.click(await main().findByRole("button", { name: "再審査" }));
    expect(await main().findByText("審査しました")).toBeInTheDocument();
    await user.selectOptions(main().getByLabelText("種類"), "GUARANTOR");
    expect(main().queryByLabelText("抵当権の順位")).not.toBeInTheDocument();
    await user.type(main().getByLabelText("内容"), "連帯保証");
    await user.type(main().getByLabelText("評価額（円）"), "１，０００，０００");
    await user.type(main().getByLabelText("保証人・保証会社"), "山田 花子");
    await user.click(main().getByRole("button", { name: "登録" }));
    expect(await main().findByText("担保・保証を登録しました")).toBeInTheDocument();
    expect(lastBody("/loan-collateral")).toMatchObject({ applicationId: "11", collateralType: "GUARANTOR", appraisalValue: "1000000", guarantorName: "山田 花子" });
    expect(main().queryByRole("button", { name: "承認" })).not.toBeInTheDocument();
  });

  it("役席：提示金利を初期値に承認、理由を付けて謝絶", async () => {
    const { user } = await open(supervisorUser);
    mockApi({ "/loan-decide": {} });
    expect(await main().findByLabelText("承認金利（%）")).toHaveValue("1.2");
    await user.click(main().getByRole("button", { name: "承認" }));
    await waitFor(() => expect(lastBody("/loan-decide")).toEqual({ applicationId: "11", decision: "APPROVE", note: "", rate: "1.2" }));
    await user.type(main().getByLabelText(/^判断理由/), "返済比率が高い");
    await user.click(main().getByRole("button", { name: "謝絶" }));
    expect(await main().findByText("謝絶しました")).toBeInTheDocument();
    expect(lastBody("/loan-decide")).toEqual({ applicationId: "11", decision: "REJECT", note: "返済比率が高い" });
  });

  it("謝絶推奨の案件は承認に理由が必須である旨を出し、サーバーのエラーを表示する", async () => {
    const { user } = await open(supervisorUser, app({ recommendation: "DECLINE" }));
    mockApi({ "/loan-decide": new ApiFailure(422, "NOTE_REQUIRED", "承認理由を入力してください。") });
    expect(await main().findByText("謝絶", { selector: "dd" })).toHaveClass("minus");
    expect(main().getByLabelText("判断理由（謝絶推奨のため承認には必須）")).toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "承認" }));
    expect(await main().findByRole("alert")).toHaveTextContent("承認理由を入力してください。");
  });

  it("承認済み：返済用口座と返済日を指定して契約する", async () => {
    const { user } = await open(tellerUser, app({ status: "APPROVED" }));
    mockApi({ "/loan-contract": {} });
    await user.type(await main().findByLabelText("返済用口座"), "1000001");
    await user.click(main().getByRole("button", { name: "契約する" }));
    expect(await main().findByText("契約しました")).toBeInTheDocument();
    expect(lastBody("/loan-contract")).toEqual({ applicationId: "11", repaymentAccountNo: "1000001", paymentDay: "27" });
  });

  it("契約済み：融資を実行する（冪等キー付き）", async () => {
    const { user } = await open(tellerUser, app({ status: "CONTRACTED", loanId: 5 }));
    mockApi({ "/loan-disburse": {} });
    expect(await main().findByRole("link", { name: "No.5" })).toHaveAttribute("href", "/loans/5");
    expect(main().getByText(/（実行待ち）/)).toBeInTheDocument();
    await user.click(main().getByRole("button", { name: "融資を実行する" }));
    expect(await main().findByText("融資を実行しました")).toBeInTheDocument();
    expect(lastBody("/loan-disburse").loanId).toBe("5");
    expect(lastBody("/loan-disburse").idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("シンジケートローン：参加行と分担シェアを表示し、参加行を追加する", async () => {
    const { user } = await open(tellerUser, app({
      product: "SYNDICATED", syndicateTotal: 500_000_000, requestedAmount: 200_000_000, collaterals: [],
      participants: [{ lenderName: "永劫銀行", role: "ARRANGER", shareAmount: 200_000_000 }],
    }));
    mockApi({ "/loan-participant": {} });
    expect(await main().findByText("アレンジャー", { selector: "td" })).toBeInTheDocument();
    expect(main().getByText("40.0%")).toBeInTheDocument();
    expect(main().getByText("登録なし")).toBeInTheDocument();
    await user.type(main().getByLabelText("金融機関名"), "第二銀行");
    await user.type(main().getByLabelText("分担額（円）"), "150,000,000");
    await user.click(main().getByRole("button", { name: "追加" }));
    expect(await main().findByText("参加行を登録しました")).toBeInTheDocument();
    expect(lastBody("/loan-participant")).toEqual({ applicationId: "11", lenderName: "第二銀行", shareAmount: "150000000", role: "PARTICIPANT" });
  });

  it("未審査の申込は審査前の表示で「審査する」を出す", async () => {
    await open(tellerUser, app({ status: "APPLIED", score: null }));
    expect(await main().findByRole("button", { name: "審査する" })).toBeInTheDocument();
    expect(main().queryByText(/点 ／/)).not.toBeInTheDocument();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/loan-application": new ApiFailure(404, "NOT_FOUND", "申込が見つかりません。") });
    await renderPage(<ApplicationPage />, { user: tellerUser, path: "/loans/applications/99", params: { id: "99" } });
    expect(await screen.findByRole("alert")).toHaveTextContent("申込が見つかりません。");
  });
});
