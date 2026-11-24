// app/loans/[id]/page.tsx のテスト：融資概要、返済・繰上返済・借入、返済予定表、督促記録、債権管理（役席）。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import LoanPage from "@/app/loans/[id]/page";
import type { LoginUser } from "@/lib/types";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function schedule(n: number, paid: number) {
  return Array.from({ length: n }, (_, i) => ({
    installmentNo: i + 1, dueDate: `2026-${String((i % 12) + 1).padStart(2, "0")}-27`, principalDue: 80000, interestDue: 5000,
    principalPaid: i < paid ? 80000 : 0, interestPaid: i < paid ? 5000 : 0, lateFeePaid: i === 0 ? 120 : 0,
    status: i < paid ? "PAID" : i === paid ? "OVERDUE" : "SCHEDULED",
  }));
}

function loan(over: Record<string, unknown> = {}) {
  return {
    loanId: 5, product: "HOUSING", status: "DELINQUENT", outstanding: 1_500_000, principal: 2_000_000, creditLimit: null,
    annualRate: 1.5, repaymentMethod: "EQUAL_PAYMENT", paymentDay: 27, repaymentAccountNo: "1000001", borrowerClass: "WATCH",
    daysPastDue: 40, accruedInterest: 0, customerName: "山田 太郎", dueNow: 85000, schedule: schedule(24, 5),
    collaterals: [{ collateralId: 1, collateralType: "REAL_ESTATE", description: "自宅", appraisalValue: 30000000 }],
    transactions: [{ loanTxId: 1, createdAt: "2026-01-27T09:00:00", txType: "REPAY", principal: 80000, interest: 5000, lateFee: 0, outstandingAfter: 1920000, memo: "約定返済", createdBy: "BATCH" }],
    collectionActions: [{ actionId: 1, createdAt: "2026-11-01T10:00:00", actionType: "CALL", note: "本人と話した", promisedDate: "2026-11-30", createdBy: "teller01" }],
    ...over,
  };
}

const management = { restructured: false, restructureCount: 0, provisionAmount: 0, provisionUpdatedAt: null, requiredProvision: 0, borrowerClass: "WATCH", unsecuredBasis: false, secured: 0, unsecured: 0, provisions: [], collaterals: [], restructures: [] };

async function open(user: LoginUser, l = loan()) {
  mockApi({ "/loan": l, "/loan-management": management });
  return renderPage(<LoanPage />, { user, path: `/loans/${l.loanId}`, params: { id: String(l.loanId) } });
}

