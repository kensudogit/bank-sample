// components/Icons.tsx のテスト：全アイコンが SVG として描画され、title の有無で装飾／画像を切り替える。
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import * as Icons from "@/components/Icons";

const all = Object.entries(Icons) as [string, (p: { size?: number; title?: string }) => React.ReactElement][];

describe("Icons", () => {
  it("すべてのアイコンを export している", () => {
    expect(all.length).toBeGreaterThanOrEqual(25);
    expect(all.every(([name]) => name.startsWith("Icon"))).toBe(true);
  });

  it.each(all)("%s は 24px グリッドの SVG を描き、既定は装飾扱い（aria-hidden）", (_name, Icon) => {
    const { container } = render(<Icon size={16} />);
    const svg = container.querySelector("svg")!;
    expect(svg).toHaveAttribute("viewBox", "0 0 24 24");
    expect(svg).toHaveAttribute("width", "16");
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(svg.childElementCount).toBeGreaterThan(0);
  });

  it("title を渡すと画像として読み上げられる", () => {
    render(<Icons.IconBank title="銀行" />);
    const img = screen.getByRole("img", { name: "銀行" });
    expect(img).not.toHaveAttribute("aria-hidden");
    expect(img).toHaveAttribute("width", "20");
  });
});
