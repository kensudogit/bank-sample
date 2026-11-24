// components/TrendChart.tsx のテスト：系列の折れ線・凡例・軸ラベル・ホバー・スクリーンリーダー用の表。
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TrendChart } from "@/components/TrendChart";

const dates = ["2026-11-01", "2026-11-02", "2026-11-03"];
const series = [
  { label: "預金残高", values: [120_000_000, 125_000_000, 130_000_000] },
  { label: "貸出残高", values: [80_000_000, 81_000_000, 79_000_000] },
];

describe("TrendChart", () => {
  it("データが無ければメッセージを出す", () => {
    render(<TrendChart dates={[]} series={[]} title="残高推移" />);
    expect(screen.getByText("取込済みのデータがありません。")).toBeInTheDocument();
  });

  it("系列ごとの折れ線と右端ラベル、期間付きの aria-label を描く", () => {
    const { container } = render(<TrendChart dates={dates} series={series} title="残高推移" />);
    expect(screen.getByRole("img", { name: "残高推移（2026-11-01 〜 2026-11-03）" })).toBeInTheDocument();
    expect(container.querySelector("path.line-1")!.getAttribute("d")!.match(/[ML]/g)).toHaveLength(3);
    expect(container.querySelector("path.line-2")).not.toBeNull();
    expect(Array.from(container.querySelectorAll(".end-label")).map((e) => e.textContent)).toEqual(["預金残高", "貸出残高"]);
    // x 軸は先頭・中央・末尾の日付だけ
    const axis = Array.from(container.querySelectorAll(".axis text")).map((t) => t.textContent);
    expect(axis).toEqual(expect.arrayContaining(["11/01", "11/02", "11/03"]));
    expect(axis.some((t) => t?.endsWith("億"))).toBe(true);
  });

  it("スクリーンリーダー用に全データを表で出力する", () => {
    render(<TrendChart dates={dates} series={series} title="残高推移" />);
    const table = screen.getByRole("table", { name: "残高推移" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(4);
    expect(rows[1]).toHaveTextContent("2026-11-01120000000");
  });

  it("ホバーで十字線とツールチップを出し、離れると消す", () => {
    const { container } = render(<TrendChart dates={dates} series={series} title="残高推移" />);
    const svg = container.querySelector("svg")!;
    svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 720, height: 240, right: 720, bottom: 240, x: 0, y: 0, toJSON: () => ({}) });
    fireEvent.pointerMove(svg, { clientX: 720 });
    const tip = container.querySelector(".chart-tooltip")!;
    expect(tip).toHaveTextContent("2026-11-03");
    expect(tip).toHaveTextContent("130,000,000");
    expect(container.querySelectorAll("circle")).toHaveLength(2);
    fireEvent.pointerLeave(svg);
    expect(container.querySelector(".chart-tooltip")).toBeNull();
  });

  it("1 日分・値が一定でも描画できる", () => {
    const { container } = render(<TrendChart dates={["2026-11-01"]} series={[{ label: "件数", values: [5] }]} title="件数" />);
    expect(container.querySelector("path.line-1")!.getAttribute("d")).toMatch(/^M[\d.]+,[\d.]+$/);
  });
});