describe("LoanPage", () => {
  it("お客さま：概要・未返済の直近 12 回・担保・取引履歴を表示し、行員向けの管理は出さない", async () => {
    const { user } = await open(customerUser);
    expect(await main().findByText("住宅ローン ・ No.5")).toBeInTheDocument();
    expect(main().getByText("40日")).toBeInTheDocument();
    expect(main().getByText(/返済済 5 \/ 24 回/)).toBeInTheDocument();
    const scheduleTable = main().getByRole("heading", { name: "返済予定表" }).closest("section")!.querySelector("tbody")!;
    expect(scheduleTable.querySelectorAll("tr")).toHaveLength(12);
    expect(scheduleTable.querySelector("tr")).toHaveTextContent("延滞");
    await user.click(main().getByRole("button", { name: "すべて表示" }));
    expect(main().getByText("全 24 回")).toBeInTheDocument();
    expect(scheduleTable.querySelectorAll("tr")).toHaveLength(24);
    expect(main().getAllByText(/\+損害金/)).toHaveLength(1);
    expect(main().getByText("自宅")).toBeInTheDocument();
    expect(main().getByText("約定返済（BATCH）")).toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "延滞管理・督促" })).not.toBeInTheDocument();
    expect(main().queryByRole("heading", { name: "貸倒引当金" })).not.toBeInTheDocument();
  });

  it("返済：期日到来分を初期値にし、成功メッセージを出す", async () => {
    const { user } = await open(customerUser);
    mockApi({ "/loan-repay": {} });
    const amount = await main().findByLabelText("金額（円）");
    expect(amount).toHaveValue("85000");
    await user.click(main().getByRole("button", { name: "返済する" }));
    expect(await main().findByText("返済しました")).toBeInTheDocument();
    expect(lastBody("/loan-repay")).toMatchObject({ loanId: "5", amount: "85000" });
  });

  it("繰上返済に切り替え、エラーを表示する", async () => {
    const { user } = await open(customerUser);
    mockApi({ "/loan-prepay": new ApiFailure(409, "DELINQUENT", "延滞中は繰上返済できません。") });
    await user.click(await main().findByRole("tab", { name: "繰上返済（一部）" }));
    await user.click(main().getByRole("button", { name: "繰上返済（一部）する" }));
    expect(await main().findByText("延滞中は繰上返済できません。")).toBeInTheDocument();
  });

  it("カードローン：極度額・未払利息を表示し、借入できる。返済予定表は出さない", async () => {
    const { user } = await open(customerUser, loan({ product: "CARD", repaymentMethod: "REVOLVING", creditLimit: 500000, outstanding: 200000, accruedInterest: 123.9, dueNow: 0, schedule: [], status: "ACTIVE", daysPastDue: 0 }));
    mockApi({ "/loan-draw": {} });
    expect(await main().findByText(/ご利用可能額 .*300,000/)).toBeInTheDocument();
    expect(main().getByText("未払利息").querySelector("strong")).toHaveTextContent("123");
    expect(main().getByRole("tab", { name: "借入" })).toHaveAttribute("aria-selected", "true");
    expect(main().queryByRole("heading", { name: "返済予定表" })).not.toBeInTheDocument();
    await user.type(main().getByLabelText("金額（円）"), "１０，０００");
    await user.click(main().getByRole("button", { name: "借入する" }));
    await main().findByText("借入しました");
    expect(lastBody("/loan-draw")).toMatchObject({ loanId: "5", amount: "10000" });
  });

  it("完済済み・当座貸越は返済フォームを出さない", async () => {
    await open(customerUser, loan({ status: "PAID_OFF", daysPastDue: 0 }));
    await main().findByText("住宅ローン ・ No.5");
    expect(main().queryByRole("button", { name: "返済する" })).not.toBeInTheDocument();
  });

  it("行員：融資管理と督促の記録・履歴を表示し、記録を送る", async () => {
    const { user } = await open(tellerUser);
    mockApi({ "/loan-collection": {} });
    expect(await main().findByRole("heading", { name: "貸倒引当金" })).toBeInTheDocument();
    expect(main().getByText("本人と話した")).toBeInTheDocument();
    await user.selectOptions(main().getByLabelText("対応"), "LETTER");
    await user.type(main().getByLabelText("内容"), "督促状を送付");
    await user.click(main().getByRole("button", { name: "記録" }));
    await waitFor(() => expect(lastBody("/loan-collection")).toEqual({ loanId: "5", actionType: "LETTER", note: "督促状を送付" }));
    expect(main().getByLabelText("内容")).toHaveValue("");
    expect(main().queryByRole("heading", { name: "債権管理（役席）" })).not.toBeInTheDocument();
    // 行員には顧客向けの簡易担保一覧を出さない
    expect(main().queryByText("自宅")).not.toBeInTheDocument();
  });

  it("役席：債務者区分の変更。償却は破綻懸念先以下でのみ押せる", async () => {
    const { user } = await open(supervisorUser);
    mockApi({ "/loan-class": {} });
    const section = (await main().findByRole("heading", { name: "債権管理（役席）" })).parentElement!;
    const writeOff = screen.getByRole("button", { name: "償却（破綻懸念先以下）" });
    expect(writeOff).toBeDisabled();
    await user.selectOptions(section.querySelector("select")!, "DOUBTFUL");
    await waitFor(() => expect(lastBody("/loan-class")).toEqual({ loanId: "5", borrowerClass: "DOUBTFUL" }));
    expect(await main().findByText("債務者区分を変更しました")).toBeInTheDocument();
  });

  it("役席：破綻懸念先なら償却できる", async () => {
    const { user } = await open(supervisorUser, loan({ borrowerClass: "DOUBTFUL" }));
    mockApi({ "/loan-writeoff": {} });
    await user.click(await main().findByRole("button", { name: "償却（破綻懸念先以下）" }));
    expect(await main().findByText("償却しました")).toBeInTheDocument();
    expect(lastBody("/loan-writeoff")).toEqual({ loanId: "5", note: "回収見込みなしと判断" });
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/loan": new ApiFailure(404, "LOAN_NOT_FOUND", "融資が見つかりません。") });
    await renderPage(<LoanPage />, { user: customerUser, path: "/loans/99", params: { id: "99" } });
    expect(await main().findByRole("alert")).toHaveTextContent("融資が見つかりません。");
  });
});
