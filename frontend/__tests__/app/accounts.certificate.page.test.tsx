// app/accounts/[accountNo]/certificate/page.tsx のテスト：指定日の残高証明書の表示・日付変更・印刷。
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import CertificatePage from "@/app/accounts/[accountNo]/certificate/page";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";
import { customerUser, main, renderPage } from "../../test/render";

const cert = (r: MockRequest) => ({
  issuedAt: "2026-11-23T10:00:00", name: "山田 太郎", address: "東京都千代田区1-1", asOf: r.query.get("date"),
  accountNo: "1000001", accountType: "ORDINARY", currency: "JPY", customerId: "CU0001", balance: r.query.get("date") === "2026-11-01" ? 1250000 : 1219262,
});

async function open() {
  return renderPage(<CertificatePage />, { user: customerUser, path: "/accounts/1000001/certificate", params: { accountNo: "1000001" } });
}

describe("CertificatePage", () => {
  it("今日時点の残高証明書を表示する", async () => {
    mockApi({ "/balance-certificate": cert });
    await open();
    expect(await main().findByText("山田 太郎")).toBeInTheDocument();
    expect(screen.getByText(/1,219,262/)).toBeInTheDocument();
    expect(screen.getByText("普通（JPY）")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /口座 1000001/ })).toHaveAttribute("href", "/accounts/1000001");
    expect(callsTo("/balance-certificate")[0].query.get("accountNo")).toBe("1000001");
  });

  it("証明する日を変えると、その日の残高と「yyyy年M月d日」表記で出し直す", async () => {
    mockApi({ "/balance-certificate": cert });
    await open();
    await main().findByText("山田 太郎");
    fireEvent.change(screen.getByLabelText("証明する日"), { target: { value: "2026-11-01" } });
    expect(await screen.findByText(/1,250,000/)).toBeInTheDocument();
    expect(screen.getByText(/2026年11月1日 現在の預金残高/)).toBeInTheDocument();
  });

  it("印刷ボタンでブラウザの印刷を呼ぶ", async () => {
    mockApi({ "/balance-certificate": cert });
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    const { user } = await open();
    const btn = screen.getByRole("button", { name: "印刷" });
    await waitFor(() => expect(btn).toBeEnabled());
    await user.click(btn);
    expect(print).toHaveBeenCalled();
  });

  it("他人の口座などはエラーを表示し、印刷できない", async () => {
    mockApi({ "/balance-certificate": new ApiFailure(404, "ACCOUNT_NOT_FOUND", "口座が見つかりません。") });
    await open();
    expect(await screen.findByRole("alert")).toHaveTextContent("口座が見つかりません。");
    expect(screen.getByRole("button", { name: "印刷" })).toBeDisabled();
  });
});
