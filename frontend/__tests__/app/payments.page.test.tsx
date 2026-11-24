// app/payments/page.tsx のテスト：他行宛振込（手数料・取消・組戻し）、口座振替（契約・請求取込・振替処理）、
// 海外送金（保留の判断・MT103 電文）、全銀データと被仕向入金（役席）。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import PaymentsPage from "@/app/payments/page";
import { ApiFailure, lastBody, mockApi, type MockRequest } from "../../test/api";
import { account, accountPage, customerUser, main, renderPage, supervisorUser, tellerUser } from "../../test/render";

const outRemits = [
  { remitId: 1, createdAt: "2026-11-22T10:00:00", accountNo: "1000001", bankName: "みずほ", branchCode: "123", destAccountNo: "7654321", destNameKana: "タナカ ハナコ", amount: 10000, fee: 330, valueDate: "2026-11-25", status: "PENDING" },
  { remitId: 2, createdAt: "2026-11-21T10:00:00", accountNo: "1000001", bankName: "三菱UFJ", branchCode: "001", destAccountNo: "1111111", destNameKana: "スズキ イチロウ", amount: 50000, fee: 440, valueDate: "2026-11-21", status: "SETTLED" },
];
const heldIn = [{ remitId: 9, remitterNameKana: "ｶ)ﾃｽﾄ", destAccountNo: "1000003", destNameKana: "サトウ ハナコ", amount: 120000, note: "受取人名不一致" }];

function mockDefaults() {
  mockApi({
    "/banks": [{ bankCode: "0005", name: "三菱UFJ" }, { bankCode: "0001", name: "みずほ" }, { bankCode: "9999", name: "永劫銀行" }],
    "/remittances": (r: MockRequest) => (r.query.get("direction") === "IN" ? heldIn : outRemits),
    "/accounts": accountPage([account()]),
    "/billers": [{ billerCode: "ELEC01", name: "東京電力", category: "電気" }, { billerCode: "GAS01", name: "東京ガス", category: "ガス" }],
    "/mandates": [{ mandateId: 3, billerName: "東京電力", payerNumber: "E-0001", accountNo: "1000001", status: "ACTIVE" }],
    "/debit-requests": [
      { requestId: 1, dueDate: "2026-11-27", billerName: "東京電力", payerNumber: "E-0001", accountNo: "1000001", amount: 8000, status: "PENDING" },
      { requestId: 2, dueDate: "2026-10-27", billerName: "東京電力", payerNumber: "E-0001", accountNo: "1000001", amount: 8000, status: "FAILED", resultCode: "1" },
    ],
    "/foreign-remittances": [
      { remitId: 21, createdAt: "2026-11-20T10:00:00", accountNo: "1000001", amountMinor: 100000, currency: "USD", beneficiaryName: "JOHN SMITH", country: "GB", status: "SENT", screeningResult: "該当なし" },
      { remitId: 22, createdAt: "2026-11-21T10:00:00", accountNo: "1000001", amountMinor: 50000, currency: "USD", beneficiaryName: "IVAN X", country: "IR", status: "HELD", screeningResult: "高リスク国" },
    ],
    "/zengin-batches": [{ batchId: 4, createdAt: "2026-11-21T15:00:00", valueDate: "2026-11-21", recordCount: 2, totalAmount: 60000 }],
  });
}

