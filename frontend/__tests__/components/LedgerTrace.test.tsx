// components/LedgerTrace.tsx のテスト：ボタンで追跡情報を読み込み、明細と操作記録を対応付けて表示する。
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { LedgerTrace } from "@/components/LedgerTrace";
import { ApiFailure, callsTo, mockApi } from "../../test/api";

const items = [
  {
    txId: 11, txType: "DEPOSIT", amount: 5000, balanceAfter: 15000, counterpartyAccountNo: null, memo: null,
    createdAt: "2026-11-20T10:00:00", rowHash: "abcdef0123456789",
    operations: [{ auditId: 1, occurredAt: "2026-11-20T10:00:00", userId: "teller01", userRole: "TELLER", action: "DEPOSIT", clientIp: "10.0.0.1", outcome: "SUCCESS" }],
  },
  {
    txId: 10, txType: "INTEREST", amount: 12, balanceAfter: 10000, counterpartyAccountNo: null, memo: null,
    createdAt: "2026-09-01T01:00:00", rowHash: null, operations: [], origin: "夜間バッチ（利息決算）",
  },
];

describe("LedgerTrace", () => {
  it("自動では読み込まず、ボタンで取得して表示する", async () => {
    mockApi({ "/ledger-trace": items });
    render(<LedgerTrace accountNo="1000001" currency="JPY" />);
    expect(callsTo("/ledger-trace")).toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "追跡情報を表示" }));
    expect(await screen.findByText("teller01")).toBeInTheDocument();
    expect(screen.getByText("（窓口担当）")).toBeInTheDocument();
    expect(screen.getByText("abcdef0123…")).toBeInTheDocument();
    expect(screen.getByText("夜間バッチ（利息決算）")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "再読み込み" })).toBeInTheDocument();
    const cells = screen.getAllByRole("row")[1].querySelectorAll("td");
    expect(cells[3].textContent).toMatch(/^\+/);
  });

  it("エラーを表示する", async () => {
    mockApi({ "/ledger-trace": new ApiFailure(403, "FORBIDDEN", "権限がありません。") });
    render(<LedgerTrace accountNo="1000001" currency="JPY" />);
    await userEvent.click(screen.getByRole("button", { name: "追跡情報を表示" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("権限がありません。");
  });
});
