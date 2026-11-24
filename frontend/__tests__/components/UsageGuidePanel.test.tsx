// components/UsageGuidePanel.tsx のテスト：開閉状態の保存（useGuideOpen）、本文の開閉、Esc で閉じる、ドラッグ移動。
import { act, fireEvent, render, renderHook, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { UsageGuidePanel, useGuideOpen } from "@/components/UsageGuidePanel";

describe("useGuideOpen", () => {
  it("初回（未保存）は開いた状態にする", () => {
    const { result } = renderHook(() => useGuideOpen());
    expect(result.current[0]).toBe(true);
  });

  it("閉じた状態を localStorage に保存し、次回も閉じたまま", () => {
    const first = renderHook(() => useGuideOpen());
    act(() => first.result.current[1](false));
    expect(localStorage.getItem("bank-sample.guide.open")).toBe("0");
    const second = renderHook(() => useGuideOpen());
    expect(second.result.current[0]).toBe(false);
  });

  it("localStorage が使えなくても切り替えられる", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    const { result } = renderHook(() => useGuideOpen());
    act(() => result.current[1](false));
    expect(result.current[0]).toBe(false);
  });
});

describe("UsageGuidePanel", () => {
  it("閉じているときは何も描画しない", () => {
    const { container } = render(<UsageGuidePanel open={false} onClose={() => undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("構成・役割・各機能の手順を表示する", () => {
    render(<UsageGuidePanel open onClose={() => undefined} />);
    const dialog = screen.getByRole("dialog", { name: "利用手順" });
    expect(dialog).toHaveAttribute("aria-modal", "false");
    expect(screen.getByText("1. 起動とログイン")).toBeInTheDocument();
    expect(screen.getByText("12. 銀行バッチ（役席）")).toBeInTheDocument();
    expect(screen.getByText("13. 外部連携（Kong・MuleSoft・役席）")).toBeInTheDocument();
    expect(screen.getByText("14. 停止・初期化・環境メモ")).toBeInTheDocument();
  });

  it("▼▲ で本文を折りたたみ・展開する", async () => {
    render(<UsageGuidePanel open onClose={() => undefined} />);
    const toggle = screen.getByRole("button", { name: "折りたたむ" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(toggle);
    expect(screen.getByRole("button", { name: "開く" })).toHaveAttribute("aria-expanded", "false");
  });

  it("× ボタンと Esc キーで閉じる", async () => {
    const onClose = vi.fn();
    render(<UsageGuidePanel open onClose={onClose} />);
    await userEvent.click(screen.getByRole("button", { name: "閉じる" }));
    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.keyDown(window, { key: "Enter" });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("見出しをドラッグすると画面内に収まる位置へ移動する", () => {
    render(<UsageGuidePanel open onClose={() => undefined} />);
    const dialog = screen.getByRole("dialog");
    dialog.getBoundingClientRect = () => ({ left: 500, top: 400, width: 420, height: 300, right: 920, bottom: 700, x: 500, y: 400, toJSON: () => ({}) });
    const header = dialog.querySelector("header")!;
    fireEvent.pointerDown(header, { pointerId: 1, button: 0, clientX: 510, clientY: 410 });
    fireEvent.pointerMove(header, { pointerId: 1, clientX: 110, clientY: 60 });
    expect(dialog).toHaveStyle({ left: "100px", top: "50px" });
    // 画面外（マイナス座標）へは出ない
    fireEvent.pointerMove(header, { pointerId: 1, clientX: -500, clientY: -500 });
    expect(dialog).toHaveStyle({ left: "0px", top: "0px" });
    fireEvent.pointerUp(header, { pointerId: 1 });
    // ドラッグ終了後の移動は無視する
    fireEvent.pointerMove(header, { pointerId: 1, clientX: 300, clientY: 300 });
    expect(dialog).toHaveStyle({ left: "0px", top: "0px" });
  });

  it("ボタン上ではドラッグを開始しない", () => {
    render(<UsageGuidePanel open onClose={() => undefined} />);
    const dialog = screen.getByRole("dialog");
    fireEvent.pointerDown(screen.getByRole("button", { name: "閉じる" }), { pointerId: 1, button: 0 });
    expect(dialog.style.left).toBe("");
  });
});
