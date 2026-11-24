// app/login/page.tsx のテスト：ログイン処理、遷移先（next）の検証、失敗時のパスワード消去。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import LoginPage from "@/app/login/page";
import { ApiFailure, lastBody, mockApi } from "../../test/api";
import { nav } from "../../test/nav";
import { renderPage, tellerUser } from "../../test/render";

async function login(search = "", loginResult: unknown = tellerUser) {
  mockApi({ "/login": loginResult });
  const r = await renderPage(<LoginPage />, { user: null, path: "/login", search });
  await r.user.type(screen.getByLabelText("利用者ID"), "  teller01 ");
  await r.user.type(screen.getByLabelText("パスワード"), "secret");
  await r.user.click(screen.getByRole("button", { name: "ログイン" }));
  return r;
}

describe("LoginPage", () => {
  it("利用者 ID の前後空白を除いてログインし、next の画面へ移動する", async () => {
    await login("next=/transfer");
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/transfer"));
    expect(lastBody("/login")).toEqual({ userId: "teller01", password: "secret" });
  });

  it.each([
    ["next=//evil.example.com", "外部サイト"],
    ["next=https://evil.example.com", "絶対 URL"],
    ["next=/login", "ログイン画面自身"],
  ])("%s（%s）はトップへ移動する（オープンリダイレクト対策）", async (search) => {
    await login(search);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });

  it("初期パスワードならパスワード変更画面へ移動する", async () => {
    await login("", { ...tellerUser, mustChangePassword: true });
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/password"));
  });

  it("失敗したらエラーを出し、パスワード欄を空にする", async () => {
    await login("", new ApiFailure(401, "BAD_CREDENTIALS", "利用者IDまたはパスワードが違います。"));
    expect(await screen.findByRole("alert")).toHaveTextContent("利用者IDまたはパスワードが違います。");
    expect(screen.getByLabelText("パスワード")).toHaveValue("");
    expect(screen.getByLabelText("利用者ID")).toHaveValue("  teller01 ");
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("ログイン済みで開いたら元の画面へ戻す", async () => {
    await renderPage(<LoginPage />, { user: tellerUser, path: "/login", search: "next=/loans" });
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/loans"));
  });
});