describe("PaymentsPage", () => {
  it("お客さま・行員には全銀タブを出さない", async () => {
    mockDefaults();
    await renderPage(<PaymentsPage />, { user: customerUser, path: "/payments" });
    expect(main().getAllByRole("tab").map((t) => t.textContent)).toEqual(["他行宛振込", "口座振替", "海外送金"]);
  });

  describe("他行宛振込", () => {
    it("当行（9999）を振込先から除き、手数料を計算して受け付ける", async () => {
      mockDefaults();
      mockApi({ "/remit-send": { remitId: 3, bankName: "みずほ", amount: 30000, fee: 440, valueDate: "2026-11-24", destNameKana: "タナカ ハナコ" } });
      const { user } = await renderPage(<PaymentsPage />, { user: customerUser, path: "/payments" });
      const bank = (await main().findByLabelText("振込先金融機関")) as HTMLSelectElement;
      await waitFor(() => expect(bank.options).toHaveLength(2));
      expect(Array.from(bank.options).map((o) => o.value)).toEqual(["0005", "0001"]);
      await user.selectOptions(bank, "0001");
      await user.type(main().getByLabelText("支店番号"), "123");
      await user.type(main().getByLabelText("口座番号"), "7654321");
      await user.type(main().getByLabelText("受取人名（カナ）"), "タナカ ハナコ");
      await user.type(main().getByLabelText(/^金額（円）/), "29999");
      expect(main().getByText(/手数料 .*330/)).toBeInTheDocument();
      await user.clear(main().getByLabelText(/^金額（円）/));
      await user.type(main().getByLabelText(/^金額（円）/), "３０，０００");
      expect(main().getByText(/お引落し合計 .*30,440/)).toBeInTheDocument();
      await user.click(main().getByRole("button", { name: "振込を受け付ける" }));
      expect(await main().findByText(/受け付けました（No\.3）/)).toBeInTheDocument();
      expect(lastBody("/remit-send")).toMatchObject({ accountNo: "1000001", bankCode: "0001", amount: "30000", destType: "1" });
      expect(main().getByLabelText("口座番号")).toHaveValue("");
    });

    it("送信待ちは取消、決済済みは行員だけが組戻しできる", async () => {
      mockDefaults();
      mockApi({ "/remit-cancel": {}, "/remit-return": {} });
      const { user } = await renderPage(<PaymentsPage />, { user: tellerUser, path: "/payments" });
      expect(await main().findByText("送信待ち")).toHaveClass("badge-PENDING");
      expect(main().getByText("決済済")).toHaveClass("badge-SUCCESS");
      await user.click(main().getByRole("button", { name: "取消" }));
      await waitFor(() => expect(lastBody("/remit-cancel")).toEqual({ remitId: "1" }));
      await user.click(main().getByRole("button", { name: "組戻し" }));
      await waitFor(() => expect(lastBody("/remit-return")).toEqual({ remitId: "2", reason: "お客さまの依頼による組戻し" }));
    });

    it("お客さまには組戻しを出さない。エラーを表示する", async () => {
      mockDefaults();
      mockApi({ "/remit-cancel": new ApiFailure(409, "ALREADY_SENT", "送信済みのため取り消せません。") });
      const { user } = await renderPage(<PaymentsPage />, { user: customerUser, path: "/payments" });
      await main().findByText("送信待ち");
      expect(main().queryByRole("button", { name: "組戻し" })).not.toBeInTheDocument();
      await user.click(main().getByRole("button", { name: "取消" }));
      expect(await main().findByText("送信済みのため取り消せません。")).toBeInTheDocument();
    });
  });

  describe("口座振替", () => {
    it("契約を登録・解約し、引落し状況（不能理由）を表示する", async () => {
      mockDefaults();
      mockApi({ "/mandate-create": { billerName: "東京ガス", mandateId: 4 }, "/mandate-cancel": {} });
      const { user } = await renderPage(<PaymentsPage />, { user: customerUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "口座振替" }));
      expect(await main().findByText("不能（資金不足）")).toBeInTheDocument();
      expect(main().getByText("引落待ち")).toBeInTheDocument();
      await user.selectOptions(await main().findByLabelText("収納企業"), "GAS01");
      await user.type(main().getByLabelText("お客さま番号"), "G-0009");
      await user.click(main().getByRole("button", { name: "登録" }));
      expect(await main().findByText("口座振替を登録しました（東京ガス・No.4）")).toBeInTheDocument();
      expect(lastBody("/mandate-create")).toEqual({ accountNo: "1000001", billerCode: "GAS01", payerNumber: "G-0009" });
      await user.click(main().getByRole("button", { name: "解約" }));
      expect(await main().findByText("口座振替を解約しました")).toBeInTheDocument();
      // お客さまには請求データの取込を出さない
      expect(main().queryByRole("button", { name: "取り込む" })).not.toBeInTheDocument();
    });

    it("行員：請求データを行ごとに解析して取り込む。役席は振替処理もできる", async () => {
      mockDefaults();
      mockApi({
        "/billing-import": [{ billerCode: "ELEC01", payerNumber: "E-0001", result: "OK" }],
        "/debit-process": { paid: 3, failed: 1 },
      });
      const { user } = await renderPage(<PaymentsPage />, { user: supervisorUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "口座振替" }));
      const textarea = await main().findByRole("textbox", { name: "" });
      await user.type(textarea, " ELEC01, E-0001 ,8000,2026-11-27 {enter}{enter}");
      await user.click(main().getByRole("button", { name: "取り込む" }));
      expect(await main().findByText("取込結果: ELEC01/E-0001 OK")).toBeInTheDocument();
      expect(lastBody("/billing-import")).toEqual({ items: [{ billerCode: "ELEC01", payerNumber: "E-0001", amount: "8000", dueDate: "2026-11-27" }] });
      await user.click(main().getByRole("button", { name: "引落日の到来分を今すぐ処理" }));
      expect(await main().findByText("振替処理: 振替済 3 件・不能 1 件")).toBeInTheDocument();
    });
  });

  describe("海外送金", () => {
    it("行員：送金元・手数料口座を入力して送金し、保留になった旨を表示する", async () => {
      mockDefaults();
      mockApi({ "/foreign-send": { status: "HELD", remitId: 23, currency: "USD", amountMinor: 100000, rate: "151.00", debitAmount: 151000, screeningResult: "制裁リスト照合中" } });
      const { user } = await renderPage(<PaymentsPage />, { user: tellerUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "海外送金" }));
      await user.type(await main().findByRole("textbox", { name: "送金元口座（円または外貨）" }), "3000001");
      await user.type(main().getByRole("textbox", { name: "手数料の引落口座（円）" }), "1000001");
      await user.type(main().getByLabelText("金額"), "1000.00");
      await user.type(main().getByLabelText("受取人名（英字）"), "JOHN SMITH");
      await user.type(main().getByLabelText("受取人口座（IBAN・口座番号）"), "GB33BUKB20201555555555");
      await user.type(main().getByLabelText("受取銀行 SWIFT/BIC"), "BUKBGB22");
      await user.type(main().getByLabelText("受取国（2文字）"), "GB");
      await user.type(main().getByLabelText("送金目的（英字）"), "TUITION FEE");
      await user.click(main().getByRole("button", { name: "送金する" }));
      expect(await main().findByText(/確認のため保留しました（No\.23）/)).toHaveTextContent("制裁リスト照合中");
      expect(lastBody("/foreign-send")).toMatchObject({ accountNo: "3000001", feeAccountNo: "1000001", currency: "USD", amount: "1000.00" });
    });

    it("送信済みは MT103 電文を表示し、役席は保留案件を送金・謝絶できる", async () => {
      mockDefaults();
      mockApi({ "/foreign-remittance": { remitId: 21, message: "{1:F01EIGOJPJTAXXX}{4::20:REF21-}" }, "/foreign-decide": {} });
      const { user } = await renderPage(<PaymentsPage />, { user: supervisorUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "海外送金" }));
      await user.click(await main().findByRole("button", { name: "電文" }));
      expect(await main().findByText("SWIFT MT103（No.21）")).toBeInTheDocument();
      expect(main().getByText(/F01EIGOJPJTAXXX/)).toBeInTheDocument();
      await user.click(main().getByRole("button", { name: "謝絶" }));
      await waitFor(() => expect(lastBody("/foreign-decide")).toEqual({ remitId: "22", decision: "REJECT", note: "制裁リスト該当の疑いにより謝絶" }));
      await user.click(main().getByRole("button", { name: "送金" }));
      await waitFor(() => expect(lastBody("/foreign-decide").decision).toBe("RELEASE"));
    });
    it("電文の取得に失敗したらエラーを表示する", async () => {
      mockDefaults();
      mockApi({ "/foreign-remittance": new ApiFailure(404, "NOT_FOUND", "電文が見つかりません。") });
      const { user } = await renderPage(<PaymentsPage />, { user: customerUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "海外送金" }));
      await user.click(await main().findByRole("button", { name: "電文" }));
      expect(await main().findByRole("alert")).toHaveTextContent("電文が見つかりません。");
      expect(main().queryByText(/SWIFT MT103/)).not.toBeInTheDocument();
    });
  });

  describe("全銀・被仕向（役席）", () => {
    it("全銀データを作成し、内容表示と決済完了を行う", async () => {
      mockDefaults();
      mockApi({
        "/zengin-create": { batchId: 5, recordCount: 1, totalAmount: 10000 },
        "/zengin-batch": { batchId: 4, content: "1" + " ".repeat(119) + "\r\n2" + " ".repeat(119) + "\r\n" },
        "/zengin-settle": { settled: 2 },
      });
      const { user } = await renderPage(<PaymentsPage />, { user: supervisorUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "全銀・被仕向" }));
      await user.click(main().getByRole("button", { name: "全銀データを作成" }));
      expect(await main().findByText(/全銀データ No\.5 を作成しました（1 件/)).toBeInTheDocument();
      await user.click(await main().findByRole("button", { name: "内容" }));
      expect(await main().findByText("全銀データ No.4（120バイト × 2 レコード）")).toBeInTheDocument();
      await user.click(main().getByRole("button", { name: "決済完了" }));
      expect(await main().findByText("決済完了を反映しました（2 件）")).toBeInTheDocument();
    });

    it("被仕向の入金データを解析して送り、保留分は口座を指定して入金・返却する", async () => {
      mockDefaults();
      mockApi({
        "/remit-incoming": [{ destAccountNo: "1000003", status: "CREDITED" }, { destAccountNo: "1000009", status: "HELD", note: "口座なし" }],
        "/remit-resolve": {},
      });
      const { user } = await renderPage(<PaymentsPage />, { user: supervisorUser, path: "/payments" });
      await user.click(main().getByRole("tab", { name: "全銀・被仕向" }));
      await user.type(main().getByPlaceholderText(/^0005,/), "0005,ｶ)ﾃｽﾄ,1000003,サトウ ハナコ,120000,ZG-1");
      await user.click(main().getByRole("button", { name: "入金処理" }));
      expect(await main().findByText("被仕向: 1000003 入金済、1000009 保留（口座なし）")).toBeInTheDocument();
      expect(lastBody("/remit-incoming").items[0]).toEqual({ bankCode: "0005", remitterNameKana: "ｶ)ﾃｽﾄ", accountNo: "1000003", nameKana: "サトウ ハナコ", amount: "120000", referenceNo: "ZG-1" });
      expect(await main().findByText("保留中（1 件）")).toBeInTheDocument();
      const credit = main().getByPlaceholderText("入金口座");
      expect(credit).toHaveValue("1000003");
      await user.clear(credit);
      await user.type(credit, "1000004");
      await user.click(main().getByRole("button", { name: "入金" }));
      await waitFor(() => expect(lastBody("/remit-resolve")).toEqual({ remitId: "9", decision: "CREDIT", accountNo: "1000004" }));
      await user.click(main().getByRole("button", { name: "返却" }));
      expect(await main().findByText("返却しました")).toBeInTheDocument();
    });
  });
});
