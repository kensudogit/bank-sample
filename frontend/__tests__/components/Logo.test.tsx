// components/Logo.tsx のテスト：ロゴ画像の表示と、読み込み失敗時のアイコン代替。
import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Logo } from "@/components/Logo";

describe("Logo", () => {
  it("public/logo.png を指定サイズで表示する", () => {
    const { container } = render(<Logo size={40} />);
    const img = container.querySelector("img")!;
    expect(img).toHaveAttribute("src", "/logo.png");
    expect(img).toHaveAttribute("width", "40");
    expect(img).toHaveAttribute("alt", "");
  });

  it("画像が読めなければ銀行アイコンに切り替える", () => {
    const { container } = render(<Logo />);
    fireEvent.error(container.querySelector("img")!);
    expect(container.querySelector("img")).toBeNull();
    const mark = container.querySelector(".brand-mark") as HTMLElement;
    expect(mark).toHaveStyle({ width: "34px", height: "34px" });
    expect(mark.querySelector("svg")).toHaveAttribute("width", String(Math.round(34 * 0.53)));
  });
});
