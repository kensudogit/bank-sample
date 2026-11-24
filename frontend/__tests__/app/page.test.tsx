// app/page.tsx（ホーム・口座一覧）のテスト：お客さまは口座タイル、行員は検索・ページ送り付きの一覧表。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import DashboardPage from "@/app/page";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";
import { account, accountPage, customerUser, main, renderPage, tellerUser } from "../../test/render";

describe("DashboardPage", () => {
  it("お客さま：時間帯の挨拶・総残高・口座タイルを表示する", async () => {
    vi.spyOn(Date.prototype, "getHours").mockReturnValue(9);
    mockApi({
      "/accounts": accountPage([
        account({ accountNo: "1000001", balance: 100000 }),
        account({ accountNo: "3000001", accountType: "FOREIGN", currency: "USD", balance: 12345, status: "DORMANT" }),
      ]),
    });
    await renderPage(<DashboardPage />, { user: customerUser });
    expect(await main().findByRole("heading", { name: "おはようございます、山田 太郎 さま" })).toBeInTheDocument();
    expect(screen.getByText("総残高")).toBeInTheDocument();
    const tile = screen.getByRole("link", { name: /No\. 3000001/ });
    expect(tile).toHaveAttribute("href", "/accounts/3000001");
    expect(tile).toHaveTextContent("外貨預金 USD");
    expect(tile).toHaveTextContent("123.45");
    expect(screen.getByText("利用可能").querySelector("strong")).toHaveTextContent("1");
    expect(screen.queryByRole("link", { name: /口座開設の申込/ })).not.toBeInTheDocument();
  });

  it("お客さま：タイルにマウスを乗せると口座詳細を先読みする", async () => {
    mockApi({ "/accounts": accountPage([account()]), "/account": { account: account(), transactions: [] } });
    await renderPage(<DashboardPage />, { user: customerUser });
    fireEvent.mouseEnter(await screen.findByRole("link", { name: /No\. 1000001/ }));
    await waitFor(() => expect(callsTo("/account")).toHaveLength(1));
  });

  it("お客さま：口座が無ければその旨を表示する", async () => {
    mockApi({ "/accounts": accountPage([]) });
    await renderPage(<DashboardPage />, { user: customerUser });
    expect(await screen.findByText("ご利用の口座はありません。")).toBeInTheDocument();
  });

  it("行員：一覧表・検索・クリア", async () => {
    mockApi({
      "/accounts": (r: MockRequest) =>
        accountPage(r.query.get("q") === "999" ? [] : [account(), account({ accountNo: "1000002", customerName: "佐藤 花子" })]),
    });
    const { user } = await renderPage(<DashboardPage />, { user: tellerUser });
    expect(await screen.findByRole("link", { name: "1000002" })).toBeInTheDocument();
    expect(screen.getByText("全口座の残高合計")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /口座開設の申込/ })).toHaveAttribute("href", "/customers/new");
    await user.type(screen.getByLabelText("口座を検索"), " 999 ");
    await user.click(screen.getByRole("button", { name: "検索" }));
    expect(await screen.findByText("該当する口座はありません。")).toBeInTheDocument();
    expect(screen.getByText("「999」に該当する口座の残高合計")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "クリア" }));
    expect(await screen.findByRole("link", { name: "1000002" })).toBeInTheDocument();
    expect(screen.getByLabelText("口座を検索")).toHaveValue("");
  });

  it("行員：次ページを先読みし、ページ送りできる", async () => {
    mockApi({
      "/accounts": (r: MockRequest) => {
        const p = Number(r.query.get("page"));
        return { ...accountPage([account({ accountNo: p === 1 ? "1000001" : "1000051" })]), page: p, total: 60, hasNext: p === 1 };
      },
    });
    const { user } = await renderPage(<DashboardPage />, { user: tellerUser });
    await screen.findByText("1 / 2 ページ");
    await waitFor(() => expect(callsTo("/accounts").some((c) => c.query.get("page") === "2")).toBe(true));
    await user.click(screen.getByRole("button", { name: "次へ" }));
    expect(await screen.findByRole("link", { name: "1000051" })).toBeInTheDocument();
    expect(screen.getByText("2 / 2 ページ")).toBeInTheDocument();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/accounts": new ApiFailure(500, "SYSTEM_ERROR", "障害が発生しました。") });
    await renderPage(<DashboardPage />, { user: tellerUser });
    expect(await screen.findByRole("alert")).toHaveTextContent("障害が発生しました。");
  });
});
