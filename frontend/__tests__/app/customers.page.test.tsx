// app/customers/page.tsx のテスト：顧客一覧（行員のみ）と本人確認待ちの件数。
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import CustomersPage from "@/app/customers/page";
import { ApiFailure, mockApi } from "../../test/api";
import { customerUser, renderPage, tellerUser } from "../../test/render";

const customers = [
  { customerId: "CU0001", name: "山田 太郎", nameKana: "ヤマダ タロウ", birthDate: "1980-04-01", idDocType: "DRIVER_LICENSE", idDocLast4: "1234", kycStatus: "VERIFIED", createdAt: "2024-04-01T09:00:00" },
  { customerId: "CU0009", name: "新規 花子", nameKana: null, birthDate: null, idDocType: null, idDocLast4: null, kycStatus: "PENDING", createdAt: "2026-11-20T09:00:00" },
];

describe("CustomersPage", () => {
  it("顧客一覧と本人確認待ちの件数を表示する", async () => {
    mockApi({ "/customers": customers });
    await renderPage(<CustomersPage />, { user: tellerUser, path: "/customers" });
    expect(await screen.findByRole("link", { name: "CU0001" })).toHaveAttribute("href", "/customers/CU0001");
    expect(screen.getByText("運転免許証（下4桁 1234）")).toBeInTheDocument();
    expect(screen.getByText("本人確認待ち 1 件")).toBeInTheDocument();
    expect(screen.getByText("確認待ち")).toHaveClass("badge-PENDING");
    expect(screen.getByRole("link", { name: "口座開設の申込" })).toHaveAttribute("href", "/customers/new");
  });

  it("お客さまには表示しない", async () => {
    await renderPage(<CustomersPage />, { user: customerUser, path: "/customers" });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/customers": new ApiFailure(500, "SYSTEM_ERROR", "障害が発生しました。") });
    await renderPage(<CustomersPage />, { user: tellerUser, path: "/customers" });
    expect(await screen.findByRole("alert")).toHaveTextContent("障害が発生しました。");
  });
});
