// components/TransactionHistory.tsx のテスト：明細の表示、期間・種別の絞り込み、ページ送り、CSV 出力。
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TransactionHistory } from "@/components/TransactionHistory";
import { ApiFailure, callsTo, mockApi, type MockRequest } from "../../test/api";

function tx(txId: number, txType: string, amount: number, memo: string | null = null) {
  return { txId, accountNo: "1000001", txType, amount, balanceAfter: 100000, counterpartyAccountNo: null, memo, createdAt: "2026-11-20T10:00:00" };
}

function page(req: MockRequest) {
  const p = Number(req.query.get("page") ?? 1);
  return {
    items: p === 1 ? [tx(2, "DEPOSIT", 5000, "給与"), tx(1, "FEE", 330)] : [tx(3, "WITHDRAWAL", 1000)],
    page: p, size: 20, total: 25, hasNext: p === 1, openingBalance: 90000, closingBalance: 100000, totalIn: 5000, totalOut: 330,
  };
}

describe("TransactionHistory", () => {
  it("明細と入出金合計を表示し、入金は +、出金は − で示す", async () => {
    mockApi({ "/transactions": page });
    render(<TransactionHistory accountNo="1000001" />);
    expect(await screen.findByText("給与")).toBeInTheDocument();
    const rows = screen.getAllByRole("row");
    expect(rows[1]).toHaveTextContent("+");
    expect(rows[2]).toHaveTextContent("−");
    expect(screen.getByText(/全 25 件 ／ 1 \/ 2 ページ/)).toBeInTheDocument();
    expect(screen.getByText("期首残高").nextElementSibling).toHaveTextContent("—");
  });

  it("次ページを先読みし、ページ送りできる", async () => {
    mockApi({ "/transactions": page });
    render(<TransactionHistory accountNo="1000001" />);
    await screen.findByText("給与");
    await waitFor(() => expect(callsTo("/transactions").some((c) => c.query.get("page") === "2")).toBe(true));
    await userEvent.click(screen.getByRole("button", { name: "次へ" }));
    expect(await screen.findByText(/2 \/ 2 ページ/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "次へ" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "前へ" }));
    expect(await screen.findByText("給与")).toBeInTheDocument();
  });

  it("検索ボタンで期間・種別の条件を確定し、条件クリアで戻す", async () => {
    mockApi({ "/transactions": page });
    const { container } = render(<TransactionHistory accountNo="1000001" />);
    await screen.findByText("給与");
    const from = container.querySelector('input[type="date"]') as HTMLInputElement;
    await userEvent.type(from, "2026-11-01");
    await userEvent.click(screen.getByRole("checkbox", { name: "手数料" }));
    const before = callsTo("/transactions").length;
    await userEvent.click(screen.getByRole("button", { name: "検索" }));
    await waitFor(() => expect(callsTo("/transactions").length).toBeGreaterThan(before));
    const q = callsTo("/transactions").find((c) => c.query.get("types") === "FEE")!.query;
    expect(q.get("from")).toBe("2026-11-01");
    expect(await screen.findByText("期首残高（2026-11-01 開始時）")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "条件クリア" }));
    expect(screen.getByRole("checkbox", { name: "手数料" })).not.toBeChecked();
    expect(await screen.findByText("期首残高")).toBeInTheDocument();
  });

  it("CSV 出力は全ページを取得し、入金額・出金額を別列にする", async () => {
    mockApi({ "/transactions": page });
    let blob: Blob | undefined;
    vi.spyOn(URL, "createObjectURL").mockImplementation((b) => ((blob = b as Blob), "blob:x"));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(<TransactionHistory accountNo="1000001" />);
    await screen.findByText("給与");
    await userEvent.click(screen.getByRole("button", { name: "CSV出力" }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    const csvCalls = callsTo("/transactions").filter((c) => c.query.get("size") === "200");
    expect(csvCalls.map((c) => c.query.get("page"))).toEqual(["1", "2"]);
    const text = await new Promise<string>((resolve) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result));
      r.readAsText(blob!);
    });
    const lines = text.split("\r\n");
    expect(lines[0]).toContain("取引ID,日時,取引種別,入金額,出金額");
    expect(lines[1]).toBe("2,2026-11-20 10:00:00,入金,5000,,100000,,給与");
    expect(lines[2]).toBe("1,2026-11-20 10:00:00,手数料,,330,100000,,");
    expect(lines).toHaveLength(4);
  });

  it("明細が無ければメッセージを出し、CSV 出力を無効にする", async () => {
    mockApi({ "/transactions": { items: [], page: 1, size: 20, total: 0, hasNext: false, openingBalance: 0, closingBalance: 0, totalIn: 0, totalOut: 0 } });
    render(<TransactionHistory accountNo="1000001" />);
    expect(await screen.findByText("該当する明細はありません。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "CSV出力" })).toBeDisabled();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/transactions": new ApiFailure(400, "INVALID_PERIOD", "期間の指定が正しくありません。") });
    render(<TransactionHistory accountNo="1000001" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("期間の指定が正しくありません。");
  });
});
