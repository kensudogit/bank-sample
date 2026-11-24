// app/deposits/page.tsx のテスト：定期預金の作成（金利表示・利息概算・冪等キー）、為替相場と役席の相場更新、商品案内。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import DepositsPage from "@/app/deposits/page";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { account, accountPage, customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

function mockDefaults() {
  mockApi({
    "/time-rates": [{ termMonths: 3, annualRate: 0.1 }, { termMonths: 12, annualRate: 0.3 }, { termMonths: 60, annualRate: 0.5 }],
    "/fx-rates": [{ currency: "USD", tts: 151, ttm: 150, ttb: 149, updatedAt: "2026-11-23T09:00:00", updatedBy: "super01" }],
    "/accounts": accountPage([account(), account({ accountNo: "1000002", accountType: "CHECKING" })]),
  });
}

describe("DepositsPage", () => {
  it("期間別の金利・為替相場・商品案内を表示する", async () => {
    mockDefaults();
    await renderPage(<DepositsPage />, { user: customerUser, path: "/deposits" });
    expect(await main().findByText("3か月")).toBeInTheDocument();
    expect(main().getByText("5年")).toBeInTheDocument();
    expect(main().getByText("0.300%")).toBeInTheDocument();
    expect(await main().findByText("2026-11-23 09:00（super01）")).toBeInTheDocument();
    expect(main().getByText("通知預金").tagName).toBe("TH");
    expect(main().queryByRole("button", { name: "公示相場を更新" })).not.toBeInTheDocument();
  });

  it("お客さま：資金口座は普通・貯蓄だけ。利息概算を出して定期預金を作成する", async () => {
    mockDefaults();
    mockApi({ "/time-open": { accountNo: "2000009", principal: 1000000, annualRate: 0.3, maturityDate: "2027-11-23" } });
    const { user } = await renderPage(<DepositsPage />, { user: customerUser, path: "/deposits" });
    const source = await main().findByRole("combobox", { name: /資金口座/ });
    await waitFor(() => expect((source as HTMLSelectElement).value).toBe("1000001"));
    expect(Array.from((source as HTMLSelectElement).options).map((o) => o.value)).toEqual(["1000001"]);
    await user.type(main().getByLabelText(/^預入額/), "1,000,000");
    // 1,000,000 × 0.3% × 12 / 12 = 3,000
    expect(main().getByText(/満期時の利息（税引前・概算）/)).toHaveTextContent("3,000");
    await user.selectOptions(main().getByLabelText("満期時の取扱い"), "ROLLOVER");
    await user.click(main().getByRole("button", { name: "定期預金を作成する" }));
    expect(await main().findByRole("link", { name: "2000009" })).toHaveAttribute("href", "/accounts/2000009");
    expect(lastBody("/time-open")).toMatchObject({ sourceAccountNo: "1000001", amount: "1000000", termMonths: "12", instruction: "ROLLOVER" });
    expect(main().getByLabelText(/^預入額/)).toHaveValue("");
  });

  it("行員：資金口座は番号入力。作成エラーを表示する", async () => {
    mockDefaults();
    mockApi({ "/time-open": new ApiFailure(400, "MIN_AMOUNT", "10,000円以上で入力してください。") });
    const { user } = await renderPage(<DepositsPage />, { user: tellerUser, path: "/deposits" });
    await user.type(await main().findByRole("textbox", { name: /資金口座/ }), "1000001");
    await user.type(main().getByLabelText(/^預入額/), "500");
    await user.selectOptions(main().getByLabelText("期間"), "3");
    await user.click(main().getByRole("button", { name: "定期預金を作成する" }));
    expect(await main().findByText("10,000円以上で入力してください。")).toBeInTheDocument();
    expect(lastBody("/time-open").termMonths).toBe("3");
  });

  it("役席：公示相場を更新できる", async () => {
    mockDefaults();
    mockApi({ "/fx-rate-update": [] });
    const { user } = await renderPage(<DepositsPage />, { user: supervisorUser, path: "/deposits" });
    await user.selectOptions(await main().findByLabelText("通貨"), "EUR");
    await user.type(main().getByLabelText("仲値（TTM）"), "162.5");
    await user.type(main().getByLabelText("スプレッド（片側）"), "1.5");
    await user.click(main().getByRole("button", { name: "公示相場を更新" }));
    expect(await main().findByText("相場を更新しました。")).toBeInTheDocument();
    expect(lastBody("/fx-rate-update")).toEqual({ currency: "EUR", ttm: "162.5", spread: "1.5" });
  });
});
