// app/customers/new/page.tsx のテスト：口座開設の申込（個人・法人の切り替え、入力の正規化、冪等キー）。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import NewCustomerPage from "@/app/customers/new/page";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { nav } from "../../test/nav";
import { customerUser, main, renderPage, tellerUser } from "../../test/render";

async function fillForm(user: Awaited<ReturnType<typeof renderPage>>["user"]) {
  await user.type(main().getByLabelText("氏名"), " 新規 花子 ");
  await user.type(main().getByLabelText(/^フリガナ/), "シンキ ハナコ");
  await user.type(main().getByLabelText(/^生年月日/), "1990-01-01");
  await user.type(main().getByLabelText("電話番号"), "０９０－１２３４－５６７８");
  await user.type(main().getByLabelText("郵便番号"), "１００ー０００１");
  await user.type(main().getByLabelText("住所"), "東京都千代田区1-1");
  await user.selectOptions(main().getByLabelText("本人確認書類"), "PASSPORT");
  await user.type(main().getByLabelText(/^書類の番号/), "tk１２３４５６７");
}

describe("NewCustomerPage", () => {
  it("お客さまには表示しない", async () => {
    await renderPage(<NewCustomerPage />, { user: customerUser, path: "/customers/new" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("入力を正規化して登録し、顧客詳細へ移動する", async () => {
    mockApi({ "/customer-register": { customerId: "CU0100" } });
    const { user } = await renderPage(<NewCustomerPage />, { user: tellerUser, path: "/customers/new" });
    await fillForm(user);
    await user.click(main().getByRole("button", { name: "申込を登録する" }));
    await waitFor(() => expect(nav.router.push).toHaveBeenCalledWith("/customers/CU0100"));
    const body = lastBody("/customer-register");
    expect(body).toMatchObject({
      customerType: "INDIVIDUAL", name: "新規 花子", nameKana: "シンキ ハナコ", birthDate: "1990-01-01",
      phone: "09012345678", postalCode: "100-0001", idDocType: "PASSPORT", idDocNumber: "TK1234567",
    });
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("法人タブでは項目名を商号・設立日に変える", async () => {
    const { user } = await renderPage(<NewCustomerPage />, { user: tellerUser, path: "/customers/new" });
    await user.click(main().getByRole("tab", { name: "法人" }));
    expect(main().getByLabelText("商号")).toBeInTheDocument();
    expect(main().getByLabelText(/^設立日/)).toBeInTheDocument();
    expect(main().getByText("登記事項証明書で確認")).toBeInTheDocument();
  });

  it("エラーを表示し、DUPLICATE_REQUEST なら冪等キーを作り直して再送できる", async () => {
    mockApi({ "/customer-register": new ApiFailure(409, "DUPLICATE_REQUEST", "同じ申込を処理中です。") });
    const { user } = await renderPage(<NewCustomerPage />, { user: tellerUser, path: "/customers/new" });
    await fillForm(user);
    await user.click(main().getByRole("button", { name: "申込を登録する" }));
    expect(await main().findByRole("alert")).toHaveTextContent("同じ申込を処理中です。");
    const first = lastBody("/customer-register").idempotencyKey;
    await user.click(main().getByRole("button", { name: "申込を登録する" }));
    await waitFor(() => expect(callsTo("/customer-register")).toHaveLength(2));
    expect(lastBody("/customer-register").idempotencyKey).not.toBe(first);
    expect(nav.router.push).not.toHaveBeenCalled();
  });
});
