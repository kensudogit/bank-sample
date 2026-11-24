// components/DepositPanels.tsx のテスト：口座種別ごとのパネル（定期・通知・外貨・当座）の表示と操作。
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { DepositPanels } from "@/components/DepositPanels";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { account } from "../../test/render";

describe("DepositPanels", () => {
  it("普通・貯蓄には専用パネルを出さない", () => {
    const { container } = render(<DepositPanels account={account()} />);
    expect(container).toBeEmptyDOMElement();
  });

  describe("定期預金", () => {
    const td = {
      principal: 1_000_000, annualRate: 0.3, termMonths: 12, startDate: "2026-04-01", maturityDate: "2027-04-01",
      maturityInstruction: "ROLLOVER", linkedAccountNo: "1000001", expectedNetInterest: 2390, rolloverCount: 1, status: "ACTIVE",
    };

    it("内容を表示し、確認のうえ中途解約する", async () => {
      mockApi({
        "/time-deposit": td,
        "/time-cancel": { grossInterest: 100, nationalTax: 15, localTax: 5, payout: 1_000_080, payoutAccountNo: "1000001" },
      });
      render(<DepositPanels account={account({ accountNo: "2000001", accountType: "TIME" })} />);
      expect(await screen.findByText("0.300%")).toBeInTheDocument();
      expect(screen.getByText("元利継続（自動継続）")).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "中途解約…" }));
      await userEvent.click(screen.getByRole("button", { name: "中途解約する" }));
      expect(await screen.findByRole("status")).toHaveTextContent("1000001 へ入金しました");
      expect(lastBody("/time-cancel").accountNo).toBe("2000001");
    });

    it("やめるで確認を取り消せる。満期時の受取口座指定も表示する", async () => {
      mockApi({ "/time-deposit": { ...td, maturityInstruction: "PAYOUT" } });
      render(<DepositPanels account={account({ accountType: "TIME" })} />);
      expect(await screen.findByText("元利金を 1000001 へ")).toBeInTheDocument();
      await userEvent.click(screen.getByRole("button", { name: "中途解約…" }));
      await userEvent.click(screen.getByRole("button", { name: "やめる" }));
      expect(screen.getByRole("button", { name: "中途解約…" })).toBeInTheDocument();
      expect(callsTo("/time-cancel")).toHaveLength(0);
    });

    it("満期・解約済みなら中途解約ボタンを出さない", async () => {
      mockApi({ "/time-deposit": { ...td, status: "MATURED" } });
      render(<DepositPanels account={account({ accountType: "TIME" })} />);
      await screen.findByText("0.300%");
      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });
  });

  describe("通知預金", () => {
    it("払出予定額を正規化して通知し、有効な通知は取消できる", async () => {
      mockApi({
        "/notices": [
          { noticeId: 1, notifiedAt: "2026-11-20T10:00:00", amount: 50000, availableFrom: "2026-11-22", status: "OPEN" },
          { noticeId: 2, notifiedAt: "2026-11-01T10:00:00", amount: 10000, availableFrom: "2026-11-03", status: "USED" },
        ],
        "/notice-create": { amount: 30000, availableFrom: "2026-11-25" },
        "/notice-cancel": [],
      });
      render(<DepositPanels account={account({ accountType: "NOTICE" })} />);
      expect(await screen.findByText("払出済")).toBeInTheDocument();
      await userEvent.type(screen.getByLabelText("払出予定額（円）"), "３０，０００");
      await userEvent.click(screen.getByRole("button", { name: "通知する" }));
      expect(await screen.findByRole("status")).toHaveTextContent("払出可能日: 2026-11-25");
      expect(lastBody("/notice-create")).toEqual({ accountNo: "1000001", amount: "30000" });
      await userEvent.click(screen.getByRole("button", { name: "取消" }));
      expect(lastBody("/notice-cancel")).toEqual({ accountNo: "1000001", noticeId: "1" });
    });

    it("利用可でなければパネルを出さない", () => {
      const { container } = render(<DepositPanels account={account({ accountType: "NOTICE", status: "FROZEN" })} />);
      expect(container).toBeEmptyDOMElement();
    });
  });

  describe("外貨預金", () => {
    const usd = account({ accountNo: "3000001", accountType: "FOREIGN", currency: "USD", balance: 50000 });
    function mockFx() {
      mockApi({
        "/fx-rates": [{ currency: "USD", tts: "151.00", ttm: "150.00", ttb: "149.00", updatedAt: "2026-11-23T09:00:00" }],
        "/customer": { customerId: "CU0001", accounts: [account(), account({ accountNo: "1000009", accountType: "TIME" }), usd] },
      });
    }

    it("レートを表示し、円 → 外貨の概算（切上げ）を出して両替する", async () => {
      mockFx();
      mockApi({ "/fx-exchange": { direction: "BUY", currency: "USD", foreignAmount: "10.50", yenAmount: 1586, rate: "151.00" } });
      render(<DepositPanels account={usd} />);
      expect(await screen.findByText("151.00 円")).toBeInTheDocument();
      const select = await screen.findByRole("combobox", { name: "円の支払口座" });
      expect(Array.from((select as HTMLSelectElement).options).map((o) => o.value)).toEqual(["1000001"]);
      await userEvent.type(screen.getByRole("textbox", { name: /金額（USD）/ }), "10.50");
      expect(screen.getByText(/概算/)).toHaveTextContent("1,586");
      await userEvent.click(screen.getByRole("button", { name: "両替する" }));
      expect(await screen.findByRole("status")).toHaveTextContent("外貨を購入しました");
      expect(lastBody("/fx-exchange")).toMatchObject({ fromAccountNo: "1000001", toAccountNo: "3000001", amount: "10.50" });
    });

    it("外貨 → 円は TTB・切捨てで、口座の向きを逆にする", async () => {
      mockFx();
      mockApi({ "/fx-exchange": { direction: "SELL", currency: "USD", foreignAmount: "10.50", yenAmount: 1564, rate: "149.00" } });
      render(<DepositPanels account={usd} />);
      await screen.findByText("151.00 円");
      await userEvent.click(screen.getByRole("tab", { name: "外貨 → 円（払出）" }));
      await screen.findByRole("combobox", { name: "円の入金口座" });
      await userEvent.type(screen.getByRole("textbox", { name: /金額（USD）/ }), "10.50");
      expect(screen.getByText(/概算/)).toHaveTextContent("1,564");
      await userEvent.click(screen.getByRole("button", { name: "両替する" }));
      await screen.findByRole("status");
      expect(lastBody("/fx-exchange")).toMatchObject({ fromAccountNo: "3000001", toAccountNo: "1000001" });
    });

    it("小数 3 桁以上は送信前にエラーにする", async () => {
      mockFx();
      render(<DepositPanels account={usd} />);
      await screen.findByRole("combobox", { name: "円の支払口座" });
      await userEvent.type(screen.getByRole("textbox", { name: /金額（USD）/ }), "1.234");
      await userEvent.click(screen.getByRole("button", { name: "両替する" }));
      expect(screen.getByRole("alert")).toHaveTextContent("小数点以下2桁まで");
      expect(callsTo("/fx-exchange")).toHaveLength(0);
    });

    it("API エラーを表示する", async () => {
      mockFx();
      mockApi({ "/fx-exchange": new ApiFailure(409, "INSUFFICIENT_FUNDS", "残高が不足しています。") });
      render(<DepositPanels account={usd} />);
      await screen.findByRole("combobox", { name: "円の支払口座" });
      await userEvent.type(screen.getByRole("textbox", { name: /金額（USD）/ }), "1");
      await userEvent.click(screen.getByRole("button", { name: "両替する" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("残高が不足しています。");
    });
  });

  it("当座預金：極度額・貸越利用額・支払可能額を表示する", () => {
    render(<DepositPanels account={account({ accountType: "CHECKING", balance: -30000, overdraftLimit: 500000, availableBalance: 470000 })} />);
    expect(screen.getByText("貸越利用額").nextElementSibling).toHaveTextContent("30,000");
    expect(screen.getByText("貸越利用額").nextElementSibling).toHaveClass("minus");
    expect(screen.getByText("支払可能額").nextElementSibling).toHaveTextContent("470,000");
  });
});
