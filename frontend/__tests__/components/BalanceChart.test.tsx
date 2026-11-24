// components/BalanceChart.tsx のテスト：期間ごとの明細取得、階段状の残高線、期間タブ、ホバー表示。
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { BalanceChart } from "@/components/BalanceChart";
import { ApiFailure, callsTo, mockApi } from "../../test/api";

function txPage(hasNext = false) {
  return {
    items: [
      { txId: 2, accountNo: "1000001", txType: "DEPOSIT", amount: 50000, balanceAfter: 150000, counterpartyAccountNo: null, memo: null, createdAt: "2026-11-10T10:00:00" },
      { txId: 1, accountNo: "1000001", txType: "WITHDRAWAL", amount: 20000, balanceAfter: 100000, counterpartyAccountNo: null, memo: null, createdAt: "2026-10-01T10:00:00" },
    ],
    page: 1, size: 200, total: 2, hasNext, openingBalance: 120000, closingBalance: 150000, totalIn: 50000, totalOut: 20000,
  };
}

describe("BalanceChart", () => {
  it("既定は 90 日で、期首残高から現在残高までの線を描く", async () => {
    mockApi({ "/transactions": txPage() });
    render(<BalanceChart accountNo="1000001" currentBalance={150000} />);
    const svg = await screen.findByRole("img", { name: /残高推移（90日）/ });
    expect(svg.getAttribute("aria-label")).toMatch(/120,000 から .*150,000/);
    const line = svg.querySelector("path.line")!.getAttribute("d")!;
    // 開始点 + 2 取引 + 現在 = 3 回の水平移動
    expect(line.match(/H/g)).toHaveLength(3);
    expect(screen.getByRole("tab", { name: "90日" })).toHaveAttribute("aria-selected", "true");
    expect(callsTo("/transactions")[0].query.get("size")).toBe("200");
  });

  it("期間タブを切り替えると開始日を変えて取得し直す", async () => {
    mockApi({ "/transactions": txPage() });
    render(<BalanceChart accountNo="1000001" currentBalance={150000} />);
    await screen.findByRole("img", { name: /90日/ });
    await userEvent.click(screen.getByRole("tab", { name: "1年" }));
    await screen.findByRole("img", { name: /1年/ });
    const froms = callsTo("/transactions").map((c) => c.query.get("from"));
    expect(new Set(froms).size).toBe(2);
  });

  it("件数が多いときは直近 200 件の範囲である旨を表示する", async () => {
    mockApi({ "/transactions": txPage(true) });
    render(<BalanceChart accountNo="1000001" currentBalance={150000} />);
    expect(await screen.findByText(/直近200件の範囲/)).toBeInTheDocument();
  });

  it("ホバーでその時点の残高をツールチップ表示し、離れると消す", async () => {
    mockApi({ "/transactions": txPage() });
    const { container } = render(<BalanceChart accountNo="1000001" currentBalance={150000} />);
    await screen.findByRole("img", { name: /90日/ });
    const hit = container.querySelector("svg rect")!;
    fireEvent.pointerMove(hit, { clientX: 10_000 });
    expect(container.querySelector(".chart-tooltip")).toHaveTextContent("現在");
    expect(container.querySelector(".chart-tooltip")).toHaveTextContent("150,000");
    fireEvent.pointerLeave(hit);
    expect(container.querySelector(".chart-tooltip")).toBeNull();
  });

  it("取得エラーを表示する", async () => {
    mockApi({ "/transactions": new ApiFailure(500, "SYSTEM_ERROR", "障害が発生しました。") });
    render(<BalanceChart accountNo="1000001" currentBalance={0} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("障害が発生しました。");
  });
});
