// components/AccountLifecycle.tsx のテスト：口座解約（確認 → 実行・払戻し先の選択・冪等キー）と休眠解除。
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { CloseAccount, ReactivateAccount } from "@/components/AccountLifecycle";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { account } from "../../test/render";

const interest = {
  accountNo: "1000001", accountType: "ORDINARY", periodFrom: "2026-09-01", periodTo: "2026-11-22", days: 83,
  balanceDays: 83, averageBalance: 100000, grossInterest: 100, nationalTax: 15, localTax: 5, tax: 20, netInterest: 80,
  settled: false, note: null,
};

function mockCustomer() {
  mockApi({
    "/customer": {
      customerId: "CU0001",
      accounts: [
        account({ accountNo: "1000001" }),
        account({ accountNo: "1000002", accountType: "SAVINGS" }),
        account({ accountNo: "1000003", status: "CLOSED" }),
      ],
    },
    "/interest-preview": interest,
  });
}

describe("CloseAccount", () => {
  it("本人名義の利用可能な口座だけを払戻し先にし、確認画面を経て解約する", async () => {
    mockCustomer();
    mockApi({
      "/account-close": { accountNo: "1000001", interest, payoutAmount: 1_250_080, payoutAccountNo: "1000002", closedAt: "2026-11-23T10:00:00" },
    });
    render(<CloseAccount account={account()} />);
    const select = await screen.findByRole("combobox", { name: "払戻し方法" });
    await screen.findByRole("option", { name: /1000002/ });
    expect(screen.queryByRole("option", { name: /1000003/ })).not.toBeInTheDocument();
    await userEvent.selectOptions(select, "1000002");
    await userEvent.click(screen.getByRole("button", { name: "解約内容を確認" }));
    expect(screen.getByText("口座 1000002 へ入金")).toBeInTheDocument();
    // 払戻し見込み額 = 残高 1,250,000 + 税引後利息 80
    expect(screen.getByText("払戻し見込み額").nextElementSibling).toHaveTextContent("1,250,080");
    expect(callsTo("/account-close")).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "解約を実行する" }));
    expect(await screen.findByText("口座 1000001 を解約しました。")).toBeInTheDocument();
    expect(lastBody("/account-close")).toMatchObject({ accountNo: "1000001", payoutAccountNo: "1000002" });
    expect(lastBody("/account-close").idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("戻るで払戻し方法の選択に戻れる", async () => {
    mockCustomer();
    render(<CloseAccount account={account()} />);
    await userEvent.click(await screen.findByRole("button", { name: "解約内容を確認" }));
    await userEvent.click(screen.getByRole("button", { name: "戻る" }));
    expect(screen.getByRole("combobox", { name: "払戻し方法" })).toBeInTheDocument();
  });

  it("失敗したらエラーを出して最初の画面に戻り、DUPLICATE_REQUEST なら冪等キーを作り直す", async () => {
    mockCustomer();
    mockApi({ "/account-close": new ApiFailure(409, "DUPLICATE_REQUEST", "処理中です。") });
    render(<CloseAccount account={account()} />);
    await userEvent.click(await screen.findByRole("button", { name: "解約内容を確認" }));
    await userEvent.click(screen.getByRole("button", { name: "解約を実行する" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("処理中です。");
    const firstKey = lastBody("/account-close").idempotencyKey;
    await userEvent.click(screen.getByRole("button", { name: "解約内容を確認" }));
    await userEvent.click(screen.getByRole("button", { name: "解約を実行する" }));
    await screen.findByRole("alert");
    expect(lastBody("/account-close").idempotencyKey).not.toBe(firstKey);
  });

  it("解約済みの口座は解約日時だけを表示する", () => {
    mockCustomer();
    render(<CloseAccount account={account({ status: "CLOSED", closedAt: "2026-10-01T09:00:00" })} />);
    expect(screen.getByText("この口座は 2026-10-01 09:00 に解約済みです。")).toBeInTheDocument();
  });
});

describe("ReactivateAccount", () => {
  it("休眠を解除し、完了後はボタンを消す", async () => {
    mockApi({ "/account-reactivate": account() });
    render(<ReactivateAccount account={account({ status: "DORMANT", dormantAt: "2025-01-01T00:00:00" })} />);
    expect(screen.getByText(/休眠日 2025-01-01 00:00/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "本人確認済み・休眠を解除する" }));
    expect(await screen.findByText("休眠を解除しました。お取引が可能です。")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(lastBody("/account-reactivate").accountNo).toBe("1000001");
  });

  it("失敗したらエラーを表示し、再実行できる", async () => {
    mockApi({ "/account-reactivate": new ApiFailure(409, "KYC_REQUIRED", "本人確認が必要です。") });
    render(<ReactivateAccount account={account({ status: "DORMANT" })} />);
    await userEvent.click(screen.getByRole("button", { name: "本人確認済み・休眠を解除する" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("本人確認が必要です。");
    expect(screen.getByRole("button", { name: "本人確認済み・休眠を解除する" })).toBeEnabled();
  });
});
