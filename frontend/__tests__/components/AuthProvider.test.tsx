// components/AuthProvider.tsx のテスト：ログイン状態の確認とリダイレクト、役割ごとのメニュー、
// セッション切れ・ログアウト、テーマ切り替え、画面単位の権限ガード（RequireRole）。
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AppShell, RequireRole, ThemeToggle, roleLabel, useAuth } from "@/components/AuthProvider";
import { UNAUTHENTICATED_EVENT } from "@/lib/api";
import { ApiFailure, callsTo, mockApi } from "../../test/api";
import { nav, setNav } from "../../test/nav";
import { customerUser, renderPage, supervisorUser, tellerUser } from "../../test/render";

function Who() {
  const { user } = useAuth();
  return <p>page:{user?.userId ?? "none"}</p>;
}

const navLinks = () => Array.from(document.querySelectorAll("nav.nav a")).map((a) => a.textContent);

describe("AppShell", () => {
  it("未ログインならログイン画面へ（元の画面を next に付けて）リダイレクトする", async () => {
    mockApi({ "/me": null });
    setNav("/transfer");
    render(<AppShell><Who /></AppShell>);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Ftransfer"));
    expect(screen.queryByText(/page:/)).not.toBeInTheDocument();
  });

  it("/me が失敗しても未ログイン扱いにする", async () => {
    mockApi({ "/me": new ApiFailure(500, "SYSTEM_ERROR", "x") });
    setNav("/");
    render(<AppShell><Who /></AppShell>);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2F"));
  });

  it("ログイン画面は未ログインでも表示する（外枠なし）", async () => {
    mockApi({ "/me": null });
    setNav("/login");
    render(<AppShell><Who /></AppShell>);
    expect(await screen.findByText("page:none")).toBeInTheDocument();
    expect(document.querySelector(".sidebar")).toBeNull();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("初期パスワードのままならパスワード変更画面へ移動させる", async () => {
    mockApi({ "/me": { ...customerUser, mustChangePassword: true } });
    setNav("/");
    render(<AppShell><Who /></AppShell>);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/password"));
    expect(screen.queryByText(/page:/)).not.toBeInTheDocument();
    expect(document.querySelector("nav.nav")).toBeNull();
  });

  it("お客さまは銀行業務メニューだけ", async () => {
    await renderPage(<Who />, { user: customerUser });
    expect(screen.getByText("page:cust01")).toBeInTheDocument();
    expect(navLinks()).toEqual(["ご利用口座", "振込・振替", "為替・口座振替", "預金商品", "融資"]);
    expect(screen.getByText(`${roleLabel.CUSTOMER} ・ cust01`)).toBeInTheDocument();
  });

  it("窓口担当には顧客・顧客分析が加わる", async () => {
    await renderPage(<Who />, { user: tellerUser });
    expect(navLinks()).toEqual(["口座一覧", "振込・振替", "為替・口座振替", "預金商品", "融資", "顧客・口座開設", "顧客分析"]);
  });

  it("役席には経営・帳票・管理メニューが加わり、現在の画面を強調する", async () => {
    await renderPage(<Who />, { user: supervisorUser, path: "/accounts/1000001" });
    expect(navLinks()).toEqual(expect.arrayContaining(["経営ダッシュボード", "帳票", "リスク管理", "AML", "業務処理", "銀行バッチ", "監査ログ"]));
    expect(screen.getByRole("link", { name: "口座一覧" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "融資" })).not.toHaveAttribute("aria-current");
  });

  it("セッション切れの通知で未ログイン状態に戻す", async () => {
    await renderPage(<Who />, { user: tellerUser, path: "/transfer" });
    act(() => {
      window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
    });
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Ftransfer"));
  });

  it("ログアウトでセッションを消してログイン画面へ戻る", async () => {
    const { user } = await renderPage(<Who />, { user: tellerUser });
    mockApi({ "/logout": null });
    await user.click(screen.getByRole("button", { name: "ログアウト" }));
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login"));
    expect(callsTo("/logout")).toHaveLength(1);
  });

  it("ログアウト API が失敗してもエラーにせず、ログイン画面へ戻る", async () => {
    const { user } = await renderPage(<Who />, { user: tellerUser });
    mockApi({ "/logout": new ApiFailure(500, "SYSTEM_ERROR", "x") });
    await user.click(screen.getByRole("button", { name: "ログアウト" }));
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login"));
    expect(callsTo("/logout")).toHaveLength(1);
  });

  it("利用手順ボタンでパネルを開閉する", async () => {
    const { user } = await renderPage(<Who />, { user: tellerUser });
    expect(screen.queryByRole("dialog", { name: "利用手順" })).not.toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "利用手順" })[0]);
    expect(screen.getByRole("dialog", { name: "利用手順" })).toBeInTheDocument();
  });

  it("スマートフォン表示のメニューを開閉できる", async () => {
    const { user } = await renderPage(<Who />, { user: tellerUser });
    const open = screen.getByRole("button", { name: "メニューを開く" });
    await user.click(open);
    expect(document.querySelector(".sidebar")).toHaveAttribute("data-open", "true");
    await user.click(document.querySelector(".scrim") as HTMLElement);
    expect(document.querySelector(".sidebar")).toHaveAttribute("data-open", "false");
  });
});

describe("ThemeToggle", () => {
  it("OS 設定に従い、切り替えると保存する", async () => {
    render(<ThemeToggle />);
    const btn = screen.getByRole("button", { name: "ダークモードにする" });
    await userEvent.click(btn);
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem("theme")).toBe("dark");
    expect(screen.getByRole("button", { name: "ライトモードにする" })).toBeInTheDocument();
  });

  it("保存済みのテーマを適用する", () => {
    localStorage.setItem("theme", "dark");
    render(<ThemeToggle />);
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(screen.getByRole("button", { name: "ライトモードにする" })).toBeInTheDocument();
  });

  it("localStorage が使えなくても切り替えられる", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    render(<ThemeToggle />);
    await userEvent.click(screen.getByRole("button", { name: "ダークモードにする" }));
    expect(document.documentElement.dataset.theme).toBe("dark");
  });
});

describe("RequireRole / useAuth", () => {
  it("許可された役割だけ中身を表示する", async () => {
    await renderPage(<RequireRole roles={["SUPERVISOR"]}><p>secret</p></RequireRole>, { user: tellerUser });
    expect(screen.getByRole("alert")).toHaveTextContent("この画面を利用する権限がありません。");
    expect(screen.queryByText("secret")).not.toBeInTheDocument();
  });

  it("役割が合えば中身を表示する", async () => {
    await renderPage(<RequireRole roles={["SUPERVISOR"]}><p>secret</p></RequireRole>, { user: supervisorUser });
    expect(screen.getByText("secret")).toBeInTheDocument();
  });

  it("AppShell の外で useAuth を使うとエラー", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    expect(() => render(<Who />)).toThrow("useAuth outside AuthProvider");
  });
});
