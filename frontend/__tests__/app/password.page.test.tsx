// app/password/page.tsx のテスト：確認入力の一致チェック、変更成功時の遷移、初期パスワードの案内。
import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import PasswordPage from "@/app/password/page";
import { ApiFailure, callsTo, lastBody, mockApi } from "../../test/api";
import { nav } from "../../test/nav";
import { customerUser, renderPage } from "../../test/render";

async function fill(next: string, confirm: string) {
  const r = await renderPage(<PasswordPage />, { user: customerUser, path: "/password" });
  await r.user.type(screen.getByLabelText("現在のパスワード"), "OldPass2025");
  await r.user.type(screen.getAllByLabelText(/^新しいパスワード/)[0], next);
  await r.user.type(screen.getByLabelText("新しいパスワード（確認）"), confirm);
  await r.user.click(screen.getByRole("button", { name: "変更する" }));
  return r;
}

describe("PasswordPage", () => {
  it("確認入力が一致しなければ送信しない", async () => {
    await fill("NewPass2026x", "NewPass2026y");
    expect(screen.getByRole("alert")).toHaveTextContent("新しいパスワード（確認）が一致しません。");
    expect(callsTo("/password-change")).toHaveLength(0);
  });

  it("変更に成功したら入力を消してトップへ移動する", async () => {
    mockApi({ "/password-change": customerUser });
    await fill("NewPass2026x", "NewPass2026x");
    expect(await screen.findByText("パスワードを変更しました。")).toBeInTheDocument();
    expect(lastBody("/password-change")).toEqual({ currentPassword: "OldPass2025", newPassword: "NewPass2026x" });
    expect(screen.getByLabelText("現在のパスワード")).toHaveValue("");
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });

  it("サーバーのエラー（強度不足など）を表示する", async () => {
    mockApi({ "/password-change": new ApiFailure(400, "WEAK_PASSWORD", "英字と数字を含めてください。") });
    await fill("aaaaaaaaaa", "aaaaaaaaaa");
    expect(await screen.findByRole("alert")).toHaveTextContent("英字と数字を含めてください。");
  });

  it("初期パスワードの利用者には変更を促す", async () => {
    await renderPage(<PasswordPage />, { user: { ...customerUser, mustChangePassword: true }, path: "/password" });
    await screen.findByText(/初期パスワードのため/);
  });
});
